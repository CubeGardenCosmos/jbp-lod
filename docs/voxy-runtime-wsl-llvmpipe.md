# Voxy WSL llvmpipe runtime record
> **SEALED LEGACY — mods/voxy only, superseded by docs/todo/jbp-lod.md.** The commands and observations below are historical evidence only; do not reuse them as current launch or acceptance instructions.
> **SEALED HISTORICAL EVIDENCE (ADR 0036):** All OpenGL launches and commands in this file are from the pre-Vulkan-only investigation of sealed `mods/voxy`. They must not be reused as PC launch instructions. The current launcher policy forces Vulkan; old Voxy retains its Vulkan guard and cannot launch. These records do not establish current Vulkan support, JBP-LOD implementation, LOD correctness, or shaderpack support.

**Date:** 2026-10-17. Minecraft 26.3, Fabric Loader 0.19.5, Java 25, WSL2. Runtime used Mesa software rendering; no performance numbers are meaningful.

## GL capability baseline

`glxinfo` and `eglinfo` are unavailable. A one-off Python `ctypes` probe used `libEGL`/`libGL`, `EGL_PLATFORM=surfaceless`, and `LIBGL_ALWAYS_SOFTWARE=1 GALLIUM_DRIVER=llvmpipe`; it requested a 3.3 core context. EGL 1.5 returned **OpenGL 4.6 (Core Profile) Mesa 26.2.3**, GLSL 4.60, vendor Mesa, renderer `llvmpipe (LLVM 22.1.8, 256 bits)`. The un-overridden EGL probe already returned 4.6; the game runs also set `MESA_GL_VERSION_OVERRIDE=4.6 MESA_GLSL_VERSION_OVERRIDE=460`.

`glGetStringi` enumerated 233 extensions. Voxy-relevant extension/capability results:

| Capability | llvmpipe result |
|---|---|
| `GL_ARB_compute_shader`, `GL_ARB_shader_storage_buffer_object`, `GL_ARB_shader_image_load_store` | present |
| `GL_ARB_buffer_storage`, `GL_ARB_draw_indirect`, `GL_ARB_multi_draw_indirect`, `GL_ARB_indirect_parameters`, `GL_ARB_sync` | present |
| `GL_ARB_gpu_shader_int64` | present; Voxy's `#version 430` int64 compute-shader test compiled |
| `GL_KHR_shader_subgroup` (and basic/arithmetic subgroup extensions) | absent; Voxy emitted its optional subgroup performance warning |
| `GL_ARB_sparse_buffer`, `GL_NV_mesh_shader` | absent; neither was required for this initialization |
| `glDispatchComputeIndirect`, `glMultiDrawElementsIndirectCountARB`, `glBufferStorage` | entry points present |
| `GL_MAX_SHADER_STORAGE_BLOCK_SIZE`; storage-buffer offset alignment | 134,217,728 bytes (128 MiB); 16 bytes |

The game's OpenGL backend log reported `GL_ARB_multi_draw_indirect`, `GL_ARB_buffer_storage`, `GL_ARB_shader_draw_parameters`, `GL_ARB_base_instance`, `GL_KHR_debug`, `GL_ARB_draw_indirect`, `GL_ARB_clip_control`, `GL_ARB_vertex_attrib_binding`, `GL_ARB_direct_state_access`, and `GL_EXT_texture_filter_anisotropic`.

Minecraft 26.3 `GlBackend.createWindow` requests SDL OpenGL **3.3 core**; Voxy 263 does not raise the requested version or create another context. The actual WSLg Wayland client reported 4.6 core. This is evidence for the llvmpipe software path, not the RTX 3060 Ti hardware driver's OpenGL capabilities.

## Launches and outcome

All run directories and init scripts were under `/tmp/voxy-llvmpipe-run` (`game/` and `server/`). The Gradle init scripts redirected Loom's run directories there. The offline dev identity was `VoxyDev` with placeholder UUID `00000000-0000-4000-8000-000000000001` and access token `offline`.

An initial `xvfb-run -a ... ./gradlew ... runClient --args='--graphicsBackend opengl ...'` failed at window creation with `Couldn't find matching GLX visual`; forcing OpenGL then led to Voxy's expected Vulkan rejection. Forcing WSLg's native Wayland path fixed this:

```sh
export JAVA_HOME=/usr/lib/jvm/java-25
export PATH=/usr/lib/jvm/java-25/bin:$PATH
export LIBGL_ALWAYS_SOFTWARE=1 GALLIUM_DRIVER=llvmpipe
export MESA_GL_VERSION_OVERRIDE=4.6 MESA_GLSL_VERSION_OVERRIDE=460
export SDL_VIDEO_DRIVER=wayland
export JAVA_TOOL_OPTIONS='-Djbp.voxy.dir=/tmp/voxy-llvmpipe-run/cache'
timeout 180s ./gradlew -I /tmp/voxy-llvmpipe-run/init.gradle runClient \
  --args='--graphicsBackend opengl --width 1280 --height 720 --username VoxyDev --uuid 00000000-0000-4000-8000-000000000001 --accessToken offline --userType legacy'
```

That client created an OpenGL window and entered a new integrated singleplayer world rather than stopping at the title screen. Voxy correctly logged `Inactive: no multiplayer JBP storage scope is set` and `Not creating renderer due to null instance`; no instance or cache was created for singleplayer.

A local Fabric 26.3 server was run with the `runServer` task. Its throwaway directory had `eula=true`, `online-mode=false`, `server-ip=127.0.0.1`, and `server-port=25567`; the default whitelist was disabled with the server console's `whitelist off` command. Port 25566 was already occupied. The successful server command and matching client join were:

```sh
JAVA_HOME=/usr/lib/jvm/java-25 PATH=/usr/lib/jvm/java-25/bin:$PATH \
  ./gradlew -I /tmp/voxy-llvmpipe-run/server-init.gradle runServer --args='--nogui'

JAVA_HOME=/usr/lib/jvm/java-25 PATH=/usr/lib/jvm/java-25/bin:$PATH \
LIBGL_ALWAYS_SOFTWARE=1 GALLIUM_DRIVER=llvmpipe \
MESA_GL_VERSION_OVERRIDE=4.6 MESA_GLSL_VERSION_OVERRIDE=460 SDL_VIDEO_DRIVER=wayland \
JAVA_TOOL_OPTIONS='-Djbp.voxy.dir=/tmp/voxy-llvmpipe-run/cache -Djbp.voxy.devScope=true' \
timeout 300s ./gradlew -I /tmp/voxy-llvmpipe-run/init.gradle runClient \
  --args='--graphicsBackend opengl --quickPlayMultiplayer 127.0.0.1:25567 --width 1280 --height 720 --username VoxyDev --uuid 00000000-0000-4000-8000-000000000001 --accessToken offline --userType legacy'
```

The offline identity was accepted by the offline server; Minecraft ignored `--userType legacy` and logged a Realms authorization parse warning for the placeholder token. The server logged `SERVER IS RUNNING IN OFFLINE/INSECURE MODE!` and `Done (1.117s)!`.

For this isolated test only, a temporary `ClientSessionEvents.sessionStart` hook honored `-Djbp.voxy.devScope=true` and set scope `llvmpipe-dev/offline-test/runtime-test/26.3`. The hook was removed after the run. With `-Djbp.voxy.dir=/tmp/voxy-llvmpipe-run/cache`, the client logged `Initializing voxy instance`, created its world engine and render system, and created:

```text
/tmp/voxy-llvmpipe-run/cache/servers/llvmpipe-dev/offline-test/runtime-test/26.3/config.json
/tmp/voxy-llvmpipe-run/cache/servers/llvmpipe-dev/offline-test/runtime-test/26.3/storage/
```

The storage directory contained RocksDB `CURRENT`, `IDENTITY`, `LOCK`, `LOG`, `MANIFEST-*`, `OPTIONS-*`, and `*.log` files. Voxy created a 512 MiB geometry buffer and completed pipeline construction. Shader diagnostics were warnings (e.g. uninitialized shader variables and implicit int-to-uint conversions); no shader compile failure was observed.

### Iris runtime

The disposable client `mods/` directory contained cached Iris `1.11.7+mc26.3` alongside Sodium `0.9.2+mc26.3`. `ComplementaryReimagined_r5.9.3.zip` was placed under the temporary `shaderpacks/` directory and selected in temporary `config/iris.properties`. With that pack active, Iris logged `Using shaderpack`, Voxy logged `External opaque shader patch applied` and `External translucent shader patch applied`, then `Creating voxy iris render pipeline` and `Voxy render system created ... pipeline 'IrisVoxyRenderPipeline'`. Iris and Voxy shader compilation emitted warnings, but the Voxy Iris pipeline initialized. Minecraft later warned `Timed out while waiting for the client to load chunks, letting the player into the world anyway`; the client process reached its 300-second timeout. This did **not** establish correct LOD drawing or shaderpack visuals. Nvidium is an optional NVIDIA-only renderer compat and was not installed or needed.

## Limits

The successful Wayland runs reached a world/server, but no usable screenshot was obtained: an `ffmpeg` X11 capture was black while the game used native Wayland. The Iris client later logged `Timed out while waiting for the client to load chunks, letting the player into the world anyway`; this does not prove that terrain LOD finished drawing. No separate title-screen capture was obtained; the first WSLg client run opened an integrated `New World`, and the server run used explicit `--quickPlayMultiplayer`. No claim is made that LOD rendering is visually correct; Sodium CUTOUT drawing and actual terrain LOD output remain unverified. The full extension count and Voxy-relevant set above are from the one-off EGL probe, not a checked-in probe utility. The V1 status summary is in [`ysm-voxy-todo.md`](ysm-voxy-todo.md).

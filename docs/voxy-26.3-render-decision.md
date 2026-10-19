# Voxy 26.3 渲染后端决策

**状态：sealed / legacy（ADR 0036 superseded 本文件原“桌面保留 OpenGL”决定）。** `mods/voxy` 仅作只读参考，GL core 与 Vulkan guard 均保留；本文件解释旧 Voxy 的后端约束与历史调查，不是当前启动策略或新的实现计划。新 LOD 目标为尚未实现的 JBP-LOD，计划作为上游 `MCRcortex/voxy` 的长期 fork 维护（不是旧 `mods/voxy` 原位改名）；见 [Vulkan 核心设计](voxy-vulkan-core-design.md)。

## 基线与决定

Voxy 基线为 `MCRcortex/voxy` `263` 分支 HEAD `247adf2625324782e13a3fa8b646c0f95e68031b`，目标 Minecraft 26.3 + Fabric。

以下均为封存旧 Voxy 的结构与历史运行记录，不构成当前桌面支持或后端选择指引。26.3 RenderPearl 同时存在 OpenGL 与 Vulkan 后端；旧 Voxy 直接使用 LWJGL/OpenGL，包括 GL 4.6 级 compute、SSBO、image-store、间接 dispatch/draw、持久映射缓冲、同步与 stencil/state 操作。RenderPearl 公共 API 不提供这些任意 compute/SSBO/indirect/image-store/stencil 操作。上游 263 的 `MixinMinecraft` 在 Vulkan device 创建时抛出 `BackendCreationException("Voxy does not presently support vulkan", Reason.OTHER)`，不会自动回退；这是旧代码在本 ADR 强制 Vulkan 后无法启动的原因。
旧 WSLg 运行记录曾在 OpenGL 条件下初始化旧 Voxy，并记录过 Iris pipeline；它不构成当前 Vulkan 支持、LOD 正确性或 shaderpack 支持证明。Iris `1.11.x` 的 26.3 路径为 OpenGL-only，Vulkan 下不可作为回退；三个 shader packs 的 Vulkan 移植属后续范围。相关历史证据见 [WSL llvmpipe 记录](voxy-runtime-wsl-llvmpipe.md)。

## Sodium 0.9.2+mc26.3 hook 合约

Voxy 的 `client/mixin/sodium/MixinDefaultChunkRenderer.java` 针对 26.3 `DefaultChunkRenderer.render(...)` 的新签名，包含 RenderPearl `RenderPass`、`GpuSampler`、`GpuBufferSlice`、`GpuBuffer` 与 `OitStage` 参数。它在 `prepare(...)` 返回后、且 Voxy renderer 存在时，把 `shouldDraw[CUTOUT]` 设为 `true`，确保 Sodium 为 Voxy LOD 安排 CUTOUT pass；`render(...)` 内仅在 CUTOUT pass、目标 Voxy renderer 存在时插入 Voxy 绘制，并从 OpenGL backend 的 `GlTextureView` 取得 color/depth GL ID。该挂钩依赖 Sodium 的内部 mixin 目标和 OpenGL 纹理视图，不是 RenderPearl 后端无关 API。针对实际 Modrinth Sodium `0.9.2+mc26.3` Fabric jar（[版本 `bAZQdGpg`](https://modrinth.com/mod/sodium/version/bAZQdGpg)，SHA-512 `f3f260f204b8ce5e8777c2c73f537956755ec61f3817b0b78b5e5568e8aa40f14891f146475075109747665c7c88a45ca0ca9a8df3c68d79c58ecdaa8d8c4b93`）执行 `javap -p -s -c`，确认 `DefaultChunkRenderer.render` / `prepare` 描述符及 `ShaderChunkRenderer.end(TerrainRenderPass)` 调用；也确认 `RenderRegionManager.uploadResults(RenderRegion, Collection, UniformBufferManager)` 与 mesh-upload 构造器、两种可见区收集器调用、`ChunkTracker.chunkStatus` 和 `SodiumWorldRenderer.renderSectionManager` 字段，以及 `RenderSectionManager` 的注入目标存在且描述符匹配。本次 `compileJava` / `build` 成功；Sodium hook 的游戏运行时行为仍未验证。上游 263 已包含该 hook，本仓库没有另造一套调度适配。

## Vivecraft 26.3：双眼渲染与后端

依赖锁为 Vivecraft `26.3-1.3.17-fabric`（Modrinth [版本页](https://modrinth.com/mod/vivecraft/version/ralsIVj7)，Gradle `compileOnly`，Fabric metadata `suggests`，不设硬依赖）。该版本的 `VRRenderingAPI` 仍提供 `getCurrentRenderPass()`，`RenderPass` 包含 `VANILLA`、`LEFT`、`RIGHT` 等值；当前 pass 只保证在渲染期间有效。

Vivecraft 的 [VRPassHelper](https://github.com/Vivecraft/VivecraftMod/blob/26.3-1.3.17/common/src/main/java/org/vivecraft/client_vr/render/helpers/VRPassHelper.java) 遍历所有 render pass，对每个调用一次 `renderSingleView`，LEFT/RIGHT 分别渲染世界并使用相应眼睛的 render target。Voxy 必须仍由 Sodium `DefaultChunkRenderer.render` 的 CUTOUT hook 在该 pass 内调用：使用该次 `ChunkRenderMatrices`、当前 pass 的 target 宽高和 color/depth texture views；`setupViewport` 更新当前 pass 的投影、model-view、相机与屏幕尺寸。`ViewportSelector` 将非 VANILLA 的 Vivecraft pass 作为分开的 viewport key，因此 LEFT/RIGHT 不得共用单眼 viewport，也不得把 Voxy 改成每帧只调用一次。当前 `GlTextureView` 取 GL ID 的处理仅适用于 OpenGL target。

**Vivecraft 后端调查（封存旧 Voxy）：** Vivecraft 26.3 源码同时有 OpenGL/Vulkan 路径；这不绕过旧 Voxy Vulkan guard，也不令旧 Voxy + Vivecraft 可在当前 Vulkan-only 启动下运行。Vivecraft mixin、双眼渲染和头显运行时仍未验证。

上述结论基于固定版本源码与依赖 jar。Vivecraft mixin 实际应用、双眼的 Voxy LOD 正确绘制、目标纹理尺寸/内容以及头显运行仍未验证；本机没有头显。

## Iris + Vivecraft 联动

**Iris + Vivecraft 调查（封存旧 Voxy）：** Vivecraft `IrisHelper` 仅在 `OpenGLHelper` 时启用 Iris，Iris 在 Vulkan 下关闭。该版本的 Voxy/Iris VR mixin 与每 pass framebuffer 分析均是旧 OpenGL 架构记录；不得据此提供 OpenGL 启动例外、shaderpack 支持或新模组兼容承诺。

## GL 4.6 与 3.3 core context

Minecraft 26.3 的 `GlBackend.createWindow` 在合并后的 26.3 Minecraft jar 字节码中请求 SDL OpenGL major/minor `3.3`，并指定 `SDL_GL_CONTEXT_PROFILE_CORE`。上游 263 没有修改或提升这个请求，也没有建立独立的 GL context；它在 RenderSystem 初始化后直接调用 `GL.getCapabilities()`，以 `glDispatchComputeIndirect` 与 `glMultiDrawElementsIndirectCountARB` 检查 compute/indirect 能力，并使用高版本 OpenGL entry points / GLSL 4.6。它没有执行完整的 GL 4.6 版本或全部扩展预检。

因此 3.3 core 的请求不等同于保证驱动只能提供 3.3（扩展可提供部分功能），也不保证 Voxy 所需功能齐全。Xvfb/GLX 路径仍报 `Couldn't find matching GLX visual`；改用原生 WSLg Wayland + Mesa llvmpipe 后，实际客户端创建了 4.6 Core context。无版本 override 的 surfaceless EGL 3.3-core 请求也返回 `4.6 (Core Profile) Mesa 26.2.3`；233 个扩展中 Voxy 的 compute/SSBO/buffer-storage/indirect 能力存在，`GL_KHR_shader_subgroup` 不存在，Voxy 的 int64 探针编译通过。该结果仅是 llvmpipe 软件 GL，不代表 RTX 3060 Ti 硬件驱动能力；命令、扩展与运行日志见 [WSL llvmpipe 运行记录](voxy-runtime-wsl-llvmpipe.md)。

## Android 与未决项

Android 游戏已强制 Vulkan；桌面也由 ADR 0036 强制 Vulkan。旧 Voxy 不能直接在 Vulkan 下运行，`mods/voxy` 保持封存且按 2026-10-18 明确切换决定从 PC manifest 移除，不采用 Zink/Kopper，也不修改其 guard。新项目 JBP-LOD 是尚未实现的独立模组，采用与游戏共享 `VkDevice` 的忠实 1:1 Vulkan compute/render 实现；先桌面，移动端适配之后。JBP-LOD 重新实现旧 V0 的节点级存储隔离与 `JbpNodeIdProvider` 查询；使用新 property `-Djbp.lod.dir`，桌面缓存根 `<data_dir>/cache/lod`、缺省 `<gameDir>/.lod`，Android 根 `Context.getCacheDir()/lod`，节点路径 `<base>/nodes/<sanitized nodeId>/`，按 `[^A-Za-z0-9._-]` 替换非法字符为 `_` 并拒绝空值、`.`、`..`；旧 Voxy 属性、路径不成为新契约。外部 shader pipeline 供给 G-buffer targets 的扩展点预留给后续集成；Iris shaderpacks 的 Vulkan 移植为后续范围。详细计划见 [JBP-LOD TODO](todo.md)。
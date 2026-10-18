# JBP-LOD

JBP-LOD is a new, independent client-side Fabric mod for Minecraft 26.3. Its Java root package is `cn.cubegarden.cosmos.jbp.client.lod`; the project targets Java 25 and uses the locked Fabric Loader, Fabric API, and Loom versions in `gradle.properties`.

`mods/voxy` is a sealed, read-only legacy reference. JBP-LOD does not modify or depend on that project. V0 reimplements the node-scope and cache-lock contract; it contains no renderer. Later Vulkan work must borrow the game's existing Vulkan device and may not add an OpenGL fallback. Release/launcher runs remain server-node-only for storage scope (singleplayer is inactive); development runs may also use singleplayer maps when explicitly enabled as described below.

## Build

From this directory, run `./gradlew test lodJar` (or `gradle test lodJar` when Gradle 9.8.1 is installed). The mod JAR is written under `build/libs/`. `./gradlew lodJar` builds the JAR without running tests.

The Java unit tests are pure Java and do not start Minecraft. Run them with `./gradlew test`.

## Development launches

Singleplayer scope is development-only: it is active only when Fabric Loader reports a development environment and `-Djbp.lod.devScope` is set to literal `true` or a custom ID. `true` uses the world's save-folder ID; a custom ID replaces it. The ID is sanitized into `<base>/dev/<sanitized ID>/`, never under `nodes/`; the existing sanitizer, rejection, and cache-lock rules still apply. `-Djbp.lod.devNode=<id>` overrides the reflected node provider only in a development environment.

From this directory, run a Vulkan-only development client directly into an existing singleplayer map (replace `DevWorld` with that map's world-folder/level id):

```bash
JAVA_TOOL_OPTIONS='-Djbp.lod.devScope=true' ./gradlew runClient --args='--graphicsBackend vulkan --quickPlaySingleplayer DevWorld'
```

To use `devNode` without the platform stack, first start a local offline server listening on `127.0.0.1:25567` (with `online-mode=false`), then connect with the dev node ID instead of the reflected provider:

```bash
JAVA_TOOL_OPTIONS='-Djbp.lod.devNode=local-offline' ./gradlew runClient --args='--graphicsBackend vulkan --quickPlayMultiplayer 127.0.0.1:25567'
```

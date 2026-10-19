# Distant Horizons（DH）Vulkan / MC 26.3 评估

状态：只读评估记录，2026-10-19。**未修改 `mods/jbp-lod`，未提交，未做取舍决定。** 本文只列事实、证据与权衡；第 8 节是需要用户决定的问题，不是建议。

范围：DH 在 Minecraft 26.3 + Fabric 上的 Vulkan/Blaze3D 路径、许可证、Sodium/Iris 关系、地形数据与绘制方式、Android 可行性、与本仓库服务端/平台不变量的契合度，以及与 JBP-LOD 的逐项对比。

## 0. 证据基准与方法

| 基准 | 值 | 来源 |
| --- | --- | --- |
| DH 主仓库 | `6763cc8a6f8e96bc59e258bf48d96707b41842b3`（2026-10-18，`main`，`3.3.5-dev`） | `/home/klp/DH-reference`；`git ls-remote` 显示 `origin/main` 同 hash；[commit](https://gitlab.com/distant-horizons-team/distant-horizons/-/commit/6763cc8a6f8e96bc59e258bf48d96707b41842b3) |
| DH core 子模块 | `8d5aa7c19bf95616c57a80e1d04a7e1da35ce2ca`（2026-10-18） | `/home/klp/DH-reference/coreSubProjects`；[commit](https://gitlab.com/jeseibel/distant-horizons-core/-/commit/8d5aa7c19bf95616c57a80e1d04a7e1da35ce2ca) |
| 最新发行 jar | `DistantHorizons-3.3.4-26.3-fabric-neoforge.jar`，28,476,894 B，SHA-256 `ba3e5c85a765a109219831df99b99ef5cfcdee05b2dfa6796116993bd48d3947` | [Modrinth 3.3.4-26.3](https://modrinth.com/mod/distanthorizons/version/TGgEbP9A)；本次下载计算 |
| 本仓库 | `/home/klp/Minecraft-Server-Launcher`（工作区当前状态） | — |

路径约定：

- `DH/...` = `/home/klp/DH-reference/...`
- `DH-core/...` = `/home/klp/DH-reference/coreSubProjects/...`
- `REPO/...` = `/home/klp/Minecraft-Server-Launcher/...`

源码文件的行号引用均对应上表 commit；对应的 GitLab blob 基址为：

- `https://gitlab.com/distant-horizons-team/distant-horizons/-/blob/6763cc8a6f8e96bc59e258bf48d96707b41842b3/`
- `https://gitlab.com/jeseibel/distant-horizons-core/-/blob/8d5aa7c19bf95616c57a80e1d04a7e1da35ce2ca/`

另已浅取发行 tag 并建立只读源码快照：`/tmp/dh-eval-3.1.0b`（tag `3.1.0b`，commit `0c33b3602af59926901bf134ae5a4f2375c9c619`）与 `/tmp/dh-eval-3.3.4`（tag `3.3.4`，commit `eb1007cb6cc3e0419effe2b40ab485cf24e55b43`）。前者 renderer 直接导入 Blaze3D（`common/src/main/java/com/seibel/distanthorizons/common/render/blaze/BlazeDhTerrainRenderer.java:8-13`），后者有 26.3 的 RenderPearl 分支（同文件 `:8-22`）。深入行号分析以下仍明确以 main/core 固定 commit 为准，不把开发源码等同于发行 jar。

方法限制：本次做了源码、发行 jar、Modrinth/GitLab API 与已锁定 Sodium 0.9.2 jar 的静态检查。**没有运行 DH 客户端。** 任务允许“仅在无需改动 `mods/jbp-lod`、可简单复制 run 目录时”跑 llvmpipe；实际评估发现 DH 需要另一套 Fabric 实例/嵌套 Fabric API，且会与 JBP-LOD 开发模组同时加载，不属于“trivially possible”，因此没有启动。所有未实测项在文中标为 **未验证**。

## 1. 版本、MC 版本与 Vulkan 状态

### 1.1 发布矩阵（Modrinth）

| 版本 | MC | 加载器 | 类型 | 发布日期 | 与 Vulkan 的关系 |
| --- | --- | --- | --- | --- | --- |
| `3.3.4-26.3` | 26.3 | fabric, neoforge | release | 2026-09-29 | 当前最新发行；仅一个 bug fix（Iris 26.3 船上海面透明 LOD） |
| `3.3.3-26.3` | 26.3 | fabric, neoforge | release | 2026-09-29 | 26.3 主线；Iris 26.3 blend/lightmap 修复 |
| `3.3.3-26.2` | 26.2 | fabric, neoforge | release | 2026-09-29 | 26.2 仍有独立构建 |
| `3.3.0-26.3` | 26.3 | fabric, neoforge | release | 2026-09-17 | 26.3 首个 release；TAA、前/反向 Z、Iris 1.11.4 |
| `3.2.0-b-26.2` | 26.2 | fabric, neoforge | beta | 2026-07-07 | beta；LOD 纹理、性能/内存修复 |
| `3.1.0-b-26.2` | 26.2 | fabric, neoforge | beta | 2026-06-17 | **首次加入 Vulkan / Blaze3D**，仅 MC 26.2+ |
| `3.0.3-b-26.1.2` | 26.1.x | fabric, neoforge | beta | 2026-05-04 | 无 Vulkan 路径（26.1 及更早为 OpenGL） |

来源：Modrinth 项目 API 的完整版本列表（`https://api.modrinth.com/v2/project/uCdwusMi/version`，本次拉取 509 条），以及各版本页：

- [3.1.0-b-26.2](https://modrinth.com/mod/distanthorizons/version/69qJktf2)（用户给的链接 `.../version/3.1.0-b-26.2` 指向同一版本）。changelog 原文：“Add Vulkan support via Blaze3D rendering API … Only available for MC 26.2.0 and newer.”
- [3.2.0-b-26.2](https://modrinth.com/mod/distanthorizons/version/gBf0SaV1)
- [3.3.0-26.3](https://modrinth.com/mod/distanthorizons/version/I4fvXVOw)
- [3.3.4-26.3](https://modrinth.com/mod/distanthorizons/version/TGgEbP9A)

### 1.2 是否有 26.3 构建/分支

- **有 26.3 发行构建**：3.3.4-26.3 的 jar 在 `fabric.mod.json` 中声明 `minecraft: ["26.3"]`、`java: ">=25"`，并同时支持 Fabric 与 NeoForge（`DH/versionProperties/26.3.0.properties:2-6`；发行 jar 的 `fabric.mod.json`）。
- **源码 main 是多版本分支，不是单独的 26.3 分支**：`DH/gradle.properties:9` 为 `mod_version=3.3.5-dev`；`DH/versionProperties/26.3.0.properties:2-4` 指定 `java_version=25`、`minecraft_version=26.3`、`compatible_minecraft_versions=["26.3"]`，构建脚本用 `#if MC_VER` 预处理支持多个 MC 版本（如 `DH/common/.../BlazeDhTerrainRenderer.java:3-20`）。
- **标签**：GitLab 存在 `3.3.4`、`3.3.3`、`3.3.2`、`3.3.1`、`3.3.0` 等 tag（本次 `git ls-remote --tags origin`）。`main` 在 3.3.4 之后继续开发。
- **`stable` 分支不是当前版本**：本次 `git ls-remote` + 浅取 `stable` 读到 `mod_version=2.2.1-a`，是旧分支，不能当作 3.3.x 的稳定线。
- **`Vulkan` 分支**：head 为 `64d8f7ee2d7c0b3d0153b694a43f65dd5bd22b91`（2026-05-11，“update blaze 3d api use”），是 Blaze3D 开发分支；当前 26.3 的 Blaze 代码已经在 `main` 中，不需要切该分支。

### 1.3 Vulkan 是“稳定”还是“实验”

- 没有看到官方把 Vulkan 标为 experimental 的字段；可观察到的是：
  - 3.1.0/3.2.0 在 Modrinth 上的版本类型是 **beta**（`-b`），3.3.x 是 **release**（见 1.1 与 Modrinth API）。
  - MC 26.2 起 `AUTO` 会选择 `BLAZE_3D`；26.3 的 `getDefaultRenderingEngine()` 直接返回 `BLAZE_3D`（`DH/common/.../VersionConstants.java:115-121`）。配置注释也写明 “BLAZE_3D - The Default for MC 26.1.2 and newer (supports Vulkan)”（`DH-core/core/.../config/Config.java:960-969`）。
  - Vulkan 路径通过 `RenderSystem.getDevice().getDeviceInfo().backendName()` 识别为 `"Vulkan"`（`DH/common/.../MinecraftRenderWrapper.java:559-590`）；如果引擎与当前 MC 后端不匹配（例如在 Vulkan 下强选 OPEN_GL），会拒绝切换并给出错误（`DH/common/.../DependencySetup.java:196-200`）。
- 但 3.3.x changelog 仍持续出现 Blaze/Vulkan 修复，issue tracker 也有开放的 Vulkan 崩溃（见 1.4）。因此“release 标签”不等于“所有 Vulkan 驱动/组合已验证稳定”；**具体到 RTX 3060 Ti + Sodium 0.9.2 是否稳定，未验证**。

### 1.4 已知 Vulkan / Blaze 问题（changelog 与 issue tracker）

| 编号/版本 | 状态 | 内容 | 来源 |
| --- | --- | --- | --- |
| #1348 | **open** | MC 26.3 + DH 3.3.4 + Fabric，进入世界即崩溃：`EXCEPTION_INT_DIVIDE_BY_ZERO`，调用栈在 `amdvlk64.dll`；硬件 RX 580、驱动 26.5.2。仅 DH 的空白 Fabric 实例即可复现 | [issue #1348](https://gitlab.com/distant-horizons-team/distant-horizons/-/issues/1348) |
| #1293 | closed | 26.2 Vulkan/Blaze 路径：fog 与 SSAO 把“未绘制”深度当成 1.0，反向 Z 下判断错误；报告者使用 Sodium 0.9.1 + Vulkan 后端 | [issue #1293](https://gitlab.com/distant-horizons-team/distant-horizons/-/issues/1293) |
| #1292 | closed | 26.2：加载界面 “Missing uniform Globals” 启动崩溃；报告者注明 Vulkan 与 OpenGL 后端都出现 | [issue #1292](https://gitlab.com/distant-horizons-team/distant-horizons/-/issues/1292) |
| #1320 | open | `lodChunkRenderDistanceRadius` 调大再调小后队列卡在最大值；3.3.1 + NeoForge 1.21.1 | [issue #1320](https://gitlab.com/distant-horizons-team/distant-horizons/-/issues/1320) |
| #1313 / #1319 | closed | 3.3.0 + NeoForge 26.3 进入世界崩溃；报告称 Fabric 26.3 与 NeoForge 26.2 不崩溃 | [issue #1313](https://gitlab.com/distant-horizons-team/distant-horizons/-/issues/1313)、[#1319](https://gitlab.com/distant-horizons-team/distant-horizons/-/issues/1319) |
| #1318 | open | `[OPAQUE_AND_TRANSPARENT]` 渲染 pass 内 `GlShaderProgram.bind()` NPE；这是 **OpenGL** 路径，不是 Vulkan 路径 | [issue #1318](https://gitlab.com/distant-horizons-team/distant-horizons/-/issues/1318) |
| #525 | closed | 与 VulkanMod 不兼容。注意：VulkanMod 是第三方 Vulkan 后端，本仓库使用的是 Minecraft 自带 RenderPearl/Vulkan 后端，两者不是同一物 | [issue #525](https://gitlab.com/distant-horizons-team/distant-horizons/-/issues/525) |
| 3.2.0 changelog | — | “Fix a rare null pointer in Blaze terrain rendering”；“Fix instanced rendering not working on AMD”；“Fix a ByteBuffer memory leak”；“Fix GL using the last frame's viewport size” | [3.2.0 changelog](https://modrinth.com/mod/distanthorizons/version/gBf0SaV1) |
| 3.3.0 changelog | — | “Optimize blaze3D debug rendering”；新增前/反向 Z、TAA、Iris 1.11.4 | [3.3.0 changelog](https://modrinth.com/mod/distanthorizons/version/I4fvXVOw) |
| 3.3.4 changelog | — | “Fix Iris in MC 26.3 rendering transparent LODs incorrectly when a boat is on the screen” | [3.3.4 changelog](https://modrinth.com/mod/distanthorizons/version/TGgEbP9A) |

## 2. 许可证

### 2.1 DH 本体与 Vulkan 相关部分

- Modrinth 项目 API 的 license 字段：`LGPL-3.0-only`，名称 “GNU Lesser General Public License v3.0 only”（`https://api.modrinth.com/v2/project/uCdwusMi`）。
- 仓库 `DH/LICENSE.LESSER.txt:1` 是 LGPL-3.0 全文；`DH/LICENSE.txt:1` 是 GPL-3.0 全文；`DH/license_header.txt:3` 的文件头写 “licensed under the GNU LGPL v3 License”。`fabric.mod.json` 的 `license` 字段也是 `LGPL-3`。
- **Vulkan/Blaze 代码没有单独的许可证**：`DH/common/src/main/java/com/seibel/distanthorizons/common/render/blaze/**` 与 `DH-core/core/.../render/**` 均属于同一个 LGPL-3.0 项目；发行 jar 同时包含 `LICENSE.LESSER.txt`、`LICENSE.txt`、`LICENSE-LOGOS.txt`（`unzip -l DistantHorizons-3.3.4-...jar`）。
- 需要留意的文件头不一致：部分较新的 Blaze 文件直接从 `package` 开始，没有 LGPL 文件头，例如 `DH/common/.../render/blaze/BlazeDhTerrainRenderer.java:1`、`DH/common/.../render/blaze/wrappers/RenderPassWrapper.java:1`；而 `DH-core/core/.../render/renderer/LodRenderer.java:1-5` 有标准 LGPL 头。**这不足以推断这些文件是公有领域或无版权**；项目整体、发行 jar 和 Modrinth 元数据都声明 LGPL-3.0。是否需要在 fork/再分发时补齐文件头，属于合规提醒（见 2.4）。
- DH 的 shader 位于 `DH-core/core/src/main/resources/assets/distanthorizons/shaders/**`，同样随 LGPL 项目分发；没有发现独立 shader 许可证。

### 2.2 依赖与打包

DH 的 `core/build.gradle:42-45` 直接依赖以下库；发行 jar 通过 shadow/relocate 打包（`DH/buildSrc/src/main/groovy/dh-loader.gradle:483-520`）：

| 依赖 | 版本 | 许可证 | 证据 |
| --- | --- | --- | --- |
| `org.xerial:sqlite-jdbc` | 3.53.2.1 | Apache-2.0 | `DH/gradle.properties:31`；[POM](https://repo1.maven.org/maven2/org/xerial/sqlite-jdbc/3.53.2.1/sqlite-jdbc-3.53.2.1.pom) |
| SQLite 本体（随 sqlite-jdbc native） | — | Public Domain（SQLite） | sqlite-jdbc POM 的 `LICENSE.zentus`/项目说明；jar 内 `META-INF/maven/org.xerial/sqlite-jdbc/LICENSE` |
| `com.github.luben:zstd-jni` | 1.5.7-12 | BSD-2-Clause | `DH/gradle.properties:29`；[POM](https://repo1.maven.org/maven2/com/github/luben/zstd-jni/1.5.7-12/zstd-jni-1.5.7-12.pom) |
| `org.lz4:lz4-java` | 1.8.0 | Apache-2.0 | `DH/gradle.properties:27`；[POM](https://repo1.maven.org/maven2/org/lz4/lz4-java/1.8.0/lz4-java-1.8.0.pom) |
| `org.tukaani:xz` | 1.12 | 0BSD | `DH/gradle.properties:28`；[POM](https://repo1.maven.org/maven2/org/tukaani/xz/1.12/xz-1.12.pom) |
| `com.electronwill.night-config:toml/json` | 3.9.0 | LGPL-3.0 | `DH/gradle.properties:26`；[POM](https://repo1.maven.org/maven2/com/electronwill/night-config/toml/3.9.0/toml-3.9.0.pom) |
| `it.unimi.dsi:fastutil` | 8.2.1 | Apache-2.0 | `DH/gradle.properties:33`（`compileOnly`，由 MC 提供） |
| `org.joml:joml` | 1.10.2 | MIT | `DH/gradle.properties:40`（`compileOnly`，由 MC 提供） |
| Fabric API（嵌套打包） | 0.160.5+26.3 | Apache-2.0 | 发行 jar `fabric.mod.json` 的 `jars`/`META-INF/jars/fabric-api-0.160.5+26.3.jar` |
| Sodium（集成目标，不打包） | 0.9.2+mc26.3 | PolyForm Shield 1.0.0 | DH `versionProperties/26.3.0.properties:22`；[Sodium 0.9.2](https://modrinth.com/mod/sodium/version/bAZQdGpg)；jar 内 `LICENSE.md` |
| Iris（可选集成，不打包） | 1.11.6/1.11.7+26.3 | LGPL-3.0-only | DH `versionProperties/26.3.0.properties:23`；[Iris 1.11.7](https://modrinth.com/mod/iris/version/vTN4NRGW)；jar 内 `fabric.mod.json` |

发行 jar 的 notice 观察：

- 28.4 MB 的 3.3.4 jar 中，`unzip -l` 只看到 DH 自己的三份 license 和 `META-INF/maven/org.xerial/sqlite-jdbc/LICENSE`、`LICENSE.zentus`。没有看到 zstd-jni、lz4-java、xz、NightConfig、fastutil 的独立 license 文件。**（这是打包观察，不构成法律结论。）**
- 3.1.0 changelog 说 “Remove fabric-gametest-api from release jars”，但 3.3.4 发行 jar 里仍能看到 `META-INF/jars/fabric-client-gametest-api-v1-6.0.7+4be74c3f02.jar`。**（发行包与 changelog 不一致；未验证这是否影响运行。）**
- 发行 jar 的 `fabric.mod.json` 嵌套了 **46 个** `META-INF/jars/` 条目（含目录条目），包括完整 Fabric API 和多个模块；这是下面“与本仓库 Fabric API 0.162.0 共存”问题的来源。

### 2.3 与 `AGENTS.md` 许可证/供应链规则的关系（仅提醒）

`REPO/AGENTS.md:49-50` 规定：锁定直接依赖/模组/原生库版本，更新清单记录来源 URL、目标平台、版本、许可证、SHA-256 与校验结果；许可证检查以用户指令为准，只提醒，若用户忽略视为已获许可；第三方项目保留原有文件头与来源记录。`REPO/AGENTS.md:45` 还规定只安装管理员白名单中的辅助模组，正式客户端清单固定版本与 SHA-256。

对 DH 的具体含义（**提醒，不是法律意见**）：

1. 若把 DH 加入客户端清单，需要新增锁条目：Modrinth 项目/版本、Fabric 加载器、MC 版本、jar SHA-256（本次实测 `ba3e5c85...d3947`）和 LGPL-3.0-only；这符合 `release/client-mods.lock.toml` 的字段格式（该文件当前没有 `distanthorizons` 条目，见 `REPO/release/client-mods.lock.toml:1-40`）。
2. DH 是 LGPL-3.0；如果**原样分发未修改的 jar**，与仓库现有 LGPL 依赖（如 Controlify）在流程上同类；如果为了 Android 或嵌套 Fabric API 而**重打包/修改** DH，LGPL-3.0 会带来修改版源码、通知与可重链接等义务。**具体义务需按分发方式确认（[INFERENCE]，非法律结论）。**
3. DH 自带 SQLite/Zstd 原生库并 relocate；加入清单时，原生库的来源、ABI、SHA-256 与 16 KiB 页要求都要进入供应链记录（`REPO/AGENTS.md:49`；Android 门禁见 `REPO/docs/decisions/0019-android-16k-page-aligned-runtime.md:9`）。

## 3. Vulkan 后端是如何实现的

### 3.1 只使用 Blaze3D/RenderPearl，不直接使用 LWJGL Vulkan

- **没有直接 Vulkan 调用**：在 `DH` 与 `DH-core` 全仓执行 `grep -rn "org.lwjgl.vulkan"` 为 0 匹配。Blaze 渲染器通过 `RenderSystem.getDevice()` 取得游戏已有的 `GpuDevice`，并 `createCommandEncoder()`；不创建第二个 device（`DH/common/.../BlazeDhTerrainRenderer.java:62-63`，`DH/common/.../wrappers/RenderPassWrapper.java:46-47`）。
- **使用 Blaze3D/RenderPearl 的公开对象**：`GpuDevice`、`CommandEncoder`、`RenderPass`、`GpuBuffer`、`RenderPipeline`、`GpuTexture/GpuTextureView`、`GpuSampler`。包装层根据 MC 版本在 `com.mojang.blaze3d.*` 与 `com.mojang.renderpearl.api.*` 之间切换（`DH/common/.../wrappers/RenderPassWrapper.java:4-38`；`DH/common/.../wrappers/buffer/BlazeVertexBufferWrapper.java:4-20`）。
- **资源上传**：CPU 生成 `ByteBuffer`，再通过 `CommandEncoder.writeToBuffer(new GpuBufferSlice(...))` 写入 `GpuBuffer`（`DH/common/.../wrappers/buffer/BlazeVertexBufferWrapper.java:119-152`；索引缓冲 `:176-191`）。
- **私有附件 + 拷贝回 MC**：DH 自己创建 `DhColorTexture`/`DhDepthTexture`，在私有 pass 中绘制，最后用 `BlazeDhCopyRenderer` 拷到 MC 的颜色纹理（`DH/common/.../BlazeDhMetaRenderer.java:31-32,83-89`；`DH/common/.../BlazeDhTerrainRenderer.java:288-295`）。
- **引擎选择**：`AUTO` → 26.2+ 默认 `BLAZE_3D`（`DH/common/.../VersionConstants.java:115-121`）；如果强选 `OPEN_GL` 而 MC 当前是 Vulkan，会被后端不匹配检查拒绝（`DH/common/.../DependencySetup.java:196-200`）。OpenGL 路径本身仍使用 LWJGL GL（`DH/common/.../render/openGl/**`），但在本仓库的 Vulkan-only 启动下不会成为回退。

### 3.2 与 Sodium / Iris 的集成（含 Sodium 0.9.2）

**结论（源码级）：基础 LOD 渲染不要求 Sodium；Sodium 只在 Iris shaderpack 路径中被 mixin。** 这一点与很多“DH 依赖 Sodium”的印象不同，需要按 26.3 的代码分开看。

- `fabric.mod.json` 的 `depends` 只有 Fabric Loader、Fabric API 的 base/lifecycle/resource-loader、Minecraft 26.3 和 Java ≥25；没有 Sodium/Iris 依赖，`suggests`/`recommends` 也是空的（发行 jar `fabric.mod.json`；源码 `DH/fabric/src/main/resources/fabric.mod.json`）。
- **非 Iris 路径（26.3）**：`MixinLevelRenderer` 在 `prepareTranslucents` HEAD 调用 `ClientApi.INSTANCE.renderLods()`；在 `executeOutline` HEAD 调用 `renderDeferredLodsForShaders()` 与 `renderFadeOpaque()`；在 `executeOit` HEAD 调用 `renderFadeTransparent()`。如果 Iris shaderpack 正在使用，则这些方法直接 return，交给 Sodium 的 render pass（`DH/fabric/.../mixins/client/MixinLevelRenderer.java:275-323`）。
- **Iris shaderpack 路径（26.3）**：`MixinChunkSectionsToRender` 以 `@Pseudo` 指向 `net.caffeinemc.mods.sodium.client.util.SodiumChunkSection`，在 `renderGroup` HEAD（order 800）注入；只有 `irisAccessor != null && irisAccessor.isShaderPackInUse()` 时才渲染（`DH/fabric/.../mixins/client/MixinChunkSectionsToRender.java:154-186`）。非 Iris 时该 mixin 直接 return。
- **Sodium 0.9.2 签名匹配**：对仓库锁定的 `sodium-fabric-0.9.2+mc26.3.jar` 执行 `javap -p -s`，`SodiumChunkSection.renderGroup` 的签名是 `(ChunkSectionLayerGroup, RenderPass, GpuSampler, GpuTextureView, boolean)`，与 DH 注入的方法名/参数一致（`REPO/release/client-mods.lock.toml:328-333`；Sodium jar 来自 [bAZQdGpg](https://modrinth.com/mod/sodium/version/bAZQdGpg)）。DH 的 `versionProperties/26.3.0.properties:22` 也把 Sodium 锁在 `mc26.3-0.9.2-fabric`。
- **Sodium 版本检测**：`SodiumAccessor.isSodiumV5OrLess` 只用于判断是否需要 Indium（Sodium 0.5 及更老）；0.9.2 不走这个分支（`DH/fabric/.../wrappers/modAccessor/SodiumAccessor.java:53-59`）。
- **Iris 版本**：DH 26.3 锁 `iris_version=1.11.6+26.3-fabric`（`DH/versionProperties/26.3.0.properties:23`）。Modrinth 当前有 1.11.7+26.3（2026-09-30），其 `fabric.mod.json` 要求 Sodium `0.9.x`，并声明 `breaks.distanthorizons <=3.2.0`（[Iris 1.11.7](https://modrinth.com/mod/iris/version/vTN4NRGW)）。Iris 1.11.7 的 jar 中有 `IrisMixinPlugin.usingVulkan` 和读取 `preferredGraphicsBackend` 的代码，说明它至少有 Vulkan 门控；但**DH + Iris + Vulkan 的 26.3 实际运行未验证**，且本仓库 ADR 0036 目前把 Iris shaderpacks 的 Vulkan 路径列为后续范围（`REPO/docs/decisions/0036-vulkan-only-graphics-backend.md:10,38`）。
- **与本仓库固定版本的潜在冲突（未验证）**：DH 发行 jar 嵌套 `fabric-api-0.160.5+26.3`，而本仓库锁定 Fabric API `0.162.0+26.3`（`REPO/release/client-mods.lock.toml:21-29`；`REPO/docs/compatibility/minecraft-26.3.md:25`）。Fabric Loader 对“安装的 0.162.0 + DH 内嵌 0.160.5”的解析/去重行为没有实测；这是采用前必须验证的兼容点。

### 3.3 地形如何绘制：数据结构、剔除、CPU 还是 GPU

**数据结构：列（column），不是 3D 体素段。**

- 存储/传输单位是 64×64 数据列的 `FullDataSourceV2`，detail level 0 时每列对应一个方块列；列内是从上到下的数据点数组，另有每列的 generation step/compression mode（`DH-core/core/.../dataObjects/fullData/sources/FullDataSourceV2.java:76-115`）。
- 从区块生成 LOD 时，`LodDataBuilder.createFromChunk` 用 `max(lightBlockingHeightMap, solidHeightMap)` 找顶面，再向下编码 block/biome/height/light 变化（`DH-core/core/.../transformers/LodDataBuilder.java:64-79`，更完整的 `:161-263` 见既有调查 `REPO/mods/docs/jbp-lod-evidence/DH-reference.md`）。
- 渲染数据是 `ColumnRenderSource`；`ColumnRenderBufferBuilder` 遍历 64×64 列、跳过空列，为每个列数据点生成一个 box；`ColumnBox.addBoxQuadsToBuilder` 生成顶/底/四个侧面（`DH-core/core/.../bufferBuilding/ColumnRenderBufferBuilder.java:58-117`；`DH-core/core/.../bufferBuilding/ColumnBox.java:58-100`；`LodQuadBuilder.java:58-110`）。

**绘制：CPU 构建顶点/索引，逐 buffer 的 indexed draw；不是 GPU-driven，也没有 compute/indirect。**

- 渲染循环遍历 `bufferContainers`，对每个容器的 opaque/translucent VBO 调 `setIndexBuffer` / `setVertexBuffer` / `drawIndexed(indexCount)`（`DH/common/.../BlazeDhTerrainRenderer.java:305,336-372`）。没有 `drawIndexedIndirect`、`vkCmdDraw*`、compute pipeline 或 SSBO 剔除（全仓 grep 为 0）。
- 剔除是 CPU 侧：`RenderBufferHandler` 更新 frustum，`LodQuadTree` 按相机/渲染距离选择节点并检查范围（`DH-core/core/.../render/RenderBufferHandler.java:154-195`；`DH-core/core/.../render/QuadTree/LodQuadTree.java:478-510,584,860-928,1020-1035`）。
- 透明 LOD 按远到近绘制，避免前方透明面剔除后方（`DH/common/.../BlazeDhTerrainRenderer.java:315-318` 的 `containerIndex` 反转）。
- 近重叠策略：私有 color/depth target + 径向近裁剪 + 当前帧 MC 深度 fade；没有 stencil mask，也没有“已加载区块 AABB”剔除。`RenderUtil.getNearClipPlaneInBlocks()` 按 vanilla render distance 选 0.2/0.3/0.6/0.8/0.9 的 overdraw 比例（`DH-core/core/.../util/RenderUtil.java:131-245`）；frag shader 在 `uClipDistance` 内 discard（`DH-core/core/src/main/resources/assets/distanthorizons/shaders/terrain/blaze/frag.fsh:155-167`）；vanilla fade 在 MC 透明 pass 之后用当前帧深度做 smoothstep（`DH/common/.../render/blaze/postProcessing/BlazeVanillaFadeRenderer.java:183-204,227`；`DH/fabric/.../MixinLevelRenderer.java:302-323`）。
- 这不是 GPU compute 方案：没有 HiZ、没有 GPU 端命令生成、没有 indirect draw。优点是简单、可预测；代价是每帧 CPU 遍历/上传和较多 draw call，且近重叠依赖近裁剪/fade，而不是像素级 stencil。

### 3.4 数据存储：SQLite

- 存储后端是 **SQLite JDBC**（`org.xerial:sqlite-jdbc`），默认 `jdbc:sqlite`（`DH-core/core/.../sql/repo/AbstractDhRepo.java:52,103`；`DH-core/core/build.gradle:45`）。
- 每个 level/dimension 一个数据库文件：`<saveFolder>/DistantHorizons.sqlite`（`DH-core/core/.../file/structure/ISaveStructure.java:29`；`DH-core/core/.../file/fullDatafile/V2/FullDataSourceProviderV2.java:114`）。
- 单人多人在路径不同：
  - 单人（内置服务器）`DhClientServerLevel` 使用 `LocalSaveStructure`，save folder 就是 MC 世界存档目录（`DH-core/core/.../world/AbstractDhServerWorld.java:28`；`DH-core/core/.../file/structure/LocalSaveStructure.java:54-74`）。
  - 多人客户端 `DhClientLevel` 使用 `ClientOnlySaveStructure`，路径为 `<gameDir>/Distant_Horizons_server_data/<serverKey 或服务器名/IP>/<dimension>`（`DH-core/core/.../file/structure/ClientOnlySaveStructure.java:46,73-108,183-190`）。
- 压缩默认 `Z_STD_BLOCK`；配置注释明确提到 Android 上 Zstd 有问题（`DH-core/core/.../config/Config.java:1636-1640`）。启动时 `Initializer.preConfigInit()` 会实际做一次 Zstd 压缩/解压，并 `SQLiteJDBCLoader.initialize()`；任一失败就 crash（`DH-core/core/.../Initializer.java:76-100`）。这是 Android 评估的关键（见 4）。
- 数据可被 `PRE_EXISTING_ONLY` 从 region 文件导入，也可由 FEATURES/INTERNAL_SERVER 模式生成（见 3.5）。数据库本身没有版本/epoch 字段；它只是按 level 路径隔离。

### 3.5 地形生成与导入：使用 Minecraft 的世界生成，不是自己的噪声实现

- **默认生成计划**：`generatorPlan=SURFACE_THEN_CHUNKS`，`chunkGeneratorMode=FEATURES`（`DH-core/core/.../config/Config.java:1440-1500`）。即先做粗糙地表，再补 chunk 特征；默认 FEATURES 只生成 LOD，不把 chunk 写入 region 文件。
- **`INTERNAL_SERVER` 是例外**：配置注释明确写 “unlike other modes this option DOES save generated chunks to Minecraft's region files”（`DH-core/core/.../config/Config.java:1493-1499`）。该模式调用内置服务器生成，适合需要结构正确的场景，但会写世界存档。
- **读取已有 region**：`DhChunkGenerator.generateChunks` 先以 radius 0 读取现有 chunk（`createEmptyOrPreExistingChunkWrapperAsync`），再为缺失位置创建空 proto-chunk（`DH/common/.../worldGeneration/DhChunkGenerator.java:380-433`；`ChunkFileReader.java:117-177`）。
- **只导入已探索地形**：`PRE_EXISTING_ONLY` 只初始化 biomes 后返回，不跑噪声/地表/特征生成（`DH/common/.../worldGeneration/DhChunkGenerator.java:651-657`；枚举 `DH-core/api/.../EDhApiDistantGeneratorMode.java:37-65`）。`/dh pregen` 通过同一 `WorldGenerationQueue` 批量导入（既有调查 `REPO/mods/docs/jbp-lod-evidence/DH-reference.md:292-310`）。
- **粗糙地表生成器**：`DhRoughSurfaceGenerator.generateSurface` 以 1/4 列采样地表高度、平滑水下尖峰、插值其余列，再输出 surface/water 数据点（`DH/common/.../worldGeneration/DhRoughSurfaceGenerator.java:140-200`）；biome/height 来自 MC 的 `ChunkGenerator`/`BiomeSource`（同文件 `:713`；`GenParams.chunkGenerator` `:1024-1047`）。
- **生成结果的持久化**：`WorldGenerationQueue` 把生成的 chunk 通过 `LodDataBuilder.createFromChunk` 转成 `FullDataSourceV2`（`DH-core/core/.../generation/queues/WorldGenerationQueue.java:467-543`）；`GeneratedFullDataSourceProvider.getPositionsToRetrieve` 用 DB key 存在性与每列 generation steps 判断“缺什么”（`DH-core/core/.../file/fullDatafile/GeneratedFullDataSourceProvider.java:359-482`）。
- 范围限定：以上本地世界生成/region 导入需要持有服务器世界与生成上下文，适用于单人内置服务器或安装 DH 的服务端；**不能推广到普通多人客户端**。多人客户端使用 `RemoteWorldRetrievalQueue`（`DH-core/core/.../level/DhClientLevel.java:436-437`），无服务端支持时主要是记录实际收到的区块并复用缓存。

## 4. Android 可行性

**结论：未经原生库替换的发行包存在明确的 Android/bionic 兼容阻断；不能宣称 Android 已支持。** 本次没有 Android 运行证据，不能把静态链接检查扩大为“所有 Pojav/FCL 环境均不可用”；下面区分静态阻断证据与未验证风险。

### 4.1 硬阻断：SQLite 与 Zstd 原生库是 glibc，不是 bionic

发行 jar 中的 ABI 条目（`unzip -l DistantHorizons-3.3.4-...jar`）：

- SQLite：`dh_sqlite/native/Linux/aarch64/libsqlitejdbc.so`、`Linux/armv7`、`Linux/x86_64` 等；**没有** `Android/arm64-v8a` 条目。
- Zstd：`linux/aarch64/libzstd-jni_dh-1.5.7-12.so`、`win/aarch64` 等；**没有** Android 条目。
- LZ4：`DistantHorizons/libraries/jpountz/util/linux/aarch64/liblz4-java.so`（native 可选；lz4-java 有纯 Java fallback，DH 使用 `LZ4Factory.fastestInstance()`，`DH-core/core/.../DhDataOutputStream.java:81`；**fallback 在 Android 上的实际行为未验证**）。
- XZ：纯 Java（`org.tukaani:xz`），没有 native 阻断。

对 `Linux/aarch64` 两份必需原生库做 `readelf`：

- SQLite：`NEEDED libpthread.so.0`、`libc.so.6`；`LOAD Align 0x10000`（64 KiB，16 KiB 页对齐本身满足）。
- Zstd：`NEEDED libpthread.so.0`、`libc.so.6`、`libm.so.6`；`LOAD Align 0x10000`。

Android bionic 提供的是 `libc.so`，不提供 glibc 的 `libc.so.6`/`libpthread.so.0`，也没有 Android ABI 目录；因此这两个 `.so` 不能按原样 `dlopen`。而 `Initializer.preConfigInit()` 在启动时**强制**做 Zstd 压缩/解压并 `SQLiteJDBCLoader.initialize()`，失败即 `crashMinecraft`（`DH-core/core/.../Initializer.java:76-100`）。这与 issue tracker 一致：

- #668 “DH 2.0.1 on pojav: Linux-android aarch64 SQL in the mod is not being loaded, which results in crash”：报告者通过把 `.so` 复制到 Pojav 的 `LD_LIBRARY_PATH` 目录临时绕过（[issue #668](https://gitlab.com/distant-horizons-team/distant-horizons/-/issues/668)）。
- #1178 “Crash if Zstd algorithm not present ANDROID”：标签 **Not Going To Fix**（[issue #1178](https://gitlab.com/distant-horizons-team/distant-horizons/-/issues/1178)）。
- #1253 “Zstd crashes on Android”：标签 **Not Going To Fix**（[issue #1253](https://gitlab.com/distant-horizons-team/distant-horizons/-/issues/1253)）。

**对“16 KiB 页”的区分**：这两份 ARM64 原生库的 `LOAD Align` 是 0x10000，16 KiB 页对齐不是问题；问题是 bionic/glibc 链接。不要把它们与 ADR 0019 的“4 KiB 对齐失败”混为一谈（`REPO/docs/decisions/0019-android-16k-page-aligned-runtime.md:9`）。

### 4.2 未验证风险

- **`java.awt`**：DH 的公共/core 代码大量 `import java.awt.*`（例如 `DH/common/.../BlazeDhMetaRenderer.java:22`、`MinecraftRenderWrapper.java:22`、`DH-core/core/.../config/gui/ConfigScreen.java:23-25` 还用了 Swing/KeyListener），`Initializer` 会设置 `java.awt.headless`（`DH-core/core/.../Initializer.java:139-161`）。我们的 `:game` 进程使用 Java 25 运行时；Pojav/FCL 的 JRE 是否提供可用的 `java.desktop` 未验证。**未验证。**
- **Java 25/ARM64**：DH 26.3 要求 Java ≥25（发行 `fabric.mod.json`），与 ADR 0017 的 `:game` Java 25 一致（`REPO/docs/decisions/0017-android-runtime-architecture-and-binder-ipc.md:53,58`）。这不是阻断。
- **网络**：DH 使用 Fabric custom payload（游戏连接上的 `CommonPacketPayload`/`WRAPPER_PACKET_RESOURCE`），没有 Unix socket、回环 TCP/UDP 或本地监听（全仓 grep `AF_UNIX|LocalSocket|ServerSocket|java.net.Socket|DatagramSocket` 为 0；`DH/common/.../AbstractPluginPacketSender.java:41-48`；`DH/fabric/.../FabricServerProxy.java:196-220`）。因此不违反 `AGENTS.md:20` 的“Android 本机 IPC 全面 Binder、禁止本地 socket”规则；它本身也不使用 Binder，因为它不需要跨进程控制。
- **多线程/数据库**：DH 有独立线程池（world gen、file handler、network compression；`DH-core/core/.../util/threading/ThreadPoolUtil.java:124-125`；`FullDataSourceRequestHandler` 使用 file/network executor），数据库读写不直接在游戏 tick/render 线程上执行；这与 `AGENTS.md:23` 的方向一致。**但具体 tick 影响未在 S23 测量。**
- **内存/显存**：DH 默认 `lodChunkRenderDistanceRadius=256`（`DH-core/core/.../config/Config.java:184-193`），列数据 + SQLite + GPU buffer；S23（8 GB）和 3060 Ti（8 GB）上的实际峰值**未验证**。
- **FCL（Fold Craft Launcher）**：本次在 DH issue tracker 搜索 `FCL` 为 0 条；只有 Pojav 的 #668。**没有 FCL 专用报告。**
- **可修复性**：理论上可以为 bionic 重新编译 SQLite/Zstd（或换纯 Java 实现）并重打包 DH；这需要 fork/修改 LGPL 代码，且 `Initializer` 的硬检查、relocate 脚本（`DH/buildSrc/src/main/groovy/dh-loader.gradle:483-520`）和 16 KiB/ABI 都要重新验证。**未验证，工作量未评估。**

## 5. 服务端/平台要求：DH 的 server-side 能力与契合度

### 5.1 DH 提供了什么

- **服务端安装可选**：`fabric.mod.json` 同时有 client/server entrypoint，`environment: "*"`。无 DH 服务端时，客户端可以记录收到的区块并复用缓存；**不能据 `RemoteFullDataSourceProvider` 继承生成 provider 就断言客户端能独立生成远程世界**。多人请求使用 `RemoteWorldRetrievalQueue`，依赖服务端会话 ready（`DH-core/core/.../level/DhClientLevel.java:263-265,332-336,436-437`；`RemoteFullDataSourceProvider.java:98-103`）。
- **Level Keys**：服务器配置 `sendLevelKeys`、`serverKey`、`levelKeyPrefix` 与随机 `serverId`；客户端据此选择缓存目录。键有字符/长度校验，但不是平台授权（`DH-core/core/.../config/Config.java:1950-1990`；`DH-core/core/.../network/messages/base/LevelInitMessage.java:10-20`；`DH-core/core/.../api/internal/ClientPluginChannelApi.java:80-120`；`ClientOnlySaveStructure.java:75-108`）。
- **多人 LOD 同步**：服务端读取自己的 `FullDataSourceV2` 并通过 custom payload 分块发送；有请求速率、距离、带宽限制（`DH-core/core/.../multiplayer/server/FullDataSourceRequestHandler.java:58-140`；`Config.java:1992-2072`）。这些是 DH 会话能力，不是平台 fencing 校验。
- **服务端存储位置**：`AbstractDhServerWorld` 使用 `LocalSaveStructure`，DB 写在 MC 世界存档目录内（`DH-core/core/.../world/AbstractDhServerWorld.java:28`；`LocalSaveStructure.java:54-74`；`FullDataSourceProviderV2.java:114`）。服务端 LOD 数据是**世界级派生数据**（`FullDataSourceV2` + beacon beam），不含玩家背包/位置/权限等玩家状态（`FullDataSourceRequestHandler.java` 的 payload 类型；`BeaconBeamDTO`）。
- **服务端生成**：默认 `FEATURES` 只生成 LOD；`INTERNAL_SERVER` 会“把生成的 chunk 保存到 MC region 文件”（`Config.java:1493-1499`）。`generatorPlan=DISABLED` 可关闭生成（`EDhApiGeneratorPlan.java:67`；`FullDataSourceRequestHandler.java:155`）。

### 5.2 与本仓库平台不变量的契合/不契合

本仓库的硬规则（`REPO/AGENTS.md`）：

- `:26`：所有节点数据读写必须校验节点、存档组、世界标识、存档版本及 fencing epoch。
- `:31-32`：节点本地文件不得成为玩家状态的故障恢复来源；存档锁只能在平台确认源节点最终保存并释放后由目标节点取得；持有旧 epoch 的节点不得提交/恢复/重新获得玩家状态。
- `:45`：只安装管理员白名单中的辅助模组；正式客户端清单固定版本与 SHA-256。
- `:23`：Fabric 模组的网络 I/O、压缩、大型 NBT、等待、数据库操作不得阻塞游戏 tick/渲染线程。

| 维度 | DH 的现状 | 与本仓库规则的关系 |
| --- | --- | --- |
| 节点/存档组/世界标识 | 只按 level（维度）和 `serverKey`/`levelKey` 分目录；没有平台节点 ID、存档组 ID、存档版本、fencing epoch 字段 | **不契合**（`AGENTS.md:26`）。`serverKey`/`levelKey` 是缓存命名空间，不是平台存档授权/epoch；源码中没有这些校验（`ClientOnlySaveStructure.java:75-108`；`LevelInitMessage.java:10-20`） |
| 服务端 LOD DB 的位置 | 世界存档目录内 `DistantHorizons.sqlite` | 若作为节点数据，必须纳入存档组/epoch/版本校验；若视为可丢弃派生缓存，则应移出权威存档并明确可重建。**当前实现两者都没有声明** |
| 服务端生成写 chunk | 默认 FEATURES 不写 region；`INTERNAL_SERVER` 写 region | 后者会修改节点权威世界存档，必须被 fencing/save-group 流程覆盖；**当前没有**（`Config.java:1493-1499`） |
| 玩家状态泄漏 | LOD payload 是地形/信标数据，不含玩家状态 | **不直接泄漏玩家状态**；但服务端会向任何已连接客户端发送其请求范围内的地形 LOD，属于世界数据出口，需按平台访问策略评估 |
| 客户端缓存位置 | `<gameDir>/Distant_Horizons_server_data/...`，不能用 JVM property 改 | 与本仓库 `-Djbp.lod.dir` / `<data_dir>/cache/lod` / `Context.getCacheDir()/lod` 的节点 scope 契约不一致（`REPO/docs/todo/jbp-lod.md:11`；`REPO/AGENTS.md` 的 JBP-LOD 决策）。DH 只提供 mod API `IDhApiSaveStructure.overrideFilePath` 作为重定向点（`DH-core/api/.../IDhApiSaveStructure.java:39-58`） |
| 供应链 | 单个 28 MB jar，嵌套 46 个 Fabric API 模块 + 多 ABI native | 需要按 `AGENTS.md:45,49` 新增锁条目、SHA-256、许可证、ABI 记录；嵌套 Fabric API 与仓库 0.162.0 的共存未验证 |
| 线程 | DB/生成/网络在 DH 线程池；未在 S23 测量 | 方向符合 `AGENTS.md:23`；实际 tick 影响未验证 |

### 5.3 “我们只需要客户端远景 LOD”时 DH 需要什么

- **最小形态**：客户端安装 DH（Fabric），服务器不装 DH；客户端记录服务器实际发来的区块并保存 LOD 缓存。远程服务器没有 DH 时，不能承诺生成/同步客户端从未收到的远景；本地 region 导入需要实际持有对应世界文件。缓存路径为 `<gameDir>/Distant_Horizons_server_data/...`（`DH-core/core/.../level/DhClientLevel.java:263-265,332-336,436-437`；`ClientOnlySaveStructure.java:183-190`）。完整运行未验证。
- **要满足平台 scope/epoch**：需要额外的 companion mod 实现 `IDhApiSaveStructure`，把 DH 的 save folder 重定向到节点 scope，并在路径/生命周期上接入节点/存档组/epoch；DH 本身不会校验这些。或者接受“LOD 是可丢弃、可重建的每节点缓存”，把它排除在权威玩家状态之外，并明确删除/重建策略。**这是设计选择，不是本文的建议。**
- **要服务端 LOD 同步**：服务器安装 DH，接受 `DistantHorizons.sqlite` 写入世界存档、接受 Level Keys/同步配置；若启用 `INTERNAL_SERVER`，还要接受 region 文件被写。**这两项都与当前平台不变量有冲突点，需要显式设计。**

## 6. DH 与 JBP-LOD 对比

JBP-LOD 的状态以仓库当前文档为准；注意 `docs/todo/jbp-lod.md` 的部分“当前实现边界”文字落后于证据文档（它仍说 live ingest/mesher 缺失，但源码已有 `LiveChunkCapture`/`LiveSectionProducer`/`WorldImporter`，见 `REPO/mods/jbp-lod/src/main/java/.../data/ingest/`、`.../importing/WorldImporter.java:46-60`；证据 `REPO/mods/docs/jbp-lod-evidence/V3.md:95-157`）。这是文档漂移，不是本文的代码判断。

| 维度 | Distant Horizons 3.3.4-26.3 | JBP-LOD（当前工作区） |
| --- | --- | --- |
| 版本/维护 | 上游成熟项目，3.3.5-dev main，多 MC 版本；Modrinth release 3.3.4 | 本仓库自研，V0 DONE；V1 未通过；V2/V3/V4 DOING；V5/V6 TODO（`REPO/docs/todo/jbp-lod.md:17-108`） |
| 渲染后端 | Blaze3D/RenderPearl；26.3 默认 BLAZE_3D；不直接建 VkDevice（`VersionConstants.java:115-121`；`BlazeDhTerrainRenderer.java:62-63`） | 直接共享游戏 `VkDevice`，自建 compute/HiZ/indirect 渲染（`REPO/mods/docs/jbp-lod-evidence/V1.md:32-43`；`V4.md:57-82`） |
| 地形数据 | 64×64 列 + 高度图；SQLite（`FullDataSourceV2.java:76-115`；`LodDataBuilder.java:64-79`；`ISaveStructure.java:29`） | 32³ packed voxel sections + 层级；RocksDB JNI（`REPO/mods/docs/jbp-lod-evidence/V3-storage-decision.md:1-30`；`REPO/mods/jbp-lod/build.gradle:38`） |
| 绘制方式 | CPU 构建 VBO/IBO，逐 buffer `drawIndexed`；CPU frustum/LOD 树剔除；无 compute/indirect（`BlazeDhTerrainRenderer.java:336-372`；`RenderBufferHandler.java:154-195`） | Vulkan compute 预 pass + HiZ + indirect draw；GPU 端命令生成/剔除（`V4.md:57-82`） |
| 数据来源 | 已探索 region 导入 + 默认本地世界生成（SURFACE_THEN_CHUNKS/FEATURES）；`INTERNAL_SERVER` 会写 region（`Config.java:1440-1500`；`DhChunkGenerator.java:380-433,651-657`） | live capture + `WorldImporter` 读 region；**没有世界生成**（`V3.md:95-157`；`WorldImporter.java:46-60`） |
| 近重叠 | 私有 color/depth + 径向近裁剪 + 当前帧深度 fade；无 stencil（`RenderUtil.java:131-245`；`BlazeVanillaFadeRenderer.java:183-204`） | stencil 种子 + 已加载区块 AABB bound + 近范围排除 + composite depth test（`REPO/mods/docs/jbp-lod-evidence/V4.md:88-122,315-452`） |
| Sodium/Iris | 基础 LOD 不依赖 Sodium；Iris shaderpack 时 mixin Sodium `SodiumChunkSection.renderGroup`；Sodium 0.9.2 签名匹配（`MixinChunkSectionsToRender.java:154-186`；Sodium jar javap） | 直接 mixin Sodium `DefaultChunkRenderer` 的 prepare/render，依赖内部签名；当前锁 Sodium 0.9.2（`REPO/mods/jbp-lod/gradle.properties:9`；`REPO/docs/todo/jbp-lod.md:33-44`） |
| 26.3 PC 实机验证 | 有 release 构建；open Vulkan 崩溃 #1348（AMD）；RTX 3060 Ti 组合未验证 | 无 V1/V4 实机验收；llvmpipe RD2 可见（45.4% 像素差异）；真实 GPU 仍有 speckle/覆盖问题（`V4.md:123-133,185-239,452-480`） |
| Android | 上游明确不修 Android Zstd/SQLite 问题（#1178/#1253 Not Going To Fix）；无 Android ABI native | 设计上按节点 scope/`Context.getCacheDir()/lod`/16 KiB 门禁，但 V6 未开始，RocksDB Android 未验证（`REPO/docs/todo/jbp-lod.md:99-108`） |
| 平台 scope | 只按 level/serverKey；无节点/存档组/epoch；缓存路径固定在 gameDir | 明确 node/dev scope、`lod.lock`、`-Djbp.lod.dir`；但 V0 的节点 scope 是缓存隔离，不等于平台存档 epoch（`REPO/docs/todo/jbp-lod.md:11,17-31`） |
| 控制力 | 配置 + API 事件；深度改造需 fork（LGPL） | 完全自有 pipeline/shader/数据结构；但全部要自己维护 |
| 维护成本 | 上游维护；多版本预处理、46 个嵌套模块、多 ABI native；LGPL 修改义务 | 31,089 行 Java / 206 文件 / 250 测试（本次统计）；强耦合 RenderPearl/Vulkan/Sodium 内部 API；V1–V4 未验收 |
| 与 Vulkan-only 启动 | 兼容（Blaze3D 默认；OpenGL 引擎会被 API mismatch 拒绝） | 兼容（设计目标就是 Vulkan-only） |

## 7. 若 PC 采用 DH，JBP-LOD 哪些仍有复用价值、哪些会沉没

**可复用（与 DH 渲染器无关的部分）：**

1. **Launcher 的 Vulkan-only 强制**：`preferredGraphicsBackend:"vulkan"` + `--graphicsBackend vulkan`、设置/UI 不提供 OpenGL，是启动器层能力，和 LOD 模组选择无关（`REPO/docs/decisions/0036-vulkan-only-graphics-backend.md:18-20,37-41`）。
2. **节点 scope/路径/锁模型**：`-Djbp.lod.dir`、`<data_dir>/cache/lod`、`<base>/nodes/<sanitized nodeId>/`、`lod.lock`、dev scope 规则（`REPO/docs/todo/jbp-lod.md:11,17-31`）。DH 通过 `IDhApiSaveStructure` 允许重定向 save folder，可作为 companion mod 的接入点；JBP-LOD 这层设计可复用为平台包装，而不必复用其渲染器。
3. **Android 侧的规划与门禁**：`:game` classpath、`Context.getCacheDir()/lod`、ARM64/bionic/16 KiB 检查、原生库来源/哈希记录（`REPO/docs/todo/jbp-lod.md:99-108`；`REPO/docs/decisions/0017-android-runtime-architecture-and-binder-ipc.md:53-58`）。这些规则对 DH 的 Android 化同样适用。
4. **RenderPearl/Vulkan bridge 知识**：`GameVulkan` 快照、同队列提交、frame-slot retirement、借用 target 生命周期、`FrontendRenderPass`/`VulkanRenderPass` 的版本耦合记录（`REPO/mods/docs/jbp-lod-evidence/V1.md:32-43`）。DH 的 Blaze3D 路径不需要这套 bridge，但这些知识可用于其他 Vulkan 模组/诊断。
5. **Shader 工具链与设备能力探测**：运行时 shaderc→SPIR-V、SPVC reflection、`DeviceCaps`、fallback 设计（`REPO/docs/todo/jbp-lod.md:46-60`）；对任何 Vulkan 渲染工作可复用。
6. **测试与证据方法**：llvmpipe/真实 GPU 分离、readback 诊断、截图矩阵、验收边界（`REPO/mods/docs/jbp-lod-evidence/V4.md:15-56,315-480`）。这是流程资产。

**可能沉没（若 DH 承担 PC LOD）：**

- V3/V4 的 Vulkan compute/HiZ/node tree/indirect renderer、voxel hierarchy、section mesher、GPU 端命令生成与大部分 250 个测试（`REPO/mods/docs/jbp-lod-evidence/V4.md:57-82,315-480`）。这些是为“自研 Vulkan LOD”写的，DH 用列 + CPU buffer，不能直接替换。
- RocksDB 存储 schema 与 V3 storage decision（DH 用 SQLite；`REPO/mods/docs/jbp-lod-evidence/V3-storage-decision.md:1-30`）。
- Voxy 算法 1:1 迁移（`REPO/docs/decisions/0036-vulkan-only-graphics-backend.md:20-21`）。
- V1 的硬件 gate 与 V2 的 shader/DeviceCaps 验收，如果未来不再自研 LOD 渲染器；但第 4/5 项的知识仍可保留。
- **不一定沉没**：V0 的 scope/lock/path 代码可作为 DH 的 save-structure 包装；需要写 companion mod 或 fork，而不是直接复用渲染器。

## 8. 待决问题与可执行的实验（不推荐任何选项）

以下问题需要用户决定；每项给出能区分事实的实验。所有实验都应记录设备、OS、驱动、游戏/模组版本、jar hash、日志路径和截图。

1. **DH 3.3.4 在 RTX 3060 Ti Windows + Vulkan-only + Sodium 0.9.2 + Fabric API 0.162.0 上是否可稳定进入世界并显示 LOD？**
   - 实验：干净 Fabric 26.3 实例（Java 25），`preferredGraphicsBackend:"vulkan"`，先只装 DH 3.3.4，再逐步加入 Sodium 0.9.2，记录 F3 的 device/backend、FPS、VRAM、日志；对照 open issue #1348 的 AMD 崩溃，确认 NVIDIA 是否复现。
   - 成功判据：进入世界无 crash；LOD 可见；F3/日志显示 Vulkan；无 `[DH]` ERROR/validation 错误；记录 10 分钟稳定性。
2. **DH 内嵌 Fabric API 0.160.5 与仓库锁定的 0.162.0 是否冲突？**
   - 实验：用启动器实际 classpath（Fabric API 0.162.0）加载 DH，检查 Fabric Loader 的 mod 解析日志、重复 ID/版本选择；若冲突，再测试去掉 DH 内嵌 Fabric API 的 repack（需 LGPL 合规评估）。
   - 成功判据：Loader 日志明确选择了哪个版本；无 duplicate mod/NoSuchMethod；Sodium/Iris 正常。
3. **客户端-only 模式在“无 DH 服务端”的节点上是否完整可用？**
   - 实验：节点不装 DH，客户端装 DH，进入世界；断开/重连/切服；检查 `<gameDir>/Distant_Horizons_server_data/` 的目录命名、缓存是否复用、是否有服务器未安装 DH 的报错。
   - 成功判据：客户端能记录已收到区块、重连后复用 LOD；切服目录不串；无服务端协议错误。未收到区块的远景应保持缺失，不能当作自动世界生成验收。
4. **DH 的缓存路径/生命周期能否满足节点 scope 与 epoch？**
   - 实验：用 companion mod 实现 `IDhApiSaveStructure`，把 save folder 指到 `/tmp` 的 per-node 目录；模拟节点 A→B 转服（同 IP/不同世界、同世界不同 epoch），检查旧目录是否被复用、是否需要删除/重建；检查 `lod.lock` 类互斥是否存在。
   - 成功判据：能证明路径隔离；能证明旧 epoch 数据不会被新节点读取；否则记录为“不满足”。
5. **服务端安装 DH 时，`DistantHorizons.sqlite` 是否必须纳入存档组/epoch？**
   - 实验：节点 A 生成 LOD → 转服到 B（不复制 `DistantHorizons.sqlite`）→ 检查 B 的行为；再复制 DB 但使用旧 epoch，检查是否被读取；同时用 `generatorPlan=DISABLED` 对照。
   - 成功判据：明确 DB 是“可丢弃派生缓存”还是“必须迁移的世界数据”；若必须迁移，记录它缺少的版本/epoch 字段。
6. **默认 `FEATURES` 是否真的不写 region，`INTERNAL_SERVER` 是否真的写？**
   - 实验：对世界目录做前后 SHA-256/文件清单；分别跑默认与 `INTERNAL_SERVER` 生成；对比 `region/*.mca` 的变化。
   - 成功判据：文件清单差异与 `Config.java:1493-1499` 的注释一致；若默认也写，记录为平台风险。
7. **Android 是否有可维护的可行路径？**
   - 实验：为 bionic/ARM64 构建或获取 SQLite/Zstd 原生库（或替换为纯 Java 实现），repack DH；在 Galaxy S23 的 `:game` 进程/Java 25 上测试启动、DB、LOD；检查 `java.awt` 可用性、16 KiB 对齐、`dlopen` 日志。
   - 成功判据：`Initializer.preConfigInit()` 通过；能进入世界；能读写 `Context.getCacheDir()/lod` 类路径；无 native `UnsatisfiedLinkError`。若失败，记录为“上游明确不支持”。
8. **DH + Iris shaderpack 在 Vulkan 26.3 的可达性（若未来需要 shader）**
   - 实验：DH 3.3.4 + Iris 1.11.7 + Sodium 0.9.2，Vulkan 后端；分别测试无 shaderpack / 一个 shaderpack；检查 Iris 的 `usingVulkan` 日志、DH 的 Sodium mixin 是否触发、是否 crash。
   - 成功判据：无 crash 且 LOD/透明正确；否则记录“不在当前范围/不可用”。本仓库 ADR 0036 目前把 Iris Vulkan 列为后续范围（`REPO/docs/decisions/0036-vulkan-only-graphics-backend.md:10,38`）。
9. **内存/显存与生成成本**
   - 实验：3060 Ti 与 S23 上固定世界、固定 radius（如 128/256），记录 DH 的 RSS/VRAM、DB 大小、生成队列、帧时间；对照 JBP-LOD 的 llvmpipe/真实 GPU 数据。
   - 成功判据：得到可重复的峰值与稳态数据；没有数据就保持“未验证”。
10. **许可证/分发方式**
    - 实验/决策：确认是“原样分发 jar”还是“fork/repack（Android、去嵌套 Fabric API、改 save path）”；前者只需锁条目/哈希/许可证，后者触发 LGPL-3.0 源码与通知义务。**这是合规问题，不是代码实验。**

## 附录 A：本次未能验证的断言

- DH 在 RTX 3060 Ti、S23、任何真实 GPU 上的 Vulkan 渲染结果、帧率、内存。
- DH + Sodium 0.9.2 的非 Iris 路径运行时行为（源码显示基础 LOD 不依赖 Sodium，但没有跑客户端）。
- DH + Fabric API 0.162.0 的 Loader 解析结果。
- DH 的 `java.awt` 在 Pojav/FCL JRE 上是否可用。
- DH 服务端无客户端模组时的完整协议行为（源码显示有本地 fallback，但未运行）。
- Android 上重新编译 SQLite/Zstd 的可行性、16 KiB 对齐、bionic 加载与 S23 性能。
- LGPL-3.0 在“原样分发/repack/companion mod”三种情形下的具体义务（需按分发方式确认，非法律意见）。

## 附录 B：关键源码索引

| 主题 | 文件:行 |
| --- | --- |
| 引擎选择/后端匹配 | `DH/common/src/main/java/com/seibel/distanthorizons/common/wrappers/DependencySetup.java:133-170,196-200` |
| 26.3 默认 BLAZE_3D | `DH/common/src/main/java/com/seibel/distanthorizons/common/wrappers/VersionConstants.java:115-121` |
| Vulkan 后端识别 | `DH/common/src/main/java/com/seibel/distanthorizons/common/wrappers/minecraft/MinecraftRenderWrapper.java:559-590` |
| Blaze 设备/命令编码器 | `DH/common/src/main/java/com/seibel/distanthorizons/common/render/blaze/BlazeDhTerrainRenderer.java:62-63` |
| Blaze pipeline（depth/compare） | `DH/common/src/main/java/com/seibel/distanthorizons/common/render/blaze/BlazeDhTerrainRenderer.java:101-150` |
| 私有 color/depth + 拷贝回 MC | `DH/common/src/main/java/com/seibel/distanthorizons/common/render/blaze/BlazeDhMetaRenderer.java:31-32,83-89` |
| 逐 buffer drawIndexed | `DH/common/src/main/java/com/seibel/distanthorizons/common/render/blaze/BlazeDhTerrainRenderer.java:336-372` |
| RenderPass/GpuBuffer 包装 | `DH/common/src/main/java/com/seibel/distanthorizons/common/render/blaze/wrappers/RenderPassWrapper.java:42-164` |
| CPU→GpuBuffer 上传 | `DH/common/src/main/java/com/seibel/distanthorizons/common/render/blaze/wrappers/buffer/BlazeVertexBufferWrapper.java:119-191` |
| 26.3 非 Iris 渲染 hook | `DH/fabric/src/main/java/com/seibel/distanthorizons/fabric/mixins/client/MixinLevelRenderer.java:275-323` |
| Iris + Sodium 0.9.2 hook | `DH/fabric/src/main/java/com/seibel/distanthorizons/fabric/mixins/client/MixinChunkSectionsToRender.java:154-186` |
| Iris mixin 存在性门控 | `DH/common/src/main/java/com/seibel/distanthorizons/common/commonMixins/AbstractDhMixinPlugin.java:50-62` |
| 列数据 | `DH-core/core/src/main/java/com/seibel/distanthorizons/core/dataObjects/fullData/sources/FullDataSourceV2.java:76-115` |
| 列 box 生成 | `DH-core/core/src/main/java/com/seibel/distanthorizons/core/dataObjects/render/bufferBuilding/ColumnRenderBufferBuilder.java:58-117`；`ColumnBox.java:58-100` |
| SQLite/JDBC | `DH-core/core/src/main/java/com/seibel/distanthorizons/core/sql/repo/AbstractDhRepo.java:52,103`；`DH-core/core/build.gradle:42-45` |
| DB 路径 | `DH-core/core/src/main/java/com/seibel/distanthorizons/core/file/structure/ISaveStructure.java:29`；`FullDataSourceProviderV2.java:114` |
| 启动期原生库/Zstd 硬检查 | `DH-core/core/src/main/java/com/seibel/distanthorizons/core/Initializer.java:76-100` |
| 生成计划/模式 | `DH-core/core/src/main/java/com/seibel/distanthorizons/core/config/Config.java:1440-1500` |
| region 导入/PRE_EXISTING_ONLY | `DH/common/src/main/java/com/seibel/distanthorizons/common/wrappers/worldGeneration/DhChunkGenerator.java:380-433,651-657` |
| 服务端 DB/同步 | `DH-core/core/src/main/java/com/seibel/distanthorizons/core/multiplayer/server/FullDataSourceRequestHandler.java:58-140`；`LocalSaveStructure.java:54-74` |
| 本仓库 JBP-LOD 状态 | `REPO/docs/todo/jbp-lod.md:17-108`；`REPO/mods/docs/jbp-lod-evidence/V4.md:3,57-86,123-133,185-239,315-480` |
| 本仓库 JBP-LOD Android 门禁 | `REPO/docs/todo/jbp-lod.md:99-108`；`REPO/docs/decisions/0019-android-16k-page-aligned-runtime.md:9` |
| 本仓库 Vulkan-only | `REPO/docs/decisions/0036-vulkan-only-graphics-backend.md:18-21,37-41` |
| 本仓库平台不变量 | `REPO/AGENTS.md:23,26,31-32,45,49-50` |

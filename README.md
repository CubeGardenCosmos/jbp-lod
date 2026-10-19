# JBP-LOD

JBP-LOD 是 Minecraft 26.3 + Fabric 的客户端远景 LOD 模组，Java 根包为
`cn.cubegarden.cosmos.jbp.client.lod`，目标 Java 25、Vulkan-only，并与游戏共享同一个
`VkDevice`（不创建第二个 device，不提供 OpenGL 回退）。

本仓库于 2026-10-19 从平台单仓库（`/home/klp/Minecraft-Server-Launcher`）的
`mods/jbp-lod` 拆分而来，保留了该目录的完整提交历史。仓库目前仅存在于本机；远程地址待用户创建
（TBD，未配置 remote）。平台侧决策记录见单仓库
`docs/decisions/0038-lod-repo-split-and-dh-baseline.md`。

## 参考基线（已决定，2026-10-19）

- **暂时以 Distant Horizons（DH）的实现为准**，作为 LOD 的行为与架构参考。这是用户明确决定，
  取代“忠实 1:1 移植 Voxy”作为当前实现基线。
- 该决定只建立参考基线，**不构成对 DH 的支持声明，也不表示已经移植 DH 代码**。
  是否移植 DH 代码、PC 端是否直接采用 DH 发行包、是否保留 JBP-LOD 自有渲染器，均**尚未决定**。
- 封存的 `mods/voxy`（上游 `MCRcortex/voxy`，基线
  `247adf2625324782e13a3fa8b646c0f95e68031b`）是只读历史参考，其许可证为
  “All rights reserved / Do not redistribute”；本仓库不复制、不重分发其未授权内容。
  派生关系与来源记录见 `NOTICE`。
- DH 本体为 **LGPL-3.0**。若修改、重打包或派生 DH，需按 LGPL-3.0 履行源码、通知与可重链接义务；
  这里只是合规提醒，不是法律意见。
- DH 发行包只带 glibc/musl 的 SQLite/Zstd 原生库，没有 Android ABI；Android 方案是由我们自己构建
  ARM64/Android 原生库（待实施）。细节与证据见 `docs/dh-vulkan-evaluation.md` 第 4 节。

## 文档

- `docs/todo.md`：V0–V6 实施与验收计划。
- `docs/dh-vulkan-evaluation.md`：DH 在 Minecraft 26.3 / Fabric / Vulkan 上的评估、Android 阻断、
  许可证与逐项对比。
- `docs/jbp-lod-evidence/`：V0–V4 的实现边界、存储决策、契约与验收证据。
- `docs/voxy-26.3-render-decision.md`：封存 Voxy 的 26.3 渲染后端历史调查。

这些文档从平台单仓库复制而来，正文中的部分相对链接仍指向单仓库的 `mods/docs/`、`docs/`
路径（本仓库没有这些文件），因此可能无法直接打开；证据内容保持原样。

## 构建

```bash
JAVA_HOME=/usr/lib/jvm/java-25-openjdk ./gradlew compileJava test lodJar
```

需要 Java 25 与 Gradle Wrapper（仓库自带，Gradle 9.8.1 由 wrapper 锁定）。`test` 是纯 Java
单元测试，不启动 Minecraft；`lodJar` 生成 `build/libs/jbp-lod-0.1.0.jar`。

**注意包体：** 模组 jar 通过 `include` 打包 `org.rocksdb:rocksdbjni:10.2.1`，其中
`META-INF/jars/rocksdbjni-10.2.1.jar` 约 **72 MB**（72,770,192 B），最终 jar 约 **70 MiB**
（72,999,447 B）。这是当前桌面存储后端的直接结果，不是可忽略的构建噪音。

`run/` 与 `build/` 已在 `.gitignore` 中忽略。

## 开发运行

从仓库根目录直接启动 Vulkan-only 开发客户端。`run/` 是 Loom 的开发运行目录：

```bash
JAVA_HOME=/usr/lib/jvm/java-25-openjdk \
  ./gradlew runClient --args='--graphicsBackend vulkan --quickPlaySingleplayer DevWorld'
```

单机 scope 仅在 Fabric 开发环境（或显式 `-Djbp.lod.devBuild=true`）下可用：

```bash
JAVA_HOME=/usr/lib/jvm/java-25-openjdk \
JAVA_TOOL_OPTIONS='-Djbp.lod.devScope=true' \
  ./gradlew runClient --args='--graphicsBackend vulkan --quickPlaySingleplayer DevWorld'
```

`devScope=true` 使用当前 world folder ID；也可设为自定义 ID。开发环境多玩家可用
`-Djbp.lod.devNode=<id>` 覆盖反射式节点 provider。先启动本地离线服
（`online-mode=false`，例如 `127.0.0.1:25567`），再运行：

```bash
JAVA_HOME=/usr/lib/jvm/java-25-openjdk \
JAVA_TOOL_OPTIONS='-Djbp.lod.devNode=local-offline' \
  ./gradlew runClient --args='--graphicsBackend vulkan --quickPlayMultiplayer 127.0.0.1:25567'
```

### devScript 验收脚本

开发专用脚本由 `-Djbp.lod.devScript=<文件路径或内联脚本>` 启用，只在 Fabric 开发环境生效，
发布 jar 中不存在入口。每一步以“世界、内置服务器与 LOD runtime 就绪后的客户端 tick 数”为键：

```text
20 cmd gamerule doDaylightCycle false
30 rd 8
40 tp auto 0 0
50 tprel 150 0 0
60 look -90 10
70 draw off
80 shot rd2_lod_on
90 stats
100 quit
```

也可以内联传入（`;` 分隔）：

```bash
JAVA_HOME=/usr/lib/jvm/java-25-openjdk \
JAVA_TOOL_OPTIONS='-Djbp.lod.devScript=30 rd 8; 50 tprel 150 0 0; 80 shot rd2_lod_on; 100 quit' \
  ./gradlew runClient --args='--graphicsBackend vulkan --quickPlaySingleplayer DevWorld'
```

支持的动作：`cmd`、`rd`、`tp`、`tprel`、`look`、`draw on|off`、`shot <name>`、`stats`、`quit`。
截图写入 `-Djbp.lod.devShotDir=<dir>`（默认 `/tmp/jbp-lod-shots`）。日志前缀为
`[JBP-LOD] devScript:`。`-Djbp.lod.devQuitAfterTicks=N` 可在世界加载 N tick 后退出客户端。

### JVM 属性

| 属性 | 作用 |
| --- | --- |
| `-Djbp.lod.dir=<path>` | 覆盖缓存根；桌面默认 `<data_dir>/cache/lod`，未设置时 `<gameDir>/.lod`；Android 为 `Context.getCacheDir()/lod` |
| `-Djbp.lod.devScope=true\|<id>` | 开发环境启用单机 scope；`true` 取 world folder ID，自定义值替换它，目录在 `<base>/dev/<sanitized id>/` |
| `-Djbp.lod.devNode=<id>` | 开发环境覆盖多玩家节点 provider |
| `-Djbp.lod.devBuild=true` | 在非 Fabric development 环境仍允许开发 scope 逻辑 |
| `-Djbp.lod.devScript=<path\|inline>` | 启用开发验收脚本 |
| `-Djbp.lod.devShotDir=<dir>` | devScript 截图目录 |
| `-Djbp.lod.devQuitAfterTicks=N` | 世界加载 N tick 后停止客户端 |
| `-Djbp.lod.importWorld=true` | 开发单机启动时从 region 目录自动导入（`WorldImporter`）；发布默认关闭 |
| `-Djbp.lod.render=false` | 关闭 LOD 渲染器（诊断用） |
| `-Djbp.lod.geometryMiB=<n>` | 覆盖 packed-quad 几何堆大小 |
| `-Djbp.lod.nearExclusionMarginBlocks=<n>` | 近景排除盘向内收缩的额外安全量（默认 0，只会向内） |
| `-Djbp.lod.vkProbe=true` / `-Djbp.lod.vkProbeInterval=<n>` | Vulkan bridge 排序/标记 probe |
| `-Djbp.lod.debugView=<normal\|uv\|face\|model\|depth\|level\|pass\|bound\|stencil\|light\|texture>` | LOD 调试视图 |
| `-Djbp.lod.debugReadback=true` / `-Djbp.lod.debugReadbackIntervalFrames=<n>` | GPU 读回诊断与采样间隔 |
| `-Djbp.lod.debugFog=true` | 输出 LOD fog uniform 诊断 |
| `-Djbp.lod.debugSolid=true` | 用纯色（magenta）绘制 LOD，排查几何/覆盖 |

不保留旧 Voxy 的 `-Djbp.voxy.dir` alias。

## 状态（2026-10-19）

- 构建与纯 Java 单测可运行；V0 scope/path/lock 与 V1–V4 的大量源码接线已存在。
- **没有真实 GPU 验收。** V1 的硬件 Vulkan/validation 门禁未通过；V4 只有 WSL2 llvmpipe
  软件 Vulkan 的 dev-scope smoke（RD8 采集、teleport、LOD on/off 截图），不能替代 Windows/Linux
  实机、Vulkan validation 或视觉正确性验收。
- Android 尚未开始（V6 TODO）；RocksDB、shaderc、spvc、VMA 的 bionic/ARM64/16 KiB 加载均未验证。
- 已知视觉问题与未决项见 `docs/jbp-lod-evidence/V4.md` 和 `docs/todo.md`。不要把源码接线、
  单元测试或 llvmpipe 结果当作“已支持 LOD”的证据。

## 许可证与来源

本仓库包含派生自上游 Voxy 的实现与 shader；来源、文件映射与上游许可声明见 `NOTICE`。该记录
不授予重分发上游或派生材料的权利。DH 仅作为参考基线，其代码未复制进本仓库。

# JBP-LOD V0–V6：Vulkan LOD 实施与验收计划

> **2026-10-19 仓库与基线变更：** JBP-LOD 已拆分到独立仓库 [`CubeGardenCosmos/jbp-lod`](https://github.com/CubeGardenCosmos/jbp-lod)（保留 `mods/jbp-lod` 历史）。LOD 的行为/架构参考基线暂时以 Distant Horizons 实现为准；仓库拆分、基线边界、Android 原生库自建与未决项见 [ADR 0038](https://github.com/CubeGardenCosmos/JubaopenCraft/blob/main/docs/decisions/0038-lod-repo-split-and-dh-baseline.md)。本文既有“以 Voxy 为长期 fork 方向”等表述**不覆盖** ADR 0038：是否移植 DH 代码、PC 是否采用 DH 发行包、是否保留 JBP-LOD 渲染器均尚未决定。单仓库中的 `mods/jbp-lod`、`mods/voxy` 与 LOD 文档副本已于 2026-10-19 移除；跨仓库链接指向 JubaopenCraft 仓库的当前文档。

日期：2026-10-18。V0 scope 与开发单人扩展代码、纯 Java 单元测试及当前 source/config 的 Gradle build 已完成。初始 Vulkan-only WSL2 llvmpipe client load 已记录；后续 dev-scope client 尝试未取得 scope lock，修正 world-folder normalization 后未再次启动游戏（pending: game smoke deferred by user）。V1 bridge 代码已就绪但硬件/validation 阶段门禁未通过；V2–V4 为部分源码实现/验收未完成，V5–V6 仍为 **TODO**。Main 最终 Java 25 `compileJava test lodJar` 为 BUILD SUCCESSFUL（10s），报告 112 项全套测试通过，见 [build output](artifact://167)。证据不代表真实 Windows/Linux Vulkan 硬件验收或任何 LOD 渲染验收。

- 决策：[ADR 0036：全平台仅使用 Vulkan 游戏图形后端](https://github.com/CubeGardenCosmos/JubaopenCraft/blob/main/docs/decisions/0036-vulkan-only-graphics-backend.md)（含 2026-10-18 决策历程补充）。
- 设计：[JBP-LOD Vulkan 渲染核心设计](voxy-vulkan-core-design.md)。
- 兼容性：[Minecraft 26.3 / Fabric](https://github.com/CubeGardenCosmos/JubaopenCraft/blob/main/docs/compatibility/minecraft-26.3.md)、[平台支持矩阵](https://github.com/CubeGardenCosmos/JubaopenCraft/blob/main/docs/architecture/platform-support.md)。
- 历史参考：[旧 Voxy V0–V4 TODO（已封存，不等同于本文阶段）](ysm-voxy-todo.md)、[旧 Voxy Vulkan 核心研究](voxy-vulkan-core-design.md)。`mods/voxy` 只读封存，供研究参考。
- Distant Horizons 参考：LGPL-3.0，仅本地只读 clone 到 `/home/klp/DH-reference`，未复制进本仓库；数据可用性/重叠/column 对比与建议见 [DH-reference.md](jbp-lod-evidence/DH-reference.md)。

**状态与边界：** JBP-LOD 是独立新模组，已在 `mods/jbp-lod` 建立，Java 根包为 `cn.cubegarden.cosmos.jbp.client.lod`；V0 scope/storage 基础完成，compute/HiZ/node-tree/runtime 与 opaque renderer 已有源码接线，但 live section ingest/mesher 缺失，非零输出与 GPU 渲染未验收。Release/launcher builds 保持多玩家 server-only scope，单人 inactive；开发环境可按 `-Djbp.lod.devScope` 显式启用本地单人 scope。PC client manifest 不再含 Voxy；launcher 已使用 JVM property `-Djbp.lod.dir` 并将桌面缓存根设为 `<data_dir>/cache/lod`。旧 `mods/voxy` 自身仍保留封存的旧系统属性 `jbp.voxy.dir`，该属性不是 JBP-LOD alias 或兼容入口。可选 manifest 字段的规范名称为 `lod_map_cache`；不接受历史字段名作为 alias。

**已决定：** 新模组采用 Vulkan compute + raster，忠实迁移旧 Voxy 算法语义，与 Minecraft 共用现有 `VkDevice`；不走 Zink/Kopper GL compatibility route A，不建第二个 device，也不把核心改造成 RenderPearl 公共 API 的薄封装。先完成 Windows/Linux 桌面路径，再适配 Android。以 `MCRcortex/voxy` 为长期 fork 方向；封存的本地 `mods/voxy` 不原地改名、修补或继续开发。

**路径和 scope（已决定）：** launcher JVM property 为 `-Djbp.lod.dir`，桌面缓存根为 `<data_dir>/cache/lod`，未提供 property 时 JBP-LOD 使用 `<gameDir>/.lod`；Android 缓存根为 `Context.getCacheDir()/lod`。Release/launcher 构建只使用 `<base>/nodes/<sanitized nodeId>/` 的多玩家节点 scope；开发环境仅在 `-Djbp.lod.devScope=true` 或自定义 ID 时允许单人 scope，`true` 使用当前 world folder ID，自定义 ID 替换它，路径为 `<base>/dev/<sanitized ID>/`，绝不放在 `nodes/` 下。两类 ID 均按 `[^A-Za-z0-9._-]` → `_` 清理并拒绝空值、`.`、`..`；仅在开发环境 `-Djbp.lod.devNode=<id>` 可覆盖多玩家 provider。新模组不接受旧 property alias。

## V0 封存旧模组并建立 JBP-LOD 骨架（DONE：代码/build/tests；dev-scope smoke deferred）

- **先决条件**：Vulkan-only 与路线 B 已决定（[ADR 0036](https://github.com/CubeGardenCosmos/JubaopenCraft/blob/main/docs/decisions/0036-vulkan-only-graphics-backend.md)）；本计划、设计与新模组边界完成文档对齐；Fabric/Minecraft 26.3 + Java 25 工具链可用。
- **工作项**：
  - [x] 将 `mods/voxy` 封存为只读参考；不在旧目录继续实现、修复或改名。旧代码里的 `jbp.voxy.dir` 仅保留作历史，不属于 JBP-LOD 契约。
  - [x] 创建 `mods/jbp-lod` 的独立 Gradle/Fabric 模组与 `cn.cubegarden.cosmos.jbp.client.lod` Java 根包；不实现或加载 renderer。
  - [x] 重提供节点 scope 查询：缓存反射查询 `cn.cubegarden.cosmos.jbp.client.api.JbpNodeIdProvider.currentNodeId()`；节点 ID 缺失时保持 inactive，不创建实例、不打开 storage。
  - [x] 限定单人 dev scope 为 Fabric development environment 且 `-Djbp.lod.devScope` 是 `true` 或自定义 ID；release/launcher 单人保持 inactive。开发环境多玩家可用 `-Djbp.lod.devNode=<id>` 覆盖 provider。
  - [x] 实现 `-Djbp.lod.dir` 与 property 缺失时 `<gameDir>/.lod` fallback；Android root helper 约定为 `Context.getCacheDir()/lod`。不设置旧名 alias。
  - [x] 多玩家目录为 `<base>/nodes/<sanitized nodeId>/`；开发单人目录为 `<base>/dev/<sanitized world folder ID or custom ID>/`，不得放入 `nodes/`。两类 ID 均按 `[^A-Za-z0-9._-]` → `_` 清理，拒绝空值、`.` 与 `..`。
  - [x] 为 node/dev scope 目录增加独占 `lod.lock`；冲突时禁用实例并给出诊断。日志 tag 为 `[JBP-LOD]`，inactive reason 按状态只记录一次。
  - [x] 使用 Fabric `JOIN`/`DISCONNECT`/`CLIENT_STOPPING` hooks 启停 scope；活动 session 在断连和客户端停止时关闭并释放锁。
- **交付物**：可独立构建的 JBP-LOD Gradle/Fabric 模组、README 与 NOTICE/source record、scope/property/path/lock/logging 模块及纯 Java 单元测试。
- **验收结果**：最终 `compileJava test lodJar` 成功，10 项纯 Java 测试通过。初始 Vulkan-only 客户端在 WSL2 llvmpipe 上加载并进入 singleplayer；带 devScope 的尝试进入世界但报告 dev ID 无效、未取锁。world-folder `LevelResource.ROOT` normalization 已修正并通过编译/测试，受用户指示未再次启动游戏验证。真实 Windows/Linux Vulkan 硬件及 dev-scope lock log 仍待验收；无 LOD renderer 声明。
- **证据**：[V0 build、测试与客户端记录](jbp-lod-evidence/V0.md)。dev-scope 运行证据为 **pending: game smoke deferred by user**；初始 WSL Vulkan smoke 使用 llvmpipe，D3D12 Vulkan device 缺少 `VK_KHR_push_descriptor`。

## V1 Vulkan bridge spike 与阶段门禁（DOING；代码就绪，待 Vulkan 实机验证）

- **先决条件**：V0 新模组骨架；Minecraft 26.3 RenderPearl 与 Sodium 版本固定；真实硬件验收仍需要 Windows/Linux 桌面 Vulkan 与可用的 Vulkan validation / RenderPearl diagnostics。
- **工作项**：
  - [x] 实现 `RenderSystem.getDevice()` → `FrontendGpuDevice.backend` → `VulkanDevice` 的 fail-fast `GameVulkan` 快照，读取 game `VkInstance`/`VkPhysicalDevice`/`VkDevice`、graphics/compute/transfer queues 与 family、VMA、启用 feature/extension；不创建第二个 device。
  - [x] 实现 `FrontendRenderPass`/`VulkanRenderPass` 与 `DefaultChunkRenderer` CUTOUT hook；取得本 pass `VkCommandBuffer`、color/depth `VkImageView`/`VkImage`/format/extent、矩阵和 render area，并逐 pass 借用、不跨帧持有 view。当前锁定 RenderPearl backend 使用 `GENERAL` layout、单采样（1）；layout/sample 不是公开 getter，依赖该版本的 backend rendering 信息。
  - [x] 添加 opt-in `-Djbp.lod.vkProbe=true` 排序 probe：独立 primary command buffer 在 CUTOUT graphics command buffer 提交前向现有 graphics queue 提交 TOP_OF_PIPE timestamp；当前 CUTOUT scope 内记录 BOTTOM_OF_PIPE timestamp、debug label 和 8×8 magenta `vkCmdClearAttachments` marker。日志记录了提交先后、scope 状态与 marker；这是 bridge spike，不是 LOD compute 或 LOD draw 实现。
  - [x] 固定并记录 accessor、RenderPearl/Sodium 版本、同队列 ownership 与借用资源生命周期；WSL llvmpipe 的实际目标/log 见 [V1 证据与实机步骤](jbp-lod-evidence/V1.md)。
  - [ ] 补齐硬件 Vulkan + validation 实机验证和 timestamp query 结果；当前 WSL run 的三个 query slots 仍 in-flight，退出前没有读到 timestamp 值。后续整合 game smoke：**pending: game smoke deferred by user**；等待 Main 在渲染部分完成后安排，不再由此 slice 启动客户端。
- **交付物**：可编译的 bridge/accessor/probe 原型、同 graphics queue 双 command buffer 契约、WSL Vulkan 软件设备实际 CUTOUT 日志和 Windows/Linux 实机运行步骤。当前无 shader/LOD compute pipeline 或 LOD draw。
- **阶段门禁**：WSL2 实际启动 Vulkan llvmpipe 并在 CUTOUT 取得设备与目标、记录 magenta clear marker；但设备是软件 Vulkan，`VK_LAYER_KHRONOS_validation` 不可用，且本次 timestamp 查询结果未完成。故 V1 **未通过**；不得开始 V2–V6 renderer 实现。
- **验收条件**：[ ] Windows/Linux 桌面 Vulkan-only 游戏取得真实硬件游戏 VkDevice/queue/command buffer/color+depth views；无第二 device；Vulkan validation 与 RenderPearl debug 无 handle、layout、同步错误；通过可读 timestamp 结果证明 pre-pass → graphics 顺序，并验证 pass marker。当前 llvmpipe 运行不能替代该实机门禁。

## V2 Shader toolchain 与设备能力探测（DOING；代码与覆盖测试已写，验证待完成）

- **先决条件**：V1 bridge gate 通过；锁定的桌面 Vulkan device 与 JBP-LOD shader 输入/变体清单可复现。
- **工作项**：
  - [x] 使用游戏运行时内嵌 shaderc 实现 GLSL imports/defines 到 Vulkan SPIR-V 的编译，并列出全部纳入范围的旧 shader 和可选变体；源码到产物 trace 与测试生成 manifest 已实现。
  - [x] 加入 SPIR-V header/instruction 结构检查、SPVC parse、descriptor/stage-interface/push-constant reflection；shaderc 诊断包含 source path。
  - [x] 实现从 `GameVulkan` 快照查询和区分核心/可选能力的 `DeviceCaps`，包括 feature support/enabled、subgroup、相关格式、queue family、limits 与明确核心能力诊断；非零 indirect `firstInstance` 需要的 `drawIndirectFirstInstance` 是核心门槛，`multiDrawIndirect` 是 optional。
  - [x] 默认 `ivec2` quad 和无 subgroup prefix-sum 路径作为 shaderInt64/subgroup fallback；将默认、启用 int64/subgroup 与其他宏分支纳入 JUnit coverage。`drawIndirectCount`/`multiDrawIndirect` 是 Vulkan command 路径选择，不是 GLSL variant；由 `DeviceCaps` 暴露，V4 负责 fixed-count/single-command fallbacks。
- **交付物**：shaderc→SPIR-V 运行时编译/检查/reflection，29 个覆盖用例，DeviceCaps snapshot API；设备实际值和硬件验证尚未记录。
- **验收条件**：
  - [ ] 运行 unit tests 证明所有 shader/variant 生成并通过 SPIR-V structural/SPVC parsing checks；查看 generated descriptor/interface manifest。
  - [ ] 实际 game Vulkan device snapshot 记录其 features/formats/queues/limits，并证明可选 feature 关闭时 V4 的 draw command fallback 与 shader fallbacks 可用。
  - [ ] 缺失核心能力时产生明确诊断，不回退到 OpenGL。
  - [ ] 桌面游戏与 GPU 实机 smoke：**pending: game smoke deferred by user**；不得由本阶段启动客户端。

## V3 核心数据、存储接入与 Vulkan compute（DOING；部分资源/CPU API 已写，阶段未完成）


**当前实现边界：** 已有 VMA buffer/barrier/frame-slot retirement、section ABI/reference、`LodComputeExecutor` pass graph、HiZ pyramid、异步 node/tree 与 GPU delta upload、RocksDB session，以及 `JbpLodClient` → `LodSession`/`LodRuntime` 接线。新增真实 `SectionMesher` 已安装，迁移上游 voxel face-generation/scanline merging，并处理邻居剔除及异步 model publication；Fabric chunk/light/biome/dirty-section hooks 采集 immutable snapshots，worker 进行 voxelization、16³→8³→4³→2³→1³ hierarchy 更新、storage writes 和 node/邻居通知。已补 fixed-input mesher/hierarchy tests，本 slice 未执行；此前 baseline `compileJava test lodJar` 的 112 项通过不代表新增代码已验证。GPU correctness、resize/retirement validation 与 live ingest game smoke 均未验证，没有 acceptance 条件勾选。Evidence: [V3 implementation boundary](jbp-lod-evidence/V3.md), [storage decision](jbp-lod-evidence/V3-storage-decision.md), [V3 resource/API contract](jbp-lod-evidence/V3-contract.md).
- **先决条件**：V1 bridge gate、V2 shader/toolchain 与 capability baseline 通过；V0 scope API 固定。
- **工作项**：
  - [ ] 建立基于游戏同一 device 的 Vulkan buffer/resource 层与 VMA allocator 接入；实现 SSBO、indirect buffer、staging、生命周期/resize 管理及精确 compute→compute/vertex/indirect barriers。
  - [ ] 实现 section ingest 与 CPU/GPU buffer layout；保留旧算法语义与对应数据格式，不通过 CPU 同步读回或改写为 mesh shader 绕开原算法。
  - [ ] 忠实迁移 LOD mesh-generation compute：hierarchical traversal、可见性/队列、section preparation、prefix sum、translucent build 与 indirect command generation；按 scope 重建/关闭存储会话。
  - [ ] **部分完成/待验证**：桌面默认选择上游 RocksDB JNI 10.2.1 并记录本地 jar/repository evidence；Android ABI/bionic/16 KiB、目标端加载及存储 I/O 均 **待验证**。见 [V3 storage decision](jbp-lod-evidence/V3-storage-decision.md).
- **交付物**：已提供资源/layout/reference、compute/HiZ executor、node/tree 与 scope/storage/runtime 接线、桌面 RocksDB 记录及 live section producer/mesher source 和 fixed-input tests；非零 GPU correctness 与 acceptance evidence 尚未交付。
- **验收条件**：所有条件保持未验收；新增 section producer/mesher 的 integrated build/test/packaging 由 Main 统一执行并记录。GPU/game smoke **pending: game smoke deferred by user**。不得把 CPU references、pipeline wrapper 或 command-recording 日志当作非零 GPU 输出证据。

## V4 Vulkan LOD 绘制与 Sodium 合成（DOING；opaque renderer/model binding present, production terrain and GPU acceptance pending）

**2026-10-19 llvmpipe dev-scope smoke（不等于验收）：** 通过 dev-only `DevScript` 在软件 Vulkan 上完成 RD8 四点采集循环、返回、RD2 越 150 格 teleport 及 LOD on/off 截图；teleport 后 traversal 继续请求（`pendingRequests=428`），LOD 在 RD2 距相机超过 32 格处可见（on/off 差异 45.4% 像素，LOD 区域纯黑 0.94%、暗色 5.0%，连续帧差异 <0.1%，无 jbp-lod ERROR）。期间修复 reverse-Z quad shader 未定义导致 bounding gate 反转，并加入 source-side near-range exclusion（vanilla RD + 16 格 overlap margin，`cmdgen.comp`/`SceneUniform`，含纯逻辑测试）。仍存在黑色水面四边形与 vanilla/LOD 边界白色闪烁环，详见 [V4.md](jbp-lod-evidence/V4.md)。llvmpipe 不能替代 V1/V4 实机门禁，下列验收框保持未勾选。

**当前实现边界：** `VulkanLodRenderer` 已接 active runtime、model provider/descriptors、depth conversion/HiZ/cull/compute pre-pass 与 CUTOUT opaque indexed indirect draw，提供 indirect-count/multi-draw/single-command fallback；mixin 与 vanilla external-target provider 已接线，target validation、资源 key/state/lifecycle 代码存在。V3 live section ingest/mesher 仍缺失，非零 terrain output 未建立；translucent/temporal command regions 尚无 renderer draw consumer，CUTOUT hook 还受 Sodium `shouldDraw` 条件限制。Main 最终 build/test/packaging 已通过（112 项测试；[output](artifact://167)），但源码接线不等于实际绘制或同步正确；所有视觉、GPU、validation 与游戏运行结果均为 **pending: game smoke deferred by user**。详见 [V4 evidence](jbp-lod-evidence/V4.md)；不勾选验收。
- **先决条件**：V3 compute 数据与 buffer contract 通过；V1 已冻结当前帧 Sodium targets 与 command recording 边界。
- **工作项**：
  - [ ] 实现 Vulkan indexed indirect draw（含设备支持的 indirect-count 路径）、显式 depth/stencil/blend pipeline state 与 descriptor。
  - [ ] 将远景 LOD 画入 Sodium 当前目标，处理 depth-aware composite、color/depth views/layout、extent/format/sample count 与逐帧同步/barriers；不复制到第二 device，不做 readback。
  - [ ] 定义外部 shader pipeline 扩展接口：允许外部 provider 提供 LOD draw 使用的当帧 G-buffer targets 与相应 matrices，并定义短生命周期、格式/layout 和同步责任；首版仅接 vanilla Vulkan provider，不实现 shaderpack provider。
  - [ ] 在桌面 Vulkan 环境检查 camera/fog、resize、资源重载、disconnect/reconnect、节点变化下的 visual correctness 与资源重建。
- **交付物**：Vulkan indirect renderer、Sodium depth-aware composite、每帧同步/barrier 集成、外部 shader pipeline G-buffer/matrices 接口，以及带日志/截图的桌面视觉验收记录。
- **验收条件**：Windows/Linux Vulkan-only 受控世界中可观察远景 LOD；近景遮挡、深度与颜色合成正确；resize、camera/fog、disconnect/reconnect 与资源重建正常；Vulkan validation 无错误，记录设备/OS/构建来源/日志与结果。接口仅代表扩展点预留，不得宣称 shaderpacks 已支持。

## V5 PC 验收、隔离与稳定性（TODO）

- **先决条件**：V0–V4 全部交付；Windows 与 Linux Vulkan-capable 桌面设备、锁定构建和复现世界/节点可用。
- **工作项**：
  - [ ] 分别执行 Windows 与 Linux Vulkan-only 启动和功能验收，记录 device/OS/驱动、游戏与 mod 构建来源/提交/hash、完整日志位置、操作与结果。
  - [ ] 验收节点 A/B cache scope 不交叉、sanitize 冲突不会越界、unknown/singleplayer inactive、断线重连与节点切换关闭旧 session 并使用正确 scope；不删除其它节点旧缓存。
  - [ ] 验收资源使用、显存/内存生命周期、GPU hang/crash 与 Vulkan validation；连续长时间运行、resize、视口改变、资源 reload、退出/重启和重复切服。
  - [ ] 建立真实设备证据表，逐行包含设备型号/GPU、OS/driver、构建来源与 hash、日志位置、复现步骤、结果和未通过项；缺设备的条目保持 BLOCKED。
- **交付物**：Windows/Linux PC acceptance 报告与设备证据表、节点 scope 隔离/断线重连/切换测试、资源与稳定性记录。
- **验收条件**：Windows 与 Linux 各有可复现的 Vulkan-only 启动及渲染结果；节点隔离与 reconnect/switch 测试通过；GPU hang/validation/长期运行门禁完成并附日志；所有实机报告均具设备、OS、build/hash、log 和结果，不能以本地单测替代。V5 未通过不得开始 Android 适配。

## V6 Android 适配与 Galaxy S23 验收（TODO；仅 V5 通过后）

- **先决条件**：V5 Windows/Linux PC gate 通过；Android ARM64/Vulkan target、Galaxy S23 设备与可复现构建环境可用；按 ADR 0019 的 16 KiB 页 native gate 执行。
- **工作项**：
  - [ ] 查询 Android Vulkan feature/format/limits 与 V1–V4 所需能力，记录 Adreno 740 等设备实际能力并验证 fallback/核心门槛。
  - [ ] 验证或构建 JBP-LOD 所需 shaderc/native packaging 与 Android ARM64 加载；审核 ELF LOAD 16 KiB 对齐、bionic ABI、依赖来源/hash 与应用 `:game` classpath。
  - [ ] 对选定 storage backend 验证 RocksDB native/bionic、16 KiB 页面及应用私有缓存访问；路径为 `Context.getCacheDir()/lod`，按节点和 JBP-LOD world identifier 隔离。
  - [ ] 在 Samsung Galaxy S23 实测启动、进入世界、LOD compute/render、断线重连/节点切换、退出和长时间负载；记录设备型号/SoC/GPU、Android/One UI build、Vulkan driver/API、构建来源/hash、日志位置、温度/资源观察、结果与问题。
- **交付物**：Android ARM64 Vulkan/native packaging 记录、16 KiB 对齐证据、S23 feature snapshot 与完整端到端验收报告。
- **验收条件**：S23 真机完成 Vulkan-only 启动与 LOD 视觉验收；必需 capability/native 均通过，shaderc/native 无加载错误，RocksDB（若选用）满足 bionic/16 KiB 要求；节点隔离、断线重连与长时稳定性有复现日志和实测结果。无真机证据时保持待验证，不推断支持其它 Android GPU。

## 决策历程（2026-10-18）

1. **已决定：** PC 与 Android 游戏启动均强制 Vulkan；OpenGL 源码保留但不是启动选择或回退路径。
2. **已决定：** 选择路线 B——以封存 `mods/voxy` 为参考，忠实 1:1 迁移其 Vulkan compute/render 所需算法语义；明确拒绝路线 A（Zink/Kopper 提供 GL compatibility context）。
3. **已决定：** JBP-LOD 与 Minecraft 共用现有 Vulkan device；不创建第二个 device，不以 RenderPearl 薄封装代替所需 Vulkan 核心。
4. **已决定：** JBP-LOD 为全新独立模组，目标目录/Java 根包为 `mods/jbp-lod` / `cn.cubegarden.cosmos.jbp.client.lod`；不是 Voxy 原位改名。`mods/voxy` 封存只读，旧目录的 `jbp.voxy.dir` 等旧实现保持原样。
5. **已决定：** 新模组按 `MCRcortex/voxy` 长期 fork 方向维护；迁移时保留上游来源与文件头要求。
6. **已决定：** 桌面优先，先完成 Windows/Linux Vulkan-only 启动与功能，再进行 Android/Adreno 740/Galaxy S23 适配。
7. **已决定：** 新 JVM property 是 `-Djbp.lod.dir`，不保留旧属性 alias；桌面 root `<data_dir>/cache/lod`，缺 property fallback `<gameDir>/.lod`，Android root `Context.getCacheDir()/lod`。缓存节点目录固定为 `<base>/nodes/<sanitized nodeId>/`，按 `[^A-Za-z0-9._-]` → `_`，拒绝空、`.`、`..`；维度/世界由 JBP-LOD 自己的 world identifier 在内部隔离。该布局取代旧文档中不一致的 scope 树。
8. **已决定：** PC client manifest 移除 Voxy；launcher 已使用 `jbp.lod.dir`。旧模组目录封存例外仍含旧 property，不表示新实现保留兼容别名。
9. **已决定：** 为未来 external shader pipeline 保留接口扩展点，使 provider 能为 LOD draw 提供 G-buffer targets 与 matrices；当前只预留 interface，不承诺或实现 shaderpack 支持。
10. **后续范围：** 三个 shader packs 的 Vulkan 移植与 Vivecraft per-eye rendering 不属于 V0–V6 当前验收；须等基础桌面/Android 范围完成后另行安排。
11. **待验证：** RocksDB JNI 复用还是替换须依据目标平台 ABI、bionic 与 16 KiB 页面证据作选择。
12. **已决定（2026-10-18）：** S5.21 历史 optional manifest 字段 `voxy_map_cache` 在本 JBP-LOD 变更集中重命名为 `lod_map_cache`，不保留 alias，也不接受旧名称；平台、launcher、schema、管理面板与消费者使用新名称。该字段重命名不属于 V0–V6 后续工作项。
13. **已决定（2026-10-18）：** Release/launcher 构建只启用多玩家服务端节点 scope，单人不启用；Fabric development environment 中可通过 JVM property `-Djbp.lod.devScope=true` 启用当前 world folder 的单人 scope，也可将 property 设为自定义 ID 命名 dev scope，隔离于 `<base>/dev/<sanitized id>/`。仅 development environment 中 `-Djbp.lod.devNode=<id>` 可覆盖多玩家节点 provider。

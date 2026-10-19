# YSM 与 Voxy 渲染适配 TODO
> **SEALED LEGACY — mods/voxy only, superseded by docs/todo/jbp-lod.md.** 本文涉及的 Voxy V0–V4 仅为封存历史资料，不是当前实施计划或验收步骤；当前 JBP-LOD 范围以所链接 TODO 为准。

> **2026-10-18 状态更新（ADR 0036）：** 第一节 YSM Y0–Y7 已由 [Cosmetic 改造计划 Y0–Y16](https://github.com/CubeGardenCosmos/JubaopenCraft/blob/main/mods/docs/cosmetic/README.md) 取代。第二节 Voxy V0–V4 与 `mods/voxy` 已封存为 legacy/只读参考，不再实施或修复；旧 V0–V4 与新 JBP-LOD V0–V6 不对应，旧 OpenGL 运行记录不是当前后端支持。按明确切换决定，PC manifest 已移除旧 Voxy；PC LOD 功能验收仍待验证且阻塞，由尚未实现的新模组 JBP-LOD 接续（[V0–V6 TODO](todo.md)；[设计](voxy-vulkan-core-design.md)）；JBP-LOD 计划作为上游 `MCRcortex/voxy` 长期 fork 维护；先桌面 Vulkan，再移动端。


范围：Minecraft **26.3 + Fabric + Java 25**。YSM 适配使用原版 Vulkan/RenderPearl 管线。旧 Voxy（V0–V4）计划仅保留历史；新 JBP-LOD 是独立 Vulkan 模组议程，尚未实现。

---

## 一、YSM 纯净版移植（Y0–Y7）

> **Y16.04 归档**：本节 Y0–Y7 的执行状态与验收以 [Cosmetic 改造计划 Y0–Y16](https://github.com/CubeGardenCosmos/JubaopenCraft/blob/main/mods/docs/cosmetic/README.md) 为准，以下阶段条目不再更新。`mods/YesSteveModel` 与 `mods/YesSteveModel-Native` 为 READ-ONLY REFERENCE（见各自目录顶部只读横幅），不得修改。


### 已决定
- 只保留 Fabric，移除 Forge／NeoForge、多版本兼容及不需要的历史适配；必要的同步、状态和事件功能迁移到 Fabric。
- **YSM 这一层不含任何模型加解密**：移除加解密与旧加密格式导入，保留完整性哈希、输入校验、普通模型目录、未加密归档和当前未加密容器。模型作者权益保护（加密）推迟到后期，作为反作弊功能在更新交付层提供，不在 YSM 内实现；**在此之前模型为明文，未提供作者权益保护**。
- **YSM 只加载本地模型**：模型目录是游戏更新的一部分，随客户端清单（固定文件版本与 SHA-256）交付。**移除 YSM 网络传输模型内容的全部能力**（按需分片、限流、背压、防抖、传输会话）。服务端与客户端之间只同步状态：玩家选用的稳定模型标识、动画与表现状态、授权结果。
- 客户端缺少服务端指定的模型：只显示原版默认玩家模型（不走 YSM 渲染），并在聊天框警告玩家游戏未更新（每次进服提示一次）。
- 模型使用权限交给 LuckPerms，权限节点使用管理员配置的稳定模型标识；内容哈希不作为长期授权标识。权限撤销后服务端立即让该玩家的模型失效，回退为原版默认玩家模型。LuckPerms 不可用时受限模型默认拒绝。
- 适配 **26.3 游戏自身的 Vulkan 后端**，通过 Minecraft／RenderPearl 公共接口提交渲染；不得残留 OpenGL 直接调用与 stencil 调用（26.3 渲染抽象无 stencil，改为片元 `discard` 或深度测试）。
- Forge Capability 替换方案：先用 Fabric 自带数据附件；不引入 Cardinal Components，除非 Y1 实测不足。
- Native 构建：引入 Conan（≥ 2.28）及其依赖配方；版本与 SHA-256 按供应链规则锁定。
- **Java 根包名：`cn.cubegarden.cosmos.jbp.client.cosmetic_custom`**（2026-10-17 用户指定，取代上游 `com.elfmcys.ysm`）。Java 源码、Mixin/`fabric.mod.json` 和 Native 的 JNI 类描述符已同步改名；资源命名空间与 mod id 仍为 `ysm`。Native 的 JNI 绑定用 `RegisterNatives` 描述符字符串，不依赖按包名自动推导的符号，因此不受下划线包名影响。上游源码头与来源记录保留。

#### 联动清单（2026-10-17 用户确认）
| 联动项 | 处置 | 备注 |
|---|---|---|
| Iris | 保留 | 顶点格式探针与 PBR 扩展需按 26.3 RenderPearl 重写 |
| FirstPerson、Real Camera | 本轮不适配，移除联动 | **不适配任何第一人称模组**；标记为后续作为 Vivecraft 支持的一部分统一处理 |
| TaCZ | 保留 | 使用仓库内 `mods/TaCZ_Refabricated_Unofficial`（26.3） |
| PlayerAnimator、Cosmetic Armor、Elytra Slot、Immersive Melodies | 保留 | Cosmetic Armor、Elytra Slot 经 Trinkets Updated 接入 |
| Create | 保留但不启用 | 代码保留，默认不注册 |
| Jade | 保留 | 未经用户逐项确认，按建议处理 |
| 车万女仆（Touhou Little Maid） | 技术上移除 | **后续 Fabric 移植时再探讨是否接回**，现在不做 |
| Sophisticated Backpacks、Simple Planes | 移除 | |
| Accelerated Rendering、OptiFine、Better Combat、ParCool、Iron's Spells、SlashBlade、Superb Warfare、SWEM、Curios、Simple Hats、The One Probe | 移除 | 未经用户逐项确认，按建议处理 |
| Carry On、Immersive Aircraft | 暂按移除 | 用户未表态，待确认 |

保留项在 26.3 Fabric 的可用性（2026-10-17 调研；来源为 Modrinth/GitHub 页面，均未在本仓库实际构建或运行，状态保持「待验证」）：

| 联动项 | 26.3 Fabric 状态 | 说明 |
|---|---|---|
| Iris | 可用：`1.11.7+26.3`（LGPL-3.0） | 强依赖 Sodium `0.9.2+mc26.3` |
| FirstPerson、Real Camera | 本轮不适配（后续 Vivecraft 支持） | 调研记录：FirstPerson `2.7.3` 与 Real Camera `0.7.8-beta-26.3` 均有 26.3 Fabric 版，仅供后续参考 |
| Jade | 可用：`26.3.5`（CC-BY-NC-SA-4.0） | |
| TaCZ | 可用（本地移植）：`1.1.8+fabric.26.3.R1` | 硬依赖 `forgeconfigapiport >= 26.3.1`；对 Carry On 有专用集成 |
| PlayerAnimator | **上游无 26.x**；后继 Player Animation Library `1.2.8+26.3`（MIT）可用 | 原 KosmX 项目已停止维护。YSM 联动代码针对旧 `playeranimator`，改接后继库需适配 API |
| Cosmetic Armor | **上游仅 Forge，无 Fabric 版本** | Trinkets Updated 内置时装栏，Cosmetic Armor Updated 仅到 26.1。联动须按 Trinkets 重写或待上游 |
| Elytra Slot | **原项目无 26.x**；有独立 26.3 项目（`elytraslot-fabric-26.3-3.1.0`，MIT，及 cafekad 版）与 Trinkets Updated | 联动须针对所选项目重写 |
| Immersive Melodies | **未适配 26.3**：最新 `0.8.0+26.2`，元数据声明 `[26.2, 26.3)` | 待上游 |
| Create | **未适配 26.3**：官方 Create Fabric 停在 1.20.1，第三方 Create Fly 到 26.2 | 保留但不启用，代码本阶段不可验证 |
| 饰品 API | **已决定引入 Trinkets Updated `4.2.1+26.3`（MIT）** | 用于 Cosmetic Armor、Elytra Slot 联动；Accessories 仅非官方移植，不采用。版本与 SHA-256 须按供应链规则锁定 |
| Carry On（暂按移除） | 可用：`2.12.0`（LGPL-3.0） | 若保留，成本低且 TaCZ 有现成集成 |
| Immersive Aircraft（暂按移除） | 官方仅 26.2；社区移植 `1.5.2-port.2+26.3` | 若保留，依赖社区移植 |

没有可用版本的保留项归入「待上游」：代码保留或按期重写，但不得标为已支持，也不得因此阻塞 Y1–Y6。

### 当前基线与待验证
- Java 上游：`YesSteveModel/YesSteveModel`，提交 `74c53b58b2f9b680a6c6dc5d5d41e7422fc000bb`。
- Native 上游：`YesSteveModel/YesSteveModel-Native`，提交 `e9ac162492a97c7222f652c41624ca150f450354`。
- 两份源码已纳入 `mods/`（已被 Git 跟踪）。调查与移植进展（2026-10-17）：本机已安装 Conan 2.33.0、Clang 22、LLD 22、NASM 3.02、uv 0.12.23 等工具链；Native 端已完成 `libysm.so` 构建（110 个测试中 103 通过，2 失败，5 跳过）；Java 端主源码已完成 26.3 Fabric 与 Java 25 迁移并通过源码编译（`compileJava` 零错误），单元测试正在修复中；Y0–Y2 在源码层级完成，Y3/Y4 仅源码编译通过，目前全部未经过游戏内实机/运行时验证。
- 解密模块（Java `natives/legacy`、Native `modules/legacy`）已彻底清理移除；旧加密格式已明确拦截；LuckPerms 授权服务代码已就绪。
- 当前上游重构基线是 Forge 1.20.1；保留其来源信息不代表改变本项目游戏目标。
- Native 复用度、Vulkan 正确性及 Android ARM64 实际可运行性仍待验证；Native 的 `ParallelExecutor`、透明 scratch、矩阵暂存区全局不可重入，多模型须串行渲染。
- 已知上游缺陷：透明排序只在单模型内；仅右手持物附着点接通；Molang 缺多 Pass 与 `ysm.sync` 测试。
- 待决定：权限撤销以外的表现细节、Carry On／Immersive Aircraft 处置。独立 Vulkan 渲染器、GPU 骨骼计算和旧加密模型转换工具不纳入本轮。

### 阶段规划

#### Y0 基线与精简范围（源码级完成）
- **状态**：**源码级完成**。保留/移除范围已固化，非保留联动代码已清除，包名已全量重构为 `cn.cubegarden.cosmos.jbp.client.cosmetic_custom`，保留联动项的 26.3 Fabric 可用性已排查完毕。
- 先决条件：本计划确认。
- 工作项：核实源码状态；整理依赖；固化保留／删除范围与联动清单；核实保留联动项在 26.3 Fabric 的可用版本；文档对齐（本文件、`agenda.md`、`README.md` 收录清单）。
- 交付物：来源记录、联动清单、功能范围及必要的架构决策记录。
- 验收：保留／移除／后续范围清晰；不把上游未完成能力写成已支持。

#### Y1 26.3 Fabric 工程（源码级完成，未实机验证）
- **状态**：**源码级完成（未实机验证）**。已建立 Fabric 0.162.0+26.3、Java 25 构建；完成入口、配置、按键与事件迁移；Forge Capability 替换为 Fabric Data Attachments（服务端）与实体弱引用映射（客户端）；彻底删除网络传输模型文件能力，网络层仅保留状态同步；主源码已成功通过编译；**双端启动与运行时表现尚未实机验证**。
- 先决条件：Y0。
- 工作项：建立固定版本的 Fabric 构建（Loader 0.19.5、Fabric API 0.162.0+26.3、Loom 1.18.3、Gradle 9.8.1、JDK 25，无需 mappings）；迁移入口、配置、命令和事件；替换 Forge Capability 与网络接入，移除 Forge／NeoForge 构建及运行依赖；**删除网络传输模型能力**，网络层只保留状态同步；按联动清单移除不保留的联动代码；实现缺模型回退与聊天警告。
- 交付物：26.3 Fabric 客户端和服务端基础工程、构建说明。
- 验收：两端基础启动通过；服务端不加载客户端渲染类；依赖和元数据不再要求 Forge／NeoForge；缺模型时显示原版模型并有警告。

#### Y2 无加密模型管线（源码级完成，未实机验证）
- **状态**：**源码级完成（未实机验证）**。已彻底移除模型加解密逻辑与旧加密格式导入；JNI 加密入口与专用依赖已清理；本地目录（`cosmetic_custom/models`）与更新包目录（`cosmetic_custom/update-packages`）明文模型管线已打通；旧加密 `.ysm` 文件明确拦截报错；主源码已编译通过；**未进行真实模型文件加载与导出回读的实机验证**。
- 先决条件：Y1。
- 工作项：清理加解密残留（`ModelSourceKind`／`ModelSourceResolver`／`ModelSourceDiscovery` 中的 `LEGACY_ARCHIVE`／`UNSUPPORTED_YSM`、Native `archive.cc` 的 `.ysm` 拦截、语言文件提示、`NOTICE.md` 与 `THIRD_PARTY_LICENSES.md` 中 CityHash／ChaCha／Crypto++ 声明）；JNI 入口及专用依赖清理；打通本地目录／更新包未加密模型的加载、缓存和导出，保留校验与失败提示；旧加密文件明确拒绝。
- 交付物：纯净版模型管线及格式支持说明。
- 验收：普通模型加载、缓存重建和导出回读通过；旧加密文件明确拒绝；构建依赖及产物无模型加解密模块。

#### Y3 Vulkan 基础渲染（仅源码编译通过，未实机验证）
- **状态**：**仅源码编译通过（未实机验证）**。Linux x86-64 Native 库已构建（`libysm.so`）；Java 端已将顶点输出重构为 `SubmitNodeCollector.submitCustomGeometry` 并在提交时冻结骨骼快照，纹理上传适配 `DynamicTexture`/`GpuTextureView`，向 Native 层传递单位视口/投影矩阵；主源码编译通过。但 Native 端存在 2 个 GoogleTest 失败，且 Minecraft 26.3 游戏内 Vulkan 环境下的实际顶点渲染、UV、光照和动画表现**完全未在运行时实机验证**。
- 先决条件：Y2；Native 可在本机构建（需安装 Conan）。
- 工作项：复用 Native CPU 计算；以 `SubmitNodeCollector.submitCustomGeometry` 替换 `BufferBuilderMixin` 私有字段直写，提交时冻结骨骼快照；纹理上传改用 `DynamicTexture`／`GpuTextureView`；矩阵改取自 26.3 渲染状态；明确帧数据、缓冲区与资源寿命。
- 交付物：静态模型、基础动画和贴图的渲染链。
- 验收：确认实际启用原版 Vulkan 后端（全平台仅 Vulkan，PC 不提供 OpenGL 选择）后，模型位置、UV、光照及动画正确；延迟执行期间无状态覆盖或提前释放；不依赖直接 OpenGL 调用。

#### Y4 完整表现与界面（仅源码编译通过，未实机验证）
- **状态**：**仅源码编译通过（未实机验证）**。材质与半透明管线（去 stencil）、`CosmeticCustomRenderBridge`、`PreviewRenderHost`（离屏渲染与 GpuBuffer 回读）、模型选择界面等主源码均已编译通过；第一人称手部替换因缺少 26.3 事件驱动已删除，不支持发光隐形实体的纯外轮廓渲染，`PlayerTextureScreen` 移除地面开关，TaCZ 副手渲染基于 `ItemStackRenderState` 提交但未实机实测；**所有视觉表现、换模预览和重载均未在游戏运行时实机验证，不得以编译通过代替实机视觉验收**。
- 先决条件：Y3。
- 工作项：适配透明／裁切材质（去 stencil）、双面与面剔除、深度约定、GUI 预览；处理装备与附着点（第一人称视角相关联动不在本轮）；只恢复联动清单中保留且 26.3 可用的联动。
- 交付物：模型选择界面和保留功能的视觉验收样例。
- 验收：原版 Vulkan 下检查透明设置（含 OIT 开／关）、多模型遮挡、换模、预览和资源重载；不能用只通过编译代替视觉验收。

#### Y5 LuckPerms 授权（待办 / 源码就绪待联验）
- **状态**：**待办（源码已就绪，待联验）**。服务端 LuckPerms 权限服务（`permission/**`）源码已实现稳定模型标识授权与事件监听刷新；多人环境下的实际权限撤销与即时回退原版模型尚未进行实机联验。
- 先决条件：Y2；视觉联验依赖 Y4。可与 Y3／Y4 并行。
- 工作项：配置稳定模型标识与权限节点映射（如 `ysm.model.<id>`）；服务端读取 LuckPerms 当前上下文权限，替换 `AuthModelsCapability` 哈希授权；订阅 LuckPerms 权限变更事件以即时刷新；覆盖选择、管理员指定、重连和权限变更。
- 交付物：权限适配、配置样例及管理员使用说明。
- 验收：玩家／组／临时权限、明确拒绝及上下文切换有效；撤销后服务端立即使模型失效并回退原版模型；旧授权和命令不能旁路；LuckPerms 不可用时受限模型默认拒绝；看到他人模型不等于获准使用。

#### Y6 多人与兼容验证（待办）
- **状态**：**待办**。
- 先决条件：Y4、Y5。
- 工作项：联验模型目录与更新包一致性、缺模型回退、动画状态和权限刷新；检查断线重连、模型更新、资源释放及多玩家性能。
- 交付物：双客户端与服务端验证记录、已知兼容性问题清单。
- 验收：未授权请求被服务端拒绝；多人表现与权限一致；模型更新不因哈希变化丢失稳定标识授权；渲染与模型 I/O 不阻塞游戏 tick。

#### Y7 实机与交付（待办）
- **状态**：**待办**。
- 先决条件：Y6；Android 测试还需启动器具备可验证的原版 Vulkan 运行路径。
- 工作项：桌面与 Galaxy S23 ARM64 验证；按现行 Android 参考测试规则执行；模型目录随更新包交付并经哈希校验；锁定构件、来源、依赖和 SHA-256，整理已知限制。
- 交付物：可复现构建说明、候选产物清单和实机证据。
- 验收：记录设备、OS、构建来源、实际渲染后端、日志位置及结果；未测平台明确标记。
---

## 二、旧 Voxy 远景渲染与存储隔离记录（V0–V4，已封存 / legacy）

JBP-LOD 的目标是与游戏共享 `VkDevice` 的忠实 1:1 Vulkan compute/render 实现；先桌面、后移动端，并自行重新提供节点级存储、`JbpNodeIdProvider` 查询与缓存根语义。旧属性/路径名不能作为新契约。S5.21 历史字段名 `voxy_map_cache` 已在 JBP-LOD 变更集中改为 `lod_map_cache`，不接受 alias；新 property、缓存路径与 node sanitize 规则见 [JBP-LOD TODO](todo.md)。另见 [ADR 0036](https://github.com/CubeGardenCosmos/JubaopenCraft/blob/main/docs/decisions/0036-vulkan-only-graphics-backend.md) 与[设计文档](voxy-vulkan-core-design.md)。
### 技术背景与现状
- 上游基线：`MCRcortex/voxy` `263` 分支 HEAD `247adf2625324782e13a3fa8b646c0f95e68031b`（MC 26.3，v0.2.20-beta；已含 RenderPearl/Sodium 26.3 适配与 Vulkan 阻断）。V0 在该提交上重放。
- 存储结构（上游基线，V0 前）：多人 `<gameDirectory>/.voxy/saves/<ServerData.ip，':'→'_'>/`，单人 `<存档>/voxy/`；其下 `config.json` + `<world_identifier>/storage/`（RocksDB，ZSTD）。本地 V0 改为由客户端模组提供服务器 Scope 的隔离路径。
- 渲染特征：基于 GL 4.6 级特性（计算着色器、间接绘制、持久映射缓冲）进行大规模 LOD 地形多边形网格生成与绘制。

### 阶段规划

> **历史决定（2026-10-17，已由 ADR 0036 superseded）：** 曾决定桌面 Voxy 保留 OpenGL、Android Voxy 暂停，并将 Android Zink/Kopper 或完整 Vulkan 重写列为备选。现行决定封存 `mods/voxy`，拒绝 Zink/Kopper；以旧源码为只读参考，另建 JBP-LOD 做与游戏共享 `VkDevice` 的忠实 1:1 Vulkan compute/render 核心。以下 V0–V4 内容仅记录旧 Voxy，不适用于新模组。
> - 旧 Voxy 基线为 `MCRcortex/voxy` `263` 分支 HEAD `247adf2625324782e13a3fa8b646c0f95e68031b`（MC 26.3）；源码与 V0 补丁封存。
> - 旧 Voxy 的 GL 4.6 compute/SSBO/image-store/indirect/stencil 核心与 Vulkan guard 留在 `mods/voxy`，不在此处修复或重写。
> - 原 V0 的节点隔离、`JbpNodeIdProvider` 查询与缓存根语义由 JBP-LOD 另行重新实现，不能直接复用旧名称/路径作为新契约；JBP-LOD 的 property/path/sanitize 契约以 [V0–V6 TODO](todo.md) 为准。
> - JBP-LOD 尚未实现，先做桌面路径，移动端适配之后；外部 shader pipeline G-buffer targets 扩展点见[设计文档](voxy-vulkan-core-design.md)。Iris shaderpacks 移植是后续范围。
#### V0 存储重定向与服务器多维隔离
- **历史状态（旧 Voxy）：** 2026-10-18 曾由用户指示将 V0 存储重定向代码标记完成；这不是 Windows 实机验收，也不代表当前 Vulkan 下旧 Voxy 可运行。该节点级存储、`JbpNodeIdProvider` 查询与缓存根行为由 JBP-LOD 重新实现；旧接口/属性仅用于理解封存代码。Windows 步骤见 [legacy checklist](voxy-windows-smoke.md)。
- 先决条件：方案确认。
- 工作项：
  - `-Djbp.voxy.dir` 覆盖缓存根目录；桌面启动器传 `<data_dir>/cache/voxy`（默认 data dir `.gameruntime`）；未覆盖时 Voxy 默认 `<gameDir>/.voxy`。Android `Context.getCacheDir()/voxy` 仍属后续，不在本阶段实现。
  - `JbpVoxyScope.currentNodeId()` 反射查询客户端模组，目录为 `<base>/nodes/<nodeId>/`，其下维度/世界仍由 Voxy 自己的 `WorldIdentifier` 隔离；不读取 `ServerData.ip`。
  - 单人世界或节点 ID 未知时不创建 Voxy 实例、不打开存储；节点变化走重连（新会话），Voxy 随会话关闭并重建实例，不主动删除旧目录。
  - 启动器传入受管缓存根并且不在游戏运行中清理；Voxy 按需创建存储目录。缓存容量管理与 Android 系统缓存路径不属于本次 V0 改动。
- 交付物：客户端模组节点 ID 接口、桌面 JVM 缓存根与 Voxy 存储重定向。
- 验收：由用户指示标记完成；仓库内没有对应的 Windows 实机日志或截图。

#### V1 26.3 + Fabric 移植
- 先决条件：V0。
- **历史状态（旧 Voxy）：** 此项源码移植/构建与 WSLg llvmpipe OpenGL 运行记录属于已封存代码，仅描述此前运行；不构成 Vulkan 支持或 LOD 视觉验收。强制 Vulkan 下旧代码会被上游 guard 拒绝，PC P1/phase-1 LOD 验收仍**待验证 / blocked**，由尚未实现的新模组 JBP-LOD 接续。历史证据见 [WSL llvmpipe 运行记录](voxy-runtime-wsl-llvmpipe.md)。
- 工作项：
  - 以 upstream 263 的 26.3 Fabric/RenderPearl/Sodium 移植为基线，锁定本仓库版本并重放 V0；不得恢复 26.2 基线或先前失败的编译改动。
  - 已从 Modrinth Sodium `0.9.2+mc26.3` jar 使用 `javap` 核对 `DefaultChunkRenderer` render/prepare、CUTOUT pass 调用点、RenderRegion/section manager、collector、chunk queue 与 accessor 目标。运行时已确认 OpenGL backend 与 Voxy normal/Iris pipeline 初始化；Sodium CUTOUT 绘制是否执行及实际 LOD 画面正确性仍待验证，详见 [运行记录](voxy-runtime-wsl-llvmpipe.md)。
  - 以 compileOnly 锁定 Vivecraft `26.3-1.3.17-fabric` 与 Iris `1.11.7+26.3-fabric`，并在 Fabric metadata 中作为可选建议；Voxy VR 绘制在每个 render pass 中使用当前眼睛/视图矩阵及该 pass 的 OpenGL 目标纹理。
  - 已通过实际 Iris 26.3 jar 的 `javap` 核对 Iris mixin/accessor 目标与描述符，并以映射后的 26.3 Minecraft jar 核对 `MixinLevelRenderer` 目标签名；Iris+Sodium+Voxy 与 Complementary shaderpack 在 llvmpipe 上成功初始化 `IrisVoxyRenderPipeline`，但 shaderpack 画面正确性未验证。Iris+Vivecraft 联动与头显运行仍未验证。
  - Nvidium 联动已删除：无需该 NVIDIA-only renderer；依赖、专用 Mixin 与 repository 配置均移除。
- 交付物：26.3 Fabric 下可编译运行的 Voxy 补丁与构建工程。
- 验收：桌面 26.3 Fabric 客户端成功加载，进入世界正常生成远景 LOD，无编译与运行时类型异常。

#### V2 Android RocksDB 原生库适配
- **状态：封存 / legacy。** 该 Android RocksDB 工作项只描述旧 Voxy；JBP-LOD 先完成桌面 Vulkan 实现，之后另行确定移动端存储适配。
- 先决条件：V1。
- 工作项：
  - 默认 `rocksdbjni` 仅包含 glibc 预编译库，Android (bionic) 环境无法直接装载。
  - 为 Android 构建 ARM64 版 `librocksdbjni.so`，静态链接所需依赖（如 ZSTD/Snappy）。
  - 严格满足 Android 16 KiB 内存页对齐要求（`-Wl,-z,max-page-size=16384`），并加入 Android 构件锁定清单。
- 交付物：ARM64 `librocksdbjni.so` 构件、构建脚本。
- 验收：Android `:game` 进程在 16 KiB / 4 KiB 设备上成功加载 RocksDB JNI，数据读写无崩溃与对齐报错。

#### V3 旧 Voxy 渲染后端与着色器实机计划（封存）
- **状态：封存 / legacy。** 本 V3 旧计划不再执行；Android Zink/Kopper 已拒绝。JBP-LOD 移动端适配须在新模组桌面 Vulkan 核心后另行定义；三个 shader packs 移植属后续范围。
- 工作项：
  - 此旧工作项中的 Mesa Kopper-Zink 方案已拒绝；新 JBP-LOD 的 Android 适配待桌面核心完成后，按 Vulkan 设计重新确定范围。
  - 处理 Adreno 740 等移动 GPU 驱动在复杂计算着色器调度下的稳定性，排查潜在崩溃并提供平稳降级路径。
- 交付物：Android 原生渲染兼容补丁、着色器适配。
- 验收：在测试设备上连续运行 15 分钟无 GPU hang 或驱动 crash。

#### V4 实机端到端验收与联动交付
- **状态：封存 / legacy。** 此条目原指旧 Voxy Android 端到端验证；JBP-LOD 移动端适配排在桌面 Vulkan 路径之后，届时按新设计重新定义设备验收。
- 先决条件：V3。
- 工作项：Galaxy S23 真机实测远景渲染；检查高负载飞行生成 LOD 时帧率与功耗；验证与 YSM 模型渲染在同帧内的管线调度与深度缓冲协同。
- 交付物：实机验收报告、截图序列与性能日志。
- 验收：S23 实机远景 LOD 渲染流畅，跨节点转服世界隔离正确，缓存可安全清理。

---

## 三、YSM 与 JBP-LOD 的后续协同考量

1. **Vulkan 调度协同（JBP-LOD 后续）：** YSM 负责实体模型的顶点提交与着色，JBP-LOD 负责大场景 LOD 地形；两者需协调帧内缓冲分配与绘制阶段，避免状态冲突。
2. **移动端显存与热指标管理（后续范围）：** JBP-LOD 仅在桌面核心完成后开展移动端适配；届时实测 LOD 计算与骨骼顶点变换的 GPU 负载，再评估精度与渲染距离。
3. **安全隔离原则：** YSM 与 JBP-LOD 的原生模块在 `:game` 进程运行，需满足 16 KiB 内存页要求与崩溃隔离，不影响 `:main` 启动器控制面。

---

## 三之二、YSM 原生核心 Rust 化（后续工作，排在 JBP-LOD 之后）

**已决定（2026-10-17，用户）**：YSM 现有 C++ 原生核心（`YesSteveModel-Native`，`libysm.so` / `ysm.dll`）后续重写为 Rust，作为 JBP-LOD 之后的独立阶段。当前阶段不实施，Y0–Y7 与旧 Voxy 均继续沿用各自既有 C++ 核心及 JNI 边界。

- **动机**：与仓库约定一致（Rust 为业务核心）；去掉 C++ 工具链与交叉编译负担（Windows clang-cl/xwin、Android NDK r29）；JNI 边界的顶点布局与状态生命周期不一致会造成“无报错但画面为空”，需用类型系统约束。
- **范围（待定义）**：Molang 解释、骨骼动画求值、顶点生成与 SIMD 写出、模型解析与烘焙缓存。边界为 JNI（桌面）/ `:game` 进程内共享库（Android，16 KiB 页对齐）。
- **前置条件**：JBP-LOD 桌面 Vulkan 核心与 PC LOD 验收阶段完成；现有 C++ 核心的行为以 GoogleTest 与固定模型的顶点输出作为黄金对照（含目前失败的 `LegacyAnimationProtoTest.MatchesFrozenFieldSevenWireAndOrder`、`ModelStateTest.ProductionPolicyRendersPrewakeAndWorkerReadySchedules`，需先判明属 C++ 缺陷还是测试假设）。
- **验收（待验证）**：同一模型同一输入下，Rust 与 C++ 的顶点缓冲逐字节一致（或在已记录的浮点容差内）；桌面与 Galaxy S23 的帧耗时不劣于 C++ 基线；Windows/Linux/Android ARM64 均可构建。
- **状态**：后续范围，未排期。

### 候选提案：提前 Rust 化并调整项目目标（已由 [ADR 0029](https://github.com/CubeGardenCosmos/JubaopenCraft/blob/main/docs/decisions/0029-cosmetic-mod-server-driven-models-and-emotes.md) 采纳：Rust 渲染核提前至 Voxy 之前，首版仅做顶点/姿态，经 IPC 交回）

2026-10-17 用户提议：若提前 Rust 化，则本项目目标改为 **兼容 YSM 模型生态**，适配目标为 **渲染核 Rust 化重构、Vulkan 适配、服务器强控制**。是否提前取决于当前渲染缺陷的定位结果（若缺陷在 C++ 核心内部而非 JNI 边界，则提前的理由更充分）；用户已选择“先定位再决定”。

决定前需澄清（若采纳须追加 `docs/decisions/` ADR，并同步 `docs/architecture/`）：

1. “兼容 YSM 模型生态”的范围：仅 `.ysm` 开放格式与 `ysm-pack.json` 目录模型，还是包含上游加密/授权模型。现行决定为“无加密、仅本地加载、哈希锁定”，不含加密模型。
2. “服务器强控制”的范围：与现行平台权威、模型授权/LuckPerms 权限节点、转服与存档不变量的关系；不得削弱“客户端检查不替代服务端验证”。
3. Vulkan 适配：26.3 原版已为 Vulkan/RenderPearl，Android 为 Vulkan-only；Rust 渲染核需继续经 `submitCustomGeometry` 输出，还是自带管线。
4. 与 Voxy、Y6/Y7、Android NDK r29 的先后顺序，及对 AGENTS.md 中 Rust/Flutter 边界的影响。

---

## 四、参考依据

- [YSM CPU 渲染与顶点输出](https://github.com/CubeGardenCosmos/JubaopenCraft/blob/main/mods/YesSteveModel/docs/architecture/rendering/vertex-output.md)
- [YSM 逐帧状态与调度](https://github.com/CubeGardenCosmos/JubaopenCraft/blob/main/mods/YesSteveModel/docs/architecture/rendering/frame-execution.md)
- [Minecraft 26.3 官方更新说明](https://www.minecraft.net/en-us/article/minecraft-java-edition-26-3)
- [26.3 原版接口迁移参考](https://docs.neoforged.net/primer/docs/26.3/)

# JBP-LOD Vulkan 渲染核心设计

> 研究日期：2026-10-18。本文是方案与源码/字节码研究，不是实现或运行验收记录；凡未有实测证据处均标为「待验证」。
>
> **范围与状态**：**已决定**——JBP-LOD 是独立的新 LOD 模组，也是计划中的 upstream `MCRcortex/voxy` 长期 fork，目录为 `mods/jbp-lod/`，Java 根包为 `cn.cubegarden.cosmos.jbp.client.lod`；它与封存的本地 `mods/voxy/` 分离，后者只作只读研究参考。JBP-LOD 的 V0 项目与 scope/path/lock 源码已创建；构建和客户端运行结果见 [V0–V6 TODO](todo.md) 与 [V0 evidence](jbp-lod-evidence/V0.md)。本文仍是设计/研究，不是验收证据；Vulkan renderer 尚未实现，不能表述为已支持或已验证 Vulkan。
>
> **已决定**——渲染采用真正的 Vulkan compute + raster 核心，共用 Minecraft 26.3 RenderPearl 的 VkDevice；按旧 Voxy 算法语义逐项迁移，不另做 mesh-shader/算法重设计。V0 已在新模组中创建节点 scope/path/lock 基础，不含 renderer；桌面 Vulkan 核心先于 Android / Galaxy S23。任何新实现均不得依赖或修改封存的 Voxy 模块。
>
> **当前用户体验边界**：启动器已决定只强制 Vulkan。旧 `mods/voxy` 仍在 `MixinMinecraft` 对 Vulkan 抛出 `BackendCreationException`，因此强制 Vulkan 下旧 Voxy 无法启动；新 JBP-LOD 项目现已存在，但目前只有 V0 scope/path/lock 基础，没有 renderer 或 Vulkan 支持。历史 Voxy 决策、V0–V4 与 Windows smoke 文档只作封存资料，不是新模组的验收记录。

## 1. 源码能力盘点与 Vulkan 映射

旧 Voxy 26.3 源码基线为 `MCRcortex/voxy` 263，仓库导入提交 `247adf2625324782e13a3fa8b646c0f95e68031b`。主要渲染入口/代码位置见 [Voxy 渲染系统](https://github.com/MCRcortex/voxy/blob/247adf2625324782e13a3fa8b646c0f95e68031b/src/main/java/me/cortex/voxy/client/core/VoxyRenderSystem.java)、[MDIC renderer](https://github.com/MCRcortex/voxy/blob/247adf2625324782e13a3fa8b646c0f95e68031b/src/main/java/me/cortex/voxy/client/core/rendering/section/backend/mdic/MDICSectionRenderer.java)、[GL 包装](https://github.com/MCRcortex/voxy/blob/247adf2625324782e13a3fa8b646c0f95e68031b/src/main/java/me/cortex/voxy/client/core/gl/)、[shader 资源](https://github.com/MCRcortex/voxy/blob/247adf2625324782e13a3fa8b646c0f95e68031b/src/main/resources/assets/voxy/shaders/)与 [Capabilities](https://github.com/MCRcortex/voxy/blob/247adf2625324782e13a3fa8b646c0f95e68031b/src/main/java/me/cortex/voxy/client/core/gl/Capabilities.java)。以下描述的是 JBP-LOD 的设计映射，不表示 Vulkan 代码已经存在。

| 旧 GL 能力 / 用途 | 源码证据 | Vulkan 等价与 feature/extension | 风险 / 取舍 |
|---|---|---|---|
| Compute dispatch，含间接 dispatch；SSBO 原子队列/LOD 计算 | `client/core/rendering/hierachical/HierarchicalOcclusionTraverser.java`；`client/core/gl/Capabilities.java` 探测 `glDispatchComputeIndirect`；`assets/voxy/shaders/lod/gl46/{prep,cmdgen,buildtranslucents}.comp`、`lod/hierarchical/**` | `vkCmdDispatch`、`vkCmdDispatchIndirect`；存储缓冲 descriptor (`VK_DESCRIPTOR_TYPE_STORAGE_BUFFER`)；计算队列/图形队列可先共用同一 queue。间接 dispatch 的参数缓冲须有 `VK_BUFFER_USAGE_INDIRECT_BUFFER_BIT`，SSBO 须有 `VK_BUFFER_USAGE_STORAGE_BUFFER_BIT`。 | compute shader、indirect dispatch 和 storage buffer 均为核心 Vulkan 能力；桌面仍须按设备 limits 检查 workgroup / SSBO 范围。不能把 compute 命令塞入已经打开的 graphics render pass；须在 Sodium 目标 pass 开始前记录，或另录 command buffer 并以 Vulkan semaphore/barrier 排序。Android 设备的负载/驱动稳定性未测。 |
| SSBO / 多队列与 compute 写后读屏障 | `core/rendering/hierachical/**`、`core/rendering/section/backend/mdic/MDICSectionRenderer.java`；`lod/gl46/bindings.glsl`、`lod/hierarchical/{node,queue}.glsl` 使用 `std430`、原子计数/队列；`util/{memcpy,scatter}.comp` | `VkDescriptorSet` 的 storage-buffer binding；compute 写入后以 `vkCmdPipelineBarrier2` / `VkBufferMemoryBarrier2` 建立 `COMPUTE_SHADER` → `DRAW_INDIRECT`、`VERTEX_SHADER` 或后续 compute 的可见性。Vulkan 1.3 将 Synchronization2 核心化（或 Vulkan 1.2 + `VK_KHR_synchronization2`）。 | barrier stage/access mask 和缓冲区子范围需逐用途建模；不能以 CPU fence 或全局 idle 代替正常的 GPU 内同步。device-local / host-visible 内存类型组合要按实际物理设备查询。 |
| Image load/store、SSAO、深度采样、Hi-Z | `core/SSAO.java`、`core/rendering/util/HiZBuffer.java`、`core/rendering/util/DepthFramebuffer.java`；`assets/voxy/shaders/post/ssao.comp` 使用 `imageStore`；`lod/gl46/quads.frag` / `lod/hierarchical/screenspace.glsl` 对深度纹理采样；`post/depth_copy.frag`、`post/blit_texture_depth_cutout.frag` 写 `gl_FragDepth` | storage image descriptor (`VK_DESCRIPTOR_TYPE_STORAGE_IMAGE`) + `vkCmdPipelineBarrier2` 的 image layout/access 转换；采样纹理用 sampled-image descriptor；相应 `VkFormat` 必须有 `VK_FORMAT_FEATURE_STORAGE_IMAGE_BIT` / sampled-image feature。深度写入映射为深度附件状态/fragment shader 输出。 | 按已用格式逐一查询格式 feature，不假设任意颜色或深度格式均支持 storage-image。`HiZBuffer2` 未被当前 `Viewport` 实例化，`assets/voxy/shaders/hiz/hiz.comp` 是死路径；首轮不把其 subgroup clustered 操作列为必需能力。 |
| `glMultiDrawElementsIndirectCountARB`、普通间接绘制与 GPU 生成 draw command | `client/core/rendering/section/backend/mdic/MDICSectionRenderer.java`、`DebugRenderer.java`、`assets/voxy/shaders/lod/gl46/cmdgen.comp` | `vkCmdDrawIndexedIndirectCount`（Vulkan 1.2 核心，或 `VK_KHR_draw_indirect_count`），要求 `VkPhysicalDeviceVulkan12Features.drawIndirectCount=VK_TRUE`；draw args/count buffer 具有 `INDIRECT_BUFFER` usage。shader 读取 draw metadata 时还需要 StorageBuffer descriptor。 | `drawIndirectCount` 是可选的命令加速 feature，不可只因版本号为 Vulkan 1.2/1.3 就假定启用；查询 `vkGetPhysicalDeviceFeatures2`。不可用时选择 Vulkan fixed-count `vkCmdDrawIndexedIndirect` fallback（按安全 command span 记录），不换回 OpenGL；该 fallback 的实际绘制仍待 V4 game smoke 验证。 |
| GL buffer storage、持久映射与流式 upload/readback | `core/gl/{GlBuffer,GlPersistentMappedBuffer}.java`；`core/rendering/util/{UploadStream,DownloadStream}.java`；`core/model/{ModelFactory,ModelStore}.java`；旧源码用 `glNamedBufferStorage`、`glMapNamedBufferRange`、`GL_MAP_PERSISTENT_BIT`、flush/copy | `VkBuffer` + Vulkan Memory Allocator (RenderPearl `VulkanDevice.vma()` 可取得既有 allocator handle) 或同 device 的 allocator；CPU staging 使用 `vkMapMemory`，保持映射直到资源回收，非 coherent memory 用 `vkFlushMappedMemoryRanges` / `vkInvalidateMappedMemoryRanges`。需 `TRANSFER_SRC/DST`、`STORAGE_BUFFER`、`INDIRECT_BUFFER` 等按用途的 usage bits。 | Vulkan 没有一项与 `GL_MAP_PERSISTENT_BIT` 完全同名的 feature；持久映射是 `vkMapMemory` 与 memory type/coherency/lifetime 管理。基于当前 GL 代码没有发现 shader/device-address 指针用法，**不预设** `VK_KHR_buffer_device_address` / `bufferDeviceAddress` 为必需项；若 spike 找到使用点再加入。手机 host-visible heap 与映射带宽可能受限。 |
| Fence / sync / GPU timing | `core/gl/GlFence.java`、`core/util/GPUTiming.java` | `VkFence` 或 timeline semaphore 等待提交完成；GPU timer 用 timestamp query pool + `timestampPeriod`。GPU 内 resource hazard 使用 Synchronization2 barrier，不用等待 CPU。 | 首轮依赖现有 device/queue 的提交顺序并记录实际完成点；只有另开 queue 时才增加跨 queue semaphore 与 queue-family ownership transfer。移动设备 timestamp 支持/精度需查询。 |
| 64-bit shader integers / packed quad | `assets/voxy/shaders/lod/gl46/{quads3.vert,cmdgen.comp,buildtranslucents.comp}`；`lod/quad_format.glsl` 有 `uint64_t` 路径以及 `ivec2` 32-bit fallback；`Capabilities.INT64_t` 有编译探针但当前 `client/` 无 consumer | Vulkan 的 `shaderInt64` (`VkPhysicalDeviceFeatures.shaderInt64`) 是可选 feature；SPIR-V Int64 能力与 feature 必须一致。可选编译 64-bit path，或保留旧 2×32-bit packed path。 | 不把旧 GL 的 `INT64_t` 探针当作已正确 gating。两种 quad layout 必须与 CPU/GPU buffer producer 匹配；桌面先验证 shader 编译和数据逐字节语义。Android 上 Int64 性能/支持差异是风险，不预设可用。 |
| Subgroup arithmetic 优化 | `Capabilities.java` 检查 `GL_KHR_shader_subgroup`；`MDICSectionRenderer.java` 依能力选择 `assets/voxy/shaders/util/prefixsum/inital3.comp` 或 `simple.comp`；前者用 `subgroupExclusiveAdd` | Vulkan 1.1 subgroup 基础能力；检查 `VkPhysicalDeviceSubgroupProperties` 与 `VkSubgroupFeatureFlags`（至少计算阶段需要的 `VK_SUBGROUP_FEATURE_ARITHMETIC_BIT`），SPIR-V 选择 subgroup/fallback variant。 | 原 renderer 已有无 subgroup 的 `simple.comp` fallback，故不应把 subgroup 算术硬列为启动必需。`hiz.comp` 另用 clustered max，但当前未实例化，不因此提高最低 feature 门槛。Adreno/Mali subgroup 宽度、算术能力不同，不能固定 warp 大小。 |
| Sparse buffer / GPU memory budget 优化 | `core/RenderResourceReuse.java`、`core/rendering/section/geometry/BasicSectionGeometryData.java`；旧代码探测 `GL_ARB_sparse_buffer`、NVX GPU memory info | 可选 `sparseBinding` / `sparseResidencyBuffer` feature 与 `vkQueueBindSparse`；显存预算可选 `VK_EXT_memory_budget`。通常分配走标准 `VkDeviceMemory`/VMA。 | 这些是资源复用/显存决策路径，不应阻塞核心渲染初始化；先用普通分配正确工作，稀疏驻留作为独立优化 spike。不能将 GL 的 NVIDIA/Windows 特判原样搬成 Vulkan 设备规则。 |
| Stencil、深度/混合/剔除、scissor、framebuffer blit / texture barrier | `core/VoxyRenderSystem.java`、`core/AbstractRenderPipeline.java`、`core/NormalRenderPipeline.java`、`core/IrisVoxyRenderPipeline.java`；`core/gl/{GlFramebuffer,GlRenderBuffer}.java`；`assets/voxy/shaders/post/setup_stencil_depth.frag`、`FullscreenBlit.java` | `VkPipelineDepthStencilStateCreateInfo`（含 stencil/depth test/op）、`VkPipelineColorBlendStateCreateInfo`、pipeline/dynamic state、`VkImage`/`VkImageView` attachment 与 `vkCmdBlitImage`/copy 命令。Vulkan render-pass/dynamic-rendering 的 attachment/layout 由 RenderPearl 当前 pass 管理。 | Vulkan 状态多为 pipeline 创建时固定，不是任意 `glEnable` 全局状态；须显式建立 pipeline variants/状态。RenderPearl 通用 API 是否暴露所需 stencil/blend/compute 状态不足，须直接调用同 device 的 Vulkan 内部路径。 |
| 纹理 views：当前游戏 color/depth target hand-off | `client/mixin/sodium/MixinDefaultChunkRenderer.java` 在 Sodium `CUTOUT` pass 取 `renderPass.getTarget()` 的 color/depth `GpuTextureView`，当前强转为 `GlTextureView.glId()`；`client/core/rendering/util/LightMapHelper.java`、`core/model/SoftwareModelTextureBakery.java` 另直接使用 GL texture | 对当前帧 `GpuTextureView` 转为 `VulkanGpuTextureView.vkImageView()` / 底层 `VulkanGpuTexture.vkImage()`；按调用阶段使用 RenderPearl 交给的当前 image layout，插入采样/附件依赖。核心在游戏现有 images 上绘制，不复制到另一个 device。 | `GlTextureView` 强转是 Vulkan 下直接的硬阻断。目标属于当前帧/当前 viewport 的借用资源，不跨帧持有；color/depth 的 layout、MSAA、格式、深度只读状态都需随目标传递。Vivecraft LEFT/RIGHT 双 pass 暂属后续。 |
| GLSL shader 编译 / GL 特定扩展 | `core/gl/shader/{ShaderLoader,Shader}.java`；`ShaderLoader.parse` 注入 `#version 460 core`、展开 `#import`；`assets/voxy/shaders/` 包含 GLSL 450/460，少量旧 post shader 为 330；有 `GL_ARB_gpu_shader_int64`、subgroup、`gl_FragDepth`、`textureGather` 等 | Vulkan SPIR-V 模块；GLSL 语义需移至 Vulkan GLSL / SPIR-V descriptor、push constants、明确的 stage interface 与 image layout，不只是把 `#version` 数字改掉。游戏运行时已带 `shaderc`，用作动态 define/include variant 的编译器；build-time SPIR-V 编译可用于提前验证/缓存，但不需要自带第二个 runtime compiler。 | `GL_ARB_*`、`GL_NV_*` 是 GL 扩展，不会因 Vulkan GLSL 编译器存在而自动兼容。每个旧 shader 逐项迁移；构建阶段执行 shaderc/glslang/SPIR-V 验证，运行时 variant 也要测试。是否保留运行时编译、如何缓存 SPIR-V 是 shader spike 的交付物。 |
| Vendor 扩展 fragment test / 特殊能力探针 | `Capabilities.java`；`MDICSectionRenderer.java` 对 `GL_NV_representative_fragment_test` 做可选状态控制；`GL_NV_fragment_shader_barycentric` 与 `GL_NV_gpu_shader5` 分支未确认被启用 | 可选 `VK_NV_representative_fragment_test`；其他扩展仅在 shader define/调用确实启用后再纳入。统一用 `VkPhysicalDeviceFeatures2`/extension query，不按厂商字符串假设。 | 代表片段测试不是核心正确性要求；旧 `nvBarryCoords` 与 `USE_NV_JANK` 路径当前未见启用点，首轮不提升为硬需求。 |

### Vulkan feature 基线

**已决定**：JBP-LOD 目标为 Vulkan 1.3 路径，按源算法映射 Vulkan command/descriptor/image/pipeline，而不是通过 Zink/Kopper 获得 GL context；feature 集合在启动时以 `vkGetPhysicalDeviceFeatures2`、queue family、image-format properties 与 limits 实测，并给出不支持原因。`drawIndirectFirstInstance`（非零 indirect `firstInstance`）与 compute/SSBO、正确的深度采样和必要图形队列能力是核心门槛；`drawIndirectCount`、`multiDrawIndirect`、Int64、subgroup、sparse、memory budget、NV representative-fragment-test 为可选或优化路径，其中 draw indirect count / multi-draw 不可用时使用 Vulkan fixed-count / single-command fallback。

**待验证**：桌面运行时 Vulkan API version / RenderPearl 所启用 feature 与 JBP-LOD 所需 feature 是否重叠；设备不同 queue family 是否可复用；各桌面 GPU 对原 shader 中 image formats、64-bit variant 与 indirect count 的 limits。当前旧 Voxy 的 GL 能力记录不能证明任何 Vulkan feature 已启用。

## 2. RenderPearl 集成面与帧图位置

### 字节码证据：公共 API 与后端内部实现

通过映射后的 Minecraft 26.3 Loom jar 执行 `javap -p -s`：

```text
~/.gradle/caches/fabric-loom/minecraftMaven/net/minecraft/minecraft-merged-deobf/26.3/minecraft-merged-deobf-26.3.jar
```

检查的类包括 `RenderSystem`、RenderPearl `GpuDevice`/`CommandEncoder`/`RenderPass`、`FrontendGpuDevice`/`FrontendRenderPass`/`FrontendCommandEncoder`，以及 `VulkanBackend`、`VulkanDevice`、`VulkanInstance`、`VulkanQueue`、`VulkanCommandEncoder`、`VulkanRenderPass`、`VulkanGpuTextureView`、`VulkanGpuTexture`、`VulkanGpuSurface`。要点如下：

| Handle / operation | `javap -p -s` 观察 | 桥接含义 |
|---|---|---|
| 起点 | `RenderSystem.getDevice(): GpuDevice` 是 public；API `GpuDevice` 没有 `VkDevice`、queue 或 instance 方法。`FrontendGpuDevice` 保存 `private GpuDeviceBackend backend`，没有 public `backend()` accessor。 | 经 `RenderSystem.getDevice()` 取得当前游戏 device；需 Mixins accessor/invoker 读取 `FrontendGpuDevice.backend`，确认它是 `VulkanDevice` 后才转入 Vulkan core。不能由 API 类型本身取得 raw handles。 |
| Instance/device/queue | `VulkanDevice.instance()`, `vkDevice()`, `graphicsQueue()`, `computeQueue()`, `transferQueue()` 均是 public；`VulkanInstance.vkInstance()`、`VulkanQueue.vkQueue()` 与 `queueFamilyIndex()` 也为 public。 | 有可调用的后端 accessor，可借用 RenderPearl 已建的 instance/device/queue/VMA。具体类虽为 public，但位于 `backend.vulkan` 实现包，不是稳定 RenderPearl API 契约；需把版本锁定与 mixin smoke 当成迁移门槛。 |
| Command buffer | `VulkanCommandEncoder.currentCommandBuffer` 是 private，`commandBuffer()` 是 private，`objectInitCommandBuffer()` 为 package-private；`VulkanRenderPass.commandBuffer` 是 private 且无 public getter。`FrontendCommandEncoder.backend()`、`VulkanCommandEncoder.execute(VkCommandBuffer)`、`VulkanDevice.createCommandEncoder()` 是 public。 | 目标 Sodium pass 的 command buffer 必须经对 `FrontendRenderPass.backend`/`VulkanRenderPass.commandBuffer`（或准确捕获 encoder）的 mixin accessor 取得；也可用后端 encoder `execute` 接入自有 command buffer，但必须先用 spike 确定 submit 顺序与 GPU 同步。RenderPearl 公共 `CommandEncoder`/`RenderPass` 没有 compute dispatch API。 |
| 当前帧 color/depth image | `VulkanGpuTextureView.vkImageView()`、`.texture()` 和 `VulkanGpuTexture.vkImage()` 是 public；`VulkanGpuSurface` 的 swapchain image/current-index 是 private。 | Sodium 当前 `TerrainRenderPass.getTarget()` 给出的 color/depth views 是正确的世界 pass 目标；通过后端类型访问 image view。同一 device + 当前帧 image，不能拿 surface swapchain image 代替 framebuffer target。 |
| 绘制 API 能力 | RenderPearl `RenderPass` 有 `drawIndexedIndirect`，但没有 `drawIndexedIndirectCount`；`CommandEncoder` 不含 `dispatch`/`dispatchIndirect`。 | 原算法所需的 compute + indirect count 不能仅调用 RenderPearl 公共 API 完整表达；需基于 backend internal Vulkan handles 建自有 recording/resource 层，或为以后请求 Mojang 扩充 API。本文选择前者。 |

**已决定：共享 Minecraft 的 Vulkan device**，不创建第二 VkDevice。理由：Voxy/JBP-LOD 需要读写当前 RenderPearl color/depth image、在 Vulkan pass 中做深度测试，并与现有队列/同步同帧排序；独立 device 即使选到相同物理设备，也要 external-memory export/import、semaphore 外部同步、格式/所有权与平台 handle 支持，仍不能直接使用 Minecraft 的 image view，复杂度与风险更高。新 core 借用游戏 VkInstance/VkDevice/队列和同一 allocator；不拥有/销毁 RenderPearl 的设备对象。

### Sodium 调用点与帧图计划

旧 hook [MixinDefaultChunkRenderer](https://github.com/MCRcortex/voxy/blob/247adf2625324782e13a3fa8b646c0f95e68031b/src/main/java/me/cortex/voxy/client/mixin/sodium/MixinDefaultChunkRenderer.java)：`prepare` 尾部强制开启 `CUTOUT`，`render` 内在 `ShaderChunkRenderer.end(TerrainRenderPass)` 之前调用 Voxy `renderOpaque`；读取该 pass 的矩阵、viewport、当前 color/depth target。它不是 backend-neutral hook：当前 `GlTextureView` cast 会在 Vulkan 路径失效。新 JBP-LOD 应针对锁定的 Sodium 26.3 `DefaultChunkRenderer`/RenderPearl pass 做版本化桥接，而不是依赖旧 mixin 的 GL id 语义。

推荐的帧图顺序：

1. **LOD compute pre-pass**：在 Sodium 当前视口与相机矩阵已知、CUTOUT 的 RenderPearl graphics render pass 尚未开始时，记录层级遍历、compute prep、队列/间接参数生成、prefix-sum 与必要间接 dispatch。起步使用游戏 graphics queue，或独立 command buffer 在同 queue 上按序提交；若要使用 compute queue，须另做 semaphore 与 queue-family ownership spike。
2. **屏障**：compute 写的 vertex/storage 与 indirect args/count 对后续 vertex/fragment/indirect stages 建立精确 `VkBufferMemoryBarrier2`；有 Hi-Z/storage-image 输出则建立 image layout 与 access barrier。禁止以 `vkQueueWaitIdle` 每帧代替同步。
3. **Sodium CUTOUT 绘制**：在当前 `CUTOUT` pass 内录制 JBP-LOD indexed indirect-count draw，使用该 pass `VkImageView` color/depth views 与 `ChunkRenderMatrices`、camera/fog/viewport 数据。色彩 blend/depth test/stencil 均由 Vulkan pipeline 明确声明。复用游戏 framebuffer，不做颜色/深度 readback 或跨 device copy。
4. **深度/color hand-off**：沿用 RenderPearl 对当前附件生命周期的所有权；由桥接层传递 color/depth image view、format、extent、sample count、当前 layout 与只读/写入访问语义。深度既可能供 fragment shader 采样，也可能供 depth test；应使用正确的 depth read-only attachment/sampled layout 与依赖，不得和 Sodium 后续深度写入产生 hazard。精确 layout / dynamic-rendering 边界由 bridge spike 核实。
5. **每视口隔离**：viewport、矩阵和 render target 逐 pass 传递；Vivecraft 的 LEFT/RIGHT eye 分 pass 与独立目标属于后续范围，不可提前合并成每帧单次渲染。

**待验证**：Sodium 0.9.2 的 RenderPearl pass 在 Vulkan backend 下具体何时打开/关闭；`FrontendRenderPass` 的 private backend 能否在注入点稳定定位；是否允许向正在录制的同一 VkCommandBuffer 插入直接 Vulkan draw；compute 是否需自有 command buffer；depth image 当前 layout/usage 和 RenderPearl barrier ownership。不能在这些事项确认前把旧 `doRender` 的注入点简单移植为 Vulkan。

## 3. Shader pipeline 与依赖打包

### GLSL → SPIR-V

**已决定（实现方向）**：先保留 shader source + runtime defines/imports，在运行时用 Minecraft 已随 26.3 RenderPearl 包含的 `shaderc` 编译为 SPIR-V；开发构建阶段增加 shader compile/validation，不能用预编译成功替代运行时 define variants 的编译验收。原因是旧 `ShaderLoader` 会拼装 `#import`、`#define` 与能力变体，游戏 runtime 已提供 shaderc，无需给 JBP-LOD 再引入一套运行时编译器。`GlslCompiler.compileToSpv(...)` 与 `SPIRVModule` 是 Mojang `com.mojang.renderpearl.frontend.shaders` 实现类，不是稳定 public API，未来也可在构建时将固定基础 shader 预编译并以源码用于开发验证。

证据：mapped jar 的 `GlslCompiler.compileToSpv` 返回 `SpvModule`，类签名引用 `org.lwjgl.util.shaderc`；`SPIRVModule` 有 SPVC reflection 路径。`mods/voxy/run/logs/debug.log` 显示 26.3 的 `NativeLibrariesBootstrap` 加载 `shaderc` 和 `spvc`；同日志运行 classpath 有 `org.lwjgl:lwjgl-shaderc:3.4.3`、`lwjgl-spvc:3.4.3` 及桌面 native。launcher fixture `launcher/crates/launcher-game/tests/fixtures/26.3.json` 也包含 `lwjgl-vulkan:3.4.3`。这是旧开发环境的打包/日志证据，不证明 JBP-LOD 已调用这些 API 或 Android classpath 已包含相同 native。

迁移要点：

- GLSL 4.6 core/450 source 转换为 Vulkan 可用的 GLSL/SPIR-V：描述符集/binding、push constants 或 uniform buffer、显式 stage interface、image format/layout、clip/depth 约定、vertex/fragment builtin、barrier semantics 都需逐项处理；旧 `#extension GL_ARB_gpu_shader_int64 : enable` / subgroup extension 不可原样依赖。
- Vulkan compute 与 Vulkan raster pipeline 应使用同一套生成出的 SPIR-V 模块和明确 descriptor layout；RenderPearl 公共 graphics pipeline 编译入口是否覆盖 compute pipeline，尚待 spike。必要时直接用 `vkCreateShaderModule`/compute pipeline，而不是伪装成 `RenderPipeline`。
- 对旧文件逐一做 SPIR-V compile、validation、descriptor reflection 与桌面渲染验收。`GL_NV_*` 变体不是首版必需；subgroup、Int64 按 feature probe 选择 fallback。

### LWJGL 与 Android native 影响

**桌面现状**：旧 `mods/voxy/run/logs/debug.log` 记录 LWJGL `3.4.3` 的 `lwjgl-vulkan` Java jar 与 `lwjgl-shaderc` / `lwjgl-spvc` Java + Linux native jars；新 mod 应复用游戏 26.3 runtime/classpath，不另打包另一份 LWJGL Vulkan binding 或额外创建 Vulkan loader。若直接用 LWJGL 调用需新 `compileOnly`/开发期 API 可见性，发行包不能和游戏的 3.4.3 版本冲突。

**Android 风险（简要，非当前里程碑）**：`android_plan.rs` 的 `is_platform_native` 说明 Android classpath 排除 `natives*` classifier，native 文件由 flat `native_library_directory` 提供；`launcher/crates/android-pojavexec/src/lib.rs` 为 Android 特别提供 LWJGL Vulkan 的 `VK.getVulkanDriverHandle()`，并打开系统 `libvulkan.so`。这些是 Vulkan 游戏栈的既有集成，不等于新 shader/compiler natives 已在目标 `:game` 包内。Android 阶段须核对 `shaderc`/`spvc` ARM64 native 实际装载路径；如新增/打包 `.so`，遵守 [ADR 0019](https://github.com/CubeGardenCosmos/JubaopenCraft/blob/main/docs/decisions/0019-android-16k-page-aligned-runtime.md) 的 16 KiB ELF LOAD 对齐、来源/hash 锁定及扁平 native 目录契约。Adreno 740 和 Mali 各代对 Int64、subgroup、sparse buffer、storage image format、indirect-count、workgroup limits 的支持/速度差异均**待设备查询与实测**；当前无 S23 / Mali Vulkan Voxy 证据。优先复用游戏 native compiler；若未来决定随包 shaderc/spvc，必须同时验证其 ARM64 `.so` 与 16 KiB 对齐。

## 4. 实施阶段、验收与并行关系

所有条目均为计划，不是已完成状态。体量为相对估算（S/M/L/XL），不代表工期承诺。本文工作包编号用于设计拆解，与 [JBP-LOD V0–V6 TODO](todo.md) 不一一对应；Android / S23 工作包对应 TODO 的 V6，必须等 V5 桌面 gate 通过。桌面阶段使用强制 Vulkan 后端、无 Iris shaderpack；shaderpack 与 Android 不得混进桌面第一阶段的“通过”判定。

| 顺序 | 工作包 / 相对体量 | 内容 | 桌面验收门槛 |
|---|---|---|---|
| 0 | **封存参考并建立新模组边界** / S | `mods/voxy` 只读；`mods/jbp-lod` 与 `cn.cubegarden.cosmos.jbp.client.lod` 根包已建立；PC manifest 移除旧 Voxy，launcher 已只传 `-Djbp.lod.dir=<data_dir>/cache/lod`，不读旧 `-Djbp.voxy.dir` alias；S5.21 历史字段 `voxy_map_cache` 在本 JBP-LOD 变更集中改为 `lod_map_cache`，不接受 alias。V0 scope glue 调用既有 `JbpNodeIdProvider.currentNodeId()`：缺 ID/单人不建实例；路径遵循本节「V0 存储契约」。 | 新项目不依赖旧 `me.cortex.voxy` 运行实现；旧 `mods/voxy` 原样封存。V0 scope/path/lock 源码已创建，但须以 `mods/docs/jbp-lod-evidence/V0.md` 中的 build/test/client log 为验收依据，未记录的行为仍是待验证。目标路径、sanitize、inactive、节点锁测试按本节列出；Android path helper 不等于 Android 设备验证。storage backend/schema 选择留 V3 spike，不由 renderer 决定。 |
| 1 | **Vulkan context bridge + framegraph spike** / L | 使用 `RenderSystem.getDevice()` + Mixins accessor 获取同一 `VulkanDevice`，定位 current Sodium pass 的 `VkCommandBuffer`、queue、color/depth views、矩阵、extent/lifetime；确认 compute pre-pass 和 draw pass 的插入边界。 | Vulkan-only 桌面实际取得游戏 VkInstance/device/queue/目标 views；无第二 device；运行 Vulkan validation / RenderPearl debug 无 handle、layout、同步错误；证明 compute command 可在 graphics pass 之前执行，且 draw 可录进目标 pass。失败则停止后续核心迁移，先解决 bridge。 |
| 2 | **Vulkan buffer/memory/sync 层** / L | 同 device VMA/allocator 使用、SSBO/indirect usage、host staging 与持久映射、ring/arena 生命周期、fence/query pool；不改存储 RocksDB。 | 小型验证 pass 写 SSBO/间接 args 后在 GPU 消费；读回与预期一致；销毁/resize/frame-in-flight 无 validation 警告或泄漏；无每帧 `waitIdle`。 |
| 3 | **GLSL source 与 SPIR-V 管线** / L | GL shader loader/defines 重做为 Vulkan shaderc pipeline；先迁移简单 fullscreen、SSAO/depth/sample 与 compute test；为 descriptor reflection、格式、feature variants 建明确 contract。 | 桌面 shaderc 运行时编译所有本阶段变体；shader validation 通过；Int64/subgroup 关闭时 fallback 可编译；记录源 shader 与 SPIR-V descriptor/interface 对照。 |
| 4 | **忠实迁移 LOD compute/indirect generation** / XL | 将 hierarchical traversal、cull/request queues、section prep、prefix sum、translucent build、cmdgen 和 indirect args 逐项移至 Vulkan compute；功能语义对齐旧源码，不做 mesh shader 重写。 | 固定相机/世界输入下 compute 输出计数、buffer layout、indirect 参数在边界场景正确；barrier 验证通过；桌面 GPU 上没有 CPU 读回/同步等待替代算法的路径。 |
| 5 | **CUTOUT indirect draw + color/depth composite** / XL | 新建 Vulkan graphics pipeline、indexed indirect-count draw、深度采样/测试、color blend、stencil/state；接入 Sodium `CUTOUT` 当前 pass 和目标。 | Vulkan-only 桌面真实场景能看到远景 LOD，近景遮挡/深度/color 正确；resize、camera/fog、资源重建与 disconnect/reconnect 正常；带 Vulkan validation 日志及可用截图。旧 Voxy 运行记录没有可用图像，须建立新的受控验收场景，不声称与旧 GL 图像已对照。 |
| 6 | **桌面集成、稳定性与启用** / L | 整合客户端会话生命周期、viewport/camera 与 shader defines；仅当新 core 全链路满足桌面验收后才启用 JBP-LOD。旧 Voxy guard 不删除、不改；其封存代码继续不能在 Vulkan 启动。 | 启动器强制 Vulkan 下新 JBP-LOD 可进入世界并完成 LOD render；冷启动/资源重载/resize/节点切换/退出均无错误；不要求 OpenGL，不加载 Iris GL 代码作为 renderer；截图与日志入证据。 |
| 7 | **Android / Galaxy S23** / 后续 L | 桌面交付之后再做 ARM64 packaging、feature-set/device-limit query、16 KiB native audit、功耗/显存与热调优。 | 单独制定 S23 验收：记录型号/OS/driver/Vulkan API 与启用 feature；运行受控场景并验证 shader、LOD/depth、节点隔离；连续负载无 GPU hang/crash，并记录性能/热表现。**现在未开始、无设备结果。** |

### 首先做的 spikes

1. **Bridge/command-buffer spike（阶段 1 前置）**：从 `RenderSystem.getDevice()` 一路穿过 `FrontendGpuDevice.backend` 到 `VulkanDevice`，并在 Sodium 当前 `CUTOUT` target 取得具体 Vulkan image view；确认 `FrontendRenderPass`/`VulkanRenderPass` 的 private backend/command buffer 可用 accessor 安全访问。记录 command buffer 是否已处于 render scope；若已打开，则 compute 必须移至更早 hook 或另一个同步 command buffer。
2. **RenderPearl submission/barrier spike**：验证 `VulkanQueue.beginSubmit()`、encoder `execute()`、现有 frame submission 之间的实际排序；验证 compute-write → indirect/vertex read、depth sampled attachment 与 color attachment 的 layout transition，不以静态签名代替运行时验证。
3. **Shaderc/SPIR-V compatibility spike**：逐一编译 `lod/gl46/*.comp/.vert/.frag` 与依赖 include，定位 GL-only builtins/extensions、Int64 fallback、subgroup variants 和 uniform/block binding；确认 compute SPIR-V 如何接入 Vulkan compute pipeline。
4. **Device feature/format spike**：桌面物理设备实际查询 `drawIndirectCount`、`shaderInt64`、subgroup、storage-image format、depth sample、timestamp 与 queue family；保存能力快照。Android/Adreno/Mali 查询留到桌面完成之后。

### 可并行项

- 阶段 1 bridge 与目标/同步 spike 是单一前置，不能靠并行假设跨过。
- 阶段 1 形成稳定 buffer/descriptor/layout contract 后，阶段 2 allocator 与阶段 3 shaderc/SPIR-V 翻译可并行；两者通过固定的 resource binding/interface schema 对接。
- V0 节点 scope/provider 的新模块实现可与 renderer 阶段 2/3 并行，但最终 session activation 等阶段 6；它不得改变 RocksDB backend 的格式/ownership，也不得重新定义 node-id authority。
- shader-source compiler 与 compute-kernel port 可在接口固定后分开开发；compute 与 indirect draw 不可跳过 `COMPUTE → DRAW_INDIRECT/VERTEX` barrier 集成。
- 外部 shader provider 的接口只需随阶段 1 的 per-frame render context 预留；实际 shader-pack/Vulkan G-buffer 接入属于后续范围。

## 5. 保留的外部 shader pipeline 扩展点

**已决定**：新核心不采用旧 Voxy 的 Iris/OpenGL `IrisVoxyRenderPipeline` / `IrisShaderPatch` 作为 Vulkan 兼容层。接口要预留未来 external shader pipeline 能为 LOD draw 提供当帧 G-buffer targets 与相应 matrices；当前基础 provider 只给 vanilla RenderPearl 的 color/depth views、projection/model-view/camera/fog、extent/sample count、format/layout、render-pass identity 与同步责任。

设计边界：

- JBP-LOD renderer 消费一份短生命周期 `LodFrameContext`（接口名为设计建议），由 game/Vulkan provider 填入 borrowed color/depth target views、viewport/pass identity 与矩阵；每帧/每 eye 单独构造，不持有上一帧 VkImageView。
- G-buffer extension provider 可提供目标与矩阵，并声明目标布局、附件可读写语义、shader variant/descriptor set、pass 前后 barrier/ownership；核心负责 LOD compute/geometry，provider 负责与自身 graph 的 target hand-off。若外部 renderer 需要多 MRT，后续扩展由 provider 描述 attachment list，而不是令核心硬编码 Iris 类型。
- 首版只能通过 vanilla Vulkan provider 验收，不为了未来 shaderpacks 增加未使用的 GL compatibility、Iris dependency 或第二套 render core。
- **后续范围**：Iris-style shaderpack adaptation / 用户指定的三个 shaderpack 的 Vulkan 移植、Vivecraft per-eye pipeline；均不属于本设计当前桌面第一阶段，也没有兼容性验证。

## 6. 旧 Voxy 与新 JBP-LOD 的实现边界、V0 隔离

### V0 存储契约（已决定）

新 JBP-LOD 必须重新提供以下 V0 行为；旧 `mods/voxy` 的 `-Djbp.voxy.dir` 实现继续封存，不作为别名或兼容入口：

| 环境 | cache root | 节点目录 |
|---|---|---|
| PC launcher | 启动器显式传 `-Djbp.lod.dir=<data_dir>/cache/lod`；PC manifest 移除旧 Voxy 构件。 | `<data_dir>/cache/lod/nodes/<sanitized nodeId>/` |
| PC 未设置属性 | `-Djbp.lod.dir` 缺失或空白时使用 `<gameDir>/.lod`。 | `<gameDir>/.lod/nodes/<sanitized nodeId>/` |
| Android | 由 Android `Context.getCacheDir()/lod` 提供；不使用 PC 的 `<gameDir>/.lod` fallback。 | `<Context.getCacheDir()>/lod/nodes/<sanitized nodeId>/` |

- **节点权威与并发**：新 mod 在会话开始时查询现有客户端 `JbpNodeIdProvider.currentNodeId()`；不读 `ServerData.ip`。provider 缺失、调用失败、返回 `null`/空白或单人世界时，不创建 LOD 实例、不创建/打开 storage。每节点目录必须获取独占 `lod.lock`；锁冲突禁用当前实例并给出明确诊断。节点变化通过关闭旧 session 并新建 session 切换目录；不主动删除旧 node cache。日志 tag 为 `[JBP-LOD]`。
- **净化与拒绝**：兼容旧 V0 的单目录名规则——保留 `[A-Za-z0-9._-]`，其他字符逐个替换为 `_`；净化结果为空、`.` 或 `..` 时拒绝该 ID，不能生成 storage 路径。目录形态固定为 `<base>/nodes/<sanitized nodeId>/`；该目录下继续由 `WorldIdentifier`/维度识别隔离世界数据。
- **属性切换**：PC 启动器只设置 `-Djbp.lod.dir`，不读取旧 `-Djbp.voxy.dir`，不保留 hidden alias。Android root 由 `Context` 决定，不依赖启动器属性。

**已决定**：JBP-LOD 的 root 名称、PC manifest 移除 Voxy、启动器属性切换、节点目录 `lod.lock` 与 `[JBP-LOD]` 日志 tag 均按上表/本节执行；`voxy_map_cache` 是 S5.21 历史字段名称，已在本变更集中重命名为 `lod_map_cache`，不保留 alias。此文记录设计；该字段重命名与平台/launcher/schema/面板消费者切换属于本变更集。JBP-LOD V0 源码已创建，但其构建/运行证据以 [V0 evidence](jbp-lod-evidence/V0.md) 为准；渲染核心仍未实现。

| 处理方式 | 范围 | 证据 / 约束 |
|---|---|---|
| **JBP-LOD 重新实现算法语义** | hierarchical LOD traversal/visibility、compute preparation、section geometry/packed quad 与 GPU indirect commands、Vulkan resource/pipeline/sync、Sodium Vulkan target bridge。实现使用新包 `cn.cubegarden.cosmos.jbp.client.lod`，不把旧 GL renderer 包装成 Vulkan API。 | 研究参考 `mods/voxy/src/main/java/me/cortex/voxy/client/core/rendering/hierachical/`、`rendering/section/**`、`rendering/util/**`、shader 目录。按第 4 节小步复现并验收；V0 项目骨架已存在，表中 renderer 能力尚未实现。 |
| **V0 scope 行为（新 mod 源码已实现）** | 反射查询 `JbpNodeIdProvider.currentNodeId()`；缺类/空值/异常与单人世界均保持 inactive，不创建节点目录；桌面 `-Djbp.lod.dir` 缺失/空白时回退 `<gameDir>/.lod`，Android 路径 helper 将 `Context.getCacheDir()` 对应的 `Path` 映射为 `<cacheDir>/lod`；按 `[^A-Za-z0-9._-]` 净化节点名，建立 `<base>/nodes/<sanitized nodeId>/` 并持有独占 `lod.lock`。Fabric JOIN/DISCONNECT 与客户端停止事件管理 scope lock；世界/维度隔离由后续 JBP-LOD storage 实现负责。 | 代码在 `mods/jbp-lod/src/main/java/cn/cubegarden/cosmos/jbp/client/lod/`；单测、构建与客户端运行结果见 `mods/docs/jbp-lod-evidence/V0.md`。Android helper 是路径契约，不代表 Android 运行验证；不得据此声称 Vulkan renderer 已实现。 |
| **RocksDB scope 保持隔离** | 渲染 core 不改变 V0 node/world scope、cache cleanup ownership 或 node-id authority；RocksDB JNI 是否复用、替换，以及 schema/格式兼容性不在 Vulkan core 决定，须在 V3 按目标平台 ABI、bionic、16 KiB 页面、供应链与生命周期证据另作决定。 | 旧 `common/config/storage/rocksdb/RocksDBStorageBackend.java`、`commonImpl/WorldIdentifier.java` 是只读研究对象。Android cache root 已按上方契约决定；Android RocksDB native/backend 仍待 V3/V6 决策与验证。 |
| **只研究，不原地搬用旧 GL 实现** | `core/gl/**`、`NormalRenderPipeline` / `IrisVoxyRenderPipeline`、`GlTextureView` Sodium cast、GL shader wrapper、`MixinMinecraft` Vulkan guard 用于能力/算法/调用边界研究；封存后不修改。旧 Iris GL hooks、Nvidium/Flashback 专项不变成 JBP-LOD 新 Vulkan 依赖。 | `mods/voxy/` 保持只读。旧 Voxy doc 的运行记录不能当作 JBP-LOD 证据。新实现不声明导入/复用旧 `me.cortex.voxy.*` 运行时类。 |

## 7. 资料与未决项

- 决策/历史：[Voxy 26.3 渲染决策（封存）](voxy-26.3-render-decision.md)、[V0–V4 历史 TODO（封存）](ysm-voxy-todo.md)、[Voxy WSL llvmpipe 运行记录](voxy-runtime-wsl-llvmpipe.md)、[Voxy Windows smoke（封存）](voxy-windows-smoke.md)。这些记录涉及旧 Voxy，不代表 JBP-LOD 的实现或验证。
- 新计划：[JBP-LOD V0–V6 实施与验收计划](todo.md)；V0 源码已创建，构建/运行验收以 TODO 中的 V0 evidence 为准；V1–V6 阶段状态仍按该计划记录。
- RenderPearl/YSM API 参照：[YSM 26.3 Vulkan 适配状态](https://github.com/CubeGardenCosmos/JubaopenCraft/blob/main/mods/docs/ysm-port-status.md) 中 `GpuTextureView`、`CommandEncoder.writeToTexture` 与 `SubmitNodeCollector.submitCustomGeometry` 用法；YSM 目前走 RenderPearl public geometry path，不暴露任意 Vulkan compute/device handles，不能作为 Voxy compute 核心的替代实现。
- Android packaging：[ADR 0019](https://github.com/CubeGardenCosmos/JubaopenCraft/blob/main/docs/decisions/0019-android-16k-page-aligned-runtime.md)、`launcher/crates/launcher-game/src/android_plan.rs` 与 `launcher/crates/android-pojavexec/src/lib.rs`。
- **待验证清单**：RenderPearl private backend accessor/mixin 在锁定补丁版本下的生存期；Sodium 0.9.2 Vulkan hook 的准确录制边界；compute/draw command buffer 提交与屏障；设备 feature/image-format 支持；GLSL→SPIR-V 全 shader coverage；Windows/Linux 桌面实际画面；shaderc/spvc Android ARM64 native 装载/16 KiB；Adreno 740/Mali GPU 稳定性与性能。本文没有声称上述事项已完成。

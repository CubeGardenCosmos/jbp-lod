# V4 model bakery / draw contract

Status: interface agreed with `V4Draw` and `V3Compute`; implementation and game-device validation are not implied by this contract. Game smoke remains **pending: game smoke deferred by user**.

## Provider output

`cn.cubegarden.cosmos.jbp.client.lod.model.ModelFactory` publishes an immutable `ModelResources` snapshot for a model-table generation. The draw provider borrows the model and colour buffers and the atlas image/view; it does not destroy or mutate them. Snapshot accessors are:

- `models()` and `colours()` — borrowed V3 `GpuBuffer` wrappers; descriptor bindings use their `handle()` values and the reported byte ranges. Models are 64-byte records; colours are packed little-endian `uint32` words (4-byte stride). Model count is the model buffer byte range divided by 64.
- `atlasImage()` / `atlasView()` — Vulkan handles for the custom LOD atlas owned by the model provider.
- `atlasFormat()` — `VK_FORMAT_R8G8B8A8_UNORM`.
- `atlasLayout()` — `VK_IMAGE_LAYOUT_GENERAL`; atlas transfer writes use the same image and are ordered on the game graphics queue.
- `width()` / `height()` — 12,288 × 8,192 texels. `mipLevels()` / `maxMipLod()` — 4 levels / 3 (`ModelAtlasLayout.MIP_LEVELS = 4`, `MAX_MIP_LOD = 3`). Each 16-bit model ID addresses one 48 × 32 tile in a 256 × 256 grid; the tile contains its six 16 × 16 face images in the shader's 3 × 2 arrangement. The tile mip extents are 48 × 32, 24 × 16, 12 × 8, and 6 × 4 (levels 0–3), totaling 8,160 RGBA8 bytes. There is no fifth, 3 × 2 tile mip.
- `generation()` — monotonically increasing model-table snapshot generation.
- `readyAfter()` — V3 `GpuCompletion` for the latest atlas upload submission. The submission uses the existing game `VkDevice` and graphics queue; consumers submit/draw on that queue after the upload. It is a readiness ordering token, not permission to destroy the atlas.
- `close()` — releases snapshot-owned SSBOs and its retained atlas reference. V4 attaches the snapshot owner to the consuming frame slot and uses `LodFrameBuffers.retireAfterUse(AutoCloseable)` (or the equivalent V3 graphics-completion hand-off) so handles outlive every descriptor/draw using them. The factory retains its own atlas reference until scope shutdown.

The factory owns every allocation it creates. The borrowed `VkDevice`, queue, and VMA allocator belong to RenderPearl/Minecraft and are never destroyed by this provider. No GL texture, framebuffer, buffer, command, or fallback path is part of this interface. The block-model baker's Vulkan raster commands, atlas copies, and V4 draw commands must be submitted in graphics-queue order; no CPU readback is used as a substitute for atlas sampling.

## Descriptor and shader ABI

| Set | Binding | Descriptor | Provider output / consumer |
|---|---:|---|---|
| 0 | 2 | combined image sampler | `atlasView`, `atlasLayout`, atlas sampler max LOD 3; consumed by `quads3.vert` / LOD fragment shaders |
| 0 | 3 | storage buffer | `models()`, 64-byte `BlockModel` std430 records |
| 0 | 4 | storage buffer | `colours()`, `uint32` colour words, 4-byte stride |

The 64-byte model record is exactly: `faceData[6]` (offsets 0–23), `flagsA` (24), `colourTint` (28), `customId` (32), and seven zero padding words (36–63), little-endian. `faceData` packs bounds, depth, discard/override, and tint metadata as defined by upstream `block_model.glsl`. Model flags use bits 0 tint source, 1 biome-colour LUT, 2 translucent, 3 shaded. Constant tints are opaque ARGB words; biome tints are base indices into the colour SSBO.

## ID, atlas, and biome rules

The input ID is the dense source block-state ID used by Voxy's `Mapper.getBlockId(block)` and V3 ingest. `BlockStateModelIdResolver.modelIdForBlockState(id)` returns `0` for air, `-1` while unknown, and a packed model ID in `[1, 65535]` after a completed bake. Equal baked-model signatures may share an ID. ID bits are used directly by shader addressing: low eight bits select atlas X, next eight bits select atlas Y. IDs are never narrowed, renumbered, or reinterpreted by V4.

Tint resolution follows vanilla `BlockTintSource.colorInWorld` semantics using the real biome's `ColorResolver` result. A colour source that calls `BlockAndTintGetter.getBlockTint` is biome-dependent; non-biome constants are stored in `colourTint`, while biome-dependent models store a stable row of opaque ARGB `uint32` values. When the dense biome table expands, the provider rebuilds row offsets and publishes model records and the colour words from the same generation.

Face order is DOWN, UP, NORTH, SOUTH, WEST, EAST. Baked block models include both directional quads and the null/un culled quad group; invisible render-shape states produce the air tile. Fluid states are baked through vanilla `FluidRenderer` using the same six views and tint capture rules. Block-entity model geometry remains outside the inherited bakery behavior.

## Upstream derivation record

The behavioral baseline is sealed `mods/voxy` and is read-only. The new code derives algorithm semantics from:

- `mods/voxy/src/main/java/me/cortex/voxy/client/core/model/ModelFactory.java` — face packing, model flags, deduplication, fluid ordering, and biome colour LUT rebasing.
- `mods/voxy/src/main/java/me/cortex/voxy/client/core/model/ModelStore.java` — 64-byte model/colour storage and texture sampler expectations.
- `mods/voxy/src/main/java/me/cortex/voxy/client/core/model/ModelQueries.java` — face and global metadata bit meanings.
- `mods/voxy/src/main/java/me/cortex/voxy/client/core/model/bakery/SoftwareModelTextureBakery.java`, `ReuseVertexConsumer.java`, and `SoftwareRasterizer.java` — baked-model face selection, six view transforms, opaque/translucent ordering, fluid tessellation, and raster result semantics. The JBP implementation replaces their GL/software raster path with Vulkan rasterization.
- `mods/voxy/src/main/java/me/cortex/voxy/client/core/model/TextureUtils.java` and `MipGen.java` — pixel coverage/depth/tint analysis and atlas mip conventions.
- `mods/voxy/src/main/resources/assets/voxy/shaders/lod/block_model.glsl` and `lod/gl46/quads.frag` — model layout and atlas addressing.

The upstream source files contain no file-level license/header block to copy; provenance is recorded in `mods/jbp-lod/NOTICE`. No upstream source is modified.

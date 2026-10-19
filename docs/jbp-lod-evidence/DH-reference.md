# Distant Horizons reference study (local-only)

Status: source-level reference study only. No DH code was built, run, or copied into this
repository. No Minecraft client was launched for this note.

## Source and license

- Distant Horizons: <https://gitlab.com/distant-horizons-team/distant-horizons>
- Local reference: `/home/klp/DH-reference` (shallow clone, commit
  `6763cc8a6f8e96bc59e258bf48d96707b41842b3`, 2026-10-18), created with
  `git clone --depth 1 https://gitlab.com/distant-horizons-team/distant-horizons.git /home/klp/DH-reference`.
- Core code is the `coreSubProjects` git submodule
  (<https://gitlab.com/jeseibel/distant-horizons-core>), commit
  `8d5aa7c19bf95616c57a80e1d04a7e1da35ce2ca`. It was initialized shallow with
  `git submodule update --init --depth 1 coreSubProjects`.
- DH is **LGPL-3.0**: source headers say "licensed under the GNU LGPL v3 License" and
  `LICENSE.LESSER.txt` is LGPL-3.0 (the repository also ships a GPL-3.0 `LICENSE.txt`).
  The local clone is a read-only reference like `../FCL-reference`; it is **not** added to this
  repository, not vendored, and no DH source was copied into JBP-LOD.

Path convention below:

- `DH/...` = `/home/klp/DH-reference/...`
- `DH-core/...` = `/home/klp/DH-reference/coreSubProjects/...` (the submodule)

## Short answer for the current symptom

The screenshot shows a few dark fragments of the far structure and a dark slab but no far
ground beyond the vanilla fog edge. The current log reports only
`chunks captured=50 sections ingested=1200 meshed=2286 meshedNonEmpty=409` while
`pendingRequests` rises from `1404` to `1454` and all of those are `parked` while the player
is idle. That is a **data-supply** signature, not a depth/stencil signature:

1. JBP-LOD writes LOD data only from chunks that the client currently has loaded
   (`LiveChunkCapture.java:20-30, 38-58` -> `LiveSectionProducer.java:96-119`). It has no
   region-file importer and no world generator. DH has both, and in particular a
   `PRE_EXISTING_ONLY` mode that converts already-explored chunks from Minecraft's region
   files into LODs without generating new terrain.
2. DH's overlap strategy is a radial near-clip plus a depth-based vanilla fade. JBP-LOD's
   stencil/vanilla-mask plus bounding-volume strategy can only *remove* LOD fragments; it
   cannot remove ground that was never produced. The overlap code is therefore not the first
   suspect for the missing ground.

Details and line references follow.

## (a) How DH decides that LOD data exists for chunks not loaded this session

For a local/singleplayer level (the relevant case here), DH uses `DhClientServerLevel`
(`DH-core/core/src/main/java/com/seibel/distanthorizons/core/level/DhClientServerLevel.java:53-60`)
with a generating provider; a remote server uses `DhClientLevel` and
`RemoteFullDataSourceProvider` (`.../level/DhClientLevel.java:64-76,153`) and has no local
region files. DH's local render tree asks for data independently of the client's loaded-chunk
set:

1. **The quad tree requests retrieval for render sections.** `LodQuadTree` walks the render
   sections that want to render and calls `tryQueuePosForRetrieval` for those that have not
   been queued yet; `startQueuedRetrievalTasks` sorts missing positions by distance and submits
   them (`DH-core/core/src/main/java/com/seibel/distanthorizons/core/render/QuadTree/LodQuadTree.java:478-510`,
   `860-928`, `1020-1035`). The tree is camera/render-distance driven, not loaded-chunk driven.
2. **The provider decides "missing" from its database and per-column generation steps.**
   `GeneratedFullDataSourceProvider.getPositionsToRetrieve` checks `repo.existsWithKey(pos)`, reads
   the stored `columnGenerationSteps`, and queues a position when a child section or any of its
   64x64 columns is absent or below the required world-gen step
   (`DH-core/core/src/main/java/com/seibel/distanthorizons/core/file/fullDatafile/GeneratedFullDataSourceProvider.java:359-482`).
   `queuePositionForRetrieval` submits the task to the retrieval queue
   (`.../GeneratedFullDataSourceProvider.java:310-330`). The base provider exposes the same
   contract for non-generating providers (`.../V2/FullDataSourceProviderV2.java:376-415`).
3. **The retrieval queue runs a real generator and converts the result to LOD data.**
   `WorldGenerationQueue.submitRetrievalTask` enqueues the request
   (`DH-core/core/src/main/java/com/seibel/distanthorizons/core/generation/queues/WorldGenerationQueue.java:125-171`);
   `startVanillaChunkGenerationEvent` calls the generator and feeds every returned chunk through
   `LodDataBuilder.createFromChunk` into a `FullDataSourceV2`
   (`.../WorldGenerationQueue.java:467-543`).
4. **The default generator reads existing region files before it generates anything.**
   `DhChunkGenerator.generateChunks` first iterates the generation area with radius 0 and calls
   `ChunkFileReader.createEmptyOrPreExistingChunkWrapperAsync(..., genEvent.loadChunksFromDisk, ...)`
   (`loadChunksFromDisk` is true in the default event factory,
   `DH/common/src/main/java/com/seibel/distanthorizons/common/wrappers/worldGeneration/ChunkGenEvent.java:56,68,100`);
   that reader loads the chunk NBT, including lighting, from the world's `RegionFileStorage`
   (`DH/common/src/main/java/com/seibel/distanthorizons/common/wrappers/worldGeneration/DhChunkGenerator.java:380-410`,
   `DH/common/src/main/java/com/seibel/distanthorizons/common/wrappers/worldGeneration/chunkFileHandling/ChunkFileReader.java:117-177`).
   Only positions not present on disk are given empty proto-chunks for generation
   (`DhChunkGenerator.java:414-433`).
5. **`PRE_EXISTING_ONLY` is the "import what I already explored" mode.** With that mode the
   generator initializes biomes and returns; it does not run noise/surface/feature generation
   (`DhChunkGenerator.java:651-657`; mode enum
   `DH-core/api/src/main/java/com/seibel/distanthorizons/api/enums/worldGeneration/EDhApiDistantGeneratorMode.java:37-65`).
   The normal plans (`SURFACE_ONLY`, `SURFACE_THEN_CHUNKS`, `CHUNKS_ONLY`) are described in
   `DH-core/api/src/main/java/com/seibel/distanthorizons/api/enums/worldGeneration/EDhApiGeneratorPlan.java:22-67`.
   `PregenManager` (the `/dh pregen` driver) is not a separate importer: it walks a spiral of
   section positions, calls `getAsync`, and queues retrieval whenever
   `generationStepsAreFullyGenerated` is false (`DH-core/core/src/main/java/com/seibel/distanthorizons/core/generation/PregenManager.java:130-170`).
   With `PRE_EXISTING_ONLY` it therefore bulk-imports already-generated chunks through the same
   `WorldGenerationQueue` path.
6. **The result is persisted and later read by key.** The provider opens/uses the section
   database (`FullDataSourceProviderV2.java:89, 114, 196-230`; `AbstractDhRepo.existsWithKey`
   at `DH-core/core/src/main/java/com/seibel/distanthorizons/core/sql/repo/AbstractDhRepo.java:311`),
   and a render section reads its data source by position and transforms it to render data
   (`LodRenderSection.java` `uploadRenderDataToGpuAsync` at
   `DH-core/core/src/main/java/com/seibel/distanthorizons/core/render/QuadTree/LodRenderSection.java:137-230`,
   `getRenderSourceForPos` at `.../LodRenderSection.java:294-312`).

**JBP-LOD comparison.** There is no equivalent producer in the current port:

- `LiveChunkCapture` only records Fabric `CHUNK_LOAD` / `CHUNK_UNLOAD` events and captures
  loaded chunks on tick (`mods/jbp-lod/src/main/java/cn/cubegarden/cosmos/jbp/client/lod/data/ingest/LiveChunkCapture.java:20-58`).
- `LiveSectionProducer.writeHierarchy` writes the captured voxel hierarchy and notifies the
  node tree (`.../LiveSectionProducer.java:96-119`).
- `LodSession` opens RocksDB and installs the stored-section mesh provider, but it never
  enumerates stored positions (`.../runtime/LodSession.java:76-118`).
- `LodStorageService.listPositions(level)` exists but has **no callers** in `src/main` or
  `src/test` (`.../storage/LodStorageService.java:80-87`). The node tree therefore has no
  startup seed from previous-session JBP-LOD data; it discovers sections through traversal and
  live capture only.

Consequences for the reported scene:

- The 50 captured chunks account for exactly `50 * 24 = 1200` ingested sections, matching the
  log. No producer adds sections outside that loaded chunk set.
- Far chunks that the user explored earlier exist in Minecraft region files, but JBP-LOD
  never reads them. DH would import them with `PRE_EXISTING_ONLY` and then render their column
  data.
- Even old JBP-LOD RocksDB data may be invisible because nothing calls `listPositions` and no
  code turns stored positions into node child-existence. This is a second, independently
  checkable cause (see recommendations 1 and 2).

## (b) How DH handles the vanilla/LOD overlap

DH does **not** use a stencil mask or per-loaded-section AABB depth bounds. It uses a
near-clip radius, a separate LOD color/depth target, and a current-frame depth-based fade.
The `blaze` paths below are DH's RenderPearl-era renderer selected for MC newer than 1.21.10
(`DH/common/src/main/java/com/seibel/distanthorizons/common/wrappers/DependencySetup.java:151-159`),
which is the same backend family as this port; the `openGl` paths are the legacy equivalent
and are cited only where useful.

1. **LODs render into private color and depth targets, with normal LOD depth testing.**
   The terrain pipeline enables depth write and `LESS` (forward Z) or `GREATER` (reverse Z)
   against DH's own depth attachment
   (`DH/common/src/main/java/com/seibel/distanthorizons/common/render/blaze/BlazeDhTerrainRenderer.java:104-121`),
   then renders into `BlazeDhMetaRenderer.dhColorTextureWrapper` and `dhDepthTextureWrapper`
   (`.../BlazeDhTerrainRenderer.java:288-295`). `LodRenderer` clears those private targets each
   frame and copies the result to Minecraft's color texture near the end
   (`DH-core/core/src/main/java/com/seibel/distanthorizons/core/render/renderer/LodRenderer.java:205-209`,
   `222`, `324-328`).
2. **The near-clip plane is a percentage of the vanilla render distance.**
   `RenderUtil.getNearClipPlaneInBlocks` reads `Config...Culling.overdrawPrevention` (default
   `-1` = auto) and selects `0.2 / 0.3 / 0.6 / 0.8 / 0.9` for vanilla render distances
   `<=2 / <=4 / <=6 / <=10 / >10`, with a fast-movement reduction
   (`DH-core/core/src/main/java/com/seibel/distanthorizons/core/util/RenderUtil.java:131-198`).
   The near plane is `vanillaRenderDistance * overdraw`, never less than one block, then
   divided by an FOV/aspect term (`.../RenderUtil.java:200-245`). The same value feeds DH's
   projection near plane (`DH-core/core/src/main/java/com/seibel/distanthorizons/core/render/RenderParam.java:73-75`),
   so geometry closer than it is clipped before the shader discard. `lodOnlyMode` uses `0.5`
   blocks instead (`.../RenderUtil.java:209-212`; config
   `DH-core/core/src/main/java/com/seibel/distanthorizons/core/config/Config.java:1063-1071`).
   `overdrawPrevention` and `reduceOverdrawWithFastMovement` are documented at
   `Config.java:763-788`.
3. **The terrain fragment shader discards LOD fragments inside that radius.** The renderer
   passes the near-clip distance plus 16 blocks as `uClipDistance`
   (`BlazeDhTerrainRenderer.java:258-262`); the fragment shader discards
   `viewDist < uClipDistance`, or dithers the fragment out between `uClipDistance` and
   `1.5 * uClipDistance` when `ditherDhFade` is on
   (`DH-core/core/src/main/resources/assets/distanthorizons/shaders/terrain/blaze/frag.fsh:145-167`;
   same logic in the GL shader `.../shaders/terrain/gl/frag.frag:149-160`).
4. **The composite only writes pixels where DH drew.** `apply/blaze/frag.fsh` samples DH's
   depth; if the depth is still the clear value it discards, otherwise it copies the LOD color
   (`DH-core/core/src/main/resources/assets/distanthorizons/shaders/apply/blaze/frag.fsh:22-48`).
   The apply pipeline has depth test `NONE` and depth write `false`, so it does not write LOD
   depth into Minecraft's depth
   (`DH/common/src/main/java/com/seibel/distanthorizons/common/render/blaze/apply/BlazeDhApplyRenderer.java:170-207`;
   the copy path is `.../apply/BlazeDhCopyRenderer.java:91-109`).
5. **Vanilla is faded into LODs after the vanilla passes, using current-frame MC depth.**
   `ClientApi.renderFadeOpaque` runs after MC opaque and `renderFadeTransparent` after MC
   transparent, gated by `vanillaFadeMode` and `lodOnlyMode`
   (`DH-core/core/src/main/java/com/seibel/distanthorizons/core/api/internal/ClientApi.java:674-730`;
   Fabric hook order in
   `DH/fabric/src/main/java/com/seibel/distanthorizons/fabric/FabricClientProxy.java:264,294`
   and `DH/fabric/src/main/java/com/seibel/distanthorizons/fabric/mixins/client/MixinLevelRenderer.java:314,323`).
   `BlazeVanillaFadeRenderer` computes `fadeStart = (nearClip + 16) * 1.5` and
   `fadeEnd = (nearClip + 16) * 1.9` and uses a no-depth-test, no-depth-write full-screen
   pass (`DH/common/src/main/java/com/seibel/distanthorizons/common/render/blaze/postProcessing/BlazeVanillaFadeRenderer.java:113-120`,
   `176-216`). The shader reads `uMcDepthTexture`, reconstructs the MC fragment distance, and
   blends `combinedMcDhColor` into `dhColor` with
   `smoothstep(uStartFadeBlockDistance, uEndFadeBlockDistance, mcFragmentDistance)`; sky or
   missing MC depth takes the MC color
   (`DH-core/core/src/main/resources/assets/distanthorizons/shaders/fade/blaze/vanilla_fade.fsh:77-121`;
   GL equivalent `.../shaders/fade/gl/vanilla_fade.frag:77-114`).
   `vanillaFadeMode` (`DOUBLE_PASS` default) is at `Config.java:324-333`.
6. **There is no `lodOnlyBeyondChunkRadius` in this DH snapshot.** The LOD radius is
   `Config...Quality.lodChunkRenderDistanceRadius` (default 256 chunks;
   `Config.java:184-193`), converted to `viewDiameterInBlocks = radius * 16 * 2`
   (`DH-core/core/src/main/java/com/seibel/distanthorizons/core/level/ClientLevelModule.java:275-279`).
   The hard LOD radius cutoff is `LodQuadTree.isSectionPosInBounds` against
   `blockRenderDistanceDiameter` (`DH-core/core/src/main/java/com/seibel/distanthorizons/core/render/QuadTree/LodQuadTree.java:575-580`).
   The overlap cutoff is the near-clip radius above. `lodOnlyMode` is a
   debug/vanilla-disabling mode, not a per-chunk-radius gate.

**JBP-LOD comparison.** The current port uses the opposite composition model:

- `LodVanillaDepthSnapshot` keeps a vanilla-only depth snapshot
  (`mods/jbp-lod/src/main/java/cn/cubegarden/cosmos/jbp/client/lod/vk/render/LodVanillaDepthSnapshot.java:17-75`).
- `depth_convert.frag` seeds the LOD depth/stencil target from game depth; vanilla pixels get
  stencil 0, sky keeps stencil 1 (`mods/jbp-lod/src/main/resources/assets/jbp-lod/shaders/lod/depth_convert.frag`).
- LOD draws use stencil `EQUAL 1` (`VulkanLodPipeline.Stencil.LOD_ON_SKY` at
  `.../vk/render/VulkanLodPipeline.java:519-528`).
- `LodBoundingPass` rasterizes loaded-section AABBs and `quads.frag` discards LOD fragments
  inside that bound (`.../vk/render/LodBoundingPass.java`;
  `.../shaders/lod/gl46/quads.frag:183-188`).
- `offscreen_composite.frag` reprojects LOD depth and depth-tests it against the current game
  depth; the pipeline has depth write disabled, so the composite tests but does not write into
  the borrowed game depth (`.../shaders/lod/offscreen_composite.frag`;
  V4 notes the depth-write invariant).

What this means for the current failure:

- These mechanisms are conservative: stencil, bounding and the composite depth test can only
  reject LOD fragments that the vanilla mask or a loaded-section volume claims. They cannot
  create ground that no LOD section contains.
- The stencil seed is one frame old (it is the previous vanilla depth snapshot, not the
  current frame's). DH's fade uses the current frame's MC depth because it runs after the
  vanilla passes. With a stationary camera this is not the cause here, but it is a real
  motion/latency difference and a reason DH's transition is more robust for newly appearing
  vanilla geometry.
- The bounding pass is a second conservative difference: DH has no per-loaded-section AABB
  rejection, only a radial near clip. If a missing-ground symptom is actually *inside* a
  loaded section's bound, `-Djbp.lod.debugView=bound` would show red fragments and the bound,
  not the data source, is the suspect. For ground beyond the 50 loaded chunks there is no
  bound to blame, so the data source remains the primary explanation.

## (c) Ground/heightmap-level data in DH

DH's LOD data is column-oriented, not a full 3D voxel volume:

1. **The storage unit is a 64x64 column array per data source.** `FullDataSourceV2.WIDTH = 64`
   and `NUMB_OF_CHUNKS_WIDE = 4`; at detail level 0 each entry is a block column (at coarser
   levels it covers a larger area). Each column stores a top-to-bottom `LongArrayList` of
   data points, plus per-column generation step and compression mode
   (`DH-core/core/src/main/java/com/seibel/distanthorizons/core/dataObjects/fullData/sources/FullDataSourceV2.java:76-115`).
   `getColumnAtRelPos` / `getDataPointAtBlockPos` expose those columns
   (`.../FullDataSourceV2.java:292-311`).
2. **Chunk conversion starts from the two heightmaps.** `LodDataBuilder.createFromChunk` uses
   `max(lightBlockingHeightMap, solidHeightMap)` to find the top, walks upward if needed, then
   walks down and encodes only block/biome/height/light changes into column runs
   (`DH-core/core/src/main/java/com/seibel/distanthorizons/core/dataObjects/transformers/LodDataBuilder.java:64-79`,
   `161-201`, `239-263`). This is the same information Minecraft already keeps, so a chunk on
   disk needs no 3D voxel capture to produce LOD columns.
3. **Surface-only generation is explicitly supported.** `DhRoughSurfaceGenerator.generateSurface`
   samples the surface at 1 in 4 columns, smooths underwater spikes, interpolates the rest,
   and emits surface/water data points for each column
   (`DH/common/src/main/java/com/seibel/distanthorizons/common/wrappers/worldGeneration/DhRoughSurfaceGenerator.java:140-273`,
   `275-330`). `EDhApiGeneratorPlan.SURFACE_ONLY` selects this path
   (`EDhApiGeneratorPlan.java:22-67`;
   `GeneratedFullDataSourceProvider.java:368-382`).
4. **Column data is downsampled and tagged.** `FullDataSourceV2.downsampleFromOneAboveDetailLevel`
   merges 2x2 input columns into one recipient column and marks the recipient
   `DOWN_SAMPLED` (`.../FullDataSourceV2.java:1086-1140`). `GeneratedFullDataSourceProvider`
   treats `DOWN_SAMPLED` and `EMPTY` as "needs generation" and uses `SURFACE`/`FEATURES` steps
   to decide whether to queue (`GeneratedFullDataSourceProvider.java:368-482`).
5. **Rendering builds boxes from columns.** `FullDataToRenderDataTransformer` converts full
   data to `ColumnRenderSource` (`DH-core/core/src/main/java/com/seibel/distanthorizons/core/dataObjects/transformers/FullDataToRenderDataTransformer.java:73-119`);
   `ColumnRenderSource` stores vertical render data points and a void column
   (`.../dataObjects/render/ColumnRenderSource.java:46-120`). `ColumnRenderBufferBuilder`
   iterates every 64x64 column, skips empty columns, and emits one box per column data point
   (`.../bufferBuilding/ColumnRenderBufferBuilder.java:58-117`, `239-289`).
   `ColumnBox.addBoxQuadsToBuilder` emits the top face, bottom face and cardinal side faces
   from the column's min/max Y and adjacent columns
   (`.../bufferBuilding/ColumnBox.java:58-100`, `112-146`), which is exactly the "ground surface
   plus skirts" representation. `LodQuadBuilder` merges the quads into GPU buffers
   (`.../bufferBuilding/LodQuadBuilder.java:58-110`).

**JBP-LOD comparison.** The port stores packed 32^3 voxel sections
(`StoredVoxelSection`) and meshes faces from those sections (`SectionMesherCore`,
`VoxelHierarchy`). There is no column/heightmap layer. Far ground therefore exists only if a
chunk containing that ground was live-captured and ingested. At coarse LOD levels the merged
voxel hierarchy can still contain ground, but only for captured source chunks. DH's column
representation makes ground the primary data and makes a surface-only generator cheap; that
is the architectural difference behind its ability to fill a far horizon with grass/ground
without loading those chunks.

## Comparison summary

| Area | Distant Horizons | JBP-LOD now | Consequence here |
| --- | --- | --- | --- |
| Data for never-loaded chunks | DB lookup + `PRE_EXISTING_ONLY` region import + surface/chunk generator (`LodQuadTree`, `GeneratedFullDataSourceProvider`, `DhChunkGenerator`, `ChunkFileReader`) | Only live `CHUNK_LOAD`/`CHUNK_UNLOAD` capture (`LiveChunkCapture`, `LiveSectionProducer`); no region/worldgen | Far explored ground was never produced; 50 chunks/1200 sections match the loaded set |
| Stored-data discovery | Render tree requests missing sections by camera/render distance; DB key checks decide missing | Storage is read on demand per requested key; `listPositions` unused; no startup seeding | Prior-session JBP-LOD data can remain invisible; `parked` requests grow |
| Overlap | Private LOD color+depth; radial near clip; current-frame MC-depth fade; no stencil/AABB | Vanilla-depth stencil + loaded-section AABB bound + composite depth test | Can reject LOD but cannot create missing ground; conservative by design |
| Ground representation | 64x64 columns from heightmaps / surface generator; column boxes | 32^3 packed voxels from captured chunks; face mesher | No cheap far-surface fallback; ground requires captured source chunks |
| Missing vs empty | Per-column generation steps (`EMPTY`/`DOWN_SAMPLED`/`SURFACE`/`FEATURES`) | No coverage/generation metadata | Tree cannot distinguish "no data" from "empty air"; repeated parked requests |

## Actionable recommendations, ranked by evidence

### P0 - Import existing Minecraft region files (`PRE_EXISTING_ONLY` equivalent)

Evidence: the user explored this world earlier, so the ground exists in `region/r.X.Z.mca`;
JBP-LOD has no region reader, and the log proves only 50 live chunks / 1200 sections were
produced. DH's exact path is `DhChunkGenerator.java:380-410` -> `ChunkFileReader.java:117-177`;
its no-generation mode is `DhChunkGenerator.java:651-657`.

Implementation sketch:

- This is a local-save path (dev singleplayer or a locally hosted world where the region
  directory is readable). A remote multiplayer client has no region files; that case needs
  the server-side LOD/generation path instead. Do not claim remote-world coverage from this
  importer.
- On session open (after the storage scope lock), enumerate region files within the LOD
  render radius on the `STORAGE_IO` executor.
- Read chunk NBT with the game's `ChunkSerializer`/`RegionFileStorage` (or a bounded
  `RegionFile` reader), build the same per-section voxel snapshot `LiveChunkCapture` already
  builds, and reuse the hierarchy/write code behind `LiveSectionProducer.writeHierarchy`
  (for example via a new `RegionChunkImporter` that shares it).
- Deduplicate against live capture (a loaded chunk will later be re-captured; last writer
  wins), and record a per-section origin flag (`live` vs `imported`) so a full-quality live
  capture can replace an imported section.
- Do not import the whole world at once: queue nearest-first and cap per tick, as DH does.

Acceptance: with the player stationary at render distance 5 and no movement, the log's
`sections ingested`/`meshedNonEmpty` grows beyond 1200, `parked` stops growing without bound,
and the screenshot shows ground beyond the vanilla fog edge. This is the highest-confidence fix
for the reported scene.

### P0/P1 - Seed the node tree from stored positions

Evidence: `LodStorageService.listPositions` has no caller; `LiveChunkCapture` is the only
source of `onSectionUpdated`; the tree can request 1454 parked children without any producer.
If a previous JBP-LOD session already stored far sections, they are currently invisible unless
traversal happens to request their exact positions and the stored-mesh provider can answer.

Implementation sketch:

- On session open, call `listPositions(level)` for the top level(s) within the render
  distance, then inject child-existence/`onSectionUpdated` for those positions (or a compact
  key index) so the node tree can expand to them.
- Prefer an indexed `level -> positions` range scan over a full RocksDB scan per frame.
- Keep this independent of region import: it recovers JBP-LOD's own prior data; region import
  recovers vanilla's data.

Acceptance: a pre-populated RocksDB produces meshed/published far sections before any
`CHUNK_LOAD`; the screenshot shows prior-session terrain. If `listPositions` returns empty for
the dev scope, this recommendation is moot for the reported world and P0 region import is the
only path.

### P1 - Add a coarse surface/heightmap source for unexplored areas

Evidence: DH's `SURFACE_ONLY` path samples and interpolates a heightmap and emits column
surface/water data (`DhRoughSurfaceGenerator.java:140-273`); JBP-LOD has no such source, so
truly unexplored areas beyond region files can only ever be sky.

Implementation sketch (smaller than a full DH column port):

- Sample biome/surface height on a coarse grid (e.g. every 4 or 8 blocks) using the client
  level's `ChunkGenerator`/`BiomeSource` on a worker thread.
- Build a synthetic top-face + short side-skirt section at the requested LOD level, or add a
  column source that the mesher can consume. Reuse the existing `StoredVoxelSection` format if
  that avoids touching the renderer.
- Mark these sections as `surface` coverage so a later full capture/import replaces them.

Acceptance: a world with no region files beyond the imported radius still shows a continuous
ground horizon at coarse LOD; no sky holes beyond the region-import frontier. This is the
architectural answer for "the user has never been there" (as opposed to "the user explored it
earlier", which P0 covers).

### P2 - Adopt DH's near-clip/fade only if overlap artifacts remain

Evidence: the latest screenshot no longer shows the earlier near-zone speckle, and the
current stencil/bounding path is internally consistent. The missing ground is not explained by
overlap. DH's radial `uClipDistance` + current-frame fade is nevertheless a simpler and more
robust overlap model:

- `uClipDistance = vanillaRenderDistance * overdraw` with auto `0.2-0.9` and `+16` blocks
  (`RenderUtil.java:131-198`, `BlazeDhTerrainRenderer.java:258-262`).
- Terrain discard/fade in `terrain/blaze/frag.fsh:145-167`.
- Current-frame MC depth fade in `vanilla_fade.fsh:77-121`, run after MC opaque/transparent
  (`ClientApi.java:674-730`), with no depth write (`BlazeVanillaFadeRenderer.java:113-120`).

A low-risk first step is to add the radial discard to `quads.frag` and keep the existing
stencil/bounding as a second line of defense. Do not replace the stencil path until a GPU run
shows the fade is needed; the current evidence does not justify that rewrite.

### P2 - Add per-section coverage/generation metadata

Evidence: DH distinguishes `EMPTY` and `DOWN_SAMPLED` from complete columns and only queues
work for missing/underspecified columns (`GeneratedFullDataSourceProvider.java:359-482`;
`FullDataSourceV2.java:98-115`, `1086-1140`). JBP-LOD's node tree cannot currently tell "no
data" from "empty air", and the log's 1454 parked child requests are the visible symptom of
demand without a coverage answer.

Implementation sketch: store one coverage byte per section (or per column for a future column
source): `missing`, `imported`, `surface`, `full`. Use it when computing child existence and
when deciding whether to remesh. This also prevents repeated empty-air meshing and makes the
`parked` diagnostic meaningful.

### P2 - Do not port DH's entire column data model yet

DH's 64x64 columns, per-column generation steps, downsampling and column-box mesher are a
coherent but large architecture (`FullDataSourceV2`, `ColumnRenderSource`,
`ColumnRenderBufferBuilder`, `ColumnBox`). The immediate failure is solved by P0 + P1 with much
less risk. A column layer becomes worthwhile when far-LOD memory, surface-only generation, or
server/multiplayer LOD transfer is on the roadmap.

## What not to do

- Do not relax the stencil mask or the bounding discard to "make ground appear". Those paths
  can only increase overdraw; they cannot create geometry for sections that were never
  produced.
- Do not copy DH source into JBP-LOD. DH is LGPL-3.0 and this clone is a local reference only;
  use the algorithms/design as a reference and keep the port's own implementation.
- Do not assume the 1454 parked requests mean a node-tree bug. They may be the expected
  top-level leaf behavior when no producer supplies far sections (see P0/P1). Confirm with a
  pre-populated store before changing the request logic.

## Verification limits

- This note is source-level only. No DH build/run and no Minecraft launch were performed for
  the study. After the docs-only change, `JAVA_HOME=/usr/lib/jvm/java-25-openjdk
  PATH=$JAVA_HOME/bin:$PATH ./gradlew compileJava test lodJar --console=plain` completed
  `BUILD SUCCESSFUL` in 10 s with all tasks up-to-date; that verifies the existing mod source,
  not any DH or GPU claim.
- The exact reason the far structure fragments render while the surrounding ground is absent
  is inferred from the 50-chunk/1200-section log and the absence of any region/worldgen
  producer. The floating fragments are consistent with a sparse subset of stored or captured
  sections. The recommended diagnostics are: log stored-key counts at session open, add an
  imported-chunk counter, and capture `-Djbp.lod.debugView=level` and `bound` at the same
  camera to distinguish "no data" from "rejected data".
- No acceptance box is ticked by this document. GPU/game evidence remains pending.

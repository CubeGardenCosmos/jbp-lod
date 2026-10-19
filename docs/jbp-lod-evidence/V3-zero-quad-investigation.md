# JBP-LOD V3 zero-quad investigation

Status: source analysis and one unit test. No build, test, or game run was performed by this slice. The in-game result (nonzero quads, visible LOD) is **unverified**; the user's zero-quad log is the only in-game evidence.

## User log evidence (`latest.log`, read-only)

- L199 `First node publish: 54 node writes, 0 metadata writes, 0 quad uploads.`
- L200 `First section meshed: 4@[0, 0, 0] with 0 quads.` This precedes the first capture (L206), so the request ran before ingest and `StoredSectionMeshProvider.mesh` received `raw == null`.
- L206 `First chunk captured at [-14, -14]: 24 sections queued for ingest.`
- L207 `First section hierarchy stored: 5 levels`.
- L209 `Ingest backlog full (512 sections): dropping sections of an unloading chunk`.
- L208 onward: repeated `Tried processing a node that already has a request in flight: 747 pos: 4@[-1, 0, -1] ignoring`. These nodes stay in flight because their child mesh raised `ModelNotReady`.
- No `[JBP-LOD]` model bake or publish line appears in the log.

## Findings

1. **Null-data mesh is an authoritative empty result (race H1).** `SectionMesher.mesh` returns `BuiltSection.empty` for `data == null`, and `StoredSectionMeshProvider.mesh` submits it. If a BLOCK update arrives while that node is in flight, `NodeManager.processRequest` drops it (about L1055–1064), so the stale empty geometry persists. Open: see Risks.
2. **ModelNotReady wait (H2).** `ModelNotReady` extends `CancellationException`. `StoredSectionMeshProvider.fail` routes it to `finish` only, so NodeManager receives no result and the child request stays in flight. Recovery depends on `SectionMesher.retryReady`, which runs every tick from `LodSession.updateCamera` and re-requests a waiting position once `ModelProvider` reports a model id.
3. **retryReady spin (H3): not a real spin.** A blockId-0 wait arises only when the factory is null (the metadata/fluid lookups throw `ModelNotReady(0)`). Then `getModelId(0)` fails until the factory exists, and factory creation bumps the epoch, which replays `requested`. When the factory exists, `getModelId(0)` succeeds once and the position is re-requested once.
4. **Models never publish in the log (H4, unverified).** Without a publish log, the stone model may stay unpublished, so every nonempty mesh throws `ModelNotReady` and the nodes stay in flight. `ModelFactory`/`ModelProvider` are outside this slice's ownership; a Main/DiagYellow decision is needed before changing them.

## Changes in this slice

- `compute/SectionMesherTest.java`: `stoneFilledSectionMeshesToNonzeroQuadsAndNonzeroMetadataThroughEncodedPath` meshes a solid 32³ stone section through `StoredVoxelSection.encode` → `mesh`, asserting nonempty output, nonzero quads, and a metadata lookup for the stone model. Expected quad count with empty neighbors is 24 (not asserted; Main should confirm when running tests).
- `runtime/LodStats.java` (new): thread-safe counters for chunks captured, sections ingested, sections meshed, nonempty meshes, total quads, node/metadata-slot/request snapshots, and model published/pending snapshots.
- `runtime/LodSession.java`: a daemon `JBP-LOD stats` scheduler logs `[JBP-LOD] stats: ...` every 10 s from session construction until `closeNow`/`closeAsync`. A failed log run is caught and logged as WARN so the schedule continues. `stats()` accessor added.
- Minimal feeds: `data/ingest/LiveSectionProducer.java` (chunk count at `capture`, section count after `writeHierarchy`); `storage/StoredSectionMeshProvider.java` (new `IntConsumer onMeshed` constructor parameter, called on every submitted result); `data/tree/LodNodeService.java` (volatile count snapshots refreshed each worker batch, plus getters).

## Risks left open

- Stale empty geometry from H1 can persist if a BLOCK update is dropped while the node is in flight (`NodeManager.processRequest`, in-flight branch). The fix needs a deferred-remesh record and a re-run at the leaf-expansion completion (about L856–898), which requires verification under the NodeManager tests. Not done here.
- Model publication (H4) must be confirmed in-game with the new stats line (`models published=... pending=...`).
- The `models.factory()` counters are read from the stats thread without render-thread synchronization. They are advisory counters, and failures are caught.

## Checks for Main

- `JAVA_HOME=/usr/lib/jvm/java-25-openjdk PATH=$JAVA_HOME/bin:$PATH ./gradlew compileJava test lodJar --console=plain` in `mods/jbp-lod`.
- In the next game run, check for `[JBP-LOD] stats:` every 10 s. Expect `sections ingested` > 0 and `meshedNonEmpty` > 0 once terrain arrives; if `models published=0`, H4 is confirmed.

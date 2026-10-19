package cn.cubegarden.cosmos.jbp.client.lod.data.ingest;

import cn.cubegarden.cosmos.jbp.client.lod.data.ActivationLog;
import cn.cubegarden.cosmos.jbp.client.lod.data.IdMapper;
import cn.cubegarden.cosmos.jbp.client.lod.data.tree.SectionPos;
import cn.cubegarden.cosmos.jbp.client.lod.runtime.LodSession;
import net.minecraft.core.Holder;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import org.slf4j.LoggerFactory;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Snapshot ownership fixes upstream VoxelIngestService's mutable section handoff. Conversion follows
 * sealed Voxy WorldConversionFactory: air canonicalization, quart-biomes and sky | block << 4 light.
 * Mapping/NBT, hierarchy reads/modification/writes and notifications run exclusively on a worker.
 */
public final class LiveSectionProducer {
    private final LodSession session;
    private final VoxelHierarchyWriter hierarchy;
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "JBP-LOD voxel ingest"); thread.setDaemon(true); return thread;
    });
    private final CaptureAdmission admission = new CaptureAdmission(CaptureAdmission.MAX_PENDING_SECTIONS);
    private static final org.slf4j.Logger LOGGER = LoggerFactory.getLogger("jbp-lod");
    public LiveSectionProducer(LodSession session) {
        this.session = session;
        this.hierarchy = session.hierarchyWriter();
    }

    /**
     * Called only on the game thread; no live mutable chunk state escapes this method. Each non-empty section is
     * admitted against the ingest backlog bound before its snapshot is built; sections that do not fit are counted
     * and dropped rather than queued without limit.
     *
     * @return number of non-empty sections rejected by the backlog bound (0 when every section was admitted)
     */
    public synchronized int capture(LevelChunk chunk) {
        session.stats().chunkCaptured();
        var light = chunk.getLevel().getLightEngine();
        int sectionY = chunk.getMinSectionY();
        int accepted = 0, rejected = 0;
        for (var section : chunk.getSections()) {
            int y = sectionY++;
            if (section == null) continue;
            if (!admission.tryAcquire()) {
                rejected++;
                continue;
            }
            accepted++;
            BlockState[] states = new BlockState[4096];
            @SuppressWarnings("unchecked") Holder<Biome>[] biomes = (Holder<Biome>[]) new Holder<?>[64];
            for (int i = 0; i < 4096; i++) states[i] = section.getStates().get(i & 15, i >> 8, (i >> 4) & 15);
            for (int i = 0; i < 64; i++) biomes[i] = section.getBiomes().get(i & 3, i >> 4, (i >> 2) & 3);
            var pos = net.minecraft.core.SectionPos.of(chunk.getPos(), y);
            var block = light.getLayerListener(LightLayer.BLOCK).getDataLayerData(pos);
            var sky = light.getLayerListener(LightLayer.SKY).getDataLayerData(pos);
            var blockCopy = block == null ? null : block.copy();
            var skyCopy = sky == null ? null : sky.copy();
            int x = chunk.getPos().x(), z = chunk.getPos().z();
            try {
                worker.execute(() -> {
                    try {
                        if (admission.isClosed()) return;//Accepted before close: skip the conversion and writes
                        long[] words = new long[4096];
                        IdMapper mapper = session.mapper();
                        int skySum = 0;
                        boolean skyAllZero = skyCopy != null;
                        for (int i = 0; i < words.length; i++) {
                            int vx = i & 15, vy = i >> 8, vz = (i >> 4) & 15;
                            int skyLevel = skyCopy == null ? 0 : Math.min(15, skyCopy.get(vx, vy, vz));
                            skySum += skyLevel;
                            if (skyLevel != 0) skyAllZero = false;
                            int illumination = skyLevel
                                    | ((blockCopy == null ? 0 : Math.min(15, blockCopy.get(vx, vy, vz))) << 4);
                            words[i] = mapper.getBaseId((byte) illumination, states[i], biomes[(vx >> 2) | (vz >> 2) << 2 | (vy >> 2) << 4]);
                        }
                        session.stats().lightSection(skyCopy != null, blockCopy != null, skyAllZero,
                                skyCopy != null && skySum == 4096 * 15);
                        writeHierarchy(x, y, z, words);
                        session.stats().sectionIngested();
                    } catch (RuntimeException failure) {
                        LOGGER.warn("[JBP-LOD] Section ingest failed", failure);
                    } finally {
                        admission.release();
                    }
                });
            } catch (RuntimeException rejectedExecution) {
                admission.release();
                throw rejectedExecution;
            }
        }
        if (accepted > 0 && ActivationLog.first("chunks")) {
            LOGGER.info("[JBP-LOD] First chunk captured at {}: {} sections queued for ingest.", chunk.getPos(), accepted);
        }
        if (rejected > 0 && ActivationLog.first("capture-drop")) {
            LOGGER.warn("[JBP-LOD] Ingest backlog full ({} sections): dropping sections of an unloading chunk; they are captured again when the chunk reloads.", CaptureAdmission.MAX_PENDING_SECTIONS);
        }
        return rejected;
    }

    private void writeHierarchy(int x, int y, int z, long[] source) {
        int sectionX = x, sectionY = y, sectionZ = z;
        this.hierarchy.write(x, y, z, source);
        if (ActivationLog.first("stored")) {
            LOGGER.info("[JBP-LOD] First section hierarchy stored: {} levels from section {}.",
                    SectionPos.MAX_LOD_LAYER + 1, SectionPos.pretty(SectionPos.pack(0, sectionX, sectionY, sectionZ)));
        }
    }

    /**
     * Stops accepting snapshots and drains the backlog. Snapshots queued before close are skipped by the worker
     * (see {@link #capture}), so the sentinel completes after at most the task currently running.
     * Never waits on the game/render thread.
     */
    public synchronized CompletableFuture<Void> closeAsync() {
        if (admission.isClosed()) return CompletableFuture.completedFuture(null);
        admission.close();
        CompletableFuture<Void> done = new CompletableFuture<>();
        worker.execute(() -> done.complete(null));
        worker.shutdown();
        return done;
    }

    /** Accepted-but-unwritten section snapshots; the game thread checks this before capturing a chunk. */
    public boolean hasCapacity(int sectionCount) {
        return admission.hasCapacity(sectionCount);
    }
}

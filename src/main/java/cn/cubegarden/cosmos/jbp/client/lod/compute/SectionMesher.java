package cn.cubegarden.cosmos.jbp.client.lod.compute;

import cn.cubegarden.cosmos.jbp.client.lod.data.ActivationLog;
import cn.cubegarden.cosmos.jbp.client.lod.data.IdMapper;
import cn.cubegarden.cosmos.jbp.client.lod.data.ingest.StoredVoxelSection;
import cn.cubegarden.cosmos.jbp.client.lod.data.tree.BuiltSection;
import cn.cubegarden.cosmos.jbp.client.lod.data.tree.SectionPos;
import cn.cubegarden.cosmos.jbp.client.lod.model.ModelProvider;
import cn.cubegarden.cosmos.jbp.client.lod.storage.LodStorageService;
import java.util.Arrays;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongConsumer;
import java.util.function.LongFunction;

/** Worker-side snapshot adapter for the sealed upstream RenderDataFactory algorithm. */
public final class SectionMesher implements cn.cubegarden.cosmos.jbp.client.lod.data.tree.SectionMesher {
    /**
     * Upstream {@code ActiveSectionTracker.acquire} fills a section whose storage load misses with
     * {@code Mapper.composeMappingId((byte) 15, 0, 0)}: air at full sky light, never darkness. The mesher
     * copies a face's light from the adjacent voxel, so a missing neighbour section must be bright air;
     * an all-zero array here blackens every face on the boundary of the stored LoD region.
     */
    private static final long[] MISSING_SECTION_AIR = fullSkyAir();

    /**
     * Snapshot access for the mesher's neighbour sections (runs only on mesh workers). A stored section
     * that does not exist becomes {@link #missingSectionAir()}, matching upstream {@code WorldEngine.acquire}.
     */
    static LongFunction<long[]> sectionSource(LongFunction<byte[]> encoded) {
        return key -> {
            byte[] raw = encoded.apply(key);
            return raw == null ? MISSING_SECTION_AIR : StoredVoxelSection.decode(raw);
        };
    }

    /** Read-only reference used for every absent neighbour section; never mutated by the mesher. */
    static long[] missingSectionAir() {
        return MISSING_SECTION_AIR;
    }

    private static long[] fullSkyAir() {
        long[] air = new long[32 * 32 * 32];
        Arrays.fill(air, IdMapper.airWithLight(15));
        return air;
    }

    /** Published model metadata only; no Vulkan operations occur on mesh workers. */
    public interface Models {
        int getModelId(int blockId);
        long getModelMetadataFromClientId(int modelId);
        int getFluidClientStateId(int modelId);
    }

    /** Expected asynchronous bakery dependency, not a malformed section or fabricated empty mesh. */
    static final class ModelNotReady extends CancellationException {
        final int blockId;
        /** Waiting on the factory or the model epoch rather than one block: replayed on epoch change only. */
        static final int NO_BLOCK = -1;
        ModelNotReady(int blockId, boolean ignored) {
            super("Model is not published for block " + blockId);
            this.blockId = blockId;
        }
    }

    private static final org.slf4j.Logger LOGGER = org.slf4j.LoggerFactory.getLogger("jbp-lod");

    private final SectionMesherCore core;
    private final Models models;
    private final ConcurrentHashMap<Long, Integer> waiting = new ConcurrentHashMap<>();
    private final Set<Long> requested = ConcurrentHashMap.newKeySet();
    private long epoch;
    private java.util.function.LongSupplier modelEpoch = () -> 0L;

    public SectionMesher(ModelProvider provider, LodStorageService storage) {
        this(new Models() {
            @Override public int getModelId(int blockId) {
                int id = provider.modelIdForBlockState(blockId);
                if (id < 0) throw new ModelNotReady(blockId, true);
                return id;
            }
            @Override public long getModelMetadataFromClientId(int modelId) {
                var factory = provider.factory();
                if (factory == null) throw new ModelNotReady(ModelNotReady.NO_BLOCK, true);
                return factory.modelMetadata(modelId);
            }
            @Override public int getFluidClientStateId(int modelId) {
                var factory = provider.factory();
                if (factory == null) throw new ModelNotReady(ModelNotReady.NO_BLOCK, true);
                int id = factory.fluidModelId(modelId);
                if (id < 0) throw new IllegalStateException("Published model lacks fluid model: " + modelId);
                return id;
            }
        }, sectionSource(key -> storage.getSectionData(key).join()));
        epoch = provider.epoch();
        modelEpoch = provider::epoch;
    }

    public SectionMesher(Models models, LongFunction<long[]> neighbors) {
        this.models = models;
        this.core = new SectionMesherCore(models, neighbors);
    }

    @Override
    public synchronized BuiltSection mesh(long position, byte[] data) {
        requested.add(position);
        long startedEpoch = modelEpoch.getAsLong();
        if (data == null) return BuiltSection.empty(position);
        long[] voxels = StoredVoxelSection.decode(data);
        byte children = 0;
        if (SectionPos.level(position) != 0) {
            for (int i = 0; i < voxels.length; i++) {
                if (!IdMapper.isAir(voxels[i])) {
                    int child = ((i >> 4) & 1) | ((i >> 8) & 2) | ((i >> 12) & 4);
                    children |= (byte) (1 << child);
                }
            }
        } else {
            for (long voxel : voxels) {
                if (!IdMapper.isAir(voxel)) {
                    children = (byte) 0xff;
                    break;
                }
            }
        }
        try {
            BuiltSection result = core.generateMesh(new SectionMesherCore.Input(position, voxels, children));
            if (startedEpoch != modelEpoch.getAsLong()) throw new ModelNotReady(ModelNotReady.NO_BLOCK, true);
            if (result.translucentQuadCount() > 0 && ActivationLog.first("translucent-mesh")) {
                LOGGER.info("[JBP-LOD] First section meshed with translucent quads: {} at {}",
                        result.translucentQuadCount(), SectionPos.pretty(position));
            }
            waiting.remove(position);
            return result;
        } catch (ModelNotReady missing) {
            if (missing.blockId == ModelNotReady.NO_BLOCK) {
                waiting.remove(position);//Replayed by the epoch check in retryReady, not by a block publication
            } else {
                waiting.put(position, missing.blockId);
            }
            throw missing;
        }
    }

    /** Render-thread polling only reads published IDs and schedules work; it never waits for meshes. */
    public void retryReady(long currentEpoch, LongConsumer request) {
        if (epoch != currentEpoch) {
            epoch = currentEpoch;
            requested.forEach(request::accept);
        }
        waiting.forEach((position, blockId) -> {
            try {
                if (models.getModelId(blockId) >= 0 && waiting.remove(position, blockId)) request.accept(position);
            } catch (ModelNotReady notYetPublished) {
                // Retain the dependency until a subsequent bakery publication.
            }
        });
    }

    /** Sections whose mesh waits for a bakery publication (block-keyed dependency). */
    public int waitingCount() {
        return waiting.size();
    }

    /** Distinct positions ever submitted for meshing; meshed results above this count are remeshes. */
    public int distinctRequestedCount() {
        return requested.size();
    }
}

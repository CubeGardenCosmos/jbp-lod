package cn.cubegarden.cosmos.jbp.client.lod.model;

import cn.cubegarden.cosmos.jbp.client.lod.data.BlockModelData;
import cn.cubegarden.cosmos.jbp.client.lod.data.BlockStateModelIdResolver;
import cn.cubegarden.cosmos.jbp.client.lod.vk.bridge.GameVulkan;
import cn.cubegarden.cosmos.jbp.client.lod.vk.bridge.mixin.StairBlockAccessor;
import cn.cubegarden.cosmos.jbp.client.lod.vk.gpu.GpuCompletion;
import cn.cubegarden.cosmos.jbp.client.lod.vk.gpu.GpuRetireQueue;
import cn.cubegarden.cosmos.jbp.client.lod.vk.gpu.LodModelBuffers;
import cn.cubegarden.cosmos.jbp.client.lod.vk.shader.ShaderCompiler;
import com.mojang.renderpearl.backend.vulkan.VulkanGpuTextureView;
import it.unimi.dsi.fastutil.objects.Object2IntMap;
import net.minecraft.client.Minecraft;
import net.minecraft.client.color.block.BlockTintSource;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Vulkan port of upstream {@code ModelFactory}: bakes block-state models into the LOD atlas, the 64-byte
 * {@code BlockModel} SSBO and the biome colour SSBO, and publishes immutable {@link ModelResources}
 * generations.
 *
 * <p>Threading: {@link #requestBake(int)}, {@link #addBiome(int, String)} and
 * {@link #modelIdForBlockState(int)} may be called from any thread; everything else, in particular
 * {@link #tick()}, {@link #resources()} and {@link #close()}, runs on the render thread that owns the borrowed
 * game device. Model IDs become visible to {@link #modelIdForBlockState(int)} only when the generation that
 * contains their record, colours and atlas tile is published, so ingested IDs never exceed the SSBO a draw sees.
 */
public final class ModelFactory implements BlockStateModelIdResolver, AutoCloseable {
    public static final int MODEL_TEXTURE_SIZE = ModelAtlasLayout.FACE_SIZE;

    private static final Logger LOGGER = LoggerFactory.getLogger("jbp-lod");
    private static final int BAKER_SLOTS = 8;
    private static final int MAX_REQUESTS_PER_TICK = 256;
    private static final long PUBLISH_INTERVAL_NANOS = 100_000_000L;
    private static final int MAX_BLOCK_STATE_ID = (1 << 20) - 1;
    private static final int MAX_MODELS = ModelAtlasLayout.MAX_MODEL_ID + 1;

    private final GameVulkan.Snapshot game;
    private final ModelStateSource source;
    private final Biome defaultBiome;
    private final VulkanModelAtlas atlas;
    private final VulkanModelBaker baker;
    private final ModelGeometryBaker geometry;

    private final ModelIdTable ids = new ModelIdTable();
    private final ModelColourTable colours = new ModelColourTable();
    private final long[] metadataCache = new long[MAX_MODELS];
    private final int[] fluidStateLut = new int[MAX_MODELS];
    private byte[] recordBytes = new byte[BlockModelData.BYTE_SIZE * 256];

    private final ConcurrentLinkedQueue<Integer> requestQueue = new ConcurrentLinkedQueue<>();
    private final Set<Integer> requestedIds = ConcurrentHashMap.newKeySet();
    private final ConcurrentLinkedQueue<BiomeRequest> biomeQueue = new ConcurrentLinkedQueue<>();
    private final List<Biome> biomes = new ArrayList<>();
    private final Set<Integer> inFlight = new HashSet<>();
    private final ArrayDeque<Pending> pending = new ArrayDeque<>();
    private final ArrayList<int[]> unpublishedMappings = new ArrayList<>();
    private volatile int[] publishedMap;
    private volatile Object2IntMap<BlockState> customBlockStateIdMapping;

    private ModelResources current;
    private long generation;
    private long lastPublishNanos;
    private boolean dirty = true;
    private boolean closed;

    public ModelFactory(GameVulkan.Snapshot game, ShaderCompiler compiler, VulkanGpuTextureView blockAtlasView,
                        ModelStateSource source) {
        this.game = game;
        this.source = source;
        var level = Minecraft.getInstance().level;
        if (level == null) {
            throw new IllegalStateException("[JBP-LOD] model factory requires a loaded client level for biomes");
        }
        this.defaultBiome = level.registryAccess().lookupOrThrow(Registries.BIOME).getValue(Biomes.PLAINS);
        Arrays.fill(fluidStateLut, -1);
        int[] map = new int[256];
        Arrays.fill(map, MISSING_MODEL_ID);
        map[0] = AIR_MODEL_ID;
        this.publishedMap = map;

        VulkanModelAtlas createdAtlas = null;
        VulkanModelBaker createdBaker = null;
        try {
            createdAtlas = new VulkanModelAtlas(game);
            createdBaker = new VulkanModelBaker(game, compiler, blockAtlasView, BAKER_SLOTS);
            this.atlas = createdAtlas;
            this.baker = createdBaker;
            this.geometry = new ModelGeometryBaker();
        } catch (RuntimeException | Error failure) {
            if (createdBaker != null) createdBaker.close();
            if (createdAtlas != null) createdAtlas.close();
            throw failure;
        }
        seedAir();
    }

    public void setCustomBlockStateMapping(Object2IntMap<BlockState> mapping) {
        this.customBlockStateIdMapping = mapping;
    }

    /** Requests a bake for a dense source block-state ID. Idempotent and thread-safe. */
    public void requestBake(int blockId) {
        if (blockId < 0 || blockId > MAX_BLOCK_STATE_ID) {
            LOGGER.error("[JBP-LOD] model bake requested for out-of-range block-state ID {}", blockId);
            return;
        }
        if (blockId == 0 || !requestedIds.add(blockId)) {
            return;
        }
        requestQueue.add(blockId);
    }

    /** Registers a dense biome ID and its registry key (e.g. {@code minecraft:plains}). Thread-safe. */
    public void addBiome(int biomeId, String biomeKey) {
        if (biomeId < 0 || biomeId > 0xffff) {
            throw new IllegalArgumentException("biome ID is outside the supported dense range: " + biomeId);
        }
        biomeQueue.add(new BiomeRequest(biomeId, java.util.Objects.requireNonNull(biomeKey, "biomeKey")));
    }

    /** Returns a packed model ID once published, or -1 after queueing a bake. Air is always 0. */
    @Override
    public int modelIdForBlockState(int blockStateId) {
        int[] map = publishedMap;
        if (blockStateId >= 0 && blockStateId < map.length) {
            int modelId = map[blockStateId];
            if (modelId != MISSING_MODEL_ID) {
                return modelId;
            }
        }
        requestBake(blockStateId);
        return MISSING_MODEL_ID;
    }

    /** Advances baking: submits requests, finalizes completed bakes, uploads tiles and publishes generations. */
    public void tick() {
        ensureOpen();
        GpuRetireQueue.poll(game);
        atlas.poll();
        drainBiomes();
        submitRequests();
        drainResults();
        if (atlas.pendingTiles() > 0) {
            atlas.flush();
        }
        long now = System.nanoTime();
        boolean idle = pending.isEmpty() && requestQueue.isEmpty();
        if (dirty && (current == null || idle || now - lastPublishNanos >= PUBLISH_INTERVAL_NANOS)) {
            publish(now);
        }
    }

    /** New retained handle to the latest published generation; the caller MUST close it. Null before first tick. */
    public ModelResources resources() {
        ensureOpen();
        return current == null ? null : current.retain();
    }

    public int bakedCount() {
        return ids.modelCount();
    }

    public int inflightCount() {
        return inFlight.size() + requestQueue.size() + biomeQueue.size();
    }

    public boolean isIdle() {
        return inflightCount() == 0 && !dirty;
    }

    /** Upstream {@code getModelMetadataFromClientId}; valid for published model IDs. */
    public long modelMetadata(int modelId) {
        return metadataCache[modelId];
    }

    /** Upstream {@code getFluidClientStateId}: fluid model of a model, or -1. */
    public int fluidModelId(int modelId) {
        return fluidStateLut[modelId];
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        baker.close();
        atlas.close();
        if (current != null) {
            GpuRetireQueue.retire(GpuCompletion.submitMarker(game), current);
            current = null;
        }
    }

    private void ensureOpen() {
        if (closed) {
            throw new IllegalStateException("model factory is closed");
        }
    }

    private void seedAir() {
        ModelRecordEncoder.FaceStats[] faces = new ModelRecordEncoder.FaceStats[6];
        Arrays.fill(faces, ModelRecordEncoder.FaceStats.empty());
        var encoded = ModelRecordEncoder.encode(faces, ModelRecordEncoder.RenderLayer.SOLID,
                false, false, false, false, false, false, 0, ModelColourTable.NO_TINT, 0);
        writeRecord(AIR_MODEL_ID, encoded.data());
        metadataCache[AIR_MODEL_ID] = encoded.metadata();
        colours.registerModel(AIR_MODEL_ID, false, false, 0, null);
    }

    private void drainBiomes() {
        BiomeRequest request;
        while ((request = biomeQueue.poll()) != null) {
            var registry = Minecraft.getInstance().level.registryAccess().lookupOrThrow(Registries.BIOME);
            var entry = registry.get(Identifier.parse(request.key));
            if (entry.isEmpty()) {
                LOGGER.warn("[JBP-LOD] could not find biome {}; using the default biome colours", request.key);
            }
            addBiome0(request.id, entry.isPresent() ? entry.orElseThrow().value() : defaultBiome);
        }
    }

    private void addBiome0(int id, Biome biome) {
        while (biomes.size() <= id) {
            biomes.add(null);
        }
        Biome old = biomes.set(id, biome);
        if (old != null && old != biome) {
            throw new IllegalStateException("biome ID " + id + " was reassigned to a different biome");
        }
        if (old == biome) {
            LOGGER.error("[JBP-LOD] duplicate biome registration for dense ID {}", id);
            return;
        }
        colours.registerBiome(id);
        dirty = true;
    }

    private void submitRequests() {
        int budget = MAX_REQUESTS_PER_TICK;
        Integer next;
        while (budget-- > 0 && (next = requestQueue.peek()) != null) {
            if (baker.freeSlots() < 2) {
                return;
            }
            requestQueue.poll();
            int blockId = next;
            try {
                startBake(blockId);
            } catch (RuntimeException failure) {
                failBake(blockId, failure);
            }
        }
    }

    /** Port of upstream {@code addEntry}: fluid state models are enqueued (and so finalized) before the block. */
    private void startBake(int blockId) {
        if (ids.isMapped(blockId) || inFlight.contains(blockId)) {
            return;
        }
        if (blockId >= source.blockStateCount()) {
            LOGGER.error("[JBP-LOD] bake request for unregistered block-state ID {} (count {})",
                    blockId, source.blockStateCount());
            return;
        }
        BlockState state = source.blockStateForId(blockId);
        if (state.getBlock() instanceof StairBlock stairs) {
            state = ((StairBlockAccessor) stairs).jbpLod$getBaseState().getBlock().withPropertiesOf(state);
        }
        boolean isFluid = state.getBlock() instanceof LiquidBlock;
        if (!isFluid && !state.getFluidState().isEmpty()) {
            int fluidStateId = source.idForBlockState(state.getFluidState().createLegacyBlock());
            if (!ids.isMapped(fluidStateId) && !inFlight.contains(fluidStateId)) {
                try {
                    startBake(fluidStateId);
                } catch (RuntimeException failure) {
                    failBake(fluidStateId, failure);
                }
            }
        }
        var baked = geometry.bake(state);
        Pending entry = new Pending(blockId, state, baked.rasterFlags());
        if (baked.isEmpty()) {
            entry.complete(emptyFaces());
        } else {
            baker.submit(entry, baked.views());
        }
        inFlight.add(blockId);
        pending.addLast(entry);
    }

    private void drainResults() {
        for (VulkanModelBaker.Result result : baker.pollCompleted()) {
            ((Pending) result.token()).complete(result.faces());
        }
        while (!pending.isEmpty() && pending.peekFirst().faces != null) {
            Pending entry = pending.pollFirst();
            try {
                finalizeBake(entry);
            } catch (RuntimeException failure) {
                failBake(entry.blockId, failure);
            }
            inFlight.remove(entry.blockId);
        }
    }

    private void failBake(int blockId, RuntimeException failure) {
        LOGGER.error("[JBP-LOD] baking block-state ID {} failed; it is rendered as air", blockId, failure);
        ids.alias(blockId, AIR_MODEL_ID);
        unpublishedMappings.add(new int[]{blockId, AIR_MODEL_ID});
        dirty = true;
    }

    /** Port of upstream {@code processModelResult} + {@code processTextureBakeResult}. */
    private void finalizeBake(Pending entry) {
        ColourDepthTextureData[] faces = entry.faces;
        BlockState state = entry.state;
        int rasterFlags = entry.rasterFlags;
        boolean shaded = (rasterFlags & ModelGeometryBaker.FLAG_SHADED) != 0;
        boolean darkened = ModelRecordEncoder.needsDarkenedMips(rasterFlags);
        var layer = ModelRecordEncoder.selectLayer(rasterFlags, faces, state.is(BlockTags.LEAVES));

        boolean isFluid = state.getBlock() instanceof LiquidBlock;
        int clientFluidStateId = -1;
        boolean hasFluidState = !isFluid && !state.getFluidState().isEmpty();
        if (hasFluidState) {
            int fluidStateId = source.idForBlockState(state.getFluidState().createLegacyBlock());
            clientFluidStateId = ids.modelIdForBlockState(fluidStateId);
            if (clientFluidStateId == MISSING_MODEL_ID) {
                throw new IllegalStateException("block has a fluid state but the fluid state is not already baked");
            }
        }

        List<BlockTintSource> tintSources = ModelTinting.tintSources(state);
        boolean biomeDependent = tintSources != null && ModelTinting.isBiomeDependent(tintSources, state);
        int tintingColour = biomeDependent || tintSources == null
                ? -1 : ModelTinting.colourInBiome(tintSources, state, defaultBiome) | 0xFF000000;

        byte[] signature = ModelSignature.of(faces, clientFluidStateId, tintingColour);
        int modelCountBefore = ids.modelCount();
        int modelId;
        try {
            modelId = ids.register(entry.blockId, signature);
        } catch (ModelAtlasFullException full) {
            LOGGER.error("[JBP-LOD] the 65536-entry model atlas is full; block-state ID {} is rendered as air",
                    entry.blockId);
            ids.alias(entry.blockId, AIR_MODEL_ID);
            unpublishedMappings.add(new int[]{entry.blockId, AIR_MODEL_ID});
            dirty = true;
            return;
        }
        unpublishedMappings.add(new int[]{entry.blockId, modelId});
        dirty = true;
        if (ids.modelCount() == modelCountBefore) {
            return;//Duplicate of an already baked model
        }

        if (isFluid) {
            fluidStateLut[modelId] = modelId;
        } else if (clientFluidStateId != -1) {
            fluidStateLut[modelId] = clientFluidStateId;
        }

        boolean biomeColoured = biomeDependent;
        if (hasFluidState && clientFluidStateId != -1) {
            biomeColoured |= ModelQueries.isBiomeColoured(metadataCache[clientFluidStateId]);
        }

        int colourTint;
        if (tintSources == null) {
            colourTint = colours.registerModel(modelId, false, false, 0, null);
        } else if (!biomeColoured) {
            colourTint = colours.registerModel(modelId, true, false, tintingColour, null);
        } else {
            colourTint = colours.registerModel(modelId, true, true, 0,
                    biomeId -> biomeColour(tintSources, state, biomeId));
        }

        ModelRecordEncoder.FaceStats[] stats = new ModelRecordEncoder.FaceStats[6];
        for (int face = 0; face < 6; face++) {
            stats[face] = ModelRecordEncoder.statsFor(faces[face], layer);
        }
        var encoded = ModelRecordEncoder.encode(stats, layer, tintSources != null, biomeColoured, shaded,
                hasFluidState, isFluid, cullsSame(state), lightEmission(state), colourTint, customId(state));
        writeRecord(modelId, encoded.data());
        metadataCache[modelId] = encoded.metadata();
        atlas.queueTile(modelId, MipGen.putTextures(darkened, faces));
    }

    private int biomeColour(List<BlockTintSource> tintSources, BlockState state, int biomeId) {
        Biome biome = biomeId < biomes.size() ? biomes.get(biomeId) : null;
        return biome == null ? -1 : ModelTinting.colourInBiome(tintSources, state, biome);
    }

    private int customId(BlockState state) {
        Object2IntMap<BlockState> custom = customBlockStateIdMapping;
        return custom != null && custom.containsKey(state) ? custom.getInt(state) : 0;
    }

    private static int lightEmission(BlockState state) {
        return state.emissiveRendering() ? 15 : Math.clamp(state.getLightEmission(), 0, 15);
    }

    /** Guesstimate from upstream: a block culls itself when it skips rendering against itself on every side. */
    private static boolean cullsSame(BlockState state) {
        for (Direction direction : Direction.values()) {
            if (!state.skipRendering(state, direction)) {
                return false;
            }
        }
        return true;
    }

    private void writeRecord(int modelId, BlockModelData data) {
        int end = Math.multiplyExact(modelId + 1, BlockModelData.BYTE_SIZE);
        if (end > recordBytes.length) {
            recordBytes = Arrays.copyOf(recordBytes, Math.max(end, recordBytes.length * 2));
        }
        data.writeTo(ByteBuffer.wrap(recordBytes), modelId * BlockModelData.BYTE_SIZE);
    }

    private void publish(long now) {
        int modelCount = ids.modelCount();
        ModelColourTable.Snapshot colourSnapshot = colours.snapshot(modelCount);
        byte[] models = Arrays.copyOf(recordBytes, Math.multiplyExact(modelCount, BlockModelData.BYTE_SIZE));
        ByteBuffer view = ByteBuffer.wrap(models).order(ByteOrder.LITTLE_ENDIAN);
        for (int modelId = 0; modelId < modelCount; modelId++) {
            view.putInt(modelId * BlockModelData.BYTE_SIZE + 28, colourSnapshot.colourTint(modelId));
        }
        if (atlas.pendingTiles() > 0) {
            atlas.flush();
        }
        LodModelBuffers buffers = LodModelBuffers.upload(game, models, colourSnapshot.colourBytes());
        ModelResources next;
        try {
            next = ModelResources.create(buffers, atlas, ++generation, modelCount);
        } catch (RuntimeException | Error failure) {
            buffers.close();
            throw failure;
        }
        ModelResources previous = current;
        current = next;
        if (previous != null) {
            GpuRetireQueue.retire(GpuCompletion.submitMarker(game), previous);
        }
        publishMappings();
        dirty = false;
        lastPublishNanos = now;
    }

    private void publishMappings() {
        int[] map = publishedMap;
        int maxId = map.length - 1;
        for (int[] mapping : unpublishedMappings) {
            maxId = Math.max(maxId, mapping[0]);
        }
        if (maxId >= map.length) {
            int length = map.length;
            while (length <= maxId) {
                length = Math.min(MAX_BLOCK_STATE_ID + 1, length * 2);
            }
            int old = map.length;
            map = Arrays.copyOf(map, length);
            Arrays.fill(map, old, length, MISSING_MODEL_ID);
        } else {
            map = map.clone();
        }
        for (int[] mapping : unpublishedMappings) {
            map[mapping[0]] = mapping[1];
        }
        unpublishedMappings.clear();
        publishedMap = map;
    }

    private static ColourDepthTextureData[] emptyFaces() {
        int size = MODEL_TEXTURE_SIZE * MODEL_TEXTURE_SIZE;
        ColourDepthTextureData[] faces = new ColourDepthTextureData[6];
        for (int face = 0; face < 6; face++) {
            faces[face] = new ColourDepthTextureData(new int[size], new int[size],
                    MODEL_TEXTURE_SIZE, MODEL_TEXTURE_SIZE);
        }
        return faces;
    }

    private record BiomeRequest(int id, String key) {
    }

    private static final class Pending {
        final int blockId;
        final BlockState state;
        final int rasterFlags;
        ColourDepthTextureData[] faces;

        Pending(int blockId, BlockState state, int rasterFlags) {
            this.blockId = blockId;
            this.state = state;
            this.rasterFlags = rasterFlags;
        }

        void complete(ColourDepthTextureData[] result) {
            this.faces = result;
        }
    }
}

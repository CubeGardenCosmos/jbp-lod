package cn.cubegarden.cosmos.jbp.client.lod.model;

import cn.cubegarden.cosmos.jbp.client.lod.data.BlockStateModelIdResolver;
import cn.cubegarden.cosmos.jbp.client.lod.vk.bridge.GameVulkan;
import cn.cubegarden.cosmos.jbp.client.lod.vk.shader.ShaderCompiler;
import com.mojang.renderpearl.backend.vulkan.VulkanGpuTextureView;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * Scope-owned model bakery lifecycle: creates a {@link ModelFactory} against the game's Vulkan device and
 * vanilla block atlas, and rebuilds it (replaying every bake and biome request) when the device, the block
 * atlas or the client resources change. Closing the provider (scope shutdown) releases the factory;
 * {@link ModelResources} handles already acquired by in-flight frames stay valid until closed.
 *
 * <p>Threading: {@link #requestBake(int)}, {@link #addBiome(int, String)} and
 * {@link #modelIdForBlockState(int)} are thread-safe. {@link #tick()}, {@link #acquire()} and
 * {@link #close()} are render-thread only.
 *
 * <p>After {@link #epoch()} changes, previously issued model IDs are invalid: IDs are re-assigned by the new
 * factory, so consumers must re-run ingest for geometry packed with the old IDs.
 */
public final class ModelProvider implements BlockStateModelIdResolver, AutoCloseable {
    private static final Logger LOGGER = LoggerFactory.getLogger("jbp-lod");
    private static final Identifier BLOCK_ATLAS = Identifier.fromNamespaceAndPath("minecraft", "textures/atlas/blocks.png");

    private final ModelStateSource source;
    private final ShaderCompiler compiler = new ShaderCompiler();
    private final Set<Integer> requested = Collections.synchronizedSet(new LinkedHashSet<>());
    private final Map<Integer, String> biomes = Collections.synchronizedMap(new LinkedHashMap<>());

    private volatile ModelFactory factory;
    private volatile long epoch;
    private long factoryResourceGeneration;
    private long factoryDeviceAddress;
    private VulkanGpuTextureView factoryAtlasView;
    private boolean closed;

    public ModelProvider(ModelStateSource source) {
        this.source = java.util.Objects.requireNonNull(source, "source");
        ModelLifecycle.registerOnce();
    }

    /** Registers the resource-reload listener early (mod init); the constructor also does this. */
    public static void registerLifecycle() {
        ModelLifecycle.registerOnce();
    }

    /** Increments whenever a new factory replaces the previous one (model IDs are then re-assigned). */
    public long epoch() {
        return epoch;
    }

    public void requestBake(int blockId) {
        if (blockId > 0) {
            requested.add(blockId);
        }
        ModelFactory current = factory;
        if (current != null) {
            current.requestBake(blockId);
        }
    }

    public void addBiome(int biomeId, String biomeKey) {
        if (biomes.putIfAbsent(biomeId, biomeKey) != null) {
            return;
        }
        ModelFactory current = factory;
        if (current != null) {
            current.addBiome(biomeId, biomeKey);
        }
    }

    @Override
    public int modelIdForBlockState(int blockStateId) {
        ModelFactory current = factory;
        if (current == null) {
            if (blockStateId > 0) {
                requested.add(blockStateId);
            }
            return blockStateId == 0 ? AIR_MODEL_ID : MISSING_MODEL_ID;
        }
        return current.modelIdForBlockState(blockStateId);
    }

    /**
     * Advances the bakery on the render thread. Creates or rebuilds the factory as needed and returns
     * without waiting for the GPU.
     */
    public void tick() {
        if (closed) {
            throw new IllegalStateException("model provider is closed");
        }
        GameVulkan.Snapshot game = GameVulkan.current();
        VulkanGpuTextureView atlasView = blockAtlasView();
        if (atlasView == null || Minecraft.getInstance().level == null) {
            return;
        }
        ModelFactory current = factory;
        if (current != null && (factoryResourceGeneration != ModelLifecycle.resourceGeneration()
                || factoryDeviceAddress != game.device().address() || factoryAtlasView != atlasView)) {
            LOGGER.info("[JBP-LOD] Rebuilding model bakery (resource reload, atlas or device change).");
            factory = null;
            current.close();
            current = null;
        }
        if (current == null) {
            ModelFactory created = new ModelFactory(game, compiler, atlasView, source);
            synchronized (biomes) {
                biomes.forEach(created::addBiome);
            }
            synchronized (requested) {
                requested.forEach(created::requestBake);
            }
            factoryResourceGeneration = ModelLifecycle.resourceGeneration();
            factoryDeviceAddress = game.device().address();
            factoryAtlasView = atlasView;
            epoch++;
            factory = created;
            current = created;
        }
        current.tick();
    }

    /** Retained handle to the latest published generation (caller closes), or null before the first publish. */
    public ModelResources acquire() {
        ModelFactory current = factory;
        return current == null ? null : current.resources();
    }

    /** The active factory (metadata/fluid queries), or null while the bakery is not initialized. */
    public ModelFactory factory() {
        return factory;
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        ModelFactory current = factory;
        factory = null;
        if (current != null) {
            current.close();
        }
    }

    private static VulkanGpuTextureView blockAtlasView() {
        var texture = Minecraft.getInstance().getTextureManager().getTexture(BLOCK_ATLAS);
        var view = texture == null ? null : texture.getTextureView();
        if (view == null) {
            return null;
        }
        if (!(view instanceof VulkanGpuTextureView vulkanView)) {
            throw new IllegalStateException("[JBP-LOD] vanilla block atlas view is not a Vulkan view: "
                    + view.getClass().getName());
        }
        return vulkanView;
    }
}

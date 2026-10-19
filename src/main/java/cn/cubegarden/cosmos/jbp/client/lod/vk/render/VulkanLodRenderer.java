package cn.cubegarden.cosmos.jbp.client.lod.vk.render;

import cn.cubegarden.cosmos.jbp.client.lod.JbpLodClient;
import cn.cubegarden.cosmos.jbp.client.lod.api.LodExternalTargets;
import cn.cubegarden.cosmos.jbp.client.lod.api.LodExternalTargetsProvider;
import cn.cubegarden.cosmos.jbp.client.lod.api.LodFrameContext;
import cn.cubegarden.cosmos.jbp.client.lod.api.LodFrameTargets;
import cn.cubegarden.cosmos.jbp.client.lod.compute.LodComputeExecutor;
import cn.cubegarden.cosmos.jbp.client.lod.compute.LodComputeLayout;
import cn.cubegarden.cosmos.jbp.client.lod.data.LodNearExclusion;
import cn.cubegarden.cosmos.jbp.client.lod.model.ModelAtlasLayout;
import cn.cubegarden.cosmos.jbp.client.lod.model.ModelProvider;
import cn.cubegarden.cosmos.jbp.client.lod.model.ModelResources;
import cn.cubegarden.cosmos.jbp.client.lod.runtime.LodRuntime;
import cn.cubegarden.cosmos.jbp.client.lod.vk.bridge.GameVulkan;
import cn.cubegarden.cosmos.jbp.client.lod.vk.caps.DeviceCaps;
import cn.cubegarden.cosmos.jbp.client.lod.vk.gpu.GpuBuffer;
import cn.cubegarden.cosmos.jbp.client.lod.vk.gpu.HizPyramid;
import cn.cubegarden.cosmos.jbp.client.lod.vk.gpu.LodFrameBuffers;
import cn.cubegarden.cosmos.jbp.client.lod.vk.gpu.LodNodeGpuBuffers;
import cn.cubegarden.cosmos.jbp.client.lod.vk.shader.ShaderCompiler;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import com.mojang.renderpearl.backend.vulkan.VulkanGpuTextureView;
import net.caffeinemc.mods.sodium.client.render.chunk.ChunkRenderMatrices;
import net.caffeinemc.mods.sodium.client.render.chunk.terrain.TerrainRenderPass;
import net.caffeinemc.mods.sodium.client.render.viewport.CameraTransform;
import net.caffeinemc.mods.sodium.client.util.FogParameters;
import net.minecraft.client.Minecraft;
import org.joml.Matrix4f;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.lwjgl.vulkan.VK10.VK_BUFFER_USAGE_UNIFORM_BUFFER_BIT;
import static org.lwjgl.vulkan.VK10.VK_COMPARE_OP_GREATER_OR_EQUAL;
import static org.lwjgl.vulkan.VK10.VK_COMPARE_OP_LESS_OR_EQUAL;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_LAYOUT_GENERAL;
import static org.lwjgl.vulkan.VK10.VK_INDEX_TYPE_UINT16;
import static org.lwjgl.vulkan.VK10.vkCmdBindIndexBuffer;
import static org.lwjgl.vulkan.VK10.vkCmdDrawIndexedIndirect;
import static org.lwjgl.vulkan.VK12.vkCmdDrawIndexedIndirectCount;

/**
 * Vulkan LOD renderer hooked into Sodium's CUTOUT pass.
 *
 * <p>The pre-pass renders the previous opaque command snapshot into owned per-slot color/depth,
 * builds HiZ and current visibility/commands, then renders only newly visible temporal opaque and
 * sorted translucent commands. Current opaque commands are captured for the next frame, not drawn
 * again. After the game's CUTOUT scope closes, its vanilla-only depth is snapshotted before an owned
 * composite scope writes LOD depth. Queue-submission frame identities and frame-slot leases protect
 * resources through both pre-pass and game draw completion; no per-frame CPU wait or second device is used.</p>
 *
 * <p>Failures are logged once and latch the renderer off until the scope, resource, provider or LOD runtime
 * generation changes; the game's own rendering is never aborted by this hook.</p>
 */
public final class VulkanLodRenderer {
    public static final String RENDER_PROPERTY = "jbp.lod.render";

    private static final Logger LOGGER = LoggerFactory.getLogger("jbp-lod-render");
    private static final float SUBDIVISION_SIZE = 64.0f;
    /** {@code VkDrawIndexedIndirectCommand} stride (V3 contract: 20 bytes). */
    private static final int DRAW_COMMAND_STRIDE = 20;
    private static final int LIGHTMAP_BINDING = 7;
    private static final int FOG_BINDING = 8;
    private static final VulkanLodRenderer INSTANCE = new VulkanLodRenderer();
    /** Process-wide once-only flag for the first recorded draw path log (render thread only). */
    private static boolean drawPathLogged;
    /** Dev-script A/B toggle; release builds never set it. Volatile because the tick thread sets it. */
    private static volatile boolean devDrawEnabled = true;

    private final LodRenderState state = new LodRenderState();
    private final Set<String> reportedInvalid = new HashSet<>();
    private Resources resources;
    private DeviceCaps caps;
    private long capsDevice;
    private long gpuRuntimeGeneration = -1;
    private boolean gpuReverseZ;
    private long[] failedGenerations;
    private long preparedFrame = Long.MIN_VALUE;
    private final LodReadbackProbe readbackProbe = new LodReadbackProbe();
    /**
     * Recorded-LOD-frame counter for the section/node visibility words. Never reset: a reset while uploaded
     * node/top-level buffers still hold the previous generation's ids could alias a stale word to "visible
     * last frame". The shaders compare only the low 31 bits, so wrapping is harmless.
     */
    private long visibilityFrames;
    private RenderPass pendingPass;
    private Frame pendingFrame;

    private VulkanLodRenderer() {
    }

    /** Default on; {@code -Djbp.lod.render=false} disables the renderer (the V1 probe is independent). */
    public static boolean enabled() {
        return devDrawEnabled && !"false".equalsIgnoreCase(System.getProperty(RENDER_PROPERTY));
    }

    /**
     * Dev-script A/B toggle for acceptance screenshots: disables the whole LOD path, including the vanilla
     * fog extension, without touching storage or the node session. Not exposed to players or release builds.
     */
    public static void setDevDrawEnabled(boolean enabled) {
        devDrawEnabled = enabled;
    }

    /**
     * Defines for {@code lod/gl46/quads.frag}. The bounding gate uses {@code DEPTH_SCALAR_COMPARE} from
     * {@code depthutils.glsl}, whose default is standard Z; on a reverse-Z device the define MUST be
     * present or {@code outsideBound} inverts and discards every far LOD fragment.
     */
    static Map<String, String> quadFragmentDefines(boolean reverseZ) {
        return reverseZ ? Map.of("USE_REVERSE_Z", "1") : Map.of();
    }

    /** Registers scope/resource-reload invalidation listeners; idempotent. */
    public static void registerLifecycle() {
        LodRenderLifecycle.registerOnce();
    }

    /** Drops renderer ownership at shutdown; submitted and recorded game uses still hold slot leases. */
    public static void shutdown() {
        INSTANCE.release("client shutdown");
    }

    /**
     * Called at the head of the CUTOUT terrain render on the render thread. Returns the frame to hand to
     * {@link #deferCutout}, or null when no LOD frame is recorded.
     */
    public static Frame beforeCutout(ChunkRenderMatrices matrices, TerrainRenderPass terrainPass,
                                     RenderPass pass, CameraTransform camera, FogParameters fog) {
        if (!enabled()) {
            return null;
        }
        return INSTANCE.prepare(matrices, terrainPass, pass, camera, fog);
    }

    /** Defers the composite until this exact frontend pass has closed its backend scope. */
    public static void deferCutout(RenderPass pass, Frame frame) {
        if (frame != null) {
            INSTANCE.pendingPass = pass;
            INSTANCE.pendingFrame = frame;
        }
    }

    /** Runs after frontend close has ended dynamic rendering, before any LOD depth is composited. */
    public static void afterPassClosed(RenderPass pass) {
        if (INSTANCE.pendingPass != pass) return;
        Frame frame = INSTANCE.pendingFrame;
        INSTANCE.pendingPass = null;
        INSTANCE.pendingFrame = null;
        if (frame != null) INSTANCE.record(frame);
    }

    // ---------------------------------------------------------------------------------------------

    private Frame prepare(ChunkRenderMatrices matrices, TerrainRenderPass terrainPass, RenderPass pass,
                          CameraTransform camera, FogParameters fog) {
        registerLifecycle();
        long[] generations = {LodRenderLifecycle.scopeGeneration(), LodRenderLifecycle.resourceGeneration(),
                LodExternalTargets.generation()};
        if (failedGenerations != null) {
            if (java.util.Arrays.equals(failedGenerations, generations)) {
                return null;
            }
            failedGenerations = null;
        }
        LodFrameBuffers slot = null;
        try {
            Optional<LodRuntime> active = JbpLodClient.activeRuntime();
            if (active.isEmpty()) {
                release("no active LOD scope");
                return null;
            }
            LodRuntime runtime = active.get();
            long frameNumber = GameVulkan.frameId();
            if (preparedFrame == frameNumber) return null;
            preparedFrame = frameNumber;
            GameVulkan.Snapshot game = GameVulkan.current();
            LodFrameContext context = VanillaLodExternalTargetsProvider.capture(frameNumber, matrices,
                    terrainPass, pass, camera, fog);
            LodExternalTargetsProvider provider = LodExternalTargets.currentOr(VanillaLodExternalTargetsProvider.INSTANCE);
            LodFrameTargets targets = provider.targetsFor(context);

            DeviceCaps deviceCaps = capabilities(game);
            LodTargetValidator.Result validation = LodTargetValidator.validate(context.dynamicRenderingActive(),
                    context.deviceAddress(), context.renderScopeIdentity(), context.renderAreaX(),
                    context.renderAreaY(), context.renderAreaWidth(), context.renderAreaHeight(),
                    targets, deviceCaps);
            if (!validation.valid()) {
                if (reportedInvalid.add(validation.reason())) {
                    LOGGER.warn("[JBP-LOD] LOD draw skipped: {}", validation.reason());
                }
                return null;
            }

            boolean zeroToOne = game.deviceInfo().isZZeroToOne();
            LodDepthConversion gameDepth = LodDepthConversion.fromProjection(
                    targets.matrices().depthProjection(), zeroToOne);
            LodDepthConversion lodDepth = LodDepthConversion.fromProjection(
                    targets.matrices().projection(), zeroToOne);
            boolean reverseZ = gameDepth.reverseZ();
            LodDrawPlan plan = LodDrawPlan.choose(deviceCaps.drawIndirectCount(), deviceCaps.multiDrawIndirect(),
                    deviceCaps.maxDrawIndirectCount(), LodFrameBuffers.drawCapacity(LodFrameBuffers.DrawRegion.OPAQUE));

            runtime.ensureGpu(game, reverseZ);
            if (gpuRuntimeGeneration != runtime.generation()) {
                gpuRuntimeGeneration = runtime.generation();
                gpuReverseZ = reverseZ;
            } else if (gpuReverseZ != reverseZ) {
                throw new IllegalStateException("depth convention changed to reverseZ=" + reverseZ
                        + " after the LOD compute executor was built; it is fixed per LOD runtime");
            }
            runtime.updateCamera(context.cameraX(), context.cameraZ());

            LodFrameTargets.ImageTarget color = targets.color();
            LodFrameTargets.ImageTarget depth = targets.depth();
            LodRenderState.ResourceKey key = new LodRenderState.ResourceKey(game.device().address(),
                    color.vkFormat(), depth.vkFormat(), color.width(), color.height(), color.sampleCount(),
                    generations[0], generations[1], generations[2], runtime.generation(), reverseZ,
                    plan.mode() == LodDrawPlan.Mode.INDIRECT_COUNT);
            LodRenderState.Action action = state.update(key);
            if (action == LodRenderState.Action.REBUILD || resources == null) {
                releaseResources("target/resource configuration changed");
                state.update(key);
                resources = Resources.create(game, deviceCaps, key, runtime.frameBuffers().framesInFlight());
                LOGGER.info("[JBP-LOD] V4 renderer built: device={} color={} depth={} extent={}x{} samples={} "
                                + "reverseZ={} draw-path={} cull-raster=on depth-conversion=on",
                        deviceCaps.deviceName(), color.vkFormat(), depth.vkFormat(), color.width(), color.height(),
                        color.sampleCount(), reverseZ, plan.describe());
            }
            Resources res = resources;

            ModelProvider models = runtime.models();
            models.tick();
            ModelResources modelResources = models.acquire();
            if (modelResources == null) {
                return null;
            }
            boolean modelsHandedOff = false;
            try {
                slot = runtime.frameBuffers().beginFrame(frameNumber);
                if (slot == null) {
                    modelResources.close();
                    modelsHandedOff = true;
                    return null;
                }
                res.consumeRetiredSlot(runtime, slot);
                res.writeDrawDescriptors(slot, runtime.nodeBuffers(), modelResources, targets);

                float[] mvp = LodFrameMath.multiply(targets.matrices().projection(), targets.matrices().modelView());
                long seedView = res.seedHistory.sourceSlot() < 0 ? 0
                        : res.vanillaDepth.view(res.seedHistory.sourceSlot());
                // The visibility frame id counts recorded LOD frames, one per recorded frame, so the shaders'
                // `previous == frameId - 1` classification is exact. GameVulkan.frameId() is a submission
                // epoch and can advance by more than one between recorded LOD frames, which would classify
                // every visible section as a temporal addition on every frame.
                int visibilityFrameId = LodComputeLayout.packFrameId(visibilityFrames++);
                boolean runCleaner;
                {
                    runCleaner = LodFrameMath.shouldRunCleaner(runtime.nodeBuffers().geometryCapacityBytes(),
                            runtime.nodeBuffers().geometryUsedBytes());
                    VkCommandBuffer commandBuffer = slot.beginCommands();
                    FrameHooks hooks = new FrameHooks(res, runtime, seedView, reverseZ, plan);
                    runtime.recordUploads(commandBuffer);
                    slot.clearDrawOutputs(commandBuffer);
                    LodComputeExecutor.FrameInputs inputs = new LodComputeExecutor.FrameInputs(visibilityFrameId, mvp,
                            context.cameraX(), context.cameraY(), context.cameraZ(),
                            color.width(), color.height(), SUBDIVISION_SIZE, runtime.renderDistanceSections(),
                            runtime.requestQueueSize(),
                            LodNearExclusion.radiusBlocks(
                                    Minecraft.getInstance().options.getEffectiveRenderDistance()),
                            runtime.nodeInputs(),
                            runtime.nodeBuffers().sectionMetadata(), runtime.takeVisibilityOps(), runCleaner,
                            runtime.cleanerMaxNodeId(), hooks);
                    res.bounding.record(commandBuffer, slot, mvp, context.cameraX(), context.cameraY(),
                            context.cameraZ(), Minecraft.getInstance().options.getEffectiveRenderDistance() * 16.0f,
                            LodBoundingSectionStore.instance());
                    runtime.executor().recordFrame(commandBuffer, slot, inputs);
                    res.offscreen.begin(commandBuffer, slot.slotIndex(), false);
                    vkCmdBindIndexBuffer(commandBuffer, res.indexBuffer.handle(), 0, VK_INDEX_TYPE_UINT16);
                    LodDrawPlan secondary = secondaryPlan(plan);
                    int drawCalls = 0;
                    for (int draw = 0; draw < LodDrawSequence.CURRENT_REGIONS.size(); draw++) {
                        LodFrameBuffers.DrawRegion region = LodDrawSequence.CURRENT_REGIONS.get(draw);
                        VulkanLodPipeline pipeline = region == LodFrameBuffers.DrawRegion.TRANSLUCENT
                                ? res.translucentPipeline : res.temporalPipeline;
                        pipeline.bind(commandBuffer, slot.slotIndex(), 0, 0, color.width(), color.height());
                        recordDraw(commandBuffer, secondary, slot, region);
                        drawCalls += drawCallCount(secondary);
                    }
                    if (!drawPathLogged) {
                        drawPathLogged = true;
                        LOGGER.info("[JBP-LOD] V4 first draw recorded: path={} regions={} draw-calls={}",
                                secondary.describe(), LodDrawSequence.CURRENT_REGIONS, drawCalls);
                    }
                    res.offscreen.end(commandBuffer);
                    res.history.capture(commandBuffer, slot);
                    runtime.frameBuffers().submitCommands(slot);
                }
                // Slot retirement guards both the pre-pass and subsequent game-command-buffer uses.
                slot.retireAfterUse(res.lifetime.acquire());
                slot.retireAfterUse(modelResources);
                modelsHandedOff = true;
                res.markSlotSubmitted(slot.slotIndex(), runCleaner);
                runtime.afterSubmit(slot);
                res.hiz.retireReplaced(slot::retireAfterUse);
                if (readbackProbe.tick()) {
                    readbackProbe.capture(game, res.offscreen, slot, frameNumber,
                            new LodReadbackProbe.VisibilitySource(runtime.executor().sectionVisibility(),
                                    visibilityFrameId));
                }
            } catch (RuntimeException | Error failure) {
                abandonSlot(slot);
                if (!modelsHandedOff) {
                    modelResources.close();
                }
                throw failure;
            }
            return new Frame(context, slot, res, plan, gameDepth, lodDepth, targets);
        } catch (RuntimeException | Error failure) {
            fail(failure, generations);
            return null;
        }
    }

    private void record(Frame frame) {
        try {
            if (frame.resources != resources) {
                throw new IllegalStateException("LOD resources were replaced between pre-pass and draw");
            }
            Resources res = frame.resources;
            LodFrameBuffers slot = frame.slot;
            LodFrameContext context = frame.context;
            VkCommandBuffer commandBuffer = context.commandBuffer();
            LodFrameTargets.ImageTarget depth = frame.targets.depth();
            LodImageViews.Owned gameDepthView = LodImageViews.borrowDepthView(res.game.device(), depth.image(),
                    depth.vkFormat());
            slot.retireAfterUse(gameDepthView);
            res.vanillaDepth.record(commandBuffer, slot.slotIndex(), gameDepthView.view(), frame.gameDepth.reverseZ());
            res.seedHistory.captured(slot.slotIndex());
            res.offscreen.recordComposite(commandBuffer, slot.slotIndex(), context, frame.targets, frame.gameDepth, frame.lodDepth);
            if (!res.drawLogged) {
                res.drawLogged = true;
                LOGGER.info("[JBP-LOD] V4 CUTOUT composite recorded: frame={} slot={} path={} area={}x{}+{}+{}; "
                                + "previous opaque, current temporal and sorted translucent counts stay GPU-resident",
                        context.frameNumber(), slot.slotIndex(), frame.plan.describe(), context.renderAreaWidth(),
                        context.renderAreaHeight(), context.renderAreaX(), context.renderAreaY());
            }
        } catch (RuntimeException | Error failure) {
            fail(failure, new long[]{LodRenderLifecycle.scopeGeneration(), LodRenderLifecycle.resourceGeneration(),
                    LodExternalTargets.generation()});
        }
    }

    /** Consumes only the selected GPU-generated span; never reads a count on the CPU. */
    static void recordDraw(VkCommandBuffer commandBuffer, LodDrawPlan plan, LodFrameBuffers slot,
                           LodFrameBuffers.DrawRegion region) {
        long commands = slot.drawCommands().handle();
        long offset = LodFrameBuffers.commandByteOffset(region);
        recordDraw(commandBuffer, plan, commands, offset, slot.drawControl().handle(),
                LodFrameBuffers.drawCountByteOffset(region));
    }

    private static void recordDraw(VkCommandBuffer commandBuffer, LodDrawPlan plan, long commands,
                                   long offset, long countBuffer, long countOffset) {
        int stride = DRAW_COMMAND_STRIDE;
        switch (plan.mode()) {
            case INDIRECT_COUNT -> vkCmdDrawIndexedIndirectCount(commandBuffer, commands, offset,
                    countBuffer, countOffset,
                    plan.maxDraws(), stride);
            case MULTI_DRAW_INDIRECT -> vkCmdDrawIndexedIndirect(commandBuffer, commands, offset,
                    plan.maxDraws(), stride);
            case SINGLE_DRAW_LOOP -> {
                for (int draw = 0; draw < plan.maxDraws(); draw++) {
                    vkCmdDrawIndexedIndirect(commandBuffer, commands, offset + (long) draw * stride, 1, stride);
                }
            }
        }
    }

    /** Number of {@code vkCmdDrawIndexedIndirect*} calls {@link #recordDraw} records for one region. */
    static int drawCallCount(LodDrawPlan plan) {
        return plan.mode() == LodDrawPlan.Mode.SINGLE_DRAW_LOOP ? plan.maxDraws() : 1;
    }

    private static LodDrawPlan secondaryPlan(LodDrawPlan plan) {
        return LodDrawPlan.choose(plan.mode() == LodDrawPlan.Mode.INDIRECT_COUNT,
                plan.mode() == LodDrawPlan.Mode.MULTI_DRAW_INDIRECT, plan.maxDraws(),
                LodFrameBuffers.drawCapacity(LodFrameBuffers.DrawRegion.TRANSLUCENT));
    }

    // ---------------------------------------------------------------------------------------------

    private DeviceCaps capabilities(GameVulkan.Snapshot game) {
        long device = game.device().address();
        if (caps == null || capsDevice != device) {
            DeviceCaps inspected = DeviceCaps.inspect(game);
            inspected.requireCore();
            if (!inspected.drawIndirectFirstInstance()) {
                throw new IllegalStateException("the game device does not enable drawIndirectFirstInstance; "
                        + "the LOD draw ID is carried in the indirect command's firstInstance");
            }
            caps = inspected;
            capsDevice = device;
        }
        return caps;
    }

    private void abandonSlot(LodFrameBuffers slot) {
        if (slot == null) {
            return;
        }
        try {
            slot.endCommands();
        } catch (RuntimeException ignored) {
            // The command buffer was not recording (it already ended or never began); nothing to abandon.
        }
    }

    private void fail(Throwable failure, long[] generations) {
        failedGenerations = generations;
        LOGGER.error("[JBP-LOD] V4 LOD rendering disabled until the scope, resources or target provider change.",
                failure);
    }

    /** Drops ownership; submitted frame-slot leases guard every compute and game draw use. */
    private void releaseResources(String reason) {
        Resources previous = resources;
        resources = null;
        pendingPass = null;
        pendingFrame = null;
        state.invalidate();
        if (previous != null) {
            previous.lifetime.release();
            LOGGER.info("[JBP-LOD] V4 renderer released: {}", reason);
        }
    }

    private void release(String reason) {
        if (resources != null) {
            try {
                releaseResources(reason);
            } catch (RuntimeException | Error failure) {
                LOGGER.error("[JBP-LOD] V4 renderer release failed.", failure);
                resources = null;
            }
        }
        reportedInvalid.clear();
    }

    // ---------------------------------------------------------------------------------------------

    /** One recorded LOD frame, valid until its frontend pass closes. */
    public static final class Frame {
        private final LodFrameContext context;
        private final LodFrameBuffers slot;
        private final Resources resources;
        private final LodDrawPlan plan;
        private final LodDepthConversion gameDepth;
        private final LodDepthConversion lodDepth;
        private final LodFrameTargets targets;

        private Frame(LodFrameContext context, LodFrameBuffers slot, Resources resources, LodDrawPlan plan,
                      LodDepthConversion gameDepth, LodDepthConversion lodDepth, LodFrameTargets targets) {
            this.context = context;
            this.slot = slot;
            this.resources = resources;
            this.plan = plan;
            this.gameDepth = gameDepth;
            this.lodDepth = lodDepth;
            this.targets = targets;
        }
    }

    /** Compute pre-pass graphics hooks for one frame. */
    private static final class FrameHooks implements LodComputeExecutor.Hooks {
        private final Resources res;
        private final LodRuntime runtime;
        private final long gameDepthView;
        private final boolean reverseZ;
        private final LodDrawPlan plan;

        FrameHooks(Resources res, LodRuntime runtime, long gameDepthView, boolean reverseZ, LodDrawPlan plan) {
            this.res = res;
            this.runtime = runtime;
            this.gameDepthView = gameDepthView;
            this.reverseZ = reverseZ;
            this.plan = plan;
        }

        @Override
        public LodComputeExecutor.HiZInputs beforeTraversal(VkCommandBuffer commandBuffer, LodFrameBuffers slot) {
            LodDepthTarget depthTarget = res.offscreen.depthTarget(slot.slotIndex());
            res.convertPass.record(commandBuffer, slot.slotIndex(), gameDepthView, reverseZ, depthTarget);
            res.offscreen.begin(commandBuffer, slot.slotIndex(), true);
            if (LodDrawSequence.drawsPreviousOpaque(res.history.valid)) {
                res.drawPipeline.bind(commandBuffer, slot.slotIndex(), 0, 0,
                        depthTarget.width(), depthTarget.height());
                vkCmdBindIndexBuffer(commandBuffer, res.indexBuffer.handle(), 0, VK_INDEX_TYPE_UINT16);
                recordDraw(commandBuffer, plan, res.history.commands.handle(), 0,
                        res.history.count.handle(), 0);
            }
            res.offscreen.end(commandBuffer);
            if (!res.hiz.record(commandBuffer, slot.slotIndex(), depthTarget.view(), VK_IMAGE_LAYOUT_GENERAL,
                    depthTarget.width(), depthTarget.height())) {
                throw new IllegalStateException("HiZ build was not recorded for a non-empty extent");
            }
            return new LodComputeExecutor.HiZInputs(res.hiz.sampledView(), res.hiz.sampler(),
                    res.hiz.sampledLayout(), res.hiz.packedSize());
        }

        @Override
        public void drawCullVisibility(VkCommandBuffer commandBuffer, LodFrameBuffers slot) {
            res.cullPass.record(commandBuffer, slot, res.offscreen.depthTarget(slot.slotIndex()), runtime.nodeBuffers().sectionMetadata(),
                    runtime.executor().sectionVisibility());
        }
    }

    /** Device/target-specific resources; closed only after every submission that used them retires. */
    private static final class Resources implements AutoCloseable {
        final GameVulkan.Snapshot game;
        final VulkanLodPipeline drawPipeline;
        final VulkanLodPipeline translucentPipeline;
        final VulkanLodPipeline temporalPipeline;
        final LodOffscreenPass offscreen;
        final LodOpaqueHistory history;
        final LodVanillaDepthSnapshot vanillaDepth;
        final LodVanillaSeedHistory seedHistory = new LodVanillaSeedHistory();
        final LodResourceLifetime lifetime = new LodResourceLifetime(this::close);
        final LodIndexBuffer indexBuffer;
        final HizPyramid hiz;
        final LodDepthConvertPass convertPass;
        final LodCullRasterPass cullPass;
        final LodBoundingPass bounding;
        final GpuBuffer[] fog;
        final long atlasSampler;
        final long lightmapSampler;
        final long depthSampler;
        private final boolean[] requestsPending;
        private final boolean[] cleanerPending;
        boolean drawLogged;
        private boolean closed;

        private Resources(GameVulkan.Snapshot game, VulkanLodPipeline drawPipeline,
                          VulkanLodPipeline translucentPipeline, VulkanLodPipeline temporalPipeline,
                          LodIndexBuffer indexBuffer, LodOffscreenPass offscreen, LodOpaqueHistory history,
                          HizPyramid hiz, LodDepthConvertPass convertPass, LodVanillaDepthSnapshot vanillaDepth,
                          LodCullRasterPass cullPass, GpuBuffer[] fog, long atlasSampler, long lightmapSampler,
                          long depthSampler, LodBoundingPass bounding, int frameSlots) {
            this.game = game;
            this.drawPipeline = drawPipeline;
            this.translucentPipeline = translucentPipeline;
            this.indexBuffer = indexBuffer;
            this.offscreen = offscreen;
            this.history = history;
            this.temporalPipeline = temporalPipeline;
            this.hiz = hiz;
            this.convertPass = convertPass;
            this.vanillaDepth = vanillaDepth;
            this.cullPass = cullPass;
            this.fog = fog;
            this.atlasSampler = atlasSampler;
            this.lightmapSampler = lightmapSampler;
            this.depthSampler = depthSampler;
            this.bounding = bounding;
            this.requestsPending = new boolean[frameSlots];
            this.cleanerPending = new boolean[frameSlots];
        }

        static Resources create(GameVulkan.Snapshot game, DeviceCaps caps, LodRenderState.ResourceKey key,
                                int frameSlots) {
            List<AutoCloseable> created = new ArrayList<>();
            try {
                ShaderCompiler compiler = new ShaderCompiler();
                LodOffscreenPass offscreen = track(created, new LodOffscreenPass(game, compiler,
                        key.colorFormat(), key.depthFormat(), key.width(), key.height(), frameSlots, key.reverseZ()));
                Map<String, String> vertexDefines = new HashMap<>();
                if (caps.shaderInt64()) {
                    vertexDefines.put("USE_SHADER_INT64", "1");
                }
                ShaderCompiler.CompiledShader vertex = compiler.compile("lod/gl46/quads3.vert", Map.copyOf(vertexDefines));
                Map<String, String> fragmentDefines = new HashMap<>(quadFragmentDefines(key.reverseZ()));
                fragmentDefines.put("BLOCK_MODEL_TEXTURE_BINDING", "2");
                // Opt-in diagnostics: debugSolid paints magenta; debugView selects an attribute/coverage view.
                // Both are off by default; see LodDebugView for names, colours and stencil limitations.
                if (Boolean.getBoolean("jbp.lod.debugSolid")) {
                    fragmentDefines.put("DEBUG_SOLID", "1");
                }
                LodDebugView debugView = LodDebugView.parse(System.getProperty(LodDebugView.PROPERTY)).orElseGet(() -> {
                    LOGGER.warn("[JBP-LOD] Unknown -D{} value '{}'; expected normal|uv|face|model|depth|level|pass|bound|stencil|light|texture. Debug view disabled.",
                            LodDebugView.PROPERTY, System.getProperty(LodDebugView.PROPERTY));
                    return LodDebugView.OFF;
                });
                if (debugView != LodDebugView.OFF) {
                    fragmentDefines.put("LOD_DEBUG_VIEW", String.valueOf(debugView.shaderCode()));
                    LOGGER.info("[JBP-LOD] Debug view active: {}", debugView);
                }
                if (debugView == LodDebugView.PASS) {
                    fragmentDefines.put("LOD_DRAW_PASS", "0"); // Previous-frame opaque history.
                }
                ShaderCompiler.CompiledShader fragment = compiler.compile("lod/gl46/quads.frag", Map.copyOf(fragmentDefines));
                VulkanLodPipeline draw = track(created, VulkanLodPipeline.create(game, vertex, fragment,
                        VulkanLodPipeline.State.of(offscreen.colorFormat(), LodDepthTarget.SEED_FORMAT, true, true,
                                key.reverseZ() ? VK_COMPARE_OP_GREATER_OR_EQUAL : VK_COMPARE_OP_LESS_OR_EQUAL,
                                VulkanLodPipeline.BlendMode.OPAQUE)
                                .withStencil(VulkanLodPipeline.Stencil.LOD_ON_SKY), frameSlots));
                ShaderCompiler.CompiledShader temporalFragment = fragment;
                if (debugView == LodDebugView.PASS) {
                    Map<String, String> temporalDefines = new HashMap<>(fragmentDefines);
                    temporalDefines.put("LOD_DRAW_PASS", "1"); // Current temporal additions.
                    temporalFragment = compiler.compile("lod/gl46/quads.frag", Map.copyOf(temporalDefines));
                }
                VulkanLodPipeline temporal = track(created, VulkanLodPipeline.create(game, vertex, temporalFragment,
                        VulkanLodPipeline.State.of(offscreen.colorFormat(), LodDepthTarget.SEED_FORMAT, true, true,
                                key.reverseZ() ? VK_COMPARE_OP_GREATER_OR_EQUAL : VK_COMPARE_OP_LESS_OR_EQUAL,
                                VulkanLodPipeline.BlendMode.OPAQUE)
                                .withStencil(VulkanLodPipeline.Stencil.LOD_ON_SKY), frameSlots));
                ShaderCompiler.CompiledShader translucentVertex = compiler.compile("lod/gl46/quads3.vert",
                        Map.copyOf(vertexDefines));
                Map<String, String> translucentDefines = new HashMap<>(fragmentDefines);
                translucentDefines.put("TRANSLUCENT", "1");
                if (debugView == LodDebugView.PASS) {
                    translucentDefines.put("LOD_DRAW_PASS", "2"); // Current translucent range.
                }
                ShaderCompiler.CompiledShader translucentFragment = compiler.compile("lod/gl46/quads.frag",
                        Map.copyOf(translucentDefines));
                VulkanLodPipeline translucent = track(created, VulkanLodPipeline.create(game,
                        translucentVertex, translucentFragment,
                        VulkanLodPipeline.State.of(offscreen.colorFormat(), LodDepthTarget.SEED_FORMAT, true, true,
                                key.reverseZ() ? VK_COMPARE_OP_GREATER_OR_EQUAL : VK_COMPARE_OP_LESS_OR_EQUAL,
                                VulkanLodPipeline.BlendMode.ALPHA)
                                .withStencil(VulkanLodPipeline.Stencil.LOD_ON_SKY), frameSlots));
                LodIndexBuffer index = track(created, LodIndexBuffer.create(game));
                LodOpaqueHistory history = track(created, new LodOpaqueHistory(game));
                HizPyramid hizPyramid = track(created, new HizPyramid(game, compiler, frameSlots,
                        caps.subgroupHizReductionSupported(), key.reverseZ()));
                hizPyramid.prepare(key.width(), key.height());
                long depthSampler = LodSamplers.depth(game.device());
                created.add(() -> org.lwjgl.vulkan.VK10.vkDestroySampler(game.device(), depthSampler, null));
                LodDepthConvertPass convert = track(created,
                        new LodDepthConvertPass(game, compiler, frameSlots, depthSampler));
                LodVanillaDepthSnapshot vanillaDepth = track(created,
                        new LodVanillaDepthSnapshot(game, compiler, frameSlots, key.width(), key.height(), depthSampler));
                LodCullRasterPass cull = track(created,
                        new LodCullRasterPass(game, compiler, frameSlots, key.reverseZ(), index.handle()));
                LodBoundingPass bounding = track(created,
                        new LodBoundingPass(game, compiler, frameSlots, key.reverseZ(), key.width(), key.height()));
                GpuBuffer[] fogBuffers = new GpuBuffer[frameSlots];
                for (int i = 0; i < frameSlots; i++) {
                    fogBuffers[i] = track(created, GpuBuffer.allocate(game, LodFogUniform.BYTE_SIZE,
                            VK_BUFFER_USAGE_UNIFORM_BUFFER_BIT, true));
                }
                long atlasSampler = LodSamplers.atlas(game.device(), ModelAtlasLayout.MAX_MIP_LOD);
                created.add(() -> org.lwjgl.vulkan.VK10.vkDestroySampler(game.device(), atlasSampler, null));
                long lightmapSampler = LodSamplers.lightmap(game.device());
                created.add(() -> org.lwjgl.vulkan.VK10.vkDestroySampler(game.device(), lightmapSampler, null));
                return new Resources(game, draw, translucent, temporal, index, offscreen, history, hizPyramid, convert,
                        vanillaDepth, cull, fogBuffers, atlasSampler, lightmapSampler, depthSampler, bounding, frameSlots);
            } catch (RuntimeException | Error failure) {
                // Nothing has been submitted yet, so immediate destruction in reverse order is safe.
                for (int i = created.size() - 1; i >= 0; i--) {
                    try {
                        created.get(i).close();
                    } catch (Exception closeFailure) {
                        failure.addSuppressed(closeFailure);
                    }
                }
                throw failure;
            }
        }

        private static <T extends AutoCloseable> T track(List<AutoCloseable> created, T resource) {
            created.add(resource);
            return resource;
        }

        /**
         * A slot returned by beginFrame has retired, so its host-visible traversal request queue and
         * cleaner output from its previous use are final: forward them to the node worker.
         */
        void consumeRetiredSlot(LodRuntime runtime, LodFrameBuffers slot) {
            int index = slot.slotIndex();
            if (requestsPending[index]) {
                requestsPending[index] = false;
                runtime.onTraversalRequests(slot.requestQueue().mappedBytes());
            }
            if (cleanerPending[index]) {
                cleanerPending[index] = false;
                runtime.onCleanerOutput(runtime.executor().cleanerOutput(index).mappedBytes());
            }
        }

        void markSlotSubmitted(int slotIndex, boolean cleanerRan) {
            requestsPending[slotIndex] = true;
            cleanerPending[slotIndex] = cleanerRan;
        }

        /** Writes every reflected set-0 binding of the draw pipeline for this slot. */
        void writeDrawDescriptors(LodFrameBuffers slot, LodNodeGpuBuffers nodes, ModelResources models,
                                  LodFrameTargets targets) {
            writeDrawDescriptors(drawPipeline, slot, nodes, models, targets);
            writeDrawDescriptors(translucentPipeline, slot, nodes, models, targets);
            writeDrawDescriptors(temporalPipeline, slot, nodes, models, targets);
        }

        private void writeDrawDescriptors(VulkanLodPipeline drawPipeline, LodFrameBuffers slot,
                                          LodNodeGpuBuffers nodes, ModelResources models,
                                          LodFrameTargets targets) {
            int index = slot.slotIndex();
            Set<Integer> written = new HashSet<>();
            GpuBuffer scene = slot.sceneUniform();
            bindBuffer(drawPipeline, index, 0, scene.handle(), scene.capacity(), written);
            bindBuffer(drawPipeline, index, 1, nodes.geometry().handle(), nodes.geometry().capacity(), written);
            drawPipeline.writeCombinedImageSampler(index, 2, atlasSampler, models.atlasView(), models.atlasLayout());
            written.add(2);
            drawPipeline.writeCombinedImageSampler(index, 9, depthSampler, bounding.depthView(index),
                    VK_IMAGE_LAYOUT_GENERAL);
            written.add(9);
            bindBuffer(drawPipeline, index, 3, models.models().handle(), models.modelBytes(), written);
            bindBuffer(drawPipeline, index, 4, models.colours().handle(), models.colourBytes(), written);
            GpuBuffer positions = drawPipeline == this.drawPipeline ? history.positions : slot.positionScratch();
            bindBuffer(drawPipeline, index, 5, positions.handle(), positions.capacity(), written);
            if (drawPipeline.hasBinding(LIGHTMAP_BINDING)) {
                GpuTextureView lightmap = Minecraft.getInstance().gameRenderer.levelLightmap();
                if (!(lightmap instanceof VulkanGpuTextureView vulkanLightmap)) {
                    throw new IllegalStateException("[JBP-LOD] the level lightmap is not a Vulkan texture view");
                }
                // RenderPearl keeps sampled textures in GENERAL (V1 bridge evidence); validate in the smoke run.
                drawPipeline.writeCombinedImageSampler(index, LIGHTMAP_BINDING, lightmapSampler,
                        vulkanLightmap.vkImageView(), VK_IMAGE_LAYOUT_GENERAL);
                written.add(LIGHTMAP_BINDING);
            }
            GpuBuffer fogBuffer = fog[index];
            LodFogUniform.write(fogBuffer.mappedBytes(), 0, targets.matrices().modelView(), targets.fog());
            fogBuffer.flush(0, LodFogUniform.BYTE_SIZE);
            bindBuffer(drawPipeline, index, FOG_BINDING, fogBuffer.handle(), LodFogUniform.BYTE_SIZE, written);
            Set<Integer> missing = drawPipeline.bindingNumbers();
            missing.removeAll(written);
            if (!missing.isEmpty()) {
                throw new IllegalStateException("[JBP-LOD] LOD draw shader reflects unwritten descriptor bindings "
                        + missing);
            }
        }

        private void bindBuffer(VulkanLodPipeline drawPipeline, int slot, int binding,
                                long buffer, long range, Set<Integer> written) {
            if (drawPipeline.hasBinding(binding)) {
                drawPipeline.writeBuffer(slot, binding, buffer, 0, range);
                written.add(binding);
            }
        }

        @Override
        public void close() {
            if (closed) {
                return;
            }
            closed = true;
            org.lwjgl.vulkan.VK10.vkDestroySampler(game.device(), lightmapSampler, null);
            org.lwjgl.vulkan.VK10.vkDestroySampler(game.device(), atlasSampler, null);
            for (GpuBuffer buffer : fog) {
                buffer.close();
            }
            cullPass.close();
            bounding.close();
            convertPass.close();
            vanillaDepth.close();
            org.lwjgl.vulkan.VK10.vkDestroySampler(game.device(), depthSampler, null);
            hiz.close();
            offscreen.close();
            history.close();
            temporalPipeline.close();
            indexBuffer.close();
            drawPipeline.close();
            translucentPipeline.close();
        }
    }
}

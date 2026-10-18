package cn.cubegarden.cosmos.jbp.client.lod.vk.gpu;

import cn.cubegarden.cosmos.jbp.client.lod.vk.bridge.GameVulkan;
import org.lwjgl.vulkan.VkCommandBuffer;

import java.util.ArrayList;
import java.util.List;

/** Owns a bounded ring of frame outputs; a slot is reused only after a queued fence signals. */
public final class LodGpuBuffers implements AutoCloseable {
    private final GameVulkan.Snapshot game;
    private final LodFrameBuffers[] slots;
    private final GpuCompletion[] retirementFences;
    private long activeFrameId = Long.MIN_VALUE;
    private int activeSlot = -1;
    private boolean closed;

    public LodGpuBuffers(GameVulkan.Snapshot game, int framesInFlight, int maxSections) {
        if (framesInFlight < 2 || framesInFlight > 4) {
            throw new IllegalArgumentException("frames in flight must be 2..4");
        }
        this.game = game;
        this.slots = new LodFrameBuffers[framesInFlight];
        this.retirementFences = new GpuCompletion[framesInFlight];
        int created = 0;
        try {
            for (; created < framesInFlight; created++) {
                slots[created] = new LodFrameBuffers(game, maxSections, created);
            }
        } catch (RuntimeException | Error failure) {
            for (int i = created - 1; i >= 0; i--) {
                slots[i].close();
            }
            throw failure;
        }
        GpuRetireQueue.poll(game);
    }

    /**
     * Acquires a reusable slot without blocking. Repeated calls with the same frameId return
     * the same slot; a null result means all slots are still in flight and the frame must skip LOD.
     */
    public LodFrameBuffers beginFrame(long frameId) {
        ensureOpen();
        GpuRetireQueue.poll(game);
        if (frameId == activeFrameId) {
            return activeSlot < 0 ? null : slots[activeSlot];
        }
        if (activeSlot >= 0) {
            LodFrameBuffers previous = slots[activeSlot];
            if (previous.isRecording()) {
                throw new IllegalStateException("previous LOD frame left its command buffer recording");
            }
            if (previous.isSubmitted() && retirementFences[activeSlot] == null) {
                retirementFences[activeSlot] = GpuCompletion.submitMarker(game);
            }
        }
        activeFrameId = frameId;
        activeSlot = -1;
        pollSlots();
        for (int i = 0; i < slots.length; i++) {
            if (retirementFences[i] == null && !slots[i].isRecording() && !slots[i].isSubmitted()) {
                activeSlot = i;
                return slots[i];
            }
        }
        return null;
    }

    /** Ends and submits the active slot's pre-cutout work to the game's existing graphics queue. */
    public void submitCommands(LodFrameBuffers slot) {
        ensureOpen();
        if (activeSlot < 0 || slots[activeSlot] != slot) {
            throw new IllegalArgumentException("slot is not active for the current frame");
        }
        VkCommandBuffer commandBuffer = slot.endCommands();
        GpuCompletion.submitUntracked(game, commandBuffer);
    }

    /** Number of outputs available per frame slot. */
    public int framesInFlight() {
        return slots.length;
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        if (activeSlot >= 0 && slots[activeSlot].isRecording()) {
            throw new IllegalStateException("cannot retire LOD buffers while command recording is open");
        }
        if (activeSlot >= 0 && slots[activeSlot].isSubmitted() && retirementFences[activeSlot] == null) {
            retirementFences[activeSlot] = GpuCompletion.submitMarker(game);
        }
        closed = true;
        for (int i = 0; i < slots.length; i++) {
            GpuCompletion completion = retirementFences[i];
            if (completion == null && !slots[i].isSubmitted()) {
                slots[i].close();
            } else {
                if (completion == null) {
                    completion = GpuCompletion.submitMarker(game);
                }
                GpuRetireQueue.retire(completion, new RetiredFrame(slots[i]));
                retirementFences[i] = null;
            }
        }
        GpuRetireQueue.poll(game);
    }

    private void pollSlots() {
        for (int i = 0; i < retirementFences.length; i++) {
            GpuCompletion completion = retirementFences[i];
            if (completion == null || !completion.isComplete()) {
                continue;
            }
            completion.close();
            retirementFences[i] = null;
            slots[i].markRetired();
        }
    }

    private void ensureOpen() {
        if (closed) {
            throw new IllegalStateException("LOD GPU buffers are closed");
        }
    }

    private record RetiredFrame(LodFrameBuffers frame) implements AutoCloseable {
        @Override
        public void close() {
            frame.markRetired();
            frame.close();
        }
    }
}

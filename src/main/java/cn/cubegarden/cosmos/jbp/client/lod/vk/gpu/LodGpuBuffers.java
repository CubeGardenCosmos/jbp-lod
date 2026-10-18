package cn.cubegarden.cosmos.jbp.client.lod.vk.gpu;

import cn.cubegarden.cosmos.jbp.client.lod.vk.bridge.GameVulkan;
import org.lwjgl.vulkan.VkCommandBuffer;

/** Owns frame outputs until a fence queued after the consuming game submission signals. */
public final class LodGpuBuffers implements AutoCloseable {
    private final GameVulkan.Snapshot game;
    private final LodFrameBuffers[] slots;
    private final GpuCompletion[] retirementFences;
    private final FrameRetirement retirement;
    private final Runnable submissionListener = this::afterGameSubmission;
    private int activeSlot = -1;
    private boolean closed;
    private boolean destroyed;
    private Runnable closeAction;

    public LodGpuBuffers(GameVulkan.Snapshot game, int framesInFlight, int maxSections) {
        if (framesInFlight < 2 || framesInFlight > 4) {
            throw new IllegalArgumentException("frames in flight must be 2..4");
        }
        this.game = game;
        this.slots = new LodFrameBuffers[framesInFlight];
        this.retirementFences = new GpuCompletion[framesInFlight];
        this.retirement = new FrameRetirement(framesInFlight);
        int created = 0;
        try {
            for (; created < framesInFlight; created++) {
                slots[created] = new LodFrameBuffers(game, maxSections, created);
            }
        } catch (RuntimeException | Error failure) {
            for (int i = created - 1; i >= 0; i--) slots[i].close();
            throw failure;
        }
        GameVulkan.addSubmissionListener(submissionListener);
    }

    /** Nonblocking acquisition; use GameVulkan.frameId(), never a per-callback counter. */
    public LodFrameBuffers beginFrame(long frameId) {
        ensureOpen();
        pollSlots();
        activeSlot = retirement.beginFrame(frameId);
        return activeSlot < 0 ? null : slots[activeSlot];
    }

    public void submitCommands(LodFrameBuffers slot) {
        ensureOpen();
        if (activeSlot < 0 || slots[activeSlot] != slot) {
            throw new IllegalArgumentException("slot is not active for the current frame");
        }
        VkCommandBuffer commandBuffer = slot.endCommands();
        GpuCompletion.submitUntracked(game, commandBuffer);
        retirement.submitted(activeSlot);
    }

    /** Retains a per-slot renderer lease through the actual consuming game submission. */
    public void retireAfterUse(LodFrameBuffers slot, Runnable release) {
        ensureOpen();
        int index = slot.slotIndex();
        if (index < 0 || index >= slots.length || slots[index] != slot) {
            throw new IllegalArgumentException("slot does not belong to this ring");
        }
        retirement.retireAfterUse(index, release);
    }

    public int framesInFlight() {
        return slots.length;
    }

    /** Only this boundary may enqueue retirement markers, including during deferred close. */
    public void afterGameSubmission() {
        if (destroyed) return;
        int pending = retirement.afterGameSubmission();
        for (int index = 0; index < slots.length; index++) {
            if ((pending & (1 << index)) != 0) {
                retirementFences[index] = GpuCompletion.submitMarker(game);
            }
        }
        pollSlots();
        finishCloseIfRetired();
    }

    @Override
    public void close() {
        closeAfterRetirement(null);
    }

    /** Stops acquisition now, destroys ring/runtime owners only after every submitted use retires. */
    public void closeAfterRetirement(Runnable action) {
        if (closed) return;
        for (LodFrameBuffers slot : slots) {
            if (slot.isRecording()) {
                throw new IllegalStateException("cannot retire LOD buffers while command recording is open");
            }
        }
        closed = true;
        closeAction = action;
        pollSlots();
        finishCloseIfRetired();
    }

    private void pollSlots() {
        GpuRetireQueue.poll(game);
        for (int i = 0; i < retirementFences.length; i++) {
            GpuCompletion completion = retirementFences[i];
            if (completion == null || !completion.isComplete()) continue;
            completion.close();
            retirementFences[i] = null;
            slots[i].markRetired();
            retirement.retired(i);
        }
    }

    private void finishCloseIfRetired() {
        if (!closed || destroyed) return;
        for (int i = 0; i < slots.length; i++) {
            if (retirement.hasPending(i)) return;
        }
        destroyed = true;
        GameVulkan.removeSubmissionListener(submissionListener);
        for (LodFrameBuffers slot : slots) slot.close();
        if (closeAction != null) closeAction.run();
    }

    private void ensureOpen() {
        if (closed) throw new IllegalStateException("LOD GPU buffers are closed");
    }
}

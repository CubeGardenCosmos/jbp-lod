package cn.cubegarden.cosmos.jbp.client.lod.vk.gpu;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Render-thread state only: fences are assigned exclusively at game submission boundaries. */
public final class FrameRetirement {
    private static final int IDLE = 0;
    private static final int SELECTED = 1;
    private static final int SUBMITTED = 2;
    private static final int FENCED = 3;

    private final int[] states;
    private final List<List<Runnable>> actions;
    private boolean hasFrame;
    private long frameId;
    private int selected = -1;

    public FrameRetirement(int slots) {
        if (slots <= 0 || slots > Integer.SIZE) {
            throw new IllegalArgumentException("slot count must be 1..32");
        }
        states = new int[slots];
        actions = new ArrayList<>(slots);
        for (int i = 0; i < slots; i++) {
            actions.add(new ArrayList<>());
        }
    }

    /** A skipped or retired frame stays skipped on subsequent calls with the same ID. */
    public int beginFrame(long frameId) {
        if (hasFrame && this.frameId == frameId) {
            return selected;
        }
        if (selected >= 0 && states[selected] == SELECTED) {
            states[selected] = IDLE;
        }
        hasFrame = true;
        this.frameId = frameId;
        selected = -1;
        for (int i = 0; i < states.length; i++) {
            if (states[i] == IDLE) {
                states[i] = SELECTED;
                selected = i;
                break;
            }
        }
        return selected;
    }

    /** Marks actual GPU use; an unfenced submission cannot be acquired by another frame. */
    public void submitted(int slot) {
        if (states[slot] != SELECTED && states[slot] != SUBMITTED) {
            throw new IllegalStateException("slot is not selected or awaiting game submission");
        }
        states[slot] = SUBMITTED;
    }

    /** Returns a bit per slot covered by the caller's new game-submission fence. */
    public int afterGameSubmission() {
        int fenced = 0;
        for (int i = 0; i < states.length; i++) {
            if (states[i] == SUBMITTED) {
                states[i] = FENCED;
                fenced |= 1 << i;
            }
        }
        return fenced;
    }

    /** Called only after the assigned fence has completed. Runs queued actions once, in order. */
    public void retired(int slot) {
        if (states[slot] != FENCED) {
            throw new IllegalStateException("slot has no game-submission fence");
        }
        states[slot] = IDLE;
        if (selected == slot) {
            selected = -1;
        }
        List<Runnable> pending = actions.get(slot);
        Throwable failure = null;
        try {
            for (Runnable action : pending) {
                try {
                    action.run();
                } catch (RuntimeException | Error thrown) {
                    if (failure == null) {
                        failure = thrown;
                    } else if (failure != thrown) {
                        failure.addSuppressed(thrown);
                    }
                }
            }
        } finally {
            pending.clear();
        }
        if (failure instanceof RuntimeException exception) {
            throw exception;
        }
        if (failure instanceof Error error) {
            throw error;
        }
    }

    /** Runs immediately if no GPU use is pending; otherwise runs when that use retires. */
    public void retireAfterUse(int slot, Runnable action) {
        Objects.requireNonNull(action, "action");
        if (hasPending(slot)) {
            actions.get(slot).add(action);
        } else {
            action.run();
        }
    }

    /** Whether GPU use is awaiting a submission boundary or fence completion. */
    public boolean hasPending(int slot) {
        return states[slot] == SUBMITTED || states[slot] == FENCED;
    }

    /** Whether the slot is available for acquisition by a new frame. */
    public boolean isIdle(int slot) {
        return states[slot] == IDLE;
    }
}

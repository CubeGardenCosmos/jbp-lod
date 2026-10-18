package cn.cubegarden.cosmos.jbp.client.lod.vk.gpu;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FrameRetirementTest {
    @Test
    void requiresAtLeastOneSlot() {
        assertThrows(IllegalArgumentException.class, () -> new FrameRetirement(0));
        assertThrows(IllegalArgumentException.class, () -> new FrameRetirement(-1));
        assertThrows(IllegalArgumentException.class, () -> new FrameRetirement(33));
    }

    @Test
    void repeatsSelectionAndReusesUnusedFramesWithoutSubmission() {
        FrameRetirement state = new FrameRetirement(1);
        assertTrue(state.isIdle(0));
        assertEquals(0, state.beginFrame(Long.MIN_VALUE));
        assertEquals(0, state.beginFrame(Long.MIN_VALUE));
        assertFalse(state.isIdle(0));
        assertFalse(state.hasPending(0));
        assertEquals(0, state.beginFrame(1));
        assertEquals(0, state.afterGameSubmission());
    }

    @Test
    void nextFrameCannotReuseUnfencedSubmission() {
        FrameRetirement state = new FrameRetirement(2);
        assertEquals(0, state.beginFrame(1));
        state.submitted(0);
        state.submitted(0);
        assertTrue(state.hasPending(0));
        assertEquals(1, state.beginFrame(2));
        state.submitted(1);
        assertEquals(-1, state.beginFrame(3));
        assertFalse(state.isIdle(0));
        assertFalse(state.isIdle(1));
        assertEquals(0b11, state.afterGameSubmission());
        assertEquals(0, state.afterGameSubmission());
        assertEquals(-1, state.beginFrame(4));
    }

    @Test
    void retiredCurrentFrameCannotAcquireAgainUntilFrameChanges() {
        FrameRetirement state = new FrameRetirement(1);
        assertEquals(0, state.beginFrame(1));
        state.submitted(0);
        assertEquals(0b1, state.afterGameSubmission());
        state.retired(0);
        assertTrue(state.isIdle(0));
        assertFalse(state.hasPending(0));
        assertEquals(-1, state.beginFrame(1));
        assertEquals(0, state.beginFrame(2));
    }

    @Test
    void skippedFrameRemainsSkippedWhenAnotherFrameRetires() {
        FrameRetirement state = new FrameRetirement(1);
        state.beginFrame(1);
        state.submitted(0);
        state.afterGameSubmission();
        assertEquals(-1, state.beginFrame(2));
        state.retired(0);
        assertEquals(-1, state.beginFrame(2));
        assertEquals(0, state.beginFrame(3));
    }

    @Test
    void boundariesOnlyCollectNewSubmissions() {
        FrameRetirement state = new FrameRetirement(2);
        state.beginFrame(1);
        state.submitted(0);
        assertEquals(0b1, state.afterGameSubmission());
        state.beginFrame(2);
        state.submitted(1);
        assertEquals(0b10, state.afterGameSubmission());
        state.retired(0);
        assertTrue(state.hasPending(1));
        assertEquals(0, state.beginFrame(3));
    }

    @Test
    void actionsWaitForRetirementAndRunOnceInOrder() {
        FrameRetirement state = new FrameRetirement(1);
        List<Integer> calls = new ArrayList<>();
        state.beginFrame(1);
        state.submitted(0);
        state.retireAfterUse(0, () -> calls.add(1));
        state.beginFrame(2);
        assertEquals(List.of(), calls);
        state.afterGameSubmission();
        state.retireAfterUse(0, () -> calls.add(2));
        assertEquals(List.of(), calls);
        state.retired(0);
        assertEquals(List.of(1, 2), calls);
        state.beginFrame(3);
        state.submitted(0);
        state.afterGameSubmission();
        state.retired(0);
        assertEquals(List.of(1, 2), calls);
    }

    @Test
    void actionsWithoutPendingGpuUseRunImmediately() {
        FrameRetirement state = new FrameRetirement(1);
        List<Integer> calls = new ArrayList<>();
        state.retireAfterUse(0, () -> calls.add(1));
        state.beginFrame(1);
        state.retireAfterUse(0, () -> calls.add(2));
        assertEquals(List.of(1, 2), calls);
        assertThrows(NullPointerException.class, () -> state.retireAfterUse(0, null));
    }

    @Test
    void refusesRetirementWithoutGameFenceAndSubmissionWithoutSelection() {
        FrameRetirement state = new FrameRetirement(1);
        assertThrows(IllegalStateException.class, () -> state.submitted(0));
        assertThrows(IllegalStateException.class, () -> state.retired(0));
        state.beginFrame(1);
        state.submitted(0);
        assertThrows(IllegalStateException.class, () -> state.retired(0));
        state.afterGameSubmission();
        assertThrows(IllegalStateException.class, () -> state.submitted(0));
        state.retired(0);
        assertThrows(IllegalStateException.class, () -> state.retired(0));
    }

    @Test
    void failingActionDoesNotDiscardRemainingRetirementActions() {
        FrameRetirement state = new FrameRetirement(1);
        List<Integer> calls = new ArrayList<>();
        state.beginFrame(1);
        state.submitted(0);
        state.retireAfterUse(0, () -> { throw new IllegalStateException("first"); });
        state.retireAfterUse(0, () -> calls.add(2));
        state.afterGameSubmission();
        assertThrows(IllegalStateException.class, () -> state.retired(0));
        assertEquals(List.of(2), calls);
        assertTrue(state.isIdle(0));
    }

    @Test
    void closeBeforeBoundaryKeepsDestructionPendingUntilActualGameSubmission() {
        FrameRetirement state = new FrameRetirement(1);
        List<Integer> destroyed = new ArrayList<>();
        state.beginFrame(1);
        state.submitted(0);
        // Closing the owner queues destruction; it does not synthesize a submission boundary.
        state.retireAfterUse(0, () -> destroyed.add(0));
        assertTrue(state.hasPending(0));
        assertFalse(state.isIdle(0));
        assertEquals(List.of(), destroyed);
        assertThrows(IllegalStateException.class, () -> state.retired(0));
        assertEquals(1, state.afterGameSubmission());
        assertEquals(List.of(), destroyed);
        state.retired(0);
        assertEquals(List.of(0), destroyed);
        assertFalse(state.hasPending(0));
    }

    @Test
    void boundaryMaskIncludesHighestSupportedSlot() {
        FrameRetirement state = new FrameRetirement(32);
        for (int i = 0; i < 32; i++) {
            assertEquals(i, state.beginFrame(i));
            state.submitted(i);
        }
        assertEquals(-1, state.afterGameSubmission());
        assertEquals(0, state.afterGameSubmission());
        state.retired(31);
        assertEquals(31, state.beginFrame(32));
    }
}

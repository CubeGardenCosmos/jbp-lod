package cn.cubegarden.cosmos.jbp.client.lod.data.ingest;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CaptureAdmissionTest {
    @Test
    void admitsUpToLimitThenRefuses() {
        var admission = new CaptureAdmission(2);
        assertTrue(admission.tryAcquire());
        assertTrue(admission.tryAcquire());
        assertFalse(admission.tryAcquire());
        assertEquals(2, admission.pendingCount());
    }

    @Test
    void releaseFreesPermit() {
        var admission = new CaptureAdmission(1);
        assertTrue(admission.tryAcquire());
        admission.release();
        assertEquals(0, admission.pendingCount());
        assertTrue(admission.tryAcquire());
    }

    @Test
    void hasCapacityReflectsPendingAndLimit() {
        var admission = new CaptureAdmission(24);
        assertTrue(admission.hasCapacity(24));
        assertTrue(admission.tryAcquire());
        assertFalse(admission.hasCapacity(24));
        assertTrue(admission.hasCapacity(23));
    }

    @Test
    void closedRefusesAcquisitionButAdmittedWorkStillReleases() {
        var admission = new CaptureAdmission(4);
        assertTrue(admission.tryAcquire());
        admission.close();
        assertTrue(admission.isClosed());
        assertFalse(admission.tryAcquire());
        assertFalse(admission.hasCapacity(1));
        // A task admitted before close observes isClosed() and still releases its permit in its finally block.
        admission.release();
        assertEquals(0, admission.pendingCount());
    }

    @Test
    void rejectsNonPositiveLimit() {
        assertThrows(IllegalArgumentException.class, () -> new CaptureAdmission(0));
    }
}

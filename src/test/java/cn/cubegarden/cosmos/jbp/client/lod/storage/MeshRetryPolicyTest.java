package cn.cubegarden.cosmos.jbp.client.lod.storage;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MeshRetryPolicyTest {
    private static final long POS = 42L;
    private static final long MILLI = 1_000_000L;

    @Test
    void retriesWithBackoffThenBecomesTerminal() {
        var policy = new MeshRetryPolicy();
        assertEquals(new MeshRetryPolicy.Retry(250L), policy.onFailure(POS, 0L));
        assertEquals(new MeshRetryPolicy.Retry(1000L), policy.onFailure(POS, 0L));
        assertEquals(new MeshRetryPolicy.Terminal(MeshRetryPolicy.COOLDOWN_MILLIS), policy.onFailure(POS, 0L));
    }

    @Test
    void terminalFailureCoolsDownThenExpires() {
        var policy = new MeshRetryPolicy();
        policy.onFailure(POS, 0L);
        policy.onFailure(POS, 0L);
        policy.onFailure(POS, 0L);
        assertEquals(MeshRetryPolicy.COOLDOWN_MILLIS - 1, policy.cooldownRemainingMillis(POS, MILLI));
        assertEquals(0L, policy.cooldownRemainingMillis(POS, MeshRetryPolicy.COOLDOWN_MILLIS * MILLI));
    }

    @Test
    void successClearsFailureCountAndCooldown() {
        var policy = new MeshRetryPolicy();
        policy.onFailure(POS, 0L);
        policy.onSuccess(POS);
        assertEquals(new MeshRetryPolicy.Retry(250L), policy.onFailure(POS, 0L));

        policy.onFailure(POS, 0L);
        policy.onFailure(POS, 0L);
        policy.onSuccess(POS);
        assertEquals(0L, policy.cooldownRemainingMillis(POS, 0L));
    }

    @Test
    void positionsAreTrackedIndependently() {
        var policy = new MeshRetryPolicy();
        policy.onFailure(1L, 0L);
        policy.onFailure(1L, 0L);
        policy.onFailure(1L, 0L);
        assertEquals(0L, policy.cooldownRemainingMillis(2L, 0L));
        assertEquals(new MeshRetryPolicy.Retry(250L), policy.onFailure(2L, 0L));
    }
}

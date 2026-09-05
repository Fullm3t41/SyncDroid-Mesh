package com.syncdeck.app.platform

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PairingAttemptStateTest {
    @Test
    fun failuresExpireAsOneFifteenMinuteWindow() {
        val start = 1_000_000L
        val active = PairingAttemptState(3, start + PairingAttemptState.WINDOW_MILLIS, start)
        assertEquals(2, active.attemptsRemaining)
        assertFalse(active.locked)

        val expired = PairingAttemptState(5, start + PairingAttemptState.WINDOW_MILLIS, start + PairingAttemptState.WINDOW_MILLIS)
        assertEquals(5, expired.attemptsRemaining)
        assertFalse(expired.locked)
    }

    @Test
    fun fifthFailureLocksUntilWindowExpires() {
        val state = PairingAttemptState(5, 2_000_000L, 1_500_000L)
        assertEquals(0, state.attemptsRemaining)
        assertTrue(state.locked)
    }
}

package com.clairvoyant.glasses.relay

import org.junit.Assert.assertEquals
import org.junit.Test

class BackoffTest {
    @Test fun doublesFromBaseAndCaps() {
        val b = Backoff(baseMs = 500, maxMs = 10_000)
        assertEquals(listOf(500L, 1000L, 2000L, 4000L, 8000L, 10_000L, 10_000L),
            (0 until 7).map { b.nextDelayMs() })
    }

    @Test fun resetGoesBackToBase() {
        val b = Backoff(baseMs = 500, maxMs = 10_000)
        b.nextDelayMs(); b.nextDelayMs()
        b.reset()
        assertEquals(500L, b.nextDelayMs())
    }
}

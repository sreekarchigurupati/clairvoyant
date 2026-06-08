package com.clairvoyant.glasses.relay

/** Exponential backoff (base·2^n, capped). Pure + deterministic so reconnect timing is testable. */
class Backoff(private val baseMs: Long = 500, private val maxMs: Long = 10_000) {
    private var attempt = 0

    fun reset() { attempt = 0 }

    fun nextDelayMs(): Long {
        val shifted = baseMs shl attempt.coerceAtMost(20)
        if (attempt < 20) attempt++
        return shifted.coerceAtMost(maxMs)
    }
}

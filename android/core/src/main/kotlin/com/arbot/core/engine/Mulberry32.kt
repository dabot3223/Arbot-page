package com.arbot.core.engine

/**
 * Tiny deterministic PRNG (mulberry32). Chosen because it only needs 32-bit
 * integer math, so the Kotlin client and the TypeScript server replay produce
 * bit-identical sequences from the same server-issued seed.
 */
class Mulberry32(seed: Int) {
    private var state = seed

    fun nextUInt(): Long {
        state += 0x6D2B79F5
        var t = state
        t = (t xor (t ushr 15)) * (t or 1)
        t = t xor (t + (t xor (t ushr 7)) * (t or 61))
        return (t xor (t ushr 14)).toLong() and 0xFFFFFFFFL
    }

    fun nextInt(bound: Int): Int = (nextUInt() % bound).toInt()
}

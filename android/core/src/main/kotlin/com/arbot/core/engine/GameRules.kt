package com.arbot.core.engine

/**
 * Every gameplay constant lives here. The server re-simulates finished games
 * with a TypeScript port of this engine (supabase/functions/_shared/engine.ts),
 * so any change here MUST be mirrored there and the shared fixtures regenerated.
 */
object GameRules {
    const val COLS = 20
    const val ROWS = 11
    const val CELLS = COLS * ROWS

    /** Row 1, column 0 – the spawn cell of the original web game (cell "31"). */
    const val START_CELL = COLS

    /** Simulation step. All timers below are expressed in ticks. */
    const val TICK_MS = 10

    const val COIN_EVERY = 500          // 5 s
    const val ENERGY_EVERY = 1_400      // 14 s
    const val MINES_EVERY = 3_000       // 30 s
    const val BOT_BASE_INTERVAL = 57    // 570 ms
    const val BOT_MIN_INTERVAL = 30
    const val BOT_SPEEDUP_PER_100 = 3
    const val POWER_TICKS = 500         // 5 s
    const val INVULN_TICKS = 150        // 1.5 s after a respawn
    const val ENERGY_FOR_POWER = 3
    const val MINE_ATTEMPTS = 81
    const val BOT_SPAWN_MIN_DISTANCE = 8
    const val BOT_SPAWN_ATTEMPTS = 64

    /** Hard cap on a single run: 30 minutes. */
    const val MAX_TICKS = 180_000
    const val MAX_INPUTS = 20_000

    const val COIN_POINTS = 5
    const val ENERGY_POINTS = 10
    const val BOT_POINTS = 25

    const val BASE_LIVES = 5

    fun row(cell: Int) = cell / COLS
    fun col(cell: Int) = cell % COLS
    fun manhattan(a: Int, b: Int) = kotlin.math.abs(row(a) - row(b)) + kotlin.math.abs(col(a) - col(b))
}

enum class Direction(val code: Int, val dc: Int, val dr: Int) {
    UP(0, 0, -1),
    RIGHT(1, 1, 0),
    DOWN(2, 0, 1),
    LEFT(3, -1, 0);

    companion object {
        fun fromCode(code: Int): Direction? = entries.firstOrNull { it.code == code }
    }
}

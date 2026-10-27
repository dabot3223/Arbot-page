package com.arbot.core.engine

import com.arbot.core.engine.GameRules.BOT_BASE_INTERVAL
import com.arbot.core.engine.GameRules.BOT_MIN_INTERVAL
import com.arbot.core.engine.GameRules.BOT_POINTS
import com.arbot.core.engine.GameRules.BOT_SPAWN_ATTEMPTS
import com.arbot.core.engine.GameRules.BOT_SPAWN_MIN_DISTANCE
import com.arbot.core.engine.GameRules.BOT_SPEEDUP_PER_100
import com.arbot.core.engine.GameRules.CELLS
import com.arbot.core.engine.GameRules.COIN_EVERY
import com.arbot.core.engine.GameRules.COIN_POINTS
import com.arbot.core.engine.GameRules.COLS
import com.arbot.core.engine.GameRules.ENERGY_EVERY
import com.arbot.core.engine.GameRules.ENERGY_FOR_POWER
import com.arbot.core.engine.GameRules.ENERGY_POINTS
import com.arbot.core.engine.GameRules.INVULN_TICKS
import com.arbot.core.engine.GameRules.MAX_INPUTS
import com.arbot.core.engine.GameRules.MAX_TICKS
import com.arbot.core.engine.GameRules.MINES_EVERY
import com.arbot.core.engine.GameRules.MINE_ATTEMPTS
import com.arbot.core.engine.GameRules.POWER_TICKS
import com.arbot.core.engine.GameRules.ROWS
import com.arbot.core.engine.GameRules.START_CELL
import com.arbot.core.engine.GameRules.col
import com.arbot.core.engine.GameRules.manhattan
import com.arbot.core.engine.GameRules.row

/**
 * Deterministic Ar_B0T simulation.
 *
 * The player (Ar_B0T) roams a wrapping 20x11 grid collecting data coins and
 * energy cells while avoiding mines and a hunter bot. Collecting three energy
 * cells powers Ar_B0T up for five seconds, during which the hunter flees and
 * can be destroyed.
 *
 * Everything is driven by [step] (one 10 ms tick) and [move]; given the same
 * seed and the same (tick, direction) input log the outcome is identical on
 * every platform, which is what lets the server verify submitted scores.
 */
class GameEngine(
    seed: Long,
    val startLives: Int = GameRules.BASE_LIVES,
    powerStart: Boolean = false,
) {
    private val rng = Mulberry32(seed.toInt())

    val mines = BooleanArray(CELLS)
    var player = START_CELL; private set
    var bot = -1; private set
    var coin = -1; private set
    var energy = -1; private set
    var lives = startLives; private set
    var score = 0; private set
    var energyCount = 0; private set
    var powerTicks = 0; private set
    var invulnTicks = 0; private set
    var tick = 0; private set
    var gameOver = false; private set

    private var botTimer = 0
    private val events = ArrayList<GameEvent>()
    private val inputs = ArrayList<Int>()

    init {
        placeMines()
        coin = randomFreeCell()
        energy = randomFreeCell()
        bot = randomFarCell()
        if (powerStart) powerTicks = POWER_TICKS
    }

    /** Flat [tick0, dir0, tick1, dir1, ...] log submitted to the server. */
    fun inputLog(): List<Int> = inputs.toList()

    fun drainEvents(): List<GameEvent> {
        if (events.isEmpty()) return emptyList()
        val out = events.toList()
        events.clear()
        return out
    }

    fun botInterval(): Int =
        maxOf(BOT_MIN_INTERVAL, BOT_BASE_INTERVAL - (score / 100) * BOT_SPEEDUP_PER_100)

    fun advanceTo(target: Int) {
        while (tick < target && !gameOver) step()
    }

    fun step() {
        if (gameOver) return
        tick++
        if (powerTicks > 0) {
            powerTicks--
            if (powerTicks == 0) events += GameEvent.PowerEnded
        }
        if (invulnTicks > 0) invulnTicks--

        if (tick % COIN_EVERY == 0) {
            coin = -1
            coin = randomFreeCell()
            events += GameEvent.CoinSpawned(coin)
        }
        if (tick % ENERGY_EVERY == 0) {
            energy = -1
            energy = randomFreeCell()
            events += GameEvent.EnergySpawned(energy)
        }
        if (tick % MINES_EVERY == 0) {
            placeMines()
            events += GameEvent.MinesShuffled
        }

        botTimer++
        if (botTimer >= botInterval()) {
            botTimer = 0
            val from = bot
            moveBot()
            events += GameEvent.BotMoved(from, bot)
            checkBotCollision()
        }

        if (!gameOver && tick >= MAX_TICKS) {
            gameOver = true
            events += GameEvent.GameOver(score, timeUp = true)
        }
    }

    fun move(dir: Direction): Boolean {
        if (gameOver) return false
        if (inputs.size / 2 >= MAX_INPUTS) return false
        inputs += tick
        inputs += dir.code

        var r = row(player) + dir.dr
        var c = col(player) + dir.dc
        val wrapped = r < 0 || r >= ROWS || c < 0 || c >= COLS
        r = (r + ROWS) % ROWS
        c = (c + COLS) % COLS
        val from = player
        player = r * COLS + c
        events += GameEvent.PlayerMoved(from, player, wrapped)

        if (mines[player] && invulnTicks == 0) {
            events += GameEvent.MineHit(player)
            loseLife()
            return true
        }
        if (player == coin) {
            score += COIN_POINTS
            events += GameEvent.CoinCollected(player, COIN_POINTS)
            coin = -1
            coin = randomFreeCell()
            events += GameEvent.CoinSpawned(coin)
        }
        if (player == energy) {
            score += ENERGY_POINTS
            energyCount++
            events += GameEvent.EnergyCollected(player, ENERGY_POINTS, energyCount)
            energy = -1
            energy = randomFreeCell()
            events += GameEvent.EnergySpawned(energy)
            if (energyCount >= ENERGY_FOR_POWER) {
                energyCount = 0
                powerTicks = POWER_TICKS
                events += GameEvent.PowerUp
            }
        }
        checkBotCollision()
        return true
    }

    fun snapshot() = GameSnapshot(
        tick = tick,
        score = score,
        lives = lives,
        player = player,
        bot = bot,
        coin = coin,
        energy = energy,
        energyCount = energyCount,
        powerTicks = powerTicks,
        invulnTicks = invulnTicks,
        gameOver = gameOver,
        mines = mines.indices.filter { mines[it] },
    )

    private fun checkBotCollision() {
        if (bot != player || gameOver) return
        if (powerTicks > 0) {
            score += BOT_POINTS
            events += GameEvent.BotDestroyed(bot, BOT_POINTS)
            bot = -1
            bot = randomFarCell()
            events += GameEvent.BotSpawned(bot)
        } else if (invulnTicks == 0) {
            events += GameEvent.CaughtByBot(player)
            loseLife()
        }
    }

    private fun loseLife() {
        lives--
        energyCount = 0
        powerTicks = 0
        if (lives <= 0) {
            lives = 0
            gameOver = true
            events += GameEvent.LifeLost(0)
            events += GameEvent.GameOver(score, timeUp = false)
        } else {
            player = START_CELL
            invulnTicks = INVULN_TICKS
            events += GameEvent.LifeLost(lives)
        }
    }

    private fun moveBot() {
        var br = row(bot)
        var bc = col(bot)
        val pr = row(player)
        val pc = col(player)
        if (powerTicks > 0) {
            // Flee: take the first neighbour (UP, RIGHT, DOWN, LEFT) that strictly
            // increases the distance to the player. The bot never wraps.
            var best = bot
            var bestDist = manhattan(bot, player)
            for (d in Direction.entries) {
                val nr = br + d.dr
                val nc = bc + d.dc
                if (nr < 0 || nr >= ROWS || nc < 0 || nc >= COLS) continue
                val cell = nr * COLS + nc
                val dist = manhattan(cell, player)
                if (dist > bestDist) {
                    best = cell
                    bestDist = dist
                }
            }
            bot = best
        } else {
            // Chase like the original: close the row gap first, then the column gap.
            if (br != pr) br += Integer.signum(pr - br)
            else if (bc != pc) bc += Integer.signum(pc - bc)
            bot = br * COLS + bc
        }
    }

    private fun placeMines() {
        mines.fill(false)
        repeat(MINE_ATTEMPTS) {
            val c = rng.nextInt(CELLS)
            if (c != START_CELL && c != coin && c != energy && c != bot && manhattan(c, player) > 1) {
                mines[c] = true
            }
        }
    }

    private fun randomFreeCell(): Int {
        while (true) {
            val c = rng.nextInt(CELLS)
            if (!mines[c] && c != player && c != coin && c != energy && c != bot && c != START_CELL) return c
        }
    }

    private fun randomFarCell(): Int {
        var c = randomFreeCell()
        var attempts = 1
        while (manhattan(c, player) < BOT_SPAWN_MIN_DISTANCE && attempts < BOT_SPAWN_ATTEMPTS) {
            c = randomFreeCell()
            attempts++
        }
        return c
    }

    companion object {
        /**
         * Re-runs a recorded game. Mirrors `replay()` in the server engine;
         * the client uses it to self-check a run before submitting it.
         */
        fun replay(
            seed: Long,
            startLives: Int,
            powerStart: Boolean,
            inputs: List<Int>,
            finalTick: Int,
        ): GameSnapshot {
            require(inputs.size % 2 == 0) { "inputs must be (tick, dir) pairs" }
            require(inputs.size / 2 <= MAX_INPUTS) { "too many inputs" }
            require(finalTick in 0..MAX_TICKS) { "finalTick out of range" }
            val engine = GameEngine(seed, startLives, powerStart)
            var previous = 0
            for (i in inputs.indices step 2) {
                val t = inputs[i]
                val dir = Direction.fromCode(inputs[i + 1]) ?: throw IllegalArgumentException("bad direction")
                require(t >= previous && t <= finalTick) { "input ticks out of order" }
                previous = t
                engine.advanceTo(t)
                if (engine.gameOver) break
                engine.move(dir)
            }
            engine.advanceTo(finalTick)
            return engine.snapshot()
        }
    }
}

data class GameSnapshot(
    val tick: Int,
    val score: Int,
    val lives: Int,
    val player: Int,
    val bot: Int,
    val coin: Int,
    val energy: Int,
    val energyCount: Int,
    val powerTicks: Int,
    val invulnTicks: Int,
    val gameOver: Boolean,
    val mines: List<Int>,
)

sealed interface GameEvent {
    data class PlayerMoved(val from: Int, val to: Int, val wrapped: Boolean) : GameEvent
    data class BotMoved(val from: Int, val to: Int) : GameEvent
    data class CoinCollected(val cell: Int, val points: Int) : GameEvent
    data class CoinSpawned(val cell: Int) : GameEvent
    data class EnergyCollected(val cell: Int, val points: Int, val charge: Int) : GameEvent
    data class EnergySpawned(val cell: Int) : GameEvent
    data object PowerUp : GameEvent
    data object PowerEnded : GameEvent
    data object MinesShuffled : GameEvent
    data class MineHit(val cell: Int) : GameEvent
    data class CaughtByBot(val cell: Int) : GameEvent
    data class BotDestroyed(val cell: Int, val points: Int) : GameEvent
    data class BotSpawned(val cell: Int) : GameEvent
    data class LifeLost(val livesLeft: Int) : GameEvent
    data class GameOver(val score: Int, val timeUp: Boolean) : GameEvent
}

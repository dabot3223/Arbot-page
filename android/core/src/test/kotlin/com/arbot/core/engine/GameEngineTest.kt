package com.arbot.core.engine

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GameEngineTest {

    private val fixtureFile = File(System.getProperty("arbot.fixtures"), "engine-vectors.json")
    private val pretty = Json { prettyPrint = true }

    @Test
    fun replayMatchesLiveRun() {
        for (seed in listOf(1L, 42L, 0xDEADBEEFL)) {
            val (live, inputs) = play(seed, 5, false, 1_500)
            val replayed = GameEngine.replay(seed, 5, false, inputs, live.tick)
            assertEquals(live, replayed)
        }
    }

    @Test
    fun startingStateIsSafe() {
        repeat(200) { seed ->
            val e = GameEngine(seed.toLong())
            val s = e.snapshot()
            assertFalse(e.mines[GameRules.START_CELL])
            assertTrue(GameRules.manhattan(s.bot, s.player) >= 1)
            assertTrue(s.coin != s.energy && s.coin != s.player && s.energy != s.player)
            assertFalse(e.mines[s.coin] || e.mines[s.energy])
        }
    }

    @Test
    fun movementWrapsAroundTheBoard() {
        val e = GameEngine(7)
        // START_CELL is column 0, so moving left wraps to column 19 of the same row.
        e.move(Direction.LEFT)
        val events = e.drainEvents()
        val moved = events.filterIsInstance<GameEvent.PlayerMoved>().first()
        assertTrue(moved.wrapped)
        assertEquals(GameRules.START_CELL + GameRules.COLS - 1, moved.to)
    }

    @Test
    fun replayRejectsOutOfOrderInputs() {
        val result = runCatching { GameEngine.replay(1, 5, false, listOf(10, 0, 5, 1), 100) }
        assertTrue(result.isFailure)
    }

    @Test
    fun tamperedInputsChangeTheOutcome() {
        val (live, inputs) = play(99, 5, false, 800)
        val tampered = inputs.toMutableList()
        // Flip every direction: the replayed run must diverge from the claimed one.
        for (i in 1 until tampered.size step 2) tampered[i] = (tampered[i] + 2) % 4
        val replayed = GameEngine.replay(99, 5, false, tampered, live.tick)
        assertTrue(replayed != live)
    }

    /**
     * Cross-language determinism: shared/fixtures/engine-vectors.json is
     * replayed by the Deno tests of the server engine as well.
     * Regenerate with `REGEN_FIXTURES=1 gradle test` after changing rules.
     */
    @Test
    fun matchesSharedFixtures() {
        val scenarios = listOf(
            Triple(1L, 5, false),
            Triple(42L, 5, false),
            Triple(2024L, 6, true),
            Triple(0xDEADBEEFL, 7, false),
            Triple(987654321L, 5, true),
            Triple(4294967295L, 6, false),
        )
        val generated = buildJsonObject {
            put("rng", buildJsonObject {
                put("seed", 12345)
                put("values", buildJsonArray {
                    val r = Mulberry32(12345)
                    repeat(8) { add(JsonPrimitive(r.nextUInt())) }
                })
            })
            put("scenarios", buildJsonArray {
                for ((seed, lives, power) in scenarios) {
                    val (snap, inputs) = play(seed, lives, power, 2_500)
                    add(buildJsonObject {
                        put("seed", seed)
                        put("startLives", lives)
                        put("powerStart", power)
                        put("inputs", JsonArray(inputs.map { JsonPrimitive(it) }))
                        put("finalTick", snap.tick)
                        put("expected", snap.toJson())
                    })
                }
            })
        }

        if (System.getenv("REGEN_FIXTURES") == "1" || !fixtureFile.exists()) {
            fixtureFile.parentFile.mkdirs()
            fixtureFile.writeText(pretty.encodeToString(JsonObject.serializer(), generated) + "\n")
        }

        val fixture = Json.parseToJsonElement(fixtureFile.readText()).jsonObject
        val rng = fixture["rng"]!!.jsonObject
        val r = Mulberry32(rng["seed"]!!.jsonPrimitive.long.toInt())
        for (v in rng["values"]!!.jsonArray) assertEquals(v.jsonPrimitive.long, r.nextUInt())

        for (sc in fixture["scenarios"]!!.jsonArray) {
            val o = sc.jsonObject
            val snap = GameEngine.replay(
                o["seed"]!!.jsonPrimitive.long,
                o["startLives"]!!.jsonPrimitive.int,
                o["powerStart"]!!.jsonPrimitive.boolean,
                o["inputs"]!!.jsonArray.map { it.jsonPrimitive.int },
                o["finalTick"]!!.jsonPrimitive.int,
            )
            assertEquals(o["expected"], snap.toJson(), "seed ${o["seed"]}")
        }
    }

    private fun GameSnapshot.toJson() = buildJsonObject {
        put("tick", tick)
        put("score", score)
        put("lives", lives)
        put("player", player)
        put("bot", bot)
        put("coin", coin)
        put("energy", energy)
        put("energyCount", energyCount)
        put("powerTicks", powerTicks)
        put("invulnTicks", invulnTicks)
        put("gameOver", gameOver)
        put("mines", JsonArray(mines.map { JsonPrimitive(it) }))
    }

    /**
     * Plays with a simple greedy bot-player so runs reach power-ups, bot kills,
     * respawns and mine reshuffles instead of dying on the first mine.
     */
    private fun play(seed: Long, lives: Int, powerStart: Boolean, maxMoves: Int): Pair<GameSnapshot, List<Int>> {
        val e = GameEngine(seed, lives, powerStart)
        val policyRng = Mulberry32((seed xor 0x5bd1e995L).toInt())
        var moves = 0
        while (!e.gameOver && moves < maxMoves) {
            e.advanceTo(e.tick + 1 + policyRng.nextInt(35))
            if (e.gameOver) break
            e.move(chooseMove(e, policyRng))
            moves++
        }
        e.advanceTo(e.tick + 700)
        return e.snapshot() to e.inputLog()
    }

    private fun chooseMove(e: GameEngine, r: Mulberry32): Direction {
        val target = if (e.powerTicks > 0) e.bot else if (r.nextInt(3) == 0) e.energy else e.coin
        val options = Direction.entries.map { d ->
            val nr = (GameRules.row(e.player) + d.dr + GameRules.ROWS) % GameRules.ROWS
            val nc = (GameRules.col(e.player) + d.dc + GameRules.COLS) % GameRules.COLS
            d to nr * GameRules.COLS + nc
        }
        // 1 in 60 moves is random so mines and the bot still get hit sometimes.
        if (r.nextInt(60) == 0) return options[r.nextInt(4)].first
        val safe = options.filter { (_, c) -> !e.mines[c] && (e.powerTicks > 0 || GameRules.manhattan(c, e.bot) > 2) }
        val pool = safe.ifEmpty { options }
        return pool.minBy { (_, c) -> GameRules.manhattan(c, target) }.first
    }
}

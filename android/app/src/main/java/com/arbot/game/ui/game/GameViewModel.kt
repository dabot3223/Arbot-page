package com.arbot.game.ui.game

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.arbot.core.api.ApiException
import com.arbot.core.api.GameSessionInfo
import com.arbot.core.engine.Direction
import com.arbot.core.engine.GameEngine
import com.arbot.core.engine.GameEvent
import com.arbot.core.engine.GameRules
import com.arbot.game.AppContainer
import com.arbot.game.audio.Sfx
import com.arbot.game.ui.theme.Neon
import kotlinx.coroutines.launch
import kotlin.random.Random

private val ScaredBotBlue = Color(0xFF3B5BFF)

sealed interface GamePhase {
    data object Idle : GamePhase
    data object Starting : GamePhase
    data object Playing : GamePhase
    data object Paused : GamePhase
    data object Submitting : GamePhase
    data class Finished(val outcome: Outcome) : GamePhase
}

data class Outcome(
    val score: Int,
    val ranked: Boolean,
    val gemsEarned: Int = 0,
    val bestScore: Int? = null,
    val newBest: Boolean = false,
    val error: String? = null,
)

class GameViewModel(private val c: AppContainer) : ViewModel() {

    var engine by mutableStateOf<GameEngine?>(null)
        private set
    var phase by mutableStateOf<GamePhase>(GamePhase.Idle)
        private set

    /** Bumped every frame; reading it in the Canvas draw block schedules redraws. */
    var frame by mutableLongStateOf(0L)
        private set

    val fx = FxState()
    var ranked by mutableStateOf(false)
        private set

    private var session: GameSessionInfo? = null
    private var accumulatorMs = 0f

    fun start(useExtraLife: Boolean, usePowerStart: Boolean) {
        if (phase == GamePhase.Starting) return
        phase = GamePhase.Starting
        viewModelScope.launch {
            // A ranked run needs a server-issued seed; otherwise play a practice run.
            session = if (c.repository.isOnlineCapable) {
                runCatching { c.repository.startGame(useExtraLife, usePowerStart) }.getOrNull()
            } else {
                null
            }
            ranked = session != null
            val e = GameEngine(
                seed = session?.seed ?: Random.nextLong(0, 1L shl 32),
                startLives = session?.startLives ?: GameRules.BASE_LIVES,
                powerStart = session?.powerStart ?: false,
            )
            engine = e
            fx.reset(e)
            e.drainEvents()
            accumulatorMs = 0f
            phase = GamePhase.Playing
        }
    }

    fun onFrame(dtSeconds: Float) {
        val e = engine ?: return
        if (phase == GamePhase.Playing) {
            accumulatorMs += dtSeconds.coerceIn(0f, 0.1f) * 1000f
            while (accumulatorMs >= GameRules.TICK_MS && !e.gameOver) {
                e.step()
                accumulatorMs -= GameRules.TICK_MS
            }
            consume(e)
            if (e.gameOver) submit()
        }
        fx.update(dtSeconds.coerceIn(0f, 0.1f), e)
        frame++
    }

    fun move(dir: Direction) {
        if (phase != GamePhase.Playing) return
        val e = engine ?: return
        if (e.move(dir)) {
            fx.playerTilt = when (dir) {
                Direction.LEFT -> -12f
                Direction.RIGHT -> 12f
                else -> 0f
            }
            consume(e)
            if (e.gameOver) submit()
        }
    }

    fun pause() {
        if (phase == GamePhase.Playing) phase = GamePhase.Paused
    }

    fun resume() {
        if (phase == GamePhase.Paused) phase = GamePhase.Playing
    }

    fun endRun() {
        if (phase == GamePhase.Playing || phase == GamePhase.Paused) submit()
    }

    private fun submit() {
        if (phase == GamePhase.Submitting || phase is GamePhase.Finished) return
        val e = engine ?: return
        phase = GamePhase.Submitting
        val s = session
        viewModelScope.launch {
            val outcome = if (s == null) {
                Outcome(score = e.score, ranked = false)
            } else {
                try {
                    val r = c.repository.finishGame(s.sessionId, e.inputLog(), e.tick, e.score)
                    Outcome(r.score, true, r.gemsEarned, r.bestScore, r.newBest)
                } catch (ex: ApiException) {
                    Outcome(e.score, true, error = if (ex.code == "score_rejected") "Run could not be verified" else "Score not saved (${ex.code})")
                } catch (_: Exception) {
                    Outcome(e.score, true, error = "Offline – score not saved")
                }
            }
            if (outcome.newBest) c.sound.play(Sfx.PURCHASE)
            phase = GamePhase.Finished(outcome)
        }
    }

    private fun consume(e: GameEngine) {
        for (ev in e.drainEvents()) when (ev) {
            is GameEvent.CoinCollected -> {
                fx.burst(ev.cell, Neon.Cyan, 18)
                fx.text(ev.cell, "+${ev.points}", Neon.Cyan)
                fx.coinAppear = 0f
                c.sound.play(Sfx.COIN)
                c.haptics.tick()
            }
            is GameEvent.EnergyCollected -> {
                fx.burst(ev.cell, Neon.Purple, 24)
                fx.text(ev.cell, "+${ev.points}", Neon.Purple)
                fx.energyAppear = 0f
                c.sound.play(Sfx.ENERGY)
                c.haptics.tick()
            }
            GameEvent.PowerUp -> {
                fx.flash = 0.8f
                fx.flashColor = Neon.Gold
                fx.burstAt(fx.player.x, fx.player.y, Neon.Gold, 40, speed = 6f)
                c.sound.play(Sfx.POWER_UP)
                c.haptics.bump()
            }
            is GameEvent.MineHit -> {
                fx.burst(ev.cell, Neon.Red, 50, speed = 7f, size = 0.11f)
                fx.burst(ev.cell, Neon.Orange, 25, speed = 4f)
                fx.hit(1f, Neon.Red)
                c.sound.play(Sfx.HIT)
                c.haptics.heavy()
            }
            is GameEvent.CaughtByBot -> {
                fx.burst(ev.cell, Neon.Orange, 40, speed = 6f)
                fx.hit(0.8f, Neon.Orange)
                c.sound.play(Sfx.HIT)
                c.haptics.heavy()
            }
            is GameEvent.BotDestroyed -> {
                fx.burst(ev.cell, ScaredBotBlue, 45, speed = 7f, size = 0.1f)
                fx.burst(ev.cell, Neon.Gold, 25, speed = 5f)
                fx.text(ev.cell, "+${ev.points}", Neon.Gold)
                fx.shake = 0.4f
                c.sound.play(Sfx.BOT_DOWN)
                c.haptics.bump()
            }
            is GameEvent.BotSpawned -> fx.botAppear = 0f
            is GameEvent.CoinSpawned -> fx.coinAppear = 0f
            is GameEvent.EnergySpawned -> fx.energyAppear = 0f
            GameEvent.MinesShuffled -> {
                fx.minesAppear = 0f
                c.sound.play(Sfx.SHUFFLE)
            }
            is GameEvent.GameOver -> {
                fx.hit(1.2f, Neon.Magenta)
                c.sound.play(Sfx.GAME_OVER)
            }
            is GameEvent.PlayerMoved -> if (ev.wrapped) {
                fx.burst(ev.from, Neon.Magenta, 10, speed = 3f)
                fx.burst(ev.to, Neon.Magenta, 10, speed = 3f)
            }
            is GameEvent.LifeLost, is GameEvent.BotMoved, GameEvent.PowerEnded -> Unit
        }
    }
}

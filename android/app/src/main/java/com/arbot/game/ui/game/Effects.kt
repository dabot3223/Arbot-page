package com.arbot.game.ui.game

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import com.arbot.core.engine.GameEngine
import com.arbot.core.engine.GameRules
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

class Particle(
    var x: Float,
    var y: Float,
    var vx: Float,
    var vy: Float,
    var life: Float,
    val maxLife: Float,
    val color: Color,
    val size: Float,
)

class FloatingText(val text: String, val x: Float, var y: Float, val color: Color, var age: Float = 0f)

/**
 * Purely cosmetic state layered on top of the deterministic engine:
 * smoothed positions, particles, floating score text, screen shake, flashes
 * and spawn animations. Coordinates are in board cells (cell centre = col+0.5).
 */
class FxState {
    val particles = ArrayList<Particle>(600)
    val texts = ArrayList<FloatingText>()
    var time = 0f
    var shake = 0f
    var flash = 0f
    var flashColor = Color.White
    var minesAppear = 1f
    var coinAppear = 1f
    var energyAppear = 1f
    var botAppear = 1f
    var player = Offset.Zero
    var bot = Offset.Zero
    var playerTilt = 0f
    private val rnd = Random(1)

    fun reset(engine: GameEngine) {
        particles.clear()
        texts.clear()
        shake = 0f
        flash = 0f
        minesAppear = 0f
        coinAppear = 0f
        energyAppear = 0f
        botAppear = 0f
        player = centerOf(engine.player)
        bot = centerOf(engine.bot)
    }

    fun update(dt: Float, engine: GameEngine) {
        time += dt
        shake = (shake - dt * 2.5f).coerceAtLeast(0f)
        flash = (flash - dt * 2.2f).coerceAtLeast(0f)
        minesAppear = min(1f, minesAppear + dt * 2.2f)
        coinAppear = min(1f, coinAppear + dt * 3f)
        energyAppear = min(1f, energyAppear + dt * 3f)
        botAppear = min(1f, botAppear + dt * 2.5f)

        player = follow(player, centerOf(engine.player), dt, 22f)
        bot = follow(bot, centerOf(engine.bot), dt, 12f)
        playerTilt *= (1f - min(1f, dt * 10f))

        val it = particles.iterator()
        while (it.hasNext()) {
            val p = it.next()
            p.life -= dt
            if (p.life <= 0f) {
                it.remove()
                continue
            }
            p.x += p.vx * dt
            p.y += p.vy * dt
            p.vx *= 1f - min(1f, dt * 2.5f)
            p.vy = p.vy * (1f - min(1f, dt * 2.5f)) + dt * 1.2f
        }
        val ti = texts.iterator()
        while (ti.hasNext()) {
            val t = ti.next()
            t.age += dt
            t.y -= dt * 1.4f
            if (t.age > 1.1f) ti.remove()
        }
    }

    /** Snaps instead of sliding across the board on wrap-around or respawn. */
    private fun follow(current: Offset, target: Offset, dt: Float, speed: Float): Offset {
        val d = target - current
        if (abs(d.x) > 2.5f || abs(d.y) > 2.5f) return target
        return current + d * min(1f, dt * speed)
    }

    fun burst(cell: Int, color: Color, count: Int, speed: Float = 4f, size: Float = 0.08f) {
        val c = centerOf(cell)
        burstAt(c.x, c.y, color, count, speed, size)
    }

    fun burstAt(x: Float, y: Float, color: Color, count: Int, speed: Float = 4f, size: Float = 0.08f) {
        repeat(count) {
            if (particles.size >= 600) return
            val a = rnd.nextFloat() * 6.2832f
            val s = speed * (0.35f + rnd.nextFloat() * 0.65f)
            val life = 0.4f + rnd.nextFloat() * 0.5f
            particles += Particle(x, y, cos(a) * s, sin(a) * s, life, life, color, size * (0.6f + rnd.nextFloat()))
        }
    }

    fun text(cell: Int, text: String, color: Color) {
        val c = centerOf(cell)
        texts += FloatingText(text, c.x, c.y - 0.3f, color)
    }

    fun hit(intensity: Float, color: Color) {
        shake = maxOf(shake, intensity)
        flash = 0.6f
        flashColor = color
    }

    companion object {
        fun centerOf(cell: Int): Offset =
            if (cell < 0) Offset(-10f, -10f)
            else Offset(GameRules.col(cell) + 0.5f, GameRules.row(cell) + 0.5f)
    }
}

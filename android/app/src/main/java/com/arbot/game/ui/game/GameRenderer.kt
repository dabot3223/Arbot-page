package com.arbot.game.ui.game

import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import com.arbot.core.engine.GameEngine
import com.arbot.core.engine.GameRules
import com.arbot.game.ui.theme.Neon
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

private val Rainbow = listOf(Neon.Cyan, Neon.Magenta, Neon.Gold, Neon.Green, Neon.Cyan)

/** Draws one frame of the board. Pure function of engine + fx state. */
fun DrawScope.drawGame(engine: GameEngine, fx: FxState, skin: SkinPalette, measurer: TextMeasurer) {
    val cell = minOf(size.width / GameRules.COLS, size.height / GameRules.ROWS)
    val boardW = cell * GameRules.COLS
    val boardH = cell * GameRules.ROWS
    val t = fx.time
    val shakeX = if (fx.shake > 0f) sin(t * 90f) * fx.shake * cell * 0.25f else 0f
    val shakeY = if (fx.shake > 0f) cos(t * 77f) * fx.shake * cell * 0.25f else 0f
    val ox = (size.width - boardW) / 2 + shakeX
    val oy = (size.height - boardH) / 2 + shakeY
    val powered = engine.powerTicks > 0

    fun px(o: Offset) = Offset(ox + o.x * cell, oy + o.y * cell)

    // --- board --------------------------------------------------------------
    val radius = CornerRadius(cell * 0.4f)
    drawRoundRect(
        Brush.verticalGradient(listOf(Color(0xF00A1028), Color(0xF0120A26))),
        topLeft = Offset(ox, oy), size = Size(boardW, boardH), cornerRadius = radius,
    )
    val pulse = 0.5f + 0.5f * sin(t * 2f)
    if (powered) {
        drawRoundRect(
            Brush.sweepGradient(Rainbow, Offset(ox + boardW / 2 + cos(t * 3) * boardW * 0.3f, oy + boardH / 2)),
            topLeft = Offset(ox, oy), size = Size(boardW, boardH), cornerRadius = radius, style = Stroke(cell * 0.12f),
        )
    } else {
        drawRoundRect(
            Neon.Cyan.copy(alpha = 0.35f + 0.25f * pulse),
            topLeft = Offset(ox, oy), size = Size(boardW, boardH), cornerRadius = radius, style = Stroke(cell * 0.06f),
        )
    }
    // grid dots
    val dot = Neon.Cyan.copy(alpha = 0.12f)
    for (r in 1 until GameRules.ROWS) for (c in 1 until GameRules.COLS) {
        drawCircle(dot, cell * 0.035f, Offset(ox + c * cell, oy + r * cell))
    }
    // Wrap portals on the edges hint that Ar_B0T can wrap around.
    val portal = Neon.Magenta.copy(alpha = 0.25f + 0.2f * pulse)
    drawLine(portal, Offset(ox, oy + cell), Offset(ox, oy + boardH - cell), cell * 0.05f)
    drawLine(portal, Offset(ox + boardW, oy + cell), Offset(ox + boardW, oy + boardH - cell), cell * 0.05f)

    // --- mines ----------------------------------------------------------------
    val mineScale = easeOutBack(fx.minesAppear)
    for (i in 0 until GameRules.CELLS) {
        if (!engine.mines[i]) continue
        val c = px(FxState.centerOf(i))
        val wob = 0.85f + 0.15f * sin(t * 4f + i)
        val rr = cell * 0.27f * wob * mineScale
        if (rr <= 0f) continue
        drawCircle(Brush.radialGradient(listOf(Neon.Red.copy(alpha = 0.55f), Color.Transparent), c, rr * 2.2f), rr * 2.2f, c)
        rotate(t * 40f + i * 13f, c) {
            for (k in 0 until 8) {
                val a = k * 0.785f
                drawLine(Neon.Red, c + Offset(cos(a), sin(a)) * rr * 0.7f, c + Offset(cos(a), sin(a)) * rr * 1.35f, cell * 0.05f)
            }
        }
        drawCircle(Color(0xFF3A0610), rr, c)
        drawCircle(Neon.Red, rr, c, style = Stroke(cell * 0.05f))
        val blink = if (sin(t * 6f + i * 0.7f) > 0.3f) 1f else 0.25f
        drawCircle(Color(0xFFFFB3BB).copy(alpha = blink), rr * 0.28f, c)
    }

    // --- coin (data gem) --------------------------------------------------------
    if (engine.coin >= 0) {
        val c = px(FxState.centerOf(engine.coin))
        val s = easeOutBack(fx.coinAppear)
        val bob = sin(t * 3f) * cell * 0.05f
        val cc = c + Offset(0f, bob)
        drawCircle(Brush.radialGradient(listOf(Neon.Cyan.copy(alpha = 0.5f), Color.Transparent), cc, cell * 0.6f), cell * 0.6f, cc)
        scale(scaleX = abs(cos(t * 2.5f)).coerceAtLeast(0.15f) * s, scaleY = s, pivot = cc) {
            val h = cell * 0.32f
            val path = Path().apply {
                moveTo(cc.x, cc.y - h); lineTo(cc.x + h * 0.75f, cc.y - h * 0.2f)
                lineTo(cc.x, cc.y + h); lineTo(cc.x - h * 0.75f, cc.y - h * 0.2f); close()
            }
            drawPath(path, Brush.linearGradient(listOf(Color.White, Neon.Cyan, Color(0xFF1C64F2)), Offset(cc.x - h, cc.y - h), Offset(cc.x + h, cc.y + h)))
            drawPath(path, Color.White.copy(alpha = 0.8f), style = Stroke(cell * 0.03f))
        }
        sparkle(cc + Offset(cell * 0.22f, -cell * 0.25f), cell * 0.09f * (0.5f + 0.5f * sin(t * 5f)), Color.White)
    }

    // --- energy cell ---------------------------------------------------------------
    if (engine.energy >= 0) {
        val c = px(FxState.centerOf(engine.energy))
        val s = easeOutBack(fx.energyAppear)
        val r = cell * 0.26f * s
        drawCircle(Brush.radialGradient(listOf(Neon.Purple.copy(alpha = 0.65f), Color.Transparent), c, cell * 0.7f), cell * 0.7f, c)
        drawCircle(Brush.radialGradient(listOf(Color(0xFFE9D5FF), Neon.Purple, Color(0xFF4C1D95)), c, r), r, c)
        val bolt = Path().apply {
            moveTo(c.x + r * 0.15f, c.y - r * 0.75f); lineTo(c.x - r * 0.35f, c.y + r * 0.1f)
            lineTo(c.x + r * 0.02f, c.y + r * 0.1f); lineTo(c.x - r * 0.15f, c.y + r * 0.75f)
            lineTo(c.x + r * 0.4f, c.y - r * 0.15f); lineTo(c.x + r * 0.02f, c.y - r * 0.15f); close()
        }
        drawPath(bolt, Neon.Gold)
        for (k in 0 until 3) {
            val a = t * 3f + k * 2.094f
            drawCircle(Color(0xFFE9D5FF), cell * 0.04f, c + Offset(cos(a), sin(a) * 0.55f) * cell * 0.42f)
        }
    }

    // --- hunter bot ------------------------------------------------------------------
    if (engine.bot >= 0) {
        val c = px(fx.bot)
        val scared = powered
        val flicker = scared && engine.powerTicks < 150 && (t * 8f).toInt() % 2 == 0
        val body = when {
            flicker -> Color.White
            scared -> Color(0xFF3B5BFF)
            else -> Neon.Orange
        }
        val r = cell * 0.36f * easeOutBack(fx.botAppear)
        drawCircle(Brush.radialGradient(listOf(body.copy(alpha = 0.5f), Color.Transparent), c, cell * 0.8f), cell * 0.8f, c)
        // spiky crown
        rotate(t * 90f * (if (scared) -1 else 1), c) {
            for (k in 0 until 6) {
                val a = k * 1.047f
                drawCircle(body, cell * 0.06f, c + Offset(cos(a), sin(a)) * r * 1.1f)
            }
        }
        drawRoundRect(body, topLeft = c - Offset(r, r), size = Size(r * 2, r * 2), cornerRadius = CornerRadius(r * 0.55f))
        drawRoundRect(Color(0xFF1B0A04), topLeft = c - Offset(r * 0.75f, r * 0.45f), size = Size(r * 1.5f, r * 0.7f), cornerRadius = CornerRadius(r * 0.3f))
        // eyes track the player
        val look = fx.player - fx.bot
        val ang = atan2(look.y, look.x)
        val pupil = Offset(cos(ang), sin(ang)) * r * 0.1f
        val eyeColor = if (scared) Color.White else Color(0xFFFFE066)
        for (side in listOf(-1f, 1f)) {
            val e = c + Offset(side * r * 0.38f, -r * 0.1f)
            drawCircle(eyeColor, r * 0.18f, e)
            drawCircle(Color.Black, r * 0.08f, e + pupil)
        }
        if (!scared) {
            // angry brows
            drawLine(Color(0xFF1B0A04), c + Offset(-r * 0.62f, -r * 0.55f), c + Offset(-r * 0.15f, -r * 0.35f), r * 0.12f)
            drawLine(Color(0xFF1B0A04), c + Offset(r * 0.62f, -r * 0.55f), c + Offset(r * 0.15f, -r * 0.35f), r * 0.12f)
        }
    }

    // --- Ar_B0T -------------------------------------------------------------------------
    run {
        val c = px(fx.player)
        val invulnerable = engine.invulnTicks > 0
        val alpha = if (invulnerable && (t * 12f).toInt() % 2 == 0) 0.35f else 1f
        if (powered) {
            rotate(t * 240f, c) {
                drawCircle(Brush.sweepGradient(Rainbow, c), cell * 0.62f, c, style = Stroke(cell * 0.09f))
            }
            drawCircle(Brush.radialGradient(listOf(Neon.Gold.copy(alpha = 0.45f), Color.Transparent), c, cell * 1.1f), cell * 1.1f, c)
        }
        val look = fx.bot - fx.player
        drawRobot(c, cell, skin, t, alpha, Offset(cos(atan2(look.y, look.x)), sin(atan2(look.y, look.x))), fx.playerTilt)
    }

    // --- particles & floating text ---------------------------------------------------------
    for (p in fx.particles) {
        val a = (p.life / p.maxLife).coerceIn(0f, 1f)
        drawCircle(p.color.copy(alpha = a), p.size * cell * (0.4f + 0.6f * a), px(Offset(p.x, p.y)))
    }
    for (tx in fx.texts) {
        val a = (1f - tx.age / 1.1f).coerceIn(0f, 1f)
        val layout = measurer.measure(
            tx.text,
            TextStyle(color = tx.color.copy(alpha = a), fontSize = (cell * 0.32f).toSp(), fontWeight = FontWeight.Black, fontFamily = FontFamily.Monospace),
        )
        val p = px(Offset(tx.x, tx.y))
        drawText(layout, topLeft = p - Offset(layout.size.width / 2f, layout.size.height / 2f))
    }

    if (fx.flash > 0f) drawRect(fx.flashColor.copy(alpha = fx.flash * 0.35f))
}

/** Ar_B0T itself – also used for previews on the home and store screens. */
fun DrawScope.drawRobot(
    c: Offset,
    cell: Float,
    skin: SkinPalette,
    t: Float,
    alpha: Float = 1f,
    look: Offset = Offset(1f, 0f),
    tilt: Float = 0f,
) {
    val r = cell * 0.38f
    val bob = sin(t * 5f) * cell * 0.03f
    val cc = c + Offset(0f, bob)
    drawCircle(Brush.radialGradient(listOf(skin.glow.copy(alpha = 0.55f * alpha), Color.Transparent), cc, cell * 0.85f), cell * 0.85f, cc)
    rotate(tilt, cc) {
        translate(cc.x, cc.y) {
            // antenna
            drawLine(skin.body.copy(alpha = alpha), Offset(0f, -r), Offset(0f, -r * 1.45f), cell * 0.05f)
            val bulb = 0.6f + 0.4f * sin(t * 7f)
            drawCircle(skin.antenna.copy(alpha = alpha * bulb), cell * 0.075f, Offset(0f, -r * 1.5f))
            // head
            drawRoundRect(
                Brush.verticalGradient(listOf(skin.body.copy(alpha = alpha), skin.bodyDark.copy(alpha = alpha)), -r, r),
                topLeft = Offset(-r, -r), size = Size(r * 2, r * 2), cornerRadius = CornerRadius(r * 0.45f),
            )
            // visor
            drawRoundRect(skin.visor.copy(alpha = alpha), topLeft = Offset(-r * 0.78f, -r * 0.5f), size = Size(r * 1.56f, r * 0.85f), cornerRadius = CornerRadius(r * 0.3f))
            // eyes (blink every ~3 s), looking toward the hunter
            val blink = if ((t % 3.1f) < 0.12f) 0.15f else 1f
            val eo = look * r * 0.08f
            for (side in listOf(-1f, 1f)) {
                val e = Offset(side * r * 0.36f, -r * 0.08f) + eo
                drawCircle(skin.eyes.copy(alpha = alpha * 0.35f), r * 0.26f, e)
                drawOval(skin.eyes.copy(alpha = alpha), topLeft = e - Offset(r * 0.15f, r * 0.15f * blink), size = Size(r * 0.3f, r * 0.3f * blink))
            }
            // mouth grille
            for (k in -2..2) {
                drawLine(skin.visor.copy(alpha = alpha), Offset(k * r * 0.2f, r * 0.55f), Offset(k * r * 0.2f, r * 0.78f), r * 0.07f)
            }
            when (skin.id) {
                "skin_pirate" -> {
                    // bandana + eye patch
                    drawRoundRect(Neon.Red.copy(alpha = alpha), topLeft = Offset(-r * 1.02f, -r * 1.02f), size = Size(r * 2.04f, r * 0.42f), cornerRadius = CornerRadius(r * 0.2f))
                    drawCircle(Color.White.copy(alpha = alpha * 0.8f), r * 0.06f, Offset(-r * 0.4f, -r * 0.82f))
                    drawCircle(Color.White.copy(alpha = alpha * 0.8f), r * 0.06f, Offset(r * 0.3f, -r * 0.85f))
                    drawCircle(Color.Black.copy(alpha = alpha), r * 0.22f, Offset(r * 0.36f, -r * 0.08f))
                    drawLine(Color.Black.copy(alpha = alpha), Offset(-r, -r * 0.55f), Offset(r, r * 0.25f), r * 0.06f)
                }
                "skin_gold" -> {
                    sparkle(Offset(r * 0.8f, -r * 0.8f), r * 0.25f * (0.5f + 0.5f * sin(t * 4f)), Color.White.copy(alpha = alpha))
                    sparkle(Offset(-r * 0.9f, r * 0.6f), r * 0.18f * (0.5f + 0.5f * cos(t * 3.3f)), Color.White.copy(alpha = alpha))
                }
                "skin_neon" -> {
                    drawRoundRect(
                        Brush.sweepGradient(listOf(Neon.Cyan, Neon.Magenta, Neon.Cyan)),
                        topLeft = Offset(-r, -r), size = Size(r * 2, r * 2), cornerRadius = CornerRadius(r * 0.45f),
                        style = Stroke(r * 0.1f), alpha = alpha,
                    )
                }
            }
        }
    }
}

private fun DrawScope.sparkle(c: Offset, r: Float, color: Color) {
    if (r <= 0f) return
    drawLine(color, c - Offset(r, 0f), c + Offset(r, 0f), r * 0.25f)
    drawLine(color, c - Offset(0f, r), c + Offset(0f, r), r * 0.25f)
}

private fun easeOutBack(x: Float): Float {
    val c1 = 1.70158f
    val c3 = c1 + 1f
    val v = x - 1f
    return (1f + c3 * v * v * v + c1 * v * v).coerceAtLeast(0f)
}

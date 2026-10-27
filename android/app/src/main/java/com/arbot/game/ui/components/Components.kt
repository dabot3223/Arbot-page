package com.arbot.game.ui.components

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.arbot.game.ui.theme.Mono
import com.arbot.game.ui.theme.Neon
import kotlin.math.sin
import kotlin.random.Random

/** Glowing pill button with a press "squash" and a slow breathing glow. */
@Composable
fun NeonButton(
    text: String,
    color: Color,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    fontSize: TextUnit = 18.sp,
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.93f else 1f, tween(120), label = "press")
    val glow by rememberInfiniteTransition(label = "glow").animateFloat(
        0.35f, 0.75f, infiniteRepeatable(tween(1400, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "g",
    )
    val c = if (enabled) color else Neon.TextDim
    val shape = RoundedCornerShape(50)
    Box(
        modifier
            .scale(scale)
            .drawBehind {
                drawRoundRect(
                    brush = Brush.radialGradient(listOf(c.copy(alpha = glow * 0.55f), Color.Transparent),
                        center = center, radius = size.maxDimension * 0.75f),
                    topLeft = Offset(-12f, -12f),
                    size = androidx.compose.ui.geometry.Size(size.width + 24f, size.height + 24f),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(size.height),
                )
            }
            .background(Brush.horizontalGradient(listOf(c.copy(alpha = 0.22f), c.copy(alpha = 0.08f))), shape)
            .border(2.dp, c, shape)
            .clickable(interactionSource = interaction, indication = null, enabled = enabled, onClick = onClick)
            .padding(horizontal = 22.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = c, fontFamily = Mono, fontWeight = FontWeight.Bold, fontSize = fontSize, letterSpacing = 2.sp)
    }
}

@Composable
fun NeonPanel(modifier: Modifier = Modifier, color: Color = Neon.Cyan, content: @Composable () -> Unit) {
    val shape = RoundedCornerShape(16.dp)
    Box(
        modifier
            .background(Neon.Panel, shape)
            .border(1.dp, Brush.linearGradient(listOf(color.copy(alpha = 0.9f), color.copy(alpha = 0.15f))), shape)
            .padding(12.dp),
    ) { content() }
}

@Composable
fun GemCount(gems: Int, modifier: Modifier = Modifier, size: Dp = 18.dp) {
    val animated by animateFloatAsState(gems.toFloat(), tween(700), label = "gems")
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        GemIcon(Modifier.padding(end = 6.dp).size(size))
        Text(animated.toInt().toString(), color = Neon.Cyan, fontFamily = Mono, fontWeight = FontWeight.Bold, fontSize = 18.sp)
    }
}

@Composable
fun GemIcon(modifier: Modifier = Modifier) {
    val shine by rememberInfiniteTransition(label = "shine").animateFloat(
        0f, 1f, infiniteRepeatable(tween(1800, easing = LinearEasing)), label = "s",
    )
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        val path = androidx.compose.ui.graphics.Path().apply {
            moveTo(w * 0.5f, 0f); lineTo(w, h * 0.38f); lineTo(w * 0.5f, h); lineTo(0f, h * 0.38f); close()
        }
        drawPath(path, Brush.linearGradient(listOf(Neon.Cyan, Neon.Purple), Offset.Zero, Offset(w, h)))
        drawPath(path, Color.White.copy(alpha = 0.6f), style = Stroke(w * 0.06f))
        val sx = w * (shine * 1.6f - 0.3f)
        drawLine(Color.White.copy(alpha = 0.7f), Offset(sx, h * 0.1f), Offset(sx - w * 0.3f, h * 0.9f), w * 0.08f)
    }
}

/** Parallax starfield + scrolling synthwave grid used behind every screen. */
@Composable
fun SpaceBackground(modifier: Modifier = Modifier, tint: Color = Neon.Magenta) {
    val stars = remember {
        val r = Random(42)
        List(140) { Triple(r.nextFloat(), r.nextFloat(), r.nextFloat()) }
    }
    val t by rememberInfiniteTransition(label = "bg").animateFloat(
        0f, 1f, infiniteRepeatable(tween(20_000, easing = LinearEasing)), label = "t",
    )
    Canvas(modifier.fillMaxSize()) {
        drawRect(Brush.verticalGradient(listOf(Neon.Space, Color(0xFF0B1030), Color(0xFF1A0B2E))))
        for ((x, y, z) in stars) {
            val px = ((x - t * (0.2f + z * 0.6f)) % 1f + 1f) % 1f * size.width
            val tw = 0.5f + 0.5f * sin((t * 40f + x * 50f) * (1f + z))
            drawCircle(Color.White.copy(alpha = 0.25f + 0.6f * z * tw), radius = 0.8f + z * 2.2f, center = Offset(px, y * size.height * 0.62f))
        }
        // Perspective grid floor.
        val horizon = size.height * 0.64f
        val floor = size.height - horizon
        val gridColor = tint.copy(alpha = 0.35f)
        for (i in 0..12) {
            val p = ((i / 12f) + t * 6f) % 1f
            val y = horizon + floor * p * p
            drawLine(gridColor.copy(alpha = 0.08f + 0.35f * p), Offset(0f, y), Offset(size.width, y), 1.5f)
        }
        val cx = size.width / 2
        for (i in -10..10) {
            drawLine(gridColor.copy(alpha = 0.25f), Offset(cx + i * size.width * 0.02f, horizon), Offset(cx + i * size.width * 0.16f, size.height), 1.5f)
        }
        drawRect(Brush.verticalGradient(listOf(tint.copy(alpha = 0.25f), Color.Transparent), startY = horizon - 40f, endY = horizon + 60f),
            topLeft = Offset(0f, horizon - 40f), size = androidx.compose.ui.geometry.Size(size.width, 100f))
    }
}

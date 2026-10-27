package com.arbot.game.ui.game

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.arbot.core.engine.Direction
import com.arbot.core.engine.GameRules
import com.arbot.game.ui.components.GemCount
import com.arbot.game.ui.components.NeonButton
import com.arbot.game.ui.components.NeonPanel
import com.arbot.game.ui.components.SpaceBackground
import com.arbot.game.ui.theme.Mono
import com.arbot.game.ui.theme.Neon
import kotlin.math.abs
import kotlin.math.sin

@Composable
fun GameScreen(
    vm: GameViewModel,
    skin: SkinPalette,
    showDpad: Boolean,
    onPlayAgain: () -> Unit,
    onHome: () -> Unit,
) {
    val measurer = rememberTextMeasurer()
    val focus = remember { FocusRequester() }

    // Fixed-step simulation driven by the display's frame clock.
    LaunchedEffect(vm) {
        var last = -1L
        while (true) {
            withFrameNanos { now ->
                val dt = if (last < 0) 0f else (now - last) / 1_000_000_000f
                last = now
                vm.onFrame(dt)
            }
        }
    }
    // Auto-pause when the app goes to the background.
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val obs = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_PAUSE) vm.pause() }
        lifecycle.addObserver(obs)
        onDispose { lifecycle.removeObserver(obs) }
    }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }

    val powered = (vm.engine?.powerTicks ?: 0) > 0
    Box(
        Modifier
            .fillMaxSize()
            .focusRequester(focus)
            .focusable()
            .onKeyEvent { ev ->
                if (ev.type != KeyEventType.KeyDown) return@onKeyEvent false
                val dir = when (ev.key) {
                    Key.DirectionUp, Key.W -> Direction.UP
                    Key.DirectionDown, Key.S -> Direction.DOWN
                    Key.DirectionLeft, Key.A -> Direction.LEFT
                    Key.DirectionRight, Key.D -> Direction.RIGHT
                    Key.P, Key.Escape -> {
                        vm.pause(); return@onKeyEvent true
                    }
                    else -> null
                }
                dir?.let(vm::move)
                dir != null
            },
    ) {
        SpaceBackground(tint = if (powered) Neon.Gold else Neon.Magenta)

        Column(Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 8.dp)) {
            Hud(vm, onPause = vm::pause)
            Spacer(Modifier.height(6.dp))
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .pointerInput(Unit) {
                        var acc = Offset.Zero
                        val threshold = 22.dp.toPx()
                        detectDragGestures(
                            onDragStart = { acc = Offset.Zero },
                            onDrag = { change, drag ->
                                change.consume()
                                acc += drag
                                if (acc.getDistance() > threshold) {
                                    vm.move(dominant(acc))
                                    acc = Offset.Zero
                                }
                            },
                        )
                    }
                    .pointerInput(Unit) {
                        // Tap: step toward the tapped point.
                        detectTapGestures { pos ->
                            val e = vm.engine ?: return@detectTapGestures
                            val cell = minOf(size.width.toFloat() / GameRules.COLS, size.height.toFloat() / GameRules.ROWS)
                            val ox = (size.width - cell * GameRules.COLS) / 2
                            val oy = (size.height - cell * GameRules.ROWS) / 2
                            val target = Offset((pos.x - ox) / cell, (pos.y - oy) / cell)
                            val d = target - FxState.centerOf(e.player)
                            if (abs(d.x) > 0.5f || abs(d.y) > 0.5f) vm.move(dominant(d))
                        }
                    },
            ) {
                Canvas(Modifier.fillMaxSize()) {
                    vm.frame // read => redraw every frame
                    val e = vm.engine ?: return@Canvas
                    drawGame(e, vm.fx, skin, measurer)
                }
            }
        }

        if (showDpad && vm.phase == GamePhase.Playing) {
            DPad(Modifier.align(Alignment.BottomEnd).padding(16.dp), vm::move)
        }

        Overlays(vm, onPlayAgain, onHome)
    }
}

private fun dominant(d: Offset): Direction =
    if (abs(d.x) > abs(d.y)) {
        if (d.x > 0) Direction.RIGHT else Direction.LEFT
    } else {
        if (d.y > 0) Direction.DOWN else Direction.UP
    }

@Composable
private fun Hud(vm: GameViewModel, onPause: () -> Unit) {
    vm.frame
    val e = vm.engine
    val lives = e?.lives ?: 0
    val score by animateFloatAsState((e?.score ?: 0).toFloat(), tween(400), label = "score")
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            repeat((e?.startLives ?: GameRules.BASE_LIVES)) { i -> Heart(alive = i < lives) }
        }
        Spacer(Modifier.width(16.dp))
        // Energy pips
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("ENERGY ", color = Neon.TextDim, fontFamily = Mono, fontSize = 11.sp)
            repeat(GameRules.ENERGY_FOR_POWER) { i ->
                val filled = i < (e?.energyCount ?: 0)
                Box(
                    Modifier.padding(horizontal = 2.dp).size(12.dp)
                        .background(if (filled) Neon.Purple else Color.Transparent, CircleShape)
                        .border(1.5.dp, Neon.Purple, CircleShape),
                )
            }
        }
        val power = e?.powerTicks ?: 0
        if (power > 0) {
            Spacer(Modifier.width(12.dp))
            Box(Modifier.width(90.dp).height(10.dp).background(Neon.Gold.copy(alpha = 0.2f), RoundedCornerShape(5.dp))) {
                Box(
                    Modifier.fillMaxWidth(power / GameRules.POWER_TICKS.toFloat()).height(10.dp)
                        .background(Neon.Gold, RoundedCornerShape(5.dp)),
                )
            }
            Text(" POWER!", color = Neon.Gold, fontFamily = Mono, fontWeight = FontWeight.Bold, fontSize = 12.sp)
        }
        Spacer(Modifier.weight(1f))
        Text(
            if (vm.ranked) "RANKED" else "PRACTICE",
            color = if (vm.ranked) Neon.Green else Neon.TextDim,
            fontFamily = Mono, fontSize = 11.sp,
            modifier = Modifier.border(1.dp, if (vm.ranked) Neon.Green else Neon.TextDim, RoundedCornerShape(6.dp))
                .padding(horizontal = 6.dp, vertical = 2.dp),
        )
        Spacer(Modifier.width(12.dp))
        Text("SCORE ", color = Neon.TextDim, fontFamily = Mono, fontSize = 12.sp)
        Text(score.toInt().toString().padStart(5, '0'), color = Neon.Text, fontFamily = Mono, fontWeight = FontWeight.Black, fontSize = 22.sp)
        Spacer(Modifier.width(12.dp))
        Box(
            Modifier.size(36.dp).border(1.5.dp, Neon.Cyan, CircleShape).clickable(onClick = onPause),
            contentAlignment = Alignment.Center,
        ) { Text("II", color = Neon.Cyan, fontFamily = Mono, fontWeight = FontWeight.Bold) }
    }
}

@Composable
private fun Heart(alive: Boolean) {
    val s by animateFloatAsState(if (alive) 1f else 0.6f, tween(300), label = "heart")
    val beat by rememberInfiniteTransition(label = "beat").animateFloat(
        1f, 1.12f, infiniteRepeatable(tween(600, easing = LinearEasing), RepeatMode.Reverse), label = "b",
    )
    Canvas(Modifier.padding(horizontal = 2.dp).size(20.dp).graphicsLayer { scaleX = s * (if (alive) beat else 1f); scaleY = scaleX }) {
        val w = size.width
        val h = size.height
        val path = Path().apply {
            moveTo(w / 2, h * 0.9f)
            cubicTo(-w * 0.2f, h * 0.45f, w * 0.15f, -h * 0.1f, w / 2, h * 0.28f)
            cubicTo(w * 0.85f, -h * 0.1f, w * 1.2f, h * 0.45f, w / 2, h * 0.9f)
            close()
        }
        drawPath(path, if (alive) Neon.Red else Neon.TextDim.copy(alpha = 0.35f))
    }
}

@Composable
private fun DPad(modifier: Modifier, onMove: (Direction) -> Unit) {
    @Composable
    fun PadKey(label: String, d: Direction) {
        Box(
            Modifier.size(46.dp).background(Neon.Panel, RoundedCornerShape(12.dp))
                .border(1.dp, Neon.Cyan.copy(alpha = 0.6f), RoundedCornerShape(12.dp))
                .pointerInput(d) { detectTapGestures(onPress = { onMove(d) }) },
            contentAlignment = Alignment.Center,
        ) { Text(label, color = Neon.Cyan, fontSize = 18.sp) }
    }
    Column(modifier.graphicsLayer { alpha = 0.85f }, horizontalAlignment = Alignment.CenterHorizontally) {
        PadKey("▲", Direction.UP)
        Row(horizontalArrangement = Arrangement.spacedBy(46.dp)) {
            PadKey("◀", Direction.LEFT)
            PadKey("▶", Direction.RIGHT)
        }
        PadKey("▼", Direction.DOWN)
    }
}

@Composable
private fun Overlays(vm: GameViewModel, onPlayAgain: () -> Unit, onHome: () -> Unit) {
    val phase = vm.phase
    val dim = phase != GamePhase.Playing
    AnimatedVisibility(dim, enter = fadeIn(), exit = fadeOut()) {
        Box(Modifier.fillMaxSize().background(Color(0xB3070B1A)).pointerInput(Unit) { detectTapGestures { } })
    }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        when (phase) {
            GamePhase.Idle, GamePhase.Starting -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator(color = Neon.Green)
                Spacer(Modifier.height(12.dp))
                Text("BOOTING Ar_B0T…", color = Neon.Green, fontFamily = Mono, fontSize = 18.sp)
            }
            GamePhase.Paused -> NeonPanel {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    GlitchText("PAUSED", Neon.Cyan, 32)
                    NeonButton("RESUME", Neon.Green, onClick = vm::resume)
                    NeonButton("END RUN", Neon.Red, onClick = vm::endRun)
                }
            }
            GamePhase.Submitting -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator(color = Neon.Cyan)
                Spacer(Modifier.height(12.dp))
                Text("VERIFYING RUN…", color = Neon.Cyan, fontFamily = Mono, fontSize = 16.sp)
            }
            is GamePhase.Finished -> AnimatedVisibility(true, enter = scaleIn() + fadeIn()) {
                GameOverCard(phase.outcome, onPlayAgain, onHome)
            }
            GamePhase.Playing -> Unit
        }
    }
}

@Composable
private fun GameOverCard(o: Outcome, onPlayAgain: () -> Unit, onHome: () -> Unit) {
    var target by remember { mutableStateOf(0f) }
    LaunchedEffect(o) { target = o.score.toFloat() }
    val shown by animateFloatAsState(target, tween(1200), label = "count")
    NeonPanel(color = Neon.Magenta) {
        Column(
            Modifier.padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            GlitchText("GAME OVER", Neon.Magenta, 36)
            Text(shown.toInt().toString(), color = Neon.Text, fontFamily = Mono, fontWeight = FontWeight.Black, fontSize = 44.sp)
            if (o.newBest) {
                val pulse by rememberInfiniteTransition(label = "nb").animateFloat(
                    0.9f, 1.15f, infiniteRepeatable(tween(500), RepeatMode.Reverse), label = "p",
                )
                Text(
                    "★ NEW BEST ★", color = Neon.Gold, fontFamily = Mono, fontWeight = FontWeight.Bold, fontSize = 18.sp,
                    modifier = Modifier.graphicsLayer { scaleX = pulse; scaleY = pulse },
                )
            } else if (o.bestScore != null) {
                Text("BEST ${o.bestScore}", color = Neon.TextDim, fontFamily = Mono)
            }
            when {
                o.error != null -> Text(o.error, color = Neon.Red, fontFamily = Mono, fontSize = 13.sp)
                o.ranked -> Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("+", color = Neon.Cyan, fontFamily = Mono, fontSize = 18.sp)
                    GemCount(o.gemsEarned)
                }
                else -> Text("Practice run – connect to play ranked", color = Neon.TextDim, fontFamily = Mono, fontSize = 12.sp)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                NeonButton("PLAY AGAIN", Neon.Green, onClick = onPlayAgain)
                NeonButton("HOME", Neon.Cyan, onClick = onHome)
            }
        }
    }
}

/** Title text with a chromatic-aberration "glitch" jitter. */
@Composable
fun GlitchText(text: String, color: Color, sizeSp: Int, modifier: Modifier = Modifier) {
    val t by rememberInfiniteTransition(label = "glitch").animateFloat(
        0f, 100f, infiniteRepeatable(tween(100_000, easing = LinearEasing)), label = "t",
    )
    val measurer = rememberTextMeasurer()
    val style = androidx.compose.ui.text.TextStyle(fontFamily = Mono, fontWeight = FontWeight.Black, fontSize = sizeSp.sp, letterSpacing = 3.sp)
    val layout = remember(text, sizeSp) { measurer.measure(text, style) }
    val density = androidx.compose.ui.platform.LocalDensity.current
    Canvas(
        modifier.size(
            with(density) { (layout.size.width + 12).toDp() },
            with(density) { layout.size.height.toDp() },
        ),
    ) {
        val glitch = sin(t * 37f) > 0.92f
        val j = if (glitch) 5f else 1.5f
        drawText(measurer, text, Offset(6f - j, 0f), style.copy(color = Neon.Magenta.copy(alpha = 0.7f)))
        drawText(measurer, text, Offset(6f + j, 0f), style.copy(color = Neon.Cyan.copy(alpha = 0.7f)))
        drawText(measurer, text, Offset(6f, 0f), style.copy(color = color))
    }
}

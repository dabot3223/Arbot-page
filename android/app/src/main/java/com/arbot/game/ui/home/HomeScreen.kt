package com.arbot.game.ui.home

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.arbot.core.api.ProfileBundle
import com.arbot.game.ui.AppViewModel
import com.arbot.game.ui.Screen
import com.arbot.game.ui.components.GemCount
import com.arbot.game.ui.components.NeonButton
import com.arbot.game.ui.components.NeonPanel
import com.arbot.game.ui.components.SpaceBackground
import com.arbot.game.ui.game.Skins
import com.arbot.game.ui.game.drawRobot
import com.arbot.game.ui.theme.Mono
import com.arbot.game.ui.theme.Neon
import kotlin.math.sin

@Composable
fun HomeScreen(app: AppViewModel, profile: ProfileBundle?, onPlay: () -> Unit) {
    val t by rememberInfiniteTransition(label = "home").animateFloat(
        0f, 1000f, infiniteRepeatable(tween(1_000_000, easing = LinearEasing)), label = "t",
    )
    var renaming by remember { mutableStateOf(false) }
    val player = profile?.player
    val skin = Skins.of(player?.equippedSkin)

    Box(Modifier.fillMaxSize()) {
        SpaceBackground()
        Row(Modifier.fillMaxSize().padding(24.dp), verticalAlignment = Alignment.CenterVertically) {
            // --- left: hero -----------------------------------------------------
            Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    "Ar_B0T",
                    style = TextStyle(
                        brush = Brush.linearGradient(
                            listOf(Neon.Green, Neon.Cyan, Neon.Magenta, Neon.Green),
                            start = Offset(sin(t) * 300f, 0f),
                            end = Offset(600f + sin(t) * 300f, 200f),
                        ),
                        fontFamily = Mono,
                        fontWeight = FontWeight.Black,
                        fontSize = 64.sp,
                        letterSpacing = 4.sp,
                    ),
                )
                Text("DODGE · COLLECT · OVERLOAD", color = Neon.TextDim, fontFamily = Mono, fontSize = 13.sp, letterSpacing = 3.sp)
                Spacer(Modifier.height(8.dp))
                Canvas(Modifier.size(150.dp)) {
                    drawRobot(center, size.minDimension * 0.9f, skin, t * 3f)
                    // orbiting data gems
                    for (k in 0 until 3) {
                        val a = t * 2f + k * 2.094f
                        drawCircle(Neon.Cyan.copy(alpha = 0.8f), 6f, center + Offset(kotlin.math.cos(a) * size.width * 0.45f, sin(a) * size.height * 0.18f))
                    }
                }
            }

            // --- right: menu --------------------------------------------------------
            Column(
                Modifier.width(320.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                NeonPanel(color = Neon.Green) {
                    Column(Modifier.width(280.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                player?.displayName ?: if (app.syncing) "Connecting…" else "Guest",
                                color = Neon.Text, fontFamily = Mono, fontWeight = FontWeight.Bold, fontSize = 18.sp,
                                modifier = Modifier.weight(1f).clickable(enabled = player != null) { renaming = true },
                            )
                            if (player?.premium == true) {
                                Text("PREMIUM", color = Neon.Gold, fontFamily = Mono, fontSize = 11.sp,
                                    modifier = Modifier.border(1.dp, Neon.Gold, RoundedCornerShape(6.dp)).padding(horizontal = 6.dp, vertical = 2.dp))
                            }
                        }
                        Spacer(Modifier.height(6.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("BEST ${player?.bestScore ?: 0}", color = Neon.TextDim, fontFamily = Mono, modifier = Modifier.weight(1f))
                            GemCount(player?.gems ?: 0)
                        }
                    }
                }

                BoosterRow(app, profile)

                NeonButton("PLAY", Neon.Green, fontSize = 26.sp) { app.click(); onPlay() }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    NeonButton("STORE", Neon.Magenta, fontSize = 15.sp) { app.click(); app.screen = Screen.STORE }
                    NeonButton("RANKS", Neon.Cyan, fontSize = 15.sp) { app.click(); app.screen = Screen.LEADERBOARD }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Toggle(if (app.soundOn) "♪ ON" else "♪ OFF", app.soundOn, app::toggleSound)
                    Toggle(if (app.showDpad) "D-PAD ON" else "D-PAD OFF", app.showDpad, app::toggleDpad)
                }
            }
        }
    }

    if (renaming && player != null) {
        var name by remember { mutableStateOf(player.displayName) }
        AlertDialog(
            onDismissRequest = { renaming = false },
            title = { Text("Pilot name", fontFamily = Mono) },
            text = {
                OutlinedTextField(
                    value = name,
                    onValueChange = { v -> name = v.filter { it.isLetterOrDigit() || it == '_' || it == ' ' }.take(16) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                )
            },
            confirmButton = {
                TextButton(onClick = { app.rename(name.trim()); renaming = false }) { Text("SAVE", color = Neon.Green) }
            },
            dismissButton = { TextButton(onClick = { renaming = false }) { Text("CANCEL", color = Neon.TextDim) } },
            containerColor = Neon.SpaceLight,
        )
    }
}

@Composable
private fun BoosterRow(app: AppViewModel, profile: ProfileBundle?) {
    val inv = profile?.inventory.orEmpty().associate { it.itemId to it.quantity }
    val lives = inv["booster_extra_life"] ?: 0
    val power = inv["booster_power_start"] ?: 0
    if (lives == 0 && power == 0) return
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (lives > 0) Toggle("♥ +1 LIFE ×$lives", app.useExtraLife) { app.useExtraLife = !app.useExtraLife }
        if (power > 0) Toggle("⚡ POWER ×$power", app.usePowerStart) { app.usePowerStart = !app.usePowerStart }
    }
}

@Composable
private fun Toggle(label: String, on: Boolean, onClick: () -> Unit) {
    val c = if (on) Neon.Green else Neon.TextDim
    Text(
        label,
        color = c,
        fontFamily = Mono,
        fontSize = 12.sp,
        modifier = Modifier
            .background(c.copy(alpha = 0.1f), RoundedCornerShape(8.dp))
            .border(1.dp, c, RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp),
    )
}

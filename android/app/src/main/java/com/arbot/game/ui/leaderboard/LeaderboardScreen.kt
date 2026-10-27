package com.arbot.game.ui.leaderboard

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.arbot.core.api.LeaderboardEntry
import com.arbot.game.AppContainer
import com.arbot.game.ui.components.NeonButton
import com.arbot.game.ui.components.SpaceBackground
import com.arbot.game.ui.theme.Mono
import com.arbot.game.ui.theme.Neon
import kotlinx.coroutines.delay

@Composable
fun LeaderboardScreen(container: AppContainer, onBack: () -> Unit) {
    var entries by remember { mutableStateOf<List<LeaderboardEntry>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        try {
            entries = container.repository.leaderboard().entries
        } catch (_: Exception) {
            error = "Leaderboard needs an internet connection"
        }
    }

    Box(Modifier.fillMaxSize()) {
        SpaceBackground(tint = Neon.Cyan)
        Column(Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                NeonButton("◀ BACK", Neon.Cyan, fontSize = 13.sp, onClick = onBack)
                Spacer(Modifier.width(16.dp))
                Text("TOP PILOTS", color = Neon.Cyan, fontFamily = Mono, fontWeight = FontWeight.Black, fontSize = 26.sp)
            }
            Spacer(Modifier.height(12.dp))
            when {
                error != null -> Text(error!!, color = Neon.TextDim, fontFamily = Mono)
                entries == null -> Shimmer()
                entries!!.isEmpty() -> Text("No scores yet – be the first!", color = Neon.TextDim, fontFamily = Mono)
                else -> LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    itemsIndexed(entries!!, key = { _, e -> e.rank }) { index, e -> EntryRow(e, index) }
                }
            }
        }
    }
}

@Composable
private fun EntryRow(e: LeaderboardEntry, index: Int) {
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        delay(index * 40L)
        visible = true
    }
    val medal = when (e.rank) {
        1 -> Neon.Gold
        2 -> androidx.compose.ui.graphics.Color(0xFFD7E1F5)
        3 -> Neon.Orange
        else -> Neon.TextDim
    }
    val color = if (e.isMe) Neon.Green else Neon.Text
    Row(
        Modifier
            .fillMaxWidth()
            .graphicsLayer {
                alpha = if (visible) 1f else 0f
                translationX = if (visible) 0f else 60f
            }
            .background(if (e.isMe) Neon.Green.copy(alpha = 0.12f) else Neon.Panel, RoundedCornerShape(12.dp))
            .border(1.dp, if (e.isMe) Neon.Green else medal.copy(alpha = 0.4f), RoundedCornerShape(12.dp))
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("#${e.rank}".padEnd(5), color = medal, fontFamily = Mono, fontWeight = FontWeight.Black, fontSize = 18.sp)
        Text(e.displayName + if (e.isMe) "  (you)" else "", color = color, fontFamily = Mono, fontSize = 16.sp, modifier = Modifier.weight(1f))
        Text(e.bestScore.toString(), color = color, fontFamily = Mono, fontWeight = FontWeight.Bold, fontSize = 18.sp)
    }
}

@Composable
private fun Shimmer() {
    val a by rememberInfiniteTransition(label = "shimmer").animateFloat(
        0.2f, 0.6f, infiniteRepeatable(tween(700), RepeatMode.Reverse), label = "a",
    )
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        repeat(6) {
            Box(Modifier.fillMaxWidth().height(44.dp).alpha(a).background(Neon.Panel, RoundedCornerShape(12.dp)))
        }
    }
}

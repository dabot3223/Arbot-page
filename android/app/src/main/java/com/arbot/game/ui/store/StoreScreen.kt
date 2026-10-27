package com.arbot.game.ui.store

import android.view.WindowManager
import androidx.activity.compose.LocalActivity
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.arbot.core.api.ProfileBundle
import com.arbot.game.ui.AppViewModel
import com.arbot.game.ui.Screen
import com.arbot.game.ui.components.GemCount
import com.arbot.game.ui.components.GemIcon
import com.arbot.game.ui.components.NeonButton
import com.arbot.game.ui.components.NeonPanel
import com.arbot.game.ui.components.SpaceBackground
import com.arbot.game.ui.game.Skins
import com.arbot.game.ui.game.drawRobot
import com.arbot.game.ui.theme.Mono
import com.arbot.game.ui.theme.Neon
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

@Composable
fun StoreScreen(app: AppViewModel, profile: ProfileBundle?) {
    val activity = LocalActivity.current
    // Keep payment screens out of screenshots, screen recordings and the recents thumbnail.
    DisposableEffect(activity) {
        activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        onDispose { activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE) }
    }
    LaunchedEffect(Unit) { app.refresh() }

    val products by app.products.collectAsStateWithLifecycle()
    val busy by app.billingBusy.collectAsStateWithLifecycle()
    val player = profile?.player
    val owned = profile?.inventory.orEmpty().associate { it.itemId to it.quantity }

    Box(Modifier.fillMaxSize()) {
        SpaceBackground(tint = Neon.Purple)
        Column(Modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                NeonButton("◀ BACK", Neon.Cyan, fontSize = 13.sp) { app.screen = Screen.HOME }
                Spacer(Modifier.width(16.dp))
                Text("STORE", color = Neon.Magenta, fontFamily = Mono, fontWeight = FontWeight.Black, fontSize = 26.sp)
                Spacer(Modifier.weight(1f))
                if (busy) CircularProgressIndicator(Modifier.size(22.dp), color = Neon.Cyan, strokeWidth = 2.dp)
                Spacer(Modifier.width(12.dp))
                NeonPanel { GemCount(player?.gems ?: 0) }
            }
            Spacer(Modifier.height(10.dp))

            if (profile == null) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        if (app.syncing) "Connecting securely…" else "The store needs an internet connection",
                        color = Neon.TextDim, fontFamily = Mono,
                    )
                }
                return@Column
            }

            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Section("GEM PACKS")
                LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(profile.iapProducts, key = { it.productId }) { p ->
                        val details = products[p.productId]
                        val price = details?.oneTimePurchaseOfferDetails?.formattedPrice
                        val premium = p.grantsPremium
                        val ownedPremium = premium && player?.premium == true
                        ProductCard(
                            title = if (premium) "PREMIUM PASS" else "${p.gems} GEMS",
                            subtitle = if (premium) "+1 life · 2× gems · all skins · ${p.gems} gems" else packLabel(p.productId),
                            price = when {
                                ownedPremium -> "OWNED"
                                price != null -> price
                                else -> "N/A"
                            },
                            accent = if (premium) Neon.Gold else Neon.Cyan,
                            highlight = premium,
                            enabled = details != null && !ownedPremium && !busy,
                        ) {
                            app.click()
                            activity?.let { app.buyProduct(it, p.productId) }
                        }
                    }
                }

                Section("SKINS")
                LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(Skins.all, key = { it.id }) { skin ->
                        val catalog = profile.catalog.firstOrNull { it.itemId == skin.id }
                        val has = skin.id == "classic" || (owned[skin.id] ?: 0) > 0 || player?.premium == true
                        val equipped = player?.equippedSkin == skin.id
                        SkinCard(skin.name, skin.id, equipped) {
                            when {
                                equipped -> Unit
                                has -> NeonButton("EQUIP", Neon.Green, fontSize = 12.sp) { app.click(); app.equip(skin.id) }
                                catalog != null -> NeonButton("${catalog.priceGems} ◆", Neon.Magenta, fontSize = 12.sp) { app.click(); app.buyItem(skin.id) }
                            }
                        }
                    }
                }

                Section("BOOSTERS")
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    profile.catalog.filter { it.kind == "booster" }.forEach { item ->
                        NeonPanel(color = Neon.Purple) {
                            Column(Modifier.width(220.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(item.displayName, color = Neon.Text, fontFamily = Mono, fontWeight = FontWeight.Bold)
                                Text(
                                    if (item.itemId == "booster_extra_life") "Start your next run with one extra life" else "Start powered-up for 5 seconds",
                                    color = Neon.TextDim, fontFamily = Mono, fontSize = 11.sp,
                                )
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text("OWNED ×${owned[item.itemId] ?: 0}", color = Neon.Purple, fontFamily = Mono, fontSize = 12.sp, modifier = Modifier.weight(1f))
                                    NeonButton("${item.priceGems} ◆", Neon.Magenta, fontSize = 12.sp) { app.click(); app.buyItem(item.itemId) }
                                }
                            }
                        }
                    }
                }
                Text(
                    "Purchases are verified with Google Play on our servers before anything is credited.",
                    color = Neon.TextDim, fontFamily = Mono, fontSize = 10.sp,
                )
                Spacer(Modifier.height(24.dp))
            }
        }
        Confetti(app.celebrate)
    }
}

private fun packLabel(id: String) = when (id) {
    "gems_pack_small" -> "A handful of gems"
    "gems_pack_medium" -> "Popular · bonus gems"
    "gems_pack_large" -> "Best value"
    else -> ""
}

@Composable
private fun Section(title: String) {
    Text(title, color = Neon.TextDim, fontFamily = Mono, fontSize = 13.sp, letterSpacing = 3.sp)
}

@Composable
private fun ProductCard(
    title: String,
    subtitle: String,
    price: String,
    accent: Color,
    highlight: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val t by rememberInfiniteTransition(label = "card").animateFloat(
        0f, 6.2832f, infiniteRepeatable(tween(4000, easing = LinearEasing)), label = "t",
    )
    val shape = RoundedCornerShape(18.dp)
    val border = if (highlight) {
        Brush.sweepGradient(listOf(Neon.Gold, Neon.Magenta, Neon.Cyan, Neon.Gold), Offset(100f + cos(t) * 60f, 80f + sin(t) * 40f))
    } else {
        Brush.linearGradient(listOf(accent, accent.copy(alpha = 0.2f)))
    }
    Box(
        Modifier
            .width(210.dp)
            .border(if (highlight) 3.dp else 1.5.dp, border, shape)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(14.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                GemIcon(Modifier.size(26.dp))
                Spacer(Modifier.width(8.dp))
                Text(title, color = accent, fontFamily = Mono, fontWeight = FontWeight.Black, fontSize = 17.sp)
            }
            Text(subtitle, color = Neon.TextDim, fontFamily = Mono, fontSize = 11.sp, minLines = 2)
            Box(
                Modifier.fillMaxWidth().border(1.dp, if (enabled) accent else Neon.TextDim, RoundedCornerShape(50)).padding(vertical = 6.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(price, color = if (enabled) accent else Neon.TextDim, fontFamily = Mono, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun SkinCard(name: String, skinId: String, equipped: Boolean, action: @Composable () -> Unit) {
    val t by rememberInfiniteTransition(label = "skin").animateFloat(
        0f, 100f, infiniteRepeatable(tween(100_000, easing = LinearEasing)), label = "t",
    )
    NeonPanel(color = if (equipped) Neon.Green else Neon.Cyan) {
        Column(Modifier.width(150.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Canvas(Modifier.size(90.dp)) { drawRobot(center, size.minDimension, Skins.of(skinId), t * 3f) }
            Text(name, color = Neon.Text, fontFamily = Mono, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            if (equipped) Text("EQUIPPED", color = Neon.Green, fontFamily = Mono, fontSize = 12.sp) else action()
        }
    }
}

/** Celebration burst whenever [trigger] changes. */
@Composable
private fun Confetti(trigger: Int) {
    if (trigger == 0) return
    val pieces = remember(trigger) {
        val r = Random(trigger)
        List(90) { floatArrayOf(r.nextFloat(), r.nextFloat() * 0.3f, (r.nextFloat() - 0.5f) * 0.6f, r.nextFloat() * 360f, r.nextInt(5).toFloat()) }
    }
    val colors = listOf(Neon.Cyan, Neon.Magenta, Neon.Gold, Neon.Green, Neon.Purple)
    val progress = remember(trigger) { androidx.compose.animation.core.Animatable(0f) }
    LaunchedEffect(trigger) { progress.animateTo(1f, tween(1800)) }
    if (progress.value >= 1f) return
    Canvas(Modifier.fillMaxSize()) {
        val p = progress.value
        for (piece in pieces) {
            val x = (piece[0] + piece[2] * p) * size.width
            val y = (-0.1f + piece[1] + p * 1.2f) * size.height
            val c = colors[piece[4].toInt()]
            val rot = piece[3] + p * 720f
            val w = 10f * kotlin.math.abs(cos(Math.toRadians(rot.toDouble())).toFloat()) + 2f
            drawRect(c.copy(alpha = 1f - p * 0.6f), topLeft = Offset(x, y), size = androidx.compose.ui.geometry.Size(w, 14f))
        }
    }
}

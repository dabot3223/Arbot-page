package com.arbot.game.ui

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.arbot.core.api.ApiException
import com.arbot.game.AppContainer
import com.arbot.game.audio.Sfx
import com.arbot.game.billing.BillingEvent
import com.arbot.game.ui.components.NeonPanel
import com.arbot.game.ui.game.GameScreen
import com.arbot.game.ui.game.GameViewModel
import com.arbot.game.ui.game.Skins
import com.arbot.game.ui.home.HomeScreen
import com.arbot.game.ui.leaderboard.LeaderboardScreen
import com.arbot.game.ui.store.StoreScreen
import com.arbot.game.ui.theme.Mono
import com.arbot.game.ui.theme.Neon
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

enum class Screen { HOME, GAME, STORE, LEADERBOARD }

class AppViewModel(private val c: AppContainer, context: Context) : ViewModel() {
    private val settings = context.getSharedPreferences("arbot_settings", Context.MODE_PRIVATE)

    var screen by mutableStateOf(Screen.HOME)
    val profile = c.repository.profile
    val products = c.billing.products
    val billingBusy = c.billing.busy
    val online get() = c.repository.isOnlineCapable

    var syncing by mutableStateOf(false)
        private set
    var message by mutableStateOf<String?>(null)
    var celebrate by mutableStateOf(0)
        private set

    var useExtraLife by mutableStateOf(false)
    var usePowerStart by mutableStateOf(false)

    var soundOn by mutableStateOf(settings.getBoolean("sound", true))
        private set
    var showDpad by mutableStateOf(settings.getBoolean("dpad", false))
        private set

    init {
        c.sound.enabled = soundOn
        c.haptics.enabled = soundOn
        refresh()
        viewModelScope.launch {
            c.billing.events.collect { ev ->
                when (ev) {
                    is BillingEvent.Granted -> if (ev.result.status == "granted") {
                        message = "+${ev.result.gemsGranted} gems" + if (ev.result.premium && ev.productId == "premium_pass") " · Premium unlocked!" else ""
                        celebrate++
                        c.sound.play(Sfx.PURCHASE)
                        c.haptics.bump()
                    }
                    is BillingEvent.Pending -> message = "Payment pending – gems arrive once it completes"
                    BillingEvent.Cancelled -> Unit
                    is BillingEvent.Failed -> message = ev.message
                }
            }
        }
    }

    fun refresh() {
        if (!online) {
            message = "Offline build – practice mode"
            return
        }
        viewModelScope.launch {
            syncing = true
            try {
                val p = c.repository.refreshProfile()
                c.billing.refresh(p.iapProducts.map { it.productId })
            } catch (e: ApiException) {
                message = "Server: ${e.code}"
            } catch (_: Exception) {
                message = "Offline – practice mode"
            } finally {
                syncing = false
            }
        }
    }

    fun toggleSound() {
        soundOn = !soundOn
        c.sound.enabled = soundOn
        c.haptics.enabled = soundOn
        settings.edit().putBoolean("sound", soundOn).apply()
    }

    fun toggleDpad() {
        showDpad = !showDpad
        settings.edit().putBoolean("dpad", showDpad).apply()
    }

    fun click() = c.sound.play(Sfx.CLICK)

    private fun guarded(block: suspend () -> Unit) {
        viewModelScope.launch {
            try {
                block()
            } catch (e: ApiException) {
                message = friendly(e.code)
            } catch (_: Exception) {
                message = "You are offline"
            }
        }
    }

    fun rename(name: String) = guarded { c.repository.rename(name) }

    fun buyItem(itemId: String) = guarded {
        c.repository.buy(itemId)
        celebrate++
        c.sound.play(Sfx.PURCHASE)
        message = "Unlocked!"
    }

    fun equip(skinId: String) = guarded { c.repository.equip(skinId) }

    fun buyProduct(activity: android.app.Activity, productId: String) {
        viewModelScope.launch { c.billing.launch(activity, productId) }
    }

    private fun friendly(code: String) = when (code) {
        "insufficient_gems" -> "Not enough gems"
        "already_owned" -> "Already owned"
        "not_owned" -> "Buy this skin first"
        "invalid_name" -> "3-16 letters, numbers, spaces or _"
        "integrity_failed" -> "This device could not be verified"
        "rate_limited" -> "Slow down a little"
        else -> "Something went wrong ($code)"
    }
}

@Composable
fun ArbotApp(container: AppContainer) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val app: AppViewModel = viewModel { AppViewModel(container, context.applicationContext) }
    val game: GameViewModel = viewModel { GameViewModel(container) }
    val profile by app.profile.collectAsStateWithLifecycle()

    BackHandler(enabled = app.screen != Screen.HOME) {
        if (app.screen == Screen.GAME) game.pause() else app.screen = Screen.HOME
    }

    fun startGame() {
        game.start(app.useExtraLife, app.usePowerStart)
        app.useExtraLife = false
        app.usePowerStart = false
        app.screen = Screen.GAME
    }

    Box(Modifier.fillMaxSize()) {
        AnimatedContent(
            targetState = app.screen,
            transitionSpec = { (fadeIn() + scaleIn(initialScale = 0.96f)) togetherWith fadeOut() },
            label = "screen",
        ) { screen ->
            when (screen) {
                Screen.HOME -> HomeScreen(app, profile, onPlay = ::startGame)
                Screen.GAME -> GameScreen(
                    vm = game,
                    skin = Skins.of(profile?.player?.equippedSkin),
                    showDpad = app.showDpad,
                    onPlayAgain = ::startGame,
                    onHome = {
                        app.screen = Screen.HOME
                        app.refresh()
                    },
                )
                Screen.STORE -> StoreScreen(app, profile)
                Screen.LEADERBOARD -> LeaderboardScreen(container, onBack = { app.screen = Screen.HOME })
            }
        }

        // Toast-style messages.
        val msg = app.message
        LaunchedEffect(msg) {
            if (msg != null) {
                delay(2600)
                app.message = null
            }
        }
        AnimatedVisibility(
            visible = msg != null,
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 20.dp),
            enter = slideInVertically { it } + fadeIn(),
            exit = slideOutVertically { it } + fadeOut(),
        ) {
            NeonPanel(color = Neon.Green) {
                Text(msg ?: "", color = Neon.Text, fontFamily = Mono, fontSize = 14.sp)
            }
        }
    }
}

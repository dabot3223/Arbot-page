package com.arbot.game

import android.content.Context
import com.arbot.core.api.SecureApiCore
import com.arbot.core.crypto.EnvelopeSealer
import com.arbot.core.crypto.ServerKey
import com.arbot.game.audio.Haptics
import com.arbot.game.audio.SoundFx
import com.arbot.game.billing.BillingManager
import com.arbot.game.data.GameRepository
import com.arbot.game.net.OkHttpTransport
import com.arbot.game.security.DeviceKeyManager
import com.arbot.game.security.PlayIntegrity
import com.arbot.game.security.RuntimeGuard
import com.arbot.game.security.SecureStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/** Manual dependency graph – small enough not to need a DI framework. */
class AppContainer(context: Context) {
    private val app = context.applicationContext

    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    val secureStore = SecureStore(app)
    private val deviceKey = DeviceKeyManager(app)
    val integrity = PlayIntegrity(app, BuildConfig.CLOUD_PROJECT_NUMBER)

    /** null when the build has no backend configured: the game runs offline (practice mode). */
    private val api: SecureApiCore? = run {
        if (BuildConfig.API_URL.isBlank() || BuildConfig.API_PUBLIC_KEY.isBlank()) return@run null
        val pins = BuildConfig.CERT_PINS.split(',').map { it.trim() }.filter { it.isNotEmpty() }
        SecureApiCore(
            transport = OkHttpTransport(BuildConfig.API_URL, pins, allowCleartext = BuildConfig.DEBUG),
            sealer = EnvelopeSealer(ServerKey.fromBase64Url(BuildConfig.API_KEY_ID, BuildConfig.API_PUBLIC_KEY)),
            signer = deviceKey,
            integrity = integrity,
        )
    }

    val repository = GameRepository(api, secureStore, deviceKey) { RuntimeGuard.isCompromised(app) }
    val billing = BillingManager(app, repository, appScope)
    val sound = SoundFx()
    val haptics = Haptics(app)
}

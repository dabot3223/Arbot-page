package com.arbot.game.security

import android.content.Context
import com.arbot.core.api.IntegrityProvider
import com.google.android.gms.tasks.Task
import com.google.android.play.core.integrity.IntegrityManagerFactory
import com.google.android.play.core.integrity.StandardIntegrityManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Play Integrity "standard" requests. Each token is bound to the SHA-256 of
 * the exact signed API message (requestHash), so a token cannot be lifted
 * from one request and replayed on another. The server decodes it with
 * Google and checks app, device and licence verdicts.
 */
class PlayIntegrity(context: Context, private val cloudProjectNumber: Long) : IntegrityProvider {

    private val manager = IntegrityManagerFactory.createStandard(context.applicationContext)
    private var provider: StandardIntegrityManager.StandardIntegrityTokenProvider? = null
    private val mutex = Mutex()

    val enabled: Boolean get() = cloudProjectNumber > 0

    /** Preparing the provider takes a few seconds; do it before it is needed. */
    fun warmUp(scope: CoroutineScope) {
        if (enabled) scope.launch { runCatching { provider() } }
    }

    private suspend fun provider(): StandardIntegrityManager.StandardIntegrityTokenProvider = mutex.withLock {
        provider ?: manager.prepareIntegrityToken(
            StandardIntegrityManager.PrepareIntegrityTokenRequest.builder()
                .setCloudProjectNumber(cloudProjectNumber)
                .build(),
        ).await().also { provider = it }
    }

    override suspend fun token(requestHash: String): String? {
        if (!enabled) return null
        repeat(2) { attempt ->
            try {
                val response = provider().request(
                    StandardIntegrityManager.StandardIntegrityTokenRequest.builder()
                        .setRequestHash(requestHash)
                        .build(),
                ).await()
                return response.token()
            } catch (_: Exception) {
                // Provider may have expired; prepare a fresh one and retry once.
                mutex.withLock { provider = null }
                if (attempt == 1) return null
            }
        }
        return null
    }
}

suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { cont ->
    addOnSuccessListener { if (cont.isActive) cont.resume(it) }
    addOnFailureListener { if (cont.isActive) cont.resumeWithException(it) }
    addOnCanceledListener { cont.cancel() }
}

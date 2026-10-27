package com.arbot.core.api

import com.arbot.core.crypto.B64
import com.arbot.core.crypto.EnvelopeSealer
import com.arbot.core.crypto.Hashes
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import java.security.SecureRandom

/** Raw HTTP: POSTs the sealed body to the single `api` edge function. */
fun interface Transport {
    /** Returns the response body for HTTP 200, throws [TransportException] otherwise. */
    suspend fun post(body: ByteArray): ByteArray
}

/** Hardware-backed (Android Keystore) device key that authenticates every request. */
interface RequestSigner {
    suspend fun publicKeySpki(): ByteArray
    /** SHA256withECDSA, DER encoded. */
    suspend fun sign(data: ByteArray): ByteArray
}

/** Play Integrity (standard request). Returns null when integrity is not configured. */
fun interface IntegrityProvider {
    suspend fun token(requestHash: String): String?
}

class TransportException(val status: Int, message: String) : Exception(message)
class ApiException(val code: String, message: String) : Exception("$code: $message")

/**
 * Inner, signed message. The server verifies the ECDSA signature over the exact
 * bytes of this JSON before trusting any field in it.
 */
@Serializable
data class InnerMessage(
    val action: String,
    val ts: Long,
    val nonce: String,
    val sub: String? = null,
    val payload: JsonElement,
)

/** Plaintext that goes into the envelope: signed message + signature + integrity token. */
@Serializable
data class OuterMessage(
    @SerialName("m") val message: String,
    @SerialName("s") val signature: String,
    @SerialName("i") val integrity: String? = null,
)

@Serializable
data class ApiError(val code: String, val message: String = "")

@Serializable
data class ApiResponse(
    val ok: Boolean,
    val data: JsonElement? = null,
    val error: ApiError? = null,
    val serverTime: Long? = null,
)

val ApiJson = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    encodeDefaults = true
}

/**
 * Builds, signs, encrypts, sends and decrypts API calls. Platform specifics
 * (HTTP, Keystore, Play Integrity) are injected so the protocol is testable on
 * a plain JVM.
 */
class SecureApiCore(
    private val transport: Transport,
    private val sealer: EnvelopeSealer,
    private val signer: RequestSigner,
    private val integrity: IntegrityProvider,
    private val clock: () -> Long = System::currentTimeMillis,
    private val random: SecureRandom = SecureRandom(),
) {
    @Volatile
    var clockOffsetMs: Long = 0
        private set

    suspend fun call(
        action: String,
        payload: JsonElement = JsonObject(emptyMap()),
        subject: String? = null,
        requireIntegrity: Boolean = false,
    ): JsonElement {
        val first = callOnce(action, payload, subject, requireIntegrity)
        if (!first.ok && first.error?.code == "clock_skew" && first.serverTime != null) {
            clockOffsetMs = first.serverTime - clock()
            return unwrap(callOnce(action, payload, subject, requireIntegrity))
        }
        return unwrap(first)
    }

    private fun unwrap(r: ApiResponse): JsonElement {
        if (r.ok) return r.data ?: JsonObject(emptyMap())
        val e = r.error ?: ApiError("unknown", "Unknown error")
        throw ApiException(e.code, e.message)
    }

    private suspend fun callOnce(
        action: String,
        payload: JsonElement,
        subject: String?,
        requireIntegrity: Boolean,
    ): ApiResponse {
        val nonce = ByteArray(16).also(random::nextBytes)
        val inner = InnerMessage(
            action = action,
            ts = clock() + clockOffsetMs,
            nonce = B64.encode(nonce),
            sub = subject,
            payload = payload,
        )
        val innerJson = ApiJson.encodeToString(InnerMessage.serializer(), inner)
        val innerBytes = innerJson.toByteArray()
        val signature = signer.sign(innerBytes)
        val integrityToken = if (requireIntegrity) integrity.token(requestHash(innerBytes)) else null
        val outer = OuterMessage(innerJson, B64.encode(signature), integrityToken)
        val plaintext = ApiJson.encodeToString(OuterMessage.serializer(), outer).toByteArray()

        val sealed = sealer.seal(plaintext)
        val responseBody = try {
            transport.post(sealed.body)
        } catch (t: Throwable) {
            sealed.context.wipe()
            throw t
        }
        val opened = sealer.open(sealed.context, responseBody)
        return ApiJson.decodeFromString(ApiResponse.serializer(), opened.decodeToString().trim())
    }

    companion object {
        /** The value bound into the Play Integrity token: base64url(SHA-256(signed message)). */
        fun requestHash(signedMessage: ByteArray): String = B64.encode(Hashes.sha256(signedMessage))
    }
}

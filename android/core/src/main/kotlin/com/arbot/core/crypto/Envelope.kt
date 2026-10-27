package com.arbot.core.crypto

import java.math.BigInteger
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.PublicKey
import java.security.SecureRandom
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec
import java.security.spec.ECPoint
import java.security.spec.ECPublicKeySpec
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Application-layer encryption for every API call ("envelope v1").
 *
 * TLS already protects the transport, but TLS terminates at CDNs/proxies and
 * can be intercepted on rooted devices with a user-installed CA. The envelope
 * keeps request and response bodies opaque to everything except the app and
 * the edge function holding the server private key:
 *
 *  1. The app embeds the server's P-256 public key (identified by `kid`).
 *  2. Per request it creates an ephemeral P-256 key pair and runs ECDH.
 *  3. HKDF-SHA256(shared, salt = ephemeral public key, info = "arbot-envelope-v1|kid")
 *     yields 64 bytes: a request key and a response key.
 *  4. The request is sealed with AES-256-GCM (request key, random 96-bit IV).
 *  5. The response is sealed with the response key and bound to the request IV
 *     through the AAD, so it can only be read by the ephemeral key's owner and
 *     cannot be replayed against another request.
 *
 * Plaintexts are padded to 256-byte buckets so body sizes leak less.
 */
object EnvelopeCrypto {
    const val VERSION = 1
    private const val INFO_PREFIX = "arbot-envelope-v1|"
    private const val REQ_AAD_PREFIX = "arbot-v1-req|"
    private const val RES_AAD_PREFIX = "arbot-v1-res|"
    const val PAD_BLOCK = 256
    private const val GCM_TAG_BITS = 128
    const val IV_BYTES = 12

    private val ecParams: ECParameterSpec by lazy {
        val kpg = KeyPairGenerator.getInstance("EC")
        kpg.initialize(ECGenParameterSpec("secp256r1"))
        (kpg.generateKeyPair().public as ECPublicKey).params
    }

    fun generateEphemeral(random: SecureRandom): KeyPair {
        val kpg = KeyPairGenerator.getInstance("EC")
        kpg.initialize(ECGenParameterSpec("secp256r1"), random)
        return kpg.generateKeyPair()
    }

    /** Uncompressed SEC1 point: 0x04 || X(32) || Y(32). */
    fun publicKeyToRaw(key: PublicKey): ByteArray {
        val w = (key as ECPublicKey).w
        return byteArrayOf(0x04) + toFixed32(w.affineX) + toFixed32(w.affineY)
    }

    fun rawToPublicKey(raw: ByteArray): PublicKey {
        require(raw.size == 65 && raw[0] == 0x04.toByte()) { "invalid P-256 public key" }
        val x = BigInteger(1, raw.copyOfRange(1, 33))
        val y = BigInteger(1, raw.copyOfRange(33, 65))
        return KeyFactory.getInstance("EC").generatePublic(ECPublicKeySpec(ECPoint(x, y), ecParams))
    }

    fun deriveKeys(ephemeralPrivate: PrivateKey, serverPublic: PublicKey, epkRaw: ByteArray, kid: String): SessionKeys {
        val ka = KeyAgreement.getInstance("ECDH")
        ka.init(ephemeralPrivate)
        ka.doPhase(serverPublic, true)
        val shared = ka.generateSecret()
        try {
            val okm = Hkdf.sha256(ikm = shared, salt = epkRaw, info = (INFO_PREFIX + kid).toByteArray(), length = 64)
            return SessionKeys(okm.copyOfRange(0, 32), okm.copyOfRange(32, 64)).also { okm.fill(0) }
        } finally {
            shared.fill(0)
        }
    }

    fun requestAad(kid: String) = (REQ_AAD_PREFIX + kid).toByteArray()
    fun responseAad(requestIv: ByteArray) = RES_AAD_PREFIX.toByteArray() + requestIv

    fun aesGcmEncrypt(key: ByteArray, iv: ByteArray, aad: ByteArray, plaintext: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(GCM_TAG_BITS, iv))
        cipher.updateAAD(aad)
        return cipher.doFinal(plaintext)
    }

    fun aesGcmDecrypt(key: ByteArray, iv: ByteArray, aad: ByteArray, ciphertext: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(GCM_TAG_BITS, iv))
        cipher.updateAAD(aad)
        return cipher.doFinal(ciphertext)
    }

    /** Pads with JSON-insignificant spaces up to the next [PAD_BLOCK] boundary. */
    fun pad(json: ByteArray): ByteArray {
        val target = ((json.size / PAD_BLOCK) + 1) * PAD_BLOCK
        return json + ByteArray(target - json.size) { ' '.code.toByte() }
    }

    private fun toFixed32(v: BigInteger): ByteArray {
        val bytes = v.toByteArray()
        return when {
            bytes.size == 32 -> bytes
            bytes.size == 33 && bytes[0] == 0.toByte() -> bytes.copyOfRange(1, 33)
            bytes.size < 32 -> ByteArray(32 - bytes.size) + bytes
            else -> throw IllegalArgumentException("coordinate too large")
        }
    }
}

class SessionKeys(val requestKey: ByteArray, val responseKey: ByteArray) {
    fun wipe() {
        requestKey.fill(0)
        responseKey.fill(0)
    }
}

/** The server key pinned into the app at build time. */
class ServerKey(val kid: String, val publicRaw: ByteArray) {
    val publicKey: PublicKey by lazy { EnvelopeCrypto.rawToPublicKey(publicRaw) }

    companion object {
        fun fromBase64Url(kid: String, b64: String) = ServerKey(kid, B64.decode(b64))
    }
}

/** Opaque per-request state needed to open the matching response. */
class ResponseContext internal constructor(internal val responseKey: ByteArray, internal val requestIv: ByteArray) {
    fun wipe() = responseKey.fill(0)
}

class SealedRequest(val body: ByteArray, val context: ResponseContext)

class EnvelopeSealer(private val serverKey: ServerKey, private val random: SecureRandom = SecureRandom()) {

    fun seal(plaintext: ByteArray): SealedRequest {
        val iv = ByteArray(EnvelopeCrypto.IV_BYTES).also(random::nextBytes)
        return sealWith(EnvelopeCrypto.generateEphemeral(random), iv, EnvelopeCrypto.pad(plaintext))
    }

    /** Deterministic variant used by the cross-language fixtures. */
    fun sealWith(ephemeral: KeyPair, iv: ByteArray, paddedPlaintext: ByteArray): SealedRequest {
        val epk = EnvelopeCrypto.publicKeyToRaw(ephemeral.public)
        val keys = EnvelopeCrypto.deriveKeys(ephemeral.private, serverKey.publicKey, epk, serverKey.kid)
        val ct = EnvelopeCrypto.aesGcmEncrypt(keys.requestKey, iv, EnvelopeCrypto.requestAad(serverKey.kid), paddedPlaintext)
        keys.requestKey.fill(0)
        val body = buildString {
            append("{\"v\":").append(EnvelopeCrypto.VERSION)
            append(",\"kid\":\"").append(serverKey.kid).append('"')
            append(",\"epk\":\"").append(B64.encode(epk)).append('"')
            append(",\"iv\":\"").append(B64.encode(iv)).append('"')
            append(",\"ct\":\"").append(B64.encode(ct)).append("\"}")
        }.toByteArray()
        return SealedRequest(body, ResponseContext(keys.responseKey, iv))
    }

    /** Opens a `{"v":1,"iv":"…","ct":"…"}` response. Throws on any tampering. */
    fun open(context: ResponseContext, body: ByteArray): ByteArray {
        val parsed = ResponseEnvelope.parse(body)
        require(parsed.v == EnvelopeCrypto.VERSION) { "unsupported envelope version" }
        val pt = EnvelopeCrypto.aesGcmDecrypt(
            context.responseKey,
            parsed.iv,
            EnvelopeCrypto.responseAad(context.requestIv),
            parsed.ct,
        )
        context.wipe()
        return pt
    }
}

internal class ResponseEnvelope(val v: Int, val iv: ByteArray, val ct: ByteArray) {
    companion object {
        fun parse(body: ByteArray): ResponseEnvelope {
            val obj = kotlinx.serialization.json.Json.parseToJsonElement(body.decodeToString())
            val o = obj as kotlinx.serialization.json.JsonObject
            fun str(k: String) = (o[k] as? kotlinx.serialization.json.JsonPrimitive)?.content
                ?: throw IllegalArgumentException("missing $k")
            return ResponseEnvelope(str("v").toInt(), B64.decode(str("iv")), B64.decode(str("ct")))
        }
    }
}

object B64 {
    private val enc = Base64.getUrlEncoder().withoutPadding()
    private val dec = Base64.getUrlDecoder()
    fun encode(b: ByteArray): String = enc.encodeToString(b)
    fun decode(s: String): ByteArray = dec.decode(s)
}

object Hashes {
    fun sha256(data: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(data)
    fun sha256Hex(data: ByteArray): String = sha256(data).joinToString("") { "%02x".format(it) }
}

object Hkdf {
    fun sha256(ikm: ByteArray, salt: ByteArray, info: ByteArray, length: Int): ByteArray {
        require(length <= 255 * 32)
        val prk = hmac(salt, ikm)
        val out = ByteArray(length)
        var t = ByteArray(0)
        var pos = 0
        var counter = 1
        while (pos < length) {
            t = hmac(prk, t + info + byteArrayOf(counter.toByte()))
            val n = minOf(t.size, length - pos)
            System.arraycopy(t, 0, out, pos, n)
            pos += n
            counter++
        }
        prk.fill(0)
        return out
    }

    private fun hmac(key: ByteArray, data: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(if (key.isEmpty()) ByteArray(32) else key, "HmacSHA256"))
        return mac.doFinal(data)
    }
}

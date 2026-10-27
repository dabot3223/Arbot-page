package com.arbot.core.api

import com.arbot.core.crypto.B64
import com.arbot.core.crypto.EnvelopeCrypto
import com.arbot.core.crypto.EnvelopeSealer
import com.arbot.core.crypto.ServerKey
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SecureApiCoreTest {

    private fun ecKeyPair(): KeyPair = KeyPairGenerator.getInstance("EC").run {
        initialize(ECGenParameterSpec("secp256r1"))
        generateKeyPair()
    }

    private class SoftwareSigner(val kp: KeyPair) : RequestSigner {
        override suspend fun publicKeySpki(): ByteArray = kp.public.encoded
        override suspend fun sign(data: ByteArray): ByteArray =
            Signature.getInstance("SHA256withECDSA").run { initSign(kp.private); update(data); sign() }
    }

    /** Minimal JVM stand-in for the edge function gateway. */
    private inner class FakeServer(val serverKp: KeyPair, val deviceKp: KeyPair, var serverTime: Long? = null) : Transport {
        val seen = mutableListOf<InnerMessage>()
        var lastIntegrity: String? = null
        var lastRawBody: String = ""

        override suspend fun post(body: ByteArray): ByteArray {
            lastRawBody = body.decodeToString()
            val env = ApiJson.parseToJsonElement(lastRawBody).jsonObject
            val epk = B64.decode(env["epk"]!!.jsonPrimitive.content)
            val iv = B64.decode(env["iv"]!!.jsonPrimitive.content)
            val kid = env["kid"]!!.jsonPrimitive.content
            val keys = EnvelopeCrypto.deriveKeys(serverKp.private, EnvelopeCrypto.rawToPublicKey(epk), epk, kid)
            val pt = EnvelopeCrypto.aesGcmDecrypt(keys.requestKey, iv, EnvelopeCrypto.requestAad(kid),
                B64.decode(env["ct"]!!.jsonPrimitive.content))
            val outer = ApiJson.decodeFromString(OuterMessage.serializer(), pt.decodeToString().trim())
            val ok = Signature.getInstance("SHA256withECDSA").run {
                initVerify(deviceKp.public); update(outer.message.toByteArray()); verify(B64.decode(outer.signature))
            }
            check(ok) { "bad signature" }
            val inner = ApiJson.decodeFromString(InnerMessage.serializer(), outer.message)
            seen += inner
            lastIntegrity = outer.integrity

            val response = if (serverTime != null && kotlin.math.abs(inner.ts - serverTime!!) > 60_000) {
                ApiResponse(ok = false, error = ApiError("clock_skew", "clock"), serverTime = serverTime)
            } else if (inner.action == "fail") {
                ApiResponse(ok = false, error = ApiError("insufficient_gems", "Not enough gems"))
            } else {
                ApiResponse(ok = true, data = buildJsonObject { put("echo", inner.action); put("sub", inner.sub) })
            }
            val json = EnvelopeCrypto.pad(ApiJson.encodeToString(ApiResponse.serializer(), response).toByteArray())
            val resIv = ByteArray(12).also { java.security.SecureRandom().nextBytes(it) }
            val ct = EnvelopeCrypto.aesGcmEncrypt(keys.responseKey, resIv, EnvelopeCrypto.responseAad(iv), json)
            return """{"v":1,"iv":"${B64.encode(resIv)}","ct":"${B64.encode(ct)}"}""".toByteArray()
        }
    }

    private fun setup(clock: () -> Long = System::currentTimeMillis, serverTime: Long? = null): Pair<SecureApiCore, FakeServer> {
        val serverKp = ecKeyPair()
        val deviceKp = ecKeyPair()
        val server = FakeServer(serverKp, deviceKp, serverTime)
        val core = SecureApiCore(
            transport = server,
            sealer = EnvelopeSealer(ServerKey("k1", EnvelopeCrypto.publicKeyToRaw(serverKp.public))),
            signer = SoftwareSigner(deviceKp),
            integrity = { hash -> "integrity-for-$hash" },
            clock = clock,
        )
        return core to server
    }

    @Test
    fun roundTripIsSignedEncryptedAndDecrypted() = runTest {
        val (core, server) = setup()
        val data = core.call("profile.get", JsonObject(emptyMap()), subject = "player-1")
        assertEquals("profile.get", data.jsonObject["echo"]!!.jsonPrimitive.content)
        assertEquals("player-1", server.seen.single().sub)
        assertNull(server.lastIntegrity)
        // Nothing sensitive is visible on the wire.
        assertTrue("profile.get" !in server.lastRawBody && "player-1" !in server.lastRawBody)
    }

    @Test
    fun integrityTokenIsBoundToTheSignedMessage() = runTest {
        val (core, server) = setup()
        core.call("purchase.verify", buildJsonObject { put("x", JsonPrimitive(1)) }, "p", requireIntegrity = true)
        val signed = ApiJson.encodeToString(InnerMessage.serializer(), server.seen.single()).toByteArray()
        assertEquals("integrity-for-" + SecureApiCore.requestHash(signed), server.lastIntegrity)
    }

    @Test
    fun noncesAreUnique() = runTest {
        val (core, server) = setup()
        repeat(20) { core.call("profile.get") }
        assertEquals(20, server.seen.map { it.nonce }.toSet().size)
    }

    @Test
    fun serverErrorsSurfaceAsApiException() = runTest {
        val (core, _) = setup()
        val e = assertFailsWith<ApiException> { core.call("fail") }
        assertEquals("insufficient_gems", e.code)
    }

    @Test
    fun clockSkewIsCorrectedOnce() = runTest {
        val (core, server) = setup(clock = { 1_000_000L }, serverTime = 50_000_000L)
        core.call("profile.get")
        assertEquals(2, server.seen.size)
        assertEquals(49_000_000L, core.clockOffsetMs)
    }
}

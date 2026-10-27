package com.arbot.core.crypto

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.security.KeyFactory
import java.security.KeyPair
import java.security.spec.PKCS8EncodedKeySpec
import javax.crypto.AEADBadTagException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * Cross-language check against shared/fixtures/envelope-vectors.json, which is
 * produced by the server code (supabase/tests/gen_envelope_fixture.ts).
 */
class EnvelopeFixtureTest {
    private val f = Json.parseToJsonElement(
        File(System.getProperty("arbot.fixtures"), "envelope-vectors.json").readText(),
    ).jsonObject

    private fun s(k: String) = f[k]!!.jsonPrimitive.content

    private val serverKey = ServerKey.fromBase64Url(s("kid"), s("serverPublicRaw"))

    private fun ephemeral(): KeyPair {
        val priv = KeyFactory.getInstance("EC").generatePrivate(PKCS8EncodedKeySpec(B64.decode(s("ephemeralPrivatePkcs8"))))
        val pub = EnvelopeCrypto.rawToPublicKey(B64.decode(s("ephemeralPublicRaw")))
        return KeyPair(pub, priv)
    }

    @Test
    fun clientSealMatchesServerExpectation() {
        val sealed = EnvelopeSealer(serverKey).sealWith(
            ephemeral(),
            B64.decode(s("requestIv")),
            EnvelopeCrypto.pad(s("requestPlaintext").toByteArray()),
        )
        assertEquals(s("requestBody"), sealed.body.decodeToString())
    }

    @Test
    fun clientOpensServerResponse() {
        val sealer = EnvelopeSealer(serverKey)
        val sealed = sealer.sealWith(ephemeral(), B64.decode(s("requestIv")), EnvelopeCrypto.pad(s("requestPlaintext").toByteArray()))
        val opened = sealer.open(sealed.context, s("responseBody").toByteArray())
        assertEquals(s("responsePlaintext"), opened.decodeToString().trim())
    }

    @Test
    fun tamperedResponseIsRejected() {
        val sealer = EnvelopeSealer(serverKey)
        val sealed = sealer.sealWith(ephemeral(), B64.decode(s("requestIv")), EnvelopeCrypto.pad(s("requestPlaintext").toByteArray()))
        val env = Json.parseToJsonElement(s("responseBody")).jsonObject
        val ct = B64.decode(env["ct"]!!.jsonPrimitive.content)
        ct[3] = (ct[3].toInt() xor 1).toByte()
        val forged = """{"v":1,"iv":"${env["iv"]!!.jsonPrimitive.content}","ct":"${B64.encode(ct)}"}"""
        assertFailsWith<AEADBadTagException> { sealer.open(sealed.context, forged.toByteArray()) }
    }

    @Test
    fun responseCannotBeReplayedOntoAnotherRequest() {
        val sealer = EnvelopeSealer(serverKey)
        // Same keys, different request IV -> different AAD -> must fail.
        val sealed = sealer.sealWith(ephemeral(), ByteArray(12), EnvelopeCrypto.pad(s("requestPlaintext").toByteArray()))
        assertFailsWith<AEADBadTagException> { sealer.open(sealed.context, s("responseBody").toByteArray()) }
    }

    @Test
    fun paddingUsesFixedBuckets() {
        assertEquals(256, EnvelopeCrypto.pad(ByteArray(10)).size)
        assertEquals(512, EnvelopeCrypto.pad(ByteArray(256)).size)
    }
}

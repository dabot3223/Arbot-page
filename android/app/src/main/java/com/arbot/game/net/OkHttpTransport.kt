package com.arbot.game.net

import com.arbot.core.api.Transport
import com.arbot.core.api.TransportException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.CertificatePinner
import okhttp3.ConnectionSpec
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * HTTPS transport for the sealed envelopes: TLS 1.2+/1.3 with modern ciphers
 * only, optional certificate pinning, no redirects, no caching, no cookies.
 */
class OkHttpTransport(
    apiUrl: String,
    certPins: List<String>,
    allowCleartext: Boolean,
) : Transport {

    private val url = apiUrl.toHttpUrl()

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectionSpecs(
            if (allowCleartext && !url.isHttps) listOf(ConnectionSpec.RESTRICTED_TLS, ConnectionSpec.CLEARTEXT)
            else listOf(ConnectionSpec.RESTRICTED_TLS),
        )
        .apply {
            if (certPins.isNotEmpty()) {
                certificatePinner(
                    CertificatePinner.Builder().apply { certPins.forEach { add(url.host, it) } }.build(),
                )
            }
        }
        .followRedirects(false)
        .followSslRedirects(false)
        .retryOnConnectionFailure(true)
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .callTimeout(30, TimeUnit.SECONDS)
        .build()

    init {
        require(url.isHttps || allowCleartext) { "API_URL must use https" }
    }

    override suspend fun post(body: ByteArray): ByteArray = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(url)
            .post(body.toRequestBody(JSON))
            .header("Cache-Control", "no-store")
            .build()
        client.newCall(request).execute().use { response ->
            if (response.code != 200) throw TransportException(response.code, "HTTP ${response.code}")
            response.body?.bytes() ?: throw TransportException(response.code, "empty body")
        }
    }

    private companion object {
        val JSON = "application/json".toMediaType()
    }
}

package com.arbot.game.security

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.arbot.core.api.RequestSigner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.Signature
import java.security.spec.ECGenParameterSpec

/**
 * Per-install identity: a P-256 signing key generated inside the Android
 * Keystore (StrongBox secure element when the device has one). The private
 * key never leaves secure hardware, so a stolen request or a copied app data
 * directory cannot be used to impersonate the player.
 */
class DeviceKeyManager(private val context: Context) : RequestSigner {

    private val lock = Any()

    private fun keyStore(): KeyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

    private fun entry(): KeyStore.PrivateKeyEntry = synchronized(lock) {
        val ks = keyStore()
        (ks.getEntry(ALIAS, null) as? KeyStore.PrivateKeyEntry)?.let { return it }
        val wantStrongBox = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P &&
            context.packageManager.hasSystemFeature(PackageManager.FEATURE_STRONGBOX_KEYSTORE)
        if (wantStrongBox) {
            // Some StrongBox implementations advertise the feature but reject
            // the key spec; fall back to the TEE-backed keystore.
            try {
                generate(strongBox = true)
            } catch (_: java.security.ProviderException) {
                // StrongBoxUnavailableException (API 28) extends ProviderException.
                generate(strongBox = false)
            } catch (_: java.security.GeneralSecurityException) {
                generate(strongBox = false)
            }
        } else {
            generate(strongBox = false)
        }
        ks.getEntry(ALIAS, null) as KeyStore.PrivateKeyEntry
    }

    private fun generate(strongBox: Boolean) {
        val builder = KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY)
            .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
            .setDigests(KeyProperties.DIGEST_SHA256)
        if (strongBox && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) builder.setIsStrongBoxBacked(true)
        KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, ANDROID_KEYSTORE).run {
            initialize(builder.build())
            generateKeyPair()
        }
    }

    override suspend fun publicKeySpki(): ByteArray = withContext(Dispatchers.Default) {
        entry().certificate.publicKey.encoded
    }

    override suspend fun sign(data: ByteArray): ByteArray = withContext(Dispatchers.Default) {
        Signature.getInstance("SHA256withECDSA").run {
            initSign(entry().privateKey)
            update(data)
            sign()
        }
    }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val ALIAS = "arbot_device_key_v1"
    }
}

package com.arbot.game.security

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.arbot.core.crypto.B64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Encrypted key/value storage. Values are sealed with AES-256-GCM under a key
 * that lives in the Android Keystore (non-exportable); the entry name is bound
 * as AAD so ciphertexts cannot be swapped between entries. The backing
 * SharedPreferences file only ever contains ciphertext.
 */
class SecureStore(context: Context) {

    private val prefs = context.getSharedPreferences("arbot_secure", Context.MODE_PRIVATE)

    @Synchronized
    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val spec = KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .setRandomizedEncryptionRequired(true)
            .build()
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(spec)
            generateKey()
        }
    }

    fun putString(name: String, value: String?) {
        if (value == null) {
            prefs.edit().remove(name).apply()
            return
        }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        cipher.updateAAD(name.toByteArray())
        val ct = cipher.doFinal(value.toByteArray())
        prefs.edit().putString(name, B64.encode(cipher.iv + ct)).apply()
    }

    fun getString(name: String): String? {
        val stored = prefs.getString(name, null) ?: return null
        return try {
            val raw = B64.decode(stored)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, raw, 0, 12))
            cipher.updateAAD(name.toByteArray())
            String(cipher.doFinal(raw, 12, raw.size - 12))
        } catch (_: Exception) {
            // Key invalidated or data tampered with: treat as absent.
            prefs.edit().remove(name).apply()
            null
        }
    }

    fun clear() = prefs.edit().clear().apply()

    private companion object {
        const val ALIAS = "arbot_store_key_v1"
    }
}

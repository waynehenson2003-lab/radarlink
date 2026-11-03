package com.sophia.signal.data

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * AES-256-GCM with a hardware-backed (where available) Android Keystore key.
 * Used for user-entered labels and watchlist values. The key never leaves the device.
 */
object LocalCrypto {
    private const val ALIAS = "sophia_local_key_v1"
    private const val PREFIX = "v1:"

    @Volatile private var cached: SecretKey? = null

    private fun key(): SecretKey {
        cached?.let { return it }
        synchronized(this) {
            cached?.let { return it }
            val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            val existing = ks.getKey(ALIAS, null) as? SecretKey
            val k = existing ?: KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
                init(
                    KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                        .setKeySize(256)
                        .build()
                )
                generateKey()
            }
            cached = k
            return k
        }
    }

    fun enc(plain: String?): String? {
        if (plain == null) return null
        return try {
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.ENCRYPT_MODE, key())
            val ct = c.doFinal(plain.toByteArray(Charsets.UTF_8))
            PREFIX + b64(c.iv) + ":" + b64(ct)
        } catch (e: Exception) {
            null
        }
    }

    fun dec(stored: String?): String? {
        if (stored == null) return null
        if (!stored.startsWith(PREFIX)) return stored
        return try {
            val parts = stored.removePrefix(PREFIX).split(":")
            val iv = Base64.decode(parts[0], Base64.NO_WRAP)
            val ct = Base64.decode(parts[1], Base64.NO_WRAP)
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv))
            String(c.doFinal(ct), Charsets.UTF_8)
        } catch (e: Exception) {
            null
        }
    }

    private fun b64(b: ByteArray) = Base64.encodeToString(b, Base64.NO_WRAP)
}

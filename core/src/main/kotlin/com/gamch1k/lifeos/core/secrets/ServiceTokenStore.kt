package com.gamch1k.lifeos.core.secrets

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.GeneralSecurityException
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** A Cloudflare Access service token. Its toString never shows the values. */
class ServiceToken(val clientId: String, val clientSecret: String) {
    override fun toString() = "ServiceToken(…)"

    /** The client ID with all but its start and Cloudflare's ".access" suffix hidden. */
    val maskedClientId: String get() = mask(clientId, keepStart = 4, keepSuffix = ".access")

    /** Never any character of the secret, only its length. */
    val maskedClientSecret: String get() = "•".repeat(8) + " (${clientSecret.length} characters)"

    companion object {
        internal fun mask(value: String, keepStart: Int, keepSuffix: String): String {
            val suffix = if (value.endsWith(keepSuffix) && value.length > keepSuffix.length + keepStart) keepSuffix else ""
            val start = value.take(minOf(keepStart, value.length / 4))
            return "$start••••$suffix"
        }
    }
}

/**
 * Keeps the service token on the phone, encrypted with an AES-256-GCM key that
 * lives in the Android Keystore and never leaves it. Only ciphertext reaches
 * SharedPreferences. Nothing here logs.
 */
class ServiceTokenStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    fun load(): ServiceToken? {
        val id = preferences.getString(KEY_ID, null) ?: return null
        val secret = preferences.getString(KEY_SECRET, null) ?: return null
        return try {
            ServiceToken(decrypt(id), decrypt(secret))
        } catch (_: GeneralSecurityException) {
            // The Keystore key is gone (e.g. app data restored elsewhere): the ciphertext is useless.
            clear()
            null
        } catch (_: IllegalArgumentException) {
            clear()
            null
        }
    }

    fun save(token: ServiceToken) {
        require(token.clientId.isNotBlank() && token.clientSecret.isNotBlank()) { "both values are required" }
        preferences.edit()
            .putString(KEY_ID, encrypt(token.clientId))
            .putString(KEY_SECRET, encrypt(token.clientSecret))
            .apply()
    }

    fun clear() {
        preferences.edit().clear().apply()
    }

    private fun key(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }

    private fun encrypt(plain: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, key()) }
        val sealed = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        return encode(cipher.iv) + ":" + encode(sealed)
    }

    private fun decrypt(stored: String): String {
        val (iv, sealed) = stored.split(':').takeIf { it.size == 2 } ?: throw IllegalArgumentException("bad ciphertext")
        val cipher = Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, decode(iv)))
        }
        return cipher.doFinal(decode(sealed)).toString(Charsets.UTF_8)
    }

    private fun encode(bytes: ByteArray) = Base64.encodeToString(bytes, Base64.NO_WRAP)

    private fun decode(text: String) = Base64.decode(text, Base64.NO_WRAP)

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "lifeos-service-token"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val PREFERENCES = "service-token"
        const val KEY_ID = "client-id"
        const val KEY_SECRET = "client-secret"
    }
}

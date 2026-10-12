package com.gamch1k.lifeos.wear.link

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.gamch1k.lifeos.core.wearlink.RelayCodec
import com.gamch1k.lifeos.core.wearlink.WearCredentials
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** The credentials the phone handed over, and when. */
class StoredCredentials(val credentials: WearCredentials, val receivedAtMillis: Long)

/**
 * Keeps the server URL and service token the phone handed over, encrypted
 * with an AES-256-GCM key that lives in the watch's Android Keystore and
 * never leaves it. Only ciphertext reaches SharedPreferences (which are not
 * backed up: allowBackup is false). Nothing here logs.
 */
class CredentialStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
    private val state = MutableStateFlow(read())

    val credentials: StateFlow<StoredCredentials?> = state.asStateFlow()

    val current: WearCredentials? get() = state.value?.credentials

    fun save(credentials: WearCredentials, receivedAtMillis: Long = System.currentTimeMillis()) {
        val json = RelayCodec.json.encodeToString(WearCredentials.serializer(), credentials)
        preferences.edit()
            .putString(KEY_SEALED, encrypt(json))
            .putLong(KEY_RECEIVED_AT, receivedAtMillis)
            .apply()
        state.value = StoredCredentials(credentials, receivedAtMillis)
    }

    fun clear() {
        preferences.edit().clear().apply()
        state.value = null
    }

    private fun read(): StoredCredentials? {
        val sealed = preferences.getString(KEY_SEALED, null) ?: return null
        return try {
            val credentials = RelayCodec.json.decodeFromString(WearCredentials.serializer(), decrypt(sealed))
            StoredCredentials(credentials, preferences.getLong(KEY_RECEIVED_AT, 0))
        } catch (_: Exception) {
            // The Keystore key is gone or the data is damaged: ask the phone again.
            preferences.edit().clear().apply()
            null
        }
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
        const val KEY_ALIAS = "lifeos-wear-credentials"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val PREFERENCES = "wear-credentials"
        const val KEY_SEALED = "sealed"
        const val KEY_RECEIVED_AT = "received-at"
    }
}

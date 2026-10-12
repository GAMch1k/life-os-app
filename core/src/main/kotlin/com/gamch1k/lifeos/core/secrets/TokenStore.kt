package com.gamch1k.lifeos.core.secrets

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.gamch1k.lifeos.core.api.Connection
import com.gamch1k.lifeos.core.wearlink.WearCredentials
import com.gamch1k.lifeos.core.wearlink.WearTokenSource
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.net.URI
import java.security.GeneralSecurityException
import java.security.KeyStore
import java.util.Base64
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

        /**
         * The values as pasted, surrounding whitespace (and invisible characters
         * such as a zero-width space) removed. Null when either is empty or has
         * anything but visible ASCII inside: an HTTP header cannot carry it, and
         * Cloudflare's IDs and secrets never contain it.
         */
        fun parse(clientId: String, clientSecret: String): ServiceToken? {
            val id = clean(clientId)
            val secret = clean(clientSecret)
            if (id.isEmpty() || secret.isEmpty() || !id.all(::visibleAscii) || !secret.all(::visibleAscii)) return null
            return ServiceToken(id, secret)
        }

        private fun visibleAscii(char: Char) = char in '!'..'~'

        private fun clean(value: String) = value.trim { it.isWhitespace() || it == '\u200B' || it == '\uFEFF' || it == '\u200C' || it == '\u200D' }
    }
}

/** The Life OS server address the owner types: always https, no trailing slash. */
object ServerUrl {
    const val DEFAULT = "https://lifeos.gamch1k.com"

    /** `lifeos.example.com` → `https://lifeos.example.com`; null for anything that is not an https URL with a host. */
    fun normalize(input: String): String? {
        val trimmed = input.trim()
        if (trimmed.isEmpty() || trimmed.any(Char::isWhitespace)) return null
        val withScheme = (if ("://" in trimmed) trimmed else "https://$trimmed").trimEnd('/')
        val uri = runCatching { URI(withScheme) }.getOrNull() ?: return null
        if (!uri.scheme.equals("https", ignoreCase = true) || uri.host.isNullOrEmpty()) return null
        if (uri.rawQuery != null || uri.rawFragment != null || uri.rawUserInfo != null) return null
        return withScheme
    }
}

/** Encrypts short secrets. The app uses [KeystoreCipher]; tests use a fake. */
interface SecretCipher {
    fun encrypt(plain: String): String

    /** Throws [GeneralSecurityException] or [IllegalArgumentException] when [sealed] cannot be opened. */
    fun decrypt(sealed: String): String
}

/**
 * AES-256-GCM with a key that lives in the Android Keystore and never leaves
 * it. Output: base64(iv) ":" base64(ciphertext and tag), the format the 0.1
 * network check used, so its stored token can be migrated.
 */
class KeystoreCipher(private val alias: String = "lifeos-service-token") : SecretCipher {
    private fun key(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getKey(alias, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }

    override fun encrypt(plain: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, key()) }
        val sealed = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        return encoder.encodeToString(cipher.iv) + ":" + encoder.encodeToString(sealed)
    }

    override fun decrypt(sealed: String): String {
        val (iv, data) = sealed.split(':').takeIf { it.size == 2 } ?: throw IllegalArgumentException("bad ciphertext")
        val cipher = Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, decoder.decode(iv)))
        }
        return cipher.doFinal(decoder.decode(data)).toString(Charsets.UTF_8)
    }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        val encoder: Base64.Encoder = Base64.getEncoder()
        val decoder: Base64.Decoder = Base64.getDecoder()
    }
}

/**
 * The server address and the service token. The token's two values are kept
 * only as [SecretCipher] ciphertext in the DataStore; the address is plain.
 * Nothing here logs.
 */
class TokenStore(private val store: DataStore<Preferences>, private val cipher: SecretCipher) : WearTokenSource {
    /** The last ciphertext opened and its result, so a request costs no Keystore work. */
    @Volatile
    private var opened: Pair<List<String?>, Connection?>? = null

    /** The current connection, or null when the phone is not set up (or the key was lost). */
    val connection: Flow<Connection?> = store.data.map(::open)

    suspend fun current(): Connection? = connection.first()

    /** What the watch gets (docs/wear.md): the phone's server and token, until each device has its own. */
    override suspend fun load(): WearCredentials? = current()?.let { WearCredentials(it.baseUrl, it.token.clientId, it.token.clientSecret) }

    /** The saved server address, or the default. */
    suspend fun serverUrl(): String = store.data.first()[SERVER_URL] ?: ServerUrl.DEFAULT

    suspend fun save(baseUrl: String, token: ServiceToken) {
        val url = requireNotNull(ServerUrl.normalize(baseUrl)) { "not an https URL" }
        require(token.clientId.isNotBlank() && token.clientSecret.isNotBlank()) { "both values are required" }
        val id = cipher.encrypt(token.clientId)
        val secret = cipher.encrypt(token.clientSecret)
        store.edit {
            it[SERVER_URL] = url
            it[CLIENT_ID] = id
            it[CLIENT_SECRET] = secret
        }
    }

    /** Sign out: forgets the token, keeps the server address for the next sign-in. */
    suspend fun clear() {
        store.edit {
            it.remove(CLIENT_ID)
            it.remove(CLIENT_SECRET)
        }
    }

    private fun open(preferences: Preferences): Connection? {
        val sealed = listOf(preferences[SERVER_URL], preferences[CLIENT_ID], preferences[CLIENT_SECRET])
        opened?.let { (key, connection) -> if (key == sealed) return connection }
        val connection = decrypt(preferences)
        opened = sealed to connection
        return connection
    }

    private fun decrypt(preferences: Preferences): Connection? {
        val id = preferences[CLIENT_ID] ?: return null
        val secret = preferences[CLIENT_SECRET] ?: return null
        val url = preferences[SERVER_URL] ?: ServerUrl.DEFAULT
        return try {
            Connection(url, ServiceToken(cipher.decrypt(id), cipher.decrypt(secret)))
        } catch (_: GeneralSecurityException) {
            // The Keystore key is gone (e.g. app data restored on another phone): the ciphertext is useless.
            null
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    /**
     * Moves the token the 0.1 network check stored in SharedPreferences (same
     * Keystore key and format) into this store, once. Returns true if it did.
     */
    suspend fun migrateFrom(context: Context): Boolean {
        val legacy = context.applicationContext.getSharedPreferences(LEGACY_PREFERENCES, Context.MODE_PRIVATE)
        val id = legacy.getString(LEGACY_ID, null)
        val secret = legacy.getString(LEGACY_SECRET, null)
        if (id == null || secret == null) return false
        var migrated = false
        if (current() == null) {
            try {
                save(serverUrl(), ServiceToken(cipher.decrypt(id), cipher.decrypt(secret)))
                migrated = true
            } catch (_: GeneralSecurityException) {
            } catch (_: IllegalArgumentException) {
            }
        }
        legacy.edit().clear().apply()
        return migrated
    }

    /** Makes this the store [ServiceTokenStore] reads; the app calls it once at start. */
    fun install() {
        installed = this
    }

    companion object {
        @Volatile
        internal var installed: TokenStore? = null
            private set

        private val SERVER_URL = stringPreferencesKey("server-url")
        private val CLIENT_ID = stringPreferencesKey("client-id")
        private val CLIENT_SECRET = stringPreferencesKey("client-secret")
        private const val LEGACY_PREFERENCES = "service-token"
        private const val LEGACY_ID = "client-id"
        private const val LEGACY_SECRET = "client-secret"
    }
}

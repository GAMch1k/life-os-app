package com.gamch1k.lifeos.core.secrets

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.gamch1k.lifeos.core.api.ChildKey
import com.gamch1k.lifeos.core.api.Connection
import com.gamch1k.lifeos.core.api.DeviceIdentity
import com.gamch1k.lifeos.core.api.DeviceKeyListener
import com.gamch1k.lifeos.core.api.LifeOsJson
import com.gamch1k.lifeos.core.api.PairedDevice
import com.gamch1k.lifeos.core.config.LifeOsEndpoints
import com.gamch1k.lifeos.core.pairing.PairingTarget
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import java.net.URI
import java.security.GeneralSecurityException
import java.security.KeyStore
import java.time.Instant
import java.util.Base64
import java.util.UUID
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
    /** The build's server (`lifeos.serverUrl`): offered at onboarding, used until one is saved. */
    val DEFAULT: String = LifeOsEndpoints.SERVER_URL

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

/** The account this phone is paired with (P21-06), as Settings shows it. */
data class Pairing(
    /** The server that issued the key: the app's server. */
    val server: String,
    val keyId: String,
    /** What Settings → Devices calls this phone. */
    val deviceName: String,
    val username: String?,
    val displayName: String?,
    val pairedAt: String?,
    /** Life OS answered 401 to the key: the phone must be paired again. Cleared by the next success. */
    val refused: Boolean,
    /** The watch's own key, once the phone made one. */
    val watch: WatchKeyInfo?,
) {
    /** "Anna (anna)", "anna", or null before the account is known. */
    val account: String?
        get() = when {
            username.isNullOrBlank() -> displayName?.takeIf { it.isNotBlank() }
            displayName.isNullOrBlank() || displayName == username -> username
            else -> "$displayName ($username)"
        }
}

/** The watch's key as Settings › Watch shows it (never the key itself). */
data class WatchKeyInfo(val keyId: String, val name: String, val createdAt: String?, val refused: Boolean)

/** The watch's key itself, for the relay and the hand-over to the watch. */
class StoredWatchKey(val key: DeviceKey, val keyId: String, val refused: Boolean) {
    override fun toString() = "StoredWatchKey($keyId, refused=$refused)"
}

/**
 * A device key the phone forgot but Life OS has not confirmed signing out
 * yet (no network, a timeout): unpairing, signing out, pairing again or
 * changing the server queued it, and the app tries again when online. The
 * key, and the service token that gets the request through Cloudflare
 * Access, are kept encrypted for that only.
 */
class PendingRevoke(val id: String, val server: String, val key: DeviceKey, val token: ServiceToken?, val sinceMillis: Long, val failed: Boolean) {
    val connection: Connection get() = Connection(server, token, key)

    override fun toString() = "PendingRevoke($id, $server, failed=$failed)"
}

/** What the app tells the person about a [PendingRevoke] (never the key). */
data class RevokeStatus(val server: String, val sinceMillis: Long, val failed: Boolean)

/** A Custom Tab pairing on its way (the browser has the page; the link comes back with [state]). */
class PendingPairing(val server: String, val state: String, val verifier: String, val startedAtMillis: Long) {
    override fun toString() = "PendingPairing($server, …)"
}

/**
 * The server address and the phone's credentials there: the device key
 * (once paired with an account, P21-06), the Cloudflare Access service
 * token, and the watch's key the phone made. Secrets are kept only as
 * [SecretCipher] ciphertext in the DataStore; the address, key ids and the
 * account's names are plain. Nothing here logs.
 *
 * The device key always belongs to the saved server: saving another server
 * forgets the pairing, and pairing with another server forgets the service
 * token, which belongs to the old server's Access.
 */
class TokenStore(private val store: DataStore<Preferences>, private val cipher: SecretCipher) : DeviceKeyListener {
    /** The last ciphertext opened and its result, so a request costs no Keystore work. */
    @Volatile
    private var opened: Pair<List<String?>, Connection?>? = null

    @Volatile
    private var openedWatch: Pair<String, DeviceKey?>? = null

    /** The current connection, or null when the phone is not set up (or the key was lost). */
    val connection: Flow<Connection?> = store.data.map(::open)

    /** The pairing, or null while the phone is not paired. */
    val pairing: Flow<Pairing?> = store.data.map(::pairingOf)

    /** Whether the owner dismissed Home's "Pair this phone" card. */
    val pairPromptDismissed: Flow<Boolean> = store.data.map { it[PAIR_PROMPT_DISMISSED] == true }

    /** The keys Life OS has not confirmed signing out yet ([PendingRevoke]), for the notice. */
    val revokes: Flow<List<RevokeStatus>> = store.data.map { preferences ->
        storedRevokes(preferences).map { RevokeStatus(it.server, it.since, it.failed) }
    }

    suspend fun current(): Connection? = connection.first()

    suspend fun currentPairing(): Pairing? = pairing.first()

    /** The saved server address, or the default. */
    suspend fun serverUrl(): String = store.data.first()[SERVER_URL] ?: ServerUrl.DEFAULT

    /**
     * Saves [baseUrl] and its service token. Another server than the saved
     * one forgets the pairing: a device key belongs to its server.
     */
    suspend fun save(baseUrl: String, token: ServiceToken) {
        val url = requireNotNull(ServerUrl.normalize(baseUrl)) { "not an https URL" }
        require(token.clientId.isNotBlank() && token.clientSecret.isNotBlank()) { "both values are required" }
        val id = cipher.encrypt(token.clientId)
        val secret = cipher.encrypt(token.clientSecret)
        store.edit {
            if ((it[SERVER_URL] ?: ServerUrl.DEFAULT) != url) removePairing(it)
            it[SERVER_URL] = url
            it[CLIENT_ID] = id
            it[CLIENT_SECRET] = secret
        }
    }

    /**
     * Keeps the device key Life OS issued at [server] for this phone, named
     * [deviceName]. A previous pairing and its watch key go; so does the
     * service token when [server] is another server. A phone that is set up
     * moves to another server only when the person typed it ([typed]), never
     * because a link said so ([PairingTarget]): otherwise this throws
     * [IllegalStateException] and changes nothing.
     */
    suspend fun savePairing(server: String, paired: PairedDevice, deviceName: String, typed: String? = null, now: Instant = Instant.now()) {
        val url = requireNotNull(ServerUrl.normalize(server)) { "not an https URL" }
        val key = requireNotNull(DeviceKey.parse(paired.key)) { "not a device key" }
        val sealed = cipher.encrypt(key.value)
        store.edit {
            val configured = (it[SERVER_URL] ?: ServerUrl.DEFAULT).takeIf { _ -> it[CLIENT_ID] != null || it[DEVICE_KEY] != null }
            check(PairingTarget.mayMoveTo(url, configured, typed)) { "this phone is set up for another server" }
            if ((it[SERVER_URL] ?: ServerUrl.DEFAULT) != url) {
                it.remove(CLIENT_ID)
                it.remove(CLIENT_SECRET)
            }
            removePairing(it)
            it[SERVER_URL] = url
            it[DEVICE_KEY] = sealed
            it[DEVICE_KEY_ID] = paired.keyId
            it[DEVICE_NAME] = paired.name.ifBlank { deviceName }
            paired.user?.username?.takeIf { name -> name.isNotBlank() }?.let { name -> it[ACCOUNT_USERNAME] = name }
            paired.user?.name?.takeIf { name -> name.isNotBlank() }?.let { name -> it[ACCOUNT_NAME] = name }
            it[PAIRED_AT] = paired.createdAt ?: now.toString()
        }
    }

    /** What `GET /api/auth/devices/me` said: the account's names may have changed. */
    suspend fun updateIdentity(identity: DeviceIdentity) {
        store.edit {
            if (it[DEVICE_KEY_ID] != identity.keyId) return@edit
            identity.user?.username?.takeIf { name -> name.isNotBlank() }?.let { name -> it[ACCOUNT_USERNAME] = name }
            val name = identity.user?.name?.takeIf { name -> name.isNotBlank() }
            if (name != null) it[ACCOUNT_NAME] = name else it.remove(ACCOUNT_NAME)
            if (identity.name.isNotBlank()) it[DEVICE_NAME] = identity.name
        }
    }

    /** Unpair: forgets the device key and the watch's; the service token, if any, stays. */
    suspend fun clearPairing() {
        store.edit { removePairing(it) }
    }

    /** Sign out: forgets the token, the pairing and the watch's key; keeps the server address for the next sign-in. */
    suspend fun clear() {
        store.edit {
            it.remove(CLIENT_ID)
            it.remove(CLIENT_SECRET)
            removePairing(it)
            removePending(it)
            it.remove(PAIR_PROMPT_DISMISSED)
        }
    }

    suspend fun dismissPairPrompt() {
        store.edit { it[PAIR_PROMPT_DISMISSED] = true }
    }

    // ---- What Life OS said about the keys (DeviceKeyListener)

    /** Life OS answered 401 to [key]: if it is still this phone's key, the phone must pair again. */
    override suspend fun refused(key: DeviceKey) {
        if (current()?.deviceKey != key) return
        store.edit { it[DEVICE_REFUSED] = true }
    }

    /** A success with [key]: a "no longer connected" from an earlier answer is over. */
    override suspend fun accepted(key: DeviceKey) {
        if (store.data.first()[DEVICE_REFUSED] != true || current()?.deviceKey != key) return
        store.edit { it.remove(DEVICE_REFUSED) }
    }

    // ---- The watch's key (made by this phone, POST /api/auth/devices/child)

    /** The watch's key, when this phone made one with its current key. */
    suspend fun watchKey(): StoredWatchKey? {
        val preferences = store.data.first()
        val sealed = preferences[WATCH_KEY] ?: return null
        val keyId = preferences[WATCH_KEY_ID] ?: return null
        if (preferences[WATCH_PARENT_ID] == null || preferences[WATCH_PARENT_ID] != preferences[DEVICE_KEY_ID]) return null
        val key = openWatch(sealed) ?: return null
        return StoredWatchKey(key, keyId, refused = preferences[WATCH_REFUSED] == true)
    }

    /** Keeps the watch's new key, made by the phone key [parentKeyId]; it replaces the previous one. */
    suspend fun saveWatchKey(child: ChildKey, parentKeyId: String, watchName: String, now: Instant = Instant.now()) {
        val key = requireNotNull(DeviceKey.parse(child.key)) { "not a device key" }
        val sealed = cipher.encrypt(key.value)
        store.edit {
            // The phone was unpaired or paired again meanwhile: this key belongs to no current pairing.
            if (it[DEVICE_KEY_ID] != parentKeyId) return@edit
            it[WATCH_KEY] = sealed
            it[WATCH_KEY_ID] = child.keyId
            it[WATCH_PARENT_ID] = parentKeyId
            it[WATCH_NAME] = child.name.ifBlank { watchName }
            it[WATCH_CREATED_AT] = child.createdAt ?: now.toString()
            it.remove(WATCH_REFUSED)
        }
    }

    suspend fun watchKeyRefused(key: DeviceKey) {
        if (watchKey()?.key != key) return
        store.edit { it[WATCH_REFUSED] = true }
    }

    suspend fun watchKeyAccepted(key: DeviceKey) {
        if (store.data.first()[WATCH_REFUSED] != true || watchKey()?.key != key) return
        store.edit { it.remove(WATCH_REFUSED) }
    }

    /** The server the phone is set up for (a service token or a key), or null on a fresh install. */
    suspend fun configuredServer(): String? = store.data.first().let { preferences ->
        (preferences[SERVER_URL] ?: ServerUrl.DEFAULT).takeIf { preferences[CLIENT_ID] != null || preferences[DEVICE_KEY] != null }
    }

    // ---- Keys Life OS has not confirmed signing out yet ([PendingRevoke])

    /** Queues [connection]'s key (with its server and service token) to be signed out when online. */
    suspend fun queueRevoke(connection: Connection, failed: Boolean = false, nowMillis: Long = System.currentTimeMillis()): String? {
        val key = connection.deviceKey ?: return null
        val entry = StoredRevoke(
            id = UUID.randomUUID().toString(),
            server = connection.baseUrl,
            key = cipher.encrypt(key.value),
            clientId = connection.token?.let { cipher.encrypt(it.clientId) },
            clientSecret = connection.token?.let { cipher.encrypt(it.clientSecret) },
            since = nowMillis,
            failed = failed,
        )
        store.edit { preferences ->
            // The same key queued twice (a second tap) stays one entry.
            val kept = storedRevokes(preferences).filterNot { openRevoke(it)?.key == key }
            writeRevokes(preferences, kept + entry)
        }
        return entry.id
    }

    /** The queued keys, opened; one that can no longer be opened (the Keystore key is gone) is left out. */
    suspend fun pendingRevokes(): List<PendingRevoke> = storedRevokes(store.data.first()).mapNotNull { openRevoke(it) }

    /** Life OS confirmed (or the key was gone already): forget it. */
    suspend fun revokeDone(id: String) {
        store.edit { preferences -> writeRevokes(preferences, storedRevokes(preferences).filterNot { it.id == id }) }
    }

    /** No more tries: the notice tells the person to sign the phone out on the web. */
    suspend fun revokeFailed(id: String) {
        store.edit { preferences ->
            writeRevokes(preferences, storedRevokes(preferences).map { if (it.id == id) it.copy(failed = true) else it })
        }
    }

    /** The notice's Try again: the given-up ones are tried again, as new. */
    suspend fun retryFailedRevokes(nowMillis: Long = System.currentTimeMillis()) {
        store.edit { preferences ->
            writeRevokes(preferences, storedRevokes(preferences).map { if (it.failed) it.copy(failed = false, since = nowMillis) else it })
        }
    }

    /** The notice's Dismiss: the person signs the phone out on the web. */
    suspend fun dismissFailedRevokes() {
        store.edit { preferences -> writeRevokes(preferences, storedRevokes(preferences).filterNot { it.failed }) }
    }

    @Serializable
    private data class StoredRevoke(
        val id: String,
        val server: String,
        /** Sealed, as are the token's two values. */
        val key: String,
        val clientId: String? = null,
        val clientSecret: String? = null,
        val since: Long,
        val failed: Boolean = false,
    ) {
        override fun toString() = "StoredRevoke($id, $server)"
    }

    private fun storedRevokes(preferences: Preferences): List<StoredRevoke> {
        val json = preferences[PENDING_REVOKES] ?: return emptyList()
        return runCatching { LifeOsJson.decodeFromString(ListSerializer(StoredRevoke.serializer()), json) }.getOrDefault(emptyList())
    }

    private fun writeRevokes(preferences: MutablePreferences, revokes: List<StoredRevoke>) {
        if (revokes.isEmpty()) preferences.remove(PENDING_REVOKES)
        else preferences[PENDING_REVOKES] = LifeOsJson.encodeToString(ListSerializer(StoredRevoke.serializer()), revokes)
    }

    private fun openRevoke(stored: StoredRevoke): PendingRevoke? = try {
        val key = DeviceKey.parse(cipher.decrypt(stored.key)) ?: return null
        val token = if (stored.clientId != null && stored.clientSecret != null) ServiceToken(cipher.decrypt(stored.clientId), cipher.decrypt(stored.clientSecret)) else null
        PendingRevoke(stored.id, stored.server, key, token, stored.since, stored.failed)
    } catch (_: GeneralSecurityException) {
        null
    } catch (_: IllegalArgumentException) {
        null
    }

    // ---- The Custom Tab flow's verifier and state, while the browser has the page

    suspend fun savePending(pending: PendingPairing) {
        val verifier = cipher.encrypt(pending.verifier)
        store.edit {
            it[PENDING_SERVER] = pending.server
            it[PENDING_STATE] = pending.state
            it[PENDING_VERIFIER] = verifier
            it[PENDING_AT] = pending.startedAtMillis
        }
    }

    /** The pairing the browser was opened for, if it is younger than [maxAgeMillis]. */
    suspend fun pending(nowMillis: Long = System.currentTimeMillis(), maxAgeMillis: Long = PENDING_MAX_AGE_MILLIS): PendingPairing? {
        val preferences = store.data.first()
        val server = preferences[PENDING_SERVER] ?: return null
        val state = preferences[PENDING_STATE] ?: return null
        val sealed = preferences[PENDING_VERIFIER] ?: return null
        val at = preferences[PENDING_AT] ?: return null
        if (nowMillis - at !in 0..maxAgeMillis) return null
        val verifier = try {
            cipher.decrypt(sealed)
        } catch (_: GeneralSecurityException) {
            return null
        } catch (_: IllegalArgumentException) {
            return null
        }
        return PendingPairing(server, state, verifier, at)
    }

    suspend fun clearPending() {
        store.edit { removePending(it) }
    }

    // ---- Reading

    private fun open(preferences: Preferences): Connection? {
        val sealed = listOf(preferences[SERVER_URL], preferences[CLIENT_ID], preferences[CLIENT_SECRET], preferences[DEVICE_KEY])
        opened?.let { (key, connection) -> if (key == sealed) return connection }
        val connection = decrypt(preferences)
        opened = sealed to connection
        return connection
    }

    private fun decrypt(preferences: Preferences): Connection? {
        val id = preferences[CLIENT_ID]
        val secret = preferences[CLIENT_SECRET]
        val sealedKey = preferences[DEVICE_KEY]
        val url = preferences[SERVER_URL] ?: ServerUrl.DEFAULT
        return try {
            val token = if (id != null && secret != null) ServiceToken(cipher.decrypt(id), cipher.decrypt(secret)) else null
            val key = sealedKey?.let { DeviceKey.parse(cipher.decrypt(it)) ?: throw IllegalArgumentException("not a device key") }
            if (token == null && key == null) null else Connection(url, token, key)
        } catch (_: GeneralSecurityException) {
            // The Keystore key is gone (e.g. app data restored on another phone): the ciphertext is useless.
            null
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    private fun openWatch(sealed: String): DeviceKey? {
        openedWatch?.let { (key, value) -> if (key == sealed) return value }
        val key = try {
            DeviceKey.parse(cipher.decrypt(sealed))
        } catch (_: GeneralSecurityException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        }
        openedWatch = sealed to key
        return key
    }

    private fun pairingOf(preferences: Preferences): Pairing? {
        if (preferences[DEVICE_KEY] == null) return null
        val keyId = preferences[DEVICE_KEY_ID] ?: return null
        val watch = preferences[WATCH_KEY_ID]?.takeIf { preferences[WATCH_KEY] != null && preferences[WATCH_PARENT_ID] == keyId }?.let {
            WatchKeyInfo(it, preferences[WATCH_NAME].orEmpty(), preferences[WATCH_CREATED_AT], preferences[WATCH_REFUSED] == true)
        }
        return Pairing(
            server = preferences[SERVER_URL] ?: ServerUrl.DEFAULT,
            keyId = keyId,
            deviceName = preferences[DEVICE_NAME].orEmpty(),
            username = preferences[ACCOUNT_USERNAME],
            displayName = preferences[ACCOUNT_NAME],
            pairedAt = preferences[PAIRED_AT],
            refused = preferences[DEVICE_REFUSED] == true,
            watch = watch,
        )
    }

    private fun removePairing(preferences: MutablePreferences) {
        for (key in listOf(DEVICE_KEY, DEVICE_KEY_ID, DEVICE_NAME, ACCOUNT_USERNAME, ACCOUNT_NAME, PAIRED_AT, WATCH_KEY, WATCH_KEY_ID, WATCH_PARENT_ID, WATCH_NAME, WATCH_CREATED_AT)) {
            preferences.remove(key)
        }
        preferences.remove(DEVICE_REFUSED)
        preferences.remove(WATCH_REFUSED)
    }

    private fun removePending(preferences: MutablePreferences) {
        for (key in listOf(PENDING_SERVER, PENDING_STATE, PENDING_VERIFIER)) preferences.remove(key)
        preferences.remove(PENDING_AT)
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

        /** How long the browser may take for the Custom Tab flow (the code itself works 5 minutes). */
        const val PENDING_MAX_AGE_MILLIS = 15 * 60_000L

        private val SERVER_URL = stringPreferencesKey("server-url")
        private val CLIENT_ID = stringPreferencesKey("client-id")
        private val CLIENT_SECRET = stringPreferencesKey("client-secret")

        // The pairing (P21-06).
        private val DEVICE_KEY = stringPreferencesKey("device-key")
        private val DEVICE_KEY_ID = stringPreferencesKey("device-key-id")
        private val DEVICE_NAME = stringPreferencesKey("device-name")
        private val DEVICE_REFUSED = booleanPreferencesKey("device-key-refused")
        private val ACCOUNT_USERNAME = stringPreferencesKey("device-account-username")
        private val ACCOUNT_NAME = stringPreferencesKey("device-account-name")
        private val PAIRED_AT = stringPreferencesKey("device-paired-at")
        private val WATCH_KEY = stringPreferencesKey("watch-key")
        private val WATCH_KEY_ID = stringPreferencesKey("watch-key-id")
        private val WATCH_PARENT_ID = stringPreferencesKey("watch-key-parent-id")
        private val WATCH_NAME = stringPreferencesKey("watch-key-name")
        private val WATCH_CREATED_AT = stringPreferencesKey("watch-key-created-at")
        private val WATCH_REFUSED = booleanPreferencesKey("watch-key-refused")
        private val PENDING_SERVER = stringPreferencesKey("pair-pending-server")
        private val PENDING_STATE = stringPreferencesKey("pair-pending-state")
        private val PENDING_VERIFIER = stringPreferencesKey("pair-pending-verifier")
        private val PENDING_AT = longPreferencesKey("pair-pending-at")
        private val PAIR_PROMPT_DISMISSED = booleanPreferencesKey("pair-prompt-dismissed")

        // Keys to sign out when online: kept across sign-out (clear() leaves them).
        private val PENDING_REVOKES = stringPreferencesKey("device-keys-to-revoke")

        private const val LEGACY_PREFERENCES = "service-token"
        private const val LEGACY_ID = "client-id"
        private const val LEGACY_SECRET = "client-secret"
    }
}

package com.gamch1k.lifeos.core.pairing

import com.gamch1k.lifeos.core.api.LifeOsClient
import com.gamch1k.lifeos.core.api.LifeOsException
import com.gamch1k.lifeos.core.secrets.DeviceKey
import com.gamch1k.lifeos.core.secrets.TokenStore
import com.gamch1k.lifeos.core.wearlink.WearCredentials
import com.gamch1k.lifeos.core.wearlink.WearTokenSource
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The watch's own device key (life-os docs/api/devices.md, "The watch"):
 * a paired phone makes one child key for its watch
 * (`POST /api/auth/devices/child`), keeps it, uses it for every request it
 * relays for the watch, and hands it to the watch for its direct calls
 * (docs/wear.md, "Token handoff"). So Settings → Devices shows the watch's
 * own last use, and revoking the phone or the watch works as expected.
 *
 * - The key is made once per phone key, the first time the watch asks (for
 *   its credentials or a relayed request), and again only when the person
 *   asks ([replace], Settings › Watch › Connect the watch). A key Life OS
 *   refused is never replaced by itself: that would undo a revocation.
 * - An unpaired phone hands over and relays with its service token alone,
 *   as before pairing ([Credential.ServiceTokenOnly]).
 */
class WatchKeys(
    private val tokens: TokenStore,
    private val client: LifeOsClient,
    /** The watch's name for Settings → Devices, such as "Galaxy Watch8"; null when none is reachable. */
    private val watchName: suspend () -> String?,
) : WearTokenSource {
    private val mutex = Mutex()

    sealed interface Credential {
        /** The phone is not paired: the service token alone, as before P21-06. */
        data object ServiceTokenOnly : Credential

        /** The watch's own key. */
        class Key(val key: DeviceKey) : Credential {
            override fun toString() = "Key(…)"
        }

        /** No credential for the watch; [why] says what to do, for the watch's Connection screen and Settings. */
        data class Missing(val why: String) : Credential
    }

    /**
     * The credential the watch's requests carry. With [create], a paired
     * phone that never made a key for its watch makes one now; that call can
     * throw [LifeOsException] (no network, the phone's key refused).
     */
    suspend fun credential(create: Boolean = true): Credential {
        val connection = tokens.current() ?: return Credential.Missing(NOT_CONNECTED)
        if (connection.deviceKey == null) {
            return if (connection.token != null) Credential.ServiceTokenOnly else Credential.Missing(NOT_CONNECTED)
        }
        stored()?.let { return it }
        if (!create) return Credential.Missing(NO_KEY_YET)
        return mutex.withLock {
            stored() ?: Credential.Key(createLocked())
        }
    }

    /**
     * Settings › Watch › Connect the watch: a new key for the watch, which
     * revokes the previous one (and its push subscriptions) on Life OS.
     */
    suspend fun replace(): DeviceKey = mutex.withLock { createLocked() }

    /** What the watch gets over the Data Layer: the server, the service token if any, and its own key once paired. */
    override suspend fun load(): WearCredentials? {
        val connection = tokens.current() ?: return null
        val token = connection.token
        return when (val credential = credential()) {
            Credential.ServiceTokenOnly -> token?.let { WearCredentials(connection.baseUrl, it.clientId, it.clientSecret) }
            is Credential.Key -> WearCredentials(connection.baseUrl, token?.clientId.orEmpty(), token?.clientSecret.orEmpty(), credential.key.value)
            is Credential.Missing -> null
        }
    }

    /** Life OS answered 401 to the watch's key (a relayed request): Settings › Watch offers a new one. */
    suspend fun refused(key: DeviceKey) = tokens.watchKeyRefused(key)

    suspend fun accepted(key: DeviceKey) = tokens.watchKeyAccepted(key)

    private suspend fun stored(): Credential? {
        val pairing = tokens.currentPairing() ?: return null
        if (pairing.refused) return Credential.Missing(PHONE_REFUSED)
        val watch = tokens.watchKey() ?: return null
        return if (watch.refused) Credential.Missing(WATCH_REFUSED) else Credential.Key(watch.key)
    }

    private suspend fun createLocked(): DeviceKey {
        val pairing = tokens.currentPairing() ?: throw LifeOsException.NotSignedIn()
        val name = DeviceNames.clean(runCatching { watchName() }.getOrNull(), DeviceNames.WATCH_FALLBACK)
        val child = client.createChildKey(name)
        val key = DeviceKey.parse(child.key) ?: throw LifeOsException.Protocol("the watch's key was not usable")
        tokens.saveWatchKey(child, parentKeyId = pairing.keyId, watchName = name)
        return key
    }

    companion object {
        const val NOT_CONNECTED = "the phone is not connected to Life OS"
        const val NO_KEY_YET = "the phone has no key for the watch yet"
        const val PHONE_REFUSED = "the phone is no longer connected to Life OS: pair it again"
        const val WATCH_REFUSED = "Life OS no longer accepts the watch's key: on the phone, Settings › Watch › Connect the watch"
    }
}

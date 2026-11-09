package com.gamch1k.lifeos.core.secrets

import android.content.Context
import com.gamch1k.lifeos.core.api.Connection
import kotlinx.coroutines.runBlocking

/**
 * The 0.1 network check's token store, kept as a read-only view of the app's
 * [TokenStore] for code that still asks for it by this name (the watch link's
 * fallback without a WearLinkHost). The token itself lives only in the
 * TokenStore, entered at onboarding or under Settings, which the app installs
 * at start.
 */
class ServiceTokenStore(@Suppress("UNUSED_PARAMETER") context: Context) {
    /** Blocks briefly; call it off the main thread. Null when signed out. */
    fun load(): ServiceToken? = loadConnection()?.token

    /** The saved server with its token; blocks briefly, call it off the main thread. Null when signed out. */
    fun loadConnection(): Connection? = TokenStore.installed?.let { store -> runBlocking { store.current() } }
}

package com.gamch1k.lifeos.core.config

import com.gamch1k.lifeos.core.BuildConfig
import java.net.URI

/**
 * Where the apps get their updates, and the addresses the network check
 * probes on a server. There is no default server (P22-13): the server is the
 * one the person typed at the first run and the phone saved
 * ([com.gamch1k.lifeos.core.secrets.TokenStore.serverUrl]); the watch takes
 * it from the phone. Build settings decide where updates come from (README,
 * "Servers and updates"), for the phone and the watch app alike.
 */
object LifeOsEndpoints {
    /**
     * Where app updates come from while the app is connected to that very
     * server (`lifeos.updatesUrl`; by default the maintainer's own, private
     * Life OS): the phone's manifest and APKs, and the watch's own APK
     * download, at `/android/`. Never another saved server: an app connected
     * to one updates from [UPDATES_REPO] instead
     * ([com.gamch1k.lifeos.core.update.UpdateSource]).
     */
    val UPDATES_URL: String = BuildConfig.UPDATES_URL

    /**
     * The GitHub repository, `owner/name`, whose Releases the apps update from
     * when they are not connected to [UPDATES_URL] (`lifeos.updatesRepo`).
     */
    val UPDATES_REPO: String = BuildConfig.UPDATES_REPO

    /** [server]'s host name, such as `lifeos.example.com`: the name the network check looks up. */
    fun host(server: String): String = URI(server).host

    /** Served by Cloudflare's edge itself at [server]'s origin, without an Access login. */
    fun traceUrl(server: String): String = origin(server) + "/cdn-cgi/trace"

    /** A cheap authenticated read on [server]: one task. */
    fun tasksProbeUrl(server: String): String = server.trimEnd('/') + "/api/tasks/v1/tasks?limit=1"

    /**
     * Whether [a] and [b] share scheme, host and port. A server's Access token
     * may only go to its own origin: an update origin that is not the owner's
     * server gets no token.
     */
    fun sameOrigin(a: String, b: String): Boolean {
        val left = runCatching { URI(a) }.getOrNull() ?: return false
        val right = runCatching { URI(b) }.getOrNull() ?: return false
        if (left.host.isNullOrEmpty() || right.host.isNullOrEmpty()) return false
        return left.scheme.equals(right.scheme, ignoreCase = true) &&
            left.host.equals(right.host, ignoreCase = true) &&
            port(left) == port(right)
    }

    private fun origin(url: String): String {
        val uri = URI(url)
        return "${uri.scheme}://${uri.rawAuthority}"
    }

    private fun port(uri: URI): Int = when {
        uri.port != -1 -> uri.port
        uri.scheme.equals("https", ignoreCase = true) -> 443
        uri.scheme.equals("http", ignoreCase = true) -> 80
        else -> -1
    }
}

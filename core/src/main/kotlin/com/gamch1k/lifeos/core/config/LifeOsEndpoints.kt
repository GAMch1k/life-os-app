package com.gamch1k.lifeos.core.config

import com.gamch1k.lifeos.core.BuildConfig
import java.net.URI

/**
 * Where Life OS lives on the internet (behind Cloudflare and Cloudflare Access).
 * Two build settings decide it (README, "Servers"), for the phone and the
 * watch app alike; both default to the official server, `https://lifeos.gamch1k.com`.
 */
object LifeOsEndpoints {
    /**
     * The server onboarding offers (`lifeos.serverUrl`), used until the owner
     * saves another: an https origin, no trailing slash. The server in use is
     * the saved one ([com.gamch1k.lifeos.core.secrets.TokenStore.serverUrl]).
     */
    val SERVER_URL: String = BuildConfig.SERVER_URL

    /**
     * Where app updates come from (`lifeos.updatesUrl`): the phone's manifest
     * and APKs, and the watch's own APK download. Never the saved server, so
     * switching servers never switches the update source.
     */
    val UPDATES_URL: String = BuildConfig.UPDATES_URL

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

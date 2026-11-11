package com.gamch1k.lifeos.core.update

import com.gamch1k.lifeos.core.api.UpdateManifest
import com.gamch1k.lifeos.core.config.LifeOsEndpoints
import java.net.URI

/**
 * Where the apps' updates come from (P20-06; docs/operations/android-releases.md
 * in the life-os repository). Both carry the same manifest (`updates.json`,
 * format 1) and get the same checks: the manifest's size and SHA-256, then the
 * package, the version, and the signer, which must be the installed app's own.
 */
sealed interface UpdateSource {
    /**
     * `/android/updates` on the update origin (`lifeos.updatesUrl`, the
     * official server), with the server's Access token: the app is connected
     * to that server, which is the owner's case. Its APKs are paths under
     * `/android/` on the same origin.
     */
    data object UpdateOrigin : UpdateSource

    /**
     * The latest GitHub Release of [repo] (`lifeos.updatesRepo`), for every
     * app that is not connected to the update origin: another server, or none
     * yet. No token ever goes there. Its APKs are assets of a release of
     * [repo]; downloads follow redirects only to GitHub's own asset hosts
     * ([GitHubReleases.mayRedirectTo]).
     */
    data class GitHubRepo(val repo: String) : UpdateSource

    /** For the owner: "the official server (lifeos.example.com)", "GitHub (owner/name releases)". */
    fun describe(updatesUrl: String = LifeOsEndpoints.UPDATES_URL): String = when (this) {
        UpdateOrigin -> "the official server (${runCatching { URI(updatesUrl).host }.getOrNull() ?: updatesUrl})"
        is GitHubRepo -> "GitHub ($repo releases)"
    }

    /** Where a person installs a version by hand. */
    fun downloadPage(updatesUrl: String = LifeOsEndpoints.UPDATES_URL): String = when (this) {
        UpdateOrigin -> updatesUrl.trimEnd('/') + "/android/"
        is GitHubRepo -> GitHubReleases.releasesPage(repo)
    }

    companion object {
        /**
         * The source for an app connected to [server] (null: not connected
         * yet): the update origin only when [server] is that origin, else
         * GitHub Releases of [repo]. The token for another server never goes
         * to the update origin, and the update origin's `/android/` stays
         * behind Access.
         */
        fun choose(server: String?, updatesUrl: String = LifeOsEndpoints.UPDATES_URL, repo: String = LifeOsEndpoints.UPDATES_REPO): UpdateSource =
            if (server != null && LifeOsEndpoints.sameOrigin(server, updatesUrl)) UpdateOrigin else GitHubRepo(repo)
    }
}

/** A manifest and the source it came from; its APK addresses are checked against that source. */
data class FetchedManifest(val source: UpdateSource, val manifest: UpdateManifest)

/**
 * The GitHub side of [UpdateSource.GitHubRepo]: what tools/publish-android-release
 * in the life-os repository publishes, a release `v<versionName>-<versionCode>`
 * with `lifeos-phone-….apk`, `lifeos-wear-….apk` and `updates.json`, whose
 * `url`s are those assets' download addresses. The latest release's manifest
 * is at a fixed address, which GitHub redirects to that release's asset and
 * then to its asset host.
 */
object GitHubReleases {
    /** The manifest's asset name. */
    const val MANIFEST = "updates.json"

    /** github.com → the release's download → the asset host is 2; a few more for slack. */
    const val MAX_REDIRECTS = 5

    /** Where github.com sends release downloads: GitHub's own asset hosts. */
    val ASSET_HOSTS = setOf("release-assets.githubusercontent.com", "objects.githubusercontent.com")

    private val REPO = Regex("^[A-Za-z0-9-]+/[A-Za-z0-9._-]+$")
    private const val TAG = "[0-9A-Za-z][0-9A-Za-z._+-]{0,127}"
    private const val NAME = "[0-9A-Za-z][0-9A-Za-z._+-]*"

    fun isRepo(repo: String): Boolean = REPO.matches(repo) && ".." !in repo

    /** The latest release's manifest: `https://github.com/<repo>/releases/latest/download/updates.json`. */
    fun manifestUrl(repo: String): String = "https://github.com/$repo/releases/latest/download/$MANIFEST"

    fun releasesPage(repo: String): String = "https://github.com/$repo/releases"

    /**
     * Whether [url] is [app]'s APK in a release of [repo]:
     * `https://github.com/<repo>/releases/download/<tag>/lifeos-<app>-<versionName>-<versionCode>.apk`.
     * Nothing else: no other host, repository, scheme, port, query, or `..`.
     */
    fun isApkUrl(repo: String, app: ReleaseApp, url: String): Boolean {
        if (!isRepo(repo) || ".." in url) return false
        val pattern = Regex(
            "^https://github\\.com/${Regex.escape(repo)}/releases/download/$TAG/lifeos-${app.key}-$NAME-[0-9]+\\.apk$",
            RegexOption.IGNORE_CASE,
        )
        return pattern.matches(url)
    }

    /** Whether [url] is an APK of either app in a release of [repo]. */
    fun isApkUrl(repo: String, url: String): Boolean = ReleaseApp.entries.any { isApkUrl(repo, it, url) }

    /**
     * Whether a download from [repo]'s releases may follow a redirect to
     * [url]: https on the default port, without a user name, to a download of
     * [repo]'s releases on github.com or to one of [ASSET_HOSTS]. GitHub signs
     * the asset host's address; the bytes are checked against the manifest
     * anyway.
     */
    fun mayRedirectTo(repo: String, url: String): Boolean {
        val uri = runCatching { URI(url) }.getOrNull() ?: return false
        if (!uri.scheme.equals("https", ignoreCase = true) || uri.rawUserInfo != null) return false
        if (uri.port != -1 && uri.port != 443) return false
        val host = uri.host?.lowercase() ?: return false
        val path = uri.rawPath ?: return false
        return when (host) {
            in ASSET_HOSTS -> true
            "github.com" -> isRepo(repo) && ".." !in path && path.startsWith("/$repo/releases/download/", ignoreCase = true)
            else -> false
        }
    }

    /** [location] (a redirect's `Location`, perhaps relative) against [base]; null when it can't be read. */
    fun resolve(base: String, location: String): String? = runCatching { URI(base).resolve(location.trim()).toString() }.getOrNull()

    /** [url]'s host, for messages. */
    fun host(url: String?): String = url?.let { runCatching { URI(it).host }.getOrNull() } ?: "an unknown host"
}

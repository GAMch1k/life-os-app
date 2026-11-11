package com.gamch1k.lifeos.core.update

import com.gamch1k.lifeos.core.config.LifeOsEndpoints
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Where updates come from (P20-06): the update origin for its own server, GitHub Releases for every other case. */
class UpdateSourceTest {
    private val origin = "https://lifeos.example.com"
    private val repo = "GAMch1k/life-os-app"

    @Test
    fun `only an app connected to the update origin updates from it`() {
        assertEquals(UpdateSource.UpdateOrigin, UpdateSource.choose("https://lifeos.example.com", origin, repo))
        assertEquals("the same origin, spelled differently", UpdateSource.UpdateOrigin, UpdateSource.choose("https://LifeOS.example.com:443/", origin, repo))
        assertEquals(UpdateSource.GitHubRepo(repo), UpdateSource.choose("https://my-own.example.org", origin, repo))
        assertEquals("another port is another server", UpdateSource.GitHubRepo(repo), UpdateSource.choose("https://lifeos.example.com:8443", origin, repo))
        assertEquals("a subdomain is another server", UpdateSource.GitHubRepo(repo), UpdateSource.choose("https://x.lifeos.example.com", origin, repo))
        assertEquals("not connected yet", UpdateSource.GitHubRepo(repo), UpdateSource.choose(null, origin, repo))
    }

    @Test
    fun `the build's settings are the defaults`() {
        assertEquals(UpdateSource.UpdateOrigin, UpdateSource.choose(LifeOsEndpoints.UPDATES_URL))
        assertEquals(UpdateSource.GitHubRepo(LifeOsEndpoints.UPDATES_REPO), UpdateSource.choose("https://my-own.example.org"))
        assertTrue(GitHubReleases.isRepo(LifeOsEndpoints.UPDATES_REPO))
    }

    @Test
    fun `the owner is told where updates come from and where to install by hand`() {
        assertEquals("the official server (lifeos.example.com)", UpdateSource.UpdateOrigin.describe(origin))
        assertEquals("https://lifeos.example.com/android/", UpdateSource.UpdateOrigin.downloadPage(origin))
        assertEquals("GitHub (GAMch1k/life-os-app releases)", UpdateSource.GitHubRepo(repo).describe(origin))
        assertEquals("https://github.com/GAMch1k/life-os-app/releases", UpdateSource.GitHubRepo(repo).downloadPage(origin))
    }

    @Test
    fun `the latest release's manifest has a fixed address`() {
        assertEquals("https://github.com/GAMch1k/life-os-app/releases/latest/download/updates.json", GitHubReleases.manifestUrl(repo))
    }

    @Test
    fun `an APK address must be that app's asset of a release of the repository`() {
        val phone = "https://github.com/GAMch1k/life-os-app/releases/download/v0.4.6-13/lifeos-phone-0.4.6-13.apk"
        assertTrue(GitHubReleases.isApkUrl(repo, ReleaseApp.PHONE, phone))
        assertTrue(GitHubReleases.isApkUrl(repo, phone))
        assertFalse(GitHubReleases.isApkUrl(repo, ReleaseApp.WEAR, phone))
        assertTrue(GitHubReleases.isApkUrl(repo, ReleaseApp.WEAR, phone.replace("phone", "wear")))
        assertFalse("another repository", GitHubReleases.isApkUrl("someone/else", phone))
        assertFalse("not a repository", GitHubReleases.isApkUrl("../x", phone))
        assertFalse(GitHubReleases.isApkUrl(repo, phone.replace("v0.4.6-13", "..")))
        assertFalse(GitHubReleases.isApkUrl(repo, phone.replace("https://", "http://")))
        assertFalse(GitHubReleases.isApkUrl(repo, phone.replace("github.com", "github.com.evil.example")))
        assertFalse(GitHubReleases.isApkUrl(repo, "$phone#x"))
    }

    @Test
    fun `GitHub's downloads may lead to GitHub's asset hosts and the repository's release downloads, nowhere else`() {
        for (url in listOf(
            "https://release-assets.githubusercontent.com/github-production-release-asset/1/abc?sp=r&sig=x",
            "https://objects.githubusercontent.com/github-production-release-asset-2e65be/1/abc",
            "https://github.com/GAMch1k/life-os-app/releases/download/v0.4.6-13/updates.json",
            "https://github.com/gamch1k/life-os-app/releases/download/v0.4.6-13/updates.json",
        )) assertTrue(url, GitHubReleases.mayRedirectTo(repo, url))
        for (url in listOf(
            "https://evil.example.com/x",
            "http://release-assets.githubusercontent.com/x",
            "https://release-assets.githubusercontent.com:444/x",
            "https://user:pass@release-assets.githubusercontent.com/x",
            "https://githubusercontent.com/x",
            "https://raw.githubusercontent.com/GAMch1k/life-os-app/main/x.apk",
            "https://github.com/someone/else/releases/download/v1/updates.json",
            "https://github.com/GAMch1k/life-os-app/releases/download/../../../someone/else/x",
            "https://github.com/login?return_to=x",
            "not a url at all ::",
        )) assertFalse(url, GitHubReleases.mayRedirectTo(repo, url))
    }

    @Test
    fun `a relative redirect is read against the address it came from`() {
        assertEquals(
            "https://github.com/GAMch1k/life-os-app/releases/download/v1-1/updates.json",
            GitHubReleases.resolve("https://github.com/GAMch1k/life-os-app/releases/latest/download/updates.json", "/GAMch1k/life-os-app/releases/download/v1-1/updates.json"),
        )
    }
}

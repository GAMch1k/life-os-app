package com.gamch1k.lifeos.core.secrets

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.gamch1k.lifeos.core.TestDataStores
import com.gamch1k.lifeos.core.api.AccountUser
import com.gamch1k.lifeos.core.api.ChildKey
import com.gamch1k.lifeos.core.api.Connection
import com.gamch1k.lifeos.core.api.DeviceIdentity
import com.gamch1k.lifeos.core.api.PairedDevice
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import java.security.GeneralSecurityException
import java.util.Base64

class TokenStoreTest {
    @get:Rule
    val stores = TestDataStores()

    /** Reversible and visibly not the plain text; can be told to lose its key. */
    private class FakeCipher : SecretCipher {
        var keyLost = false
        override fun encrypt(plain: String) = "sealed:" + Base64.getEncoder().encodeToString(plain.reversed().toByteArray())
        override fun decrypt(sealed: String): String {
            if (keyLost) throw GeneralSecurityException("key is gone")
            require(sealed.startsWith("sealed:")) { "bad ciphertext" }
            return String(Base64.getDecoder().decode(sealed.removePrefix("sealed:"))).reversed()
        }
    }

    private val token = ServiceToken("0123456789abcdef0123456789abcdef.access", "cfast_" + "a".repeat(48))

    @Test
    fun `a saved token comes back, and only ciphertext is stored`() = runBlocking {
        val dataStore = stores.create()
        val store = TokenStore(dataStore, FakeCipher())
        store.save("lifeos.example.com/", token)
        val connection = store.current()!!
        assertEquals("https://lifeos.example.com", connection.baseUrl)
        assertEquals(token.clientId, connection.token!!.clientId)
        assertEquals(token.clientSecret, connection.token!!.clientSecret)
        val raw = dataStore.data.first().asMap().values.joinToString()
        assertFalse(raw.contains(token.clientId))
        assertFalse(raw.contains(token.clientSecret))
    }

    @Test
    fun `sign out wipes the token but keeps the server address`() = runBlocking {
        val store = TokenStore(stores.create(), FakeCipher())
        store.save("https://lifeos.example.com", token)
        store.clear()
        assertNull(store.current())
        assertEquals("https://lifeos.example.com", store.serverUrl())
    }

    @Test
    fun `a lost Keystore key means signed out, not a crash`() = runBlocking {
        val cipher = FakeCipher()
        val store = TokenStore(stores.create(), cipher)
        store.save("https://lifeos.example.com", token)
        cipher.keyLost = true
        assertNull(store.current())
    }

    @Test
    fun `corrupt ciphertext means signed out`() = runBlocking {
        val dataStore = stores.create()
        val store = TokenStore(dataStore, FakeCipher())
        dataStore.edit {
            it[stringPreferencesKey("client-id")] = "garbage"
            it[stringPreferencesKey("client-secret")] = "garbage"
        }
        assertNull(store.current())
    }

    @Test
    fun `a fresh install has no server, there is no default`() = runBlocking {
        val store = TokenStore(stores.create(), FakeCipher())
        assertNull(store.serverUrl())
        assertNull(store.current())
        assertNull(store.configuredServer())
        assertNull(store.currentPairing())
    }

    @Test
    fun `server addresses are https only`() {
        assertEquals("https://lifeos.example.com", ServerUrl.normalize(" lifeos.example.com/ "))
        assertEquals("https://lifeos.example.com", ServerUrl.normalize("https://lifeos.example.com"))
        assertEquals("https://example.com:8443/lifeos", ServerUrl.normalize("https://example.com:8443/lifeos/"))
        assertNull("plain http, such as a WireGuard address", ServerUrl.normalize("http://10.0.0.1:3001"))
        assertNull(ServerUrl.normalize(""))
        assertNull(ServerUrl.normalize("https://"))
        assertNull(ServerUrl.normalize("https://example.com/?x=1"))
        assertNull(ServerUrl.normalize("https://user@example.com"))
        assertNull(ServerUrl.normalize("life os"))
    }

    @Test
    fun `a new token replaces the old one at once`() = runBlocking {
        val store = TokenStore(stores.create(), FakeCipher())
        store.save("https://lifeos.example.com", token)
        assertEquals(token.clientId, store.current()!!.token!!.clientId)
        store.save("https://lifeos.example.com", ServiceToken("other.access", "other-secret"))
        assertEquals("other.access", store.current()!!.token!!.clientId)
        assertEquals("other-secret", store.current()!!.token!!.clientSecret)
    }

    @Test
    fun `invisible characters around a paste are dropped, inside it they are refused`() {
        assertEquals("abc.access", ServiceToken.parse("\u200Babc.access\uFEFF", "s")!!.clientId)
        assertNull(ServiceToken.parse("abc\u200B.access", "s"))
        assertNull(ServiceToken.parse("abc.access", "sec\u0007ret"))
        assertNull(ServiceToken.parse("abc.access", "sécret"))
    }

    @Test
    fun `pasted values are trimmed, and a paste with spaces inside is refused`() {
        val parsed = ServiceToken.parse("  abc.access\n", "\tsecret ")!!
        assertEquals("abc.access", parsed.clientId)
        assertEquals("secret", parsed.clientSecret)
        assertNull(ServiceToken.parse("abc.access", ""))
        assertNull(ServiceToken.parse("abc .access", "secret"))
        assertTrue(ServiceToken.parse("a", "b") != null)
    }

    // ---- The pairing (P21-06)

    private val phoneKey = "lo_dev_" + "P".repeat(64)
    private val watchKey = "lo_dev_" + "W".repeat(64)
    private val paired = PairedDevice(
        key = phoneKey,
        keyId = "0b9e7c1e-5d0a-4a43-9a43-2f5f3e6f1a11",
        name = "Pixel 9",
        createdAt = "2026-11-14T18:02:11.000Z",
        user = AccountUser("00000000-0000-4000-8000-000000000001", "anna", "Anna"),
        publicUrl = "https://lifeos.example.com",
    )

    @Test
    fun `a pairing keeps the key encrypted, next to the service token, and shows the account`() = runBlocking {
        val dataStore = stores.create()
        val store = TokenStore(dataStore, FakeCipher())
        store.save("https://lifeos.example.com", token)
        store.savePairing("https://lifeos.example.com", paired, "Pixel 9")
        val connection = store.current()!!
        assertEquals(phoneKey, connection.deviceKey!!.value)
        assertEquals("the Access token stays for the same server", token.clientId, connection.token!!.clientId)
        assertFalse(dataStore.data.first().asMap().values.joinToString().contains(phoneKey))
        val pairing = store.currentPairing()!!
        assertEquals("Anna (anna)", pairing.account)
        assertEquals("Pixel 9", pairing.deviceName)
        assertEquals("https://lifeos.example.com", pairing.server)
        assertFalse(pairing.refused)
        assertNull(pairing.watch)
        assertFalse(connection.toString().contains(phoneKey))
    }

    @Test
    fun `a key alone is a connection, for a server without Access`() = runBlocking {
        val store = TokenStore(stores.create(), FakeCipher())
        store.savePairing("https://lifeos.example.com", paired, "Pixel 9")
        val connection = store.current()!!
        assertNull(connection.token)
        assertEquals(phoneKey, connection.deviceKey!!.value)
    }

    @Test
    fun `another server forgets the pairing, pairing elsewhere forgets the token`() = runBlocking {
        val store = TokenStore(stores.create(), FakeCipher())
        store.save("https://lifeos.example.com", token)
        store.savePairing("https://lifeos.example.com", paired, "Pixel 9")
        store.save("https://my-own.example.org", ServiceToken("own.access", "own-secret"))
        assertNull("a key belongs to its server", store.current()!!.deviceKey)
        assertNull(store.currentPairing())

        // Only because the person typed it (Edit connection): a link alone never moves a set-up phone.
        store.savePairing("https://other.example.net", paired, "Pixel 9", typed = "https://other.example.net")
        val connection = store.current()!!
        assertEquals("https://other.example.net", connection.baseUrl)
        assertNull("a service token belongs to its server's Access", connection.token)
    }

    @Test
    fun `refused until the key works again, and only for the current key`() = runBlocking {
        val store = TokenStore(stores.create(), FakeCipher())
        store.savePairing("https://lifeos.example.com", paired, "Pixel 9")
        val key = store.current()!!.deviceKey!!
        store.refused(DeviceKey.parse("lo_dev_" + "Z".repeat(64))!!)
        assertFalse("another key's 401 says nothing about this one", store.currentPairing()!!.refused)
        store.refused(key)
        assertTrue(store.currentPairing()!!.refused)
        store.accepted(key)
        assertFalse(store.currentPairing()!!.refused)
    }

    @Test
    fun `the watch's key belongs to the phone key that made it`() = runBlocking {
        val dataStore = stores.create()
        val store = TokenStore(dataStore, FakeCipher())
        store.savePairing("https://lifeos.example.com", paired, "Pixel 9")
        store.saveWatchKey(ChildKey(watchKey, "6a1f", "Galaxy Watch8", "watch", paired.keyId), paired.keyId, "Galaxy Watch8")
        assertEquals(watchKey, store.watchKey()!!.key.value)
        assertEquals("Galaxy Watch8", store.currentPairing()!!.watch!!.name)
        assertFalse(dataStore.data.first().asMap().values.joinToString().contains(watchKey))

        store.watchKeyRefused(store.watchKey()!!.key)
        assertTrue(store.watchKey()!!.refused)
        store.watchKeyAccepted(store.watchKey()!!.key)
        assertFalse(store.watchKey()!!.refused)

        store.clearPairing()
        assertNull(store.watchKey())
        assertNull(store.currentPairing())
    }

    @Test
    fun `sign out forgets the token, the key and the watch's key`() = runBlocking {
        val store = TokenStore(stores.create(), FakeCipher())
        store.save("https://lifeos.example.com", token)
        store.savePairing("https://lifeos.example.com", paired, "Pixel 9")
        store.saveWatchKey(ChildKey(watchKey, "6a1f", "Galaxy Watch8"), paired.keyId, "Galaxy Watch8")
        store.clear()
        assertNull(store.current())
        assertNull(store.watchKey())
        assertEquals("https://lifeos.example.com", store.serverUrl())
    }

    @Test
    fun `me updates the account's names`() = runBlocking {
        val store = TokenStore(stores.create(), FakeCipher())
        store.savePairing("https://lifeos.example.com", paired, "Pixel 9")
        store.updateIdentity(DeviceIdentity(paired.keyId, "Pixel 9 Pro", "phone", user = AccountUser("u", "anna", "Anna Example")))
        assertEquals("Anna Example (anna)", store.currentPairing()!!.account)
        assertEquals("Pixel 9 Pro", store.currentPairing()!!.deviceName)
    }

    @Test
    fun `the Custom Tab's verifier is kept encrypted, and only for a while`() = runBlocking {
        val dataStore = stores.create()
        val store = TokenStore(dataStore, FakeCipher())
        store.savePending(PendingPairing("https://lifeos.example.com", "state-abcdefgh", "verifier-secret", startedAtMillis = 1_000))
        assertFalse(dataStore.data.first().asMap().values.joinToString().contains("verifier-secret"))
        assertEquals("verifier-secret", store.pending(nowMillis = 2_000)!!.verifier)
        assertNull("too old", store.pending(nowMillis = 1_000 + TokenStore.PENDING_MAX_AGE_MILLIS + 1))
        store.clearPending()
        assertNull(store.pending(nowMillis = 2_000))
    }

    @Test
    fun `a set-up phone never moves to a server the person did not type`() = runBlocking {
        val store = TokenStore(stores.create(), FakeCipher())
        store.save("https://lifeos.example.com", token)
        store.savePairing("https://lifeos.example.com", paired, "Pixel 9")
        try {
            store.savePairing("https://evil.example.org", paired, "Pixel 9")
            fail("moved to another server on a link's word")
        } catch (_: IllegalStateException) {
        }
        val connection = store.current()!!
        assertEquals("nothing changed", "https://lifeos.example.com", connection.baseUrl)
        assertEquals(token.clientId, connection.token!!.clientId)
        assertEquals(phoneKey, connection.deviceKey!!.value)
        assertEquals("https://lifeos.example.com", store.configuredServer())
    }

    @Test
    fun `a fresh install may pair with any https server`() = runBlocking {
        val store = TokenStore(stores.create(), FakeCipher())
        assertNull(store.configuredServer())
        store.savePairing("https://own.example.org", paired, "Pixel 9")
        assertEquals("https://own.example.org", store.current()!!.baseUrl)
    }

    @Test
    fun `keys to sign out wait encrypted, outlive signing out, and are forgotten once done`() = runBlocking {
        val dataStore = stores.create()
        val store = TokenStore(dataStore, FakeCipher())
        store.save("https://lifeos.example.com", token)
        store.savePairing("https://lifeos.example.com", paired, "Pixel 9")
        val id = store.queueRevoke(store.current()!!, nowMillis = 1_000)!!
        store.queueRevoke(store.current()!!, nowMillis = 2_000)
        assertEquals("the same key queued twice is one entry", 1, store.pendingRevokes().size)
        val raw = dataStore.data.first().asMap().values.joinToString()
        store.clear()

        val pending = store.pendingRevokes().single()
        assertEquals(phoneKey, pending.key.value)
        assertEquals("the token that gets it through Access", token.clientSecret, pending.token!!.clientSecret)
        assertEquals("https://lifeos.example.com", pending.server)
        assertFalse(raw.contains(phoneKey.substring(7)))
        assertFalse(raw.contains(token.clientSecret))
        assertEquals(listOf(RevokeStatus("https://lifeos.example.com", 2_000, failed = false)), store.revokes.first())
        assertFalse(pending.toString().contains(phoneKey))

        store.revokeFailed(pending.id)
        assertTrue(store.revokes.first().single().failed)
        store.retryFailedRevokes(nowMillis = 5_000)
        assertEquals(RevokeStatus("https://lifeos.example.com", 5_000, failed = false), store.revokes.first().single())
        store.revokeFailed(pending.id)
        store.dismissFailedRevokes()
        assertTrue(store.pendingRevokes().isEmpty())

        store.save("https://lifeos.example.com", token)
        store.savePairing("https://lifeos.example.com", paired, "Pixel 9")
        val again = store.queueRevoke(store.current()!!)!!
        assertTrue(again != id)
        store.revokeDone(again)
        assertTrue(store.revokes.first().isEmpty())
    }

    // ---- No default server (P22-13): a phone set up before keeps its server

    private val legacy = TokenStore.LEGACY_DEFAULT_SERVER
    private val serverKey = stringPreferencesKey("server-url")

    /** What a build with a default server would leave had it stored a token without the address. */
    private suspend fun tokenWithoutServer(dataStore: DataStore<Preferences>, cipher: SecretCipher) {
        dataStore.edit {
            it[stringPreferencesKey("client-id")] = cipher.encrypt(token.clientId)
            it[stringPreferencesKey("client-secret")] = cipher.encrypt(token.clientSecret)
        }
    }

    @Test
    fun `a phone set up with a token but no saved server keeps the old default, saved once`() = runBlocking {
        val dataStore = stores.create()
        val cipher = FakeCipher()
        val store = TokenStore(dataStore, cipher)
        tokenWithoutServer(dataStore, cipher)

        // Before the migration ran (a worker or the watch asking first), reads already keep the server.
        assertEquals(legacy, store.current()!!.baseUrl)
        assertEquals(token.clientSecret, store.current()!!.token!!.clientSecret)
        assertEquals(legacy, store.serverUrl())
        assertEquals(legacy, store.configuredServer())

        assertTrue(store.migrateServer())
        assertEquals("saved now", legacy, dataStore.data.first()[serverKey])
        assertFalse("once", store.migrateServer())
        assertEquals(legacy, store.current()!!.baseUrl)
        assertEquals(token.clientId, store.current()!!.token!!.clientId)

        // A pairing link for that server needs nothing typed, and the token stays: same server.
        store.savePairing(legacy, paired, "Pixel 9")
        assertEquals(token.clientId, store.current()!!.token!!.clientId)
        assertEquals(legacy, store.currentPairing()!!.server)
    }

    @Test
    fun `a paired phone without a saved server keeps it with its pairing and its watch's key`() = runBlocking {
        val dataStore = stores.create()
        val store = TokenStore(dataStore, FakeCipher())
        store.save(legacy, token)
        store.savePairing(legacy, paired, "Pixel 9")
        store.saveWatchKey(ChildKey(watchKey, "6a1f", "Galaxy Watch8"), paired.keyId, "Galaxy Watch8")
        dataStore.edit { it.remove(serverKey) }

        val connection = store.current()!!
        assertEquals(legacy, connection.baseUrl)
        assertEquals(phoneKey, connection.deviceKey!!.value)
        assertEquals(legacy, store.currentPairing()!!.server)
        assertEquals(watchKey, store.watchKey()!!.key.value)

        // Edit connection with the same server and a new token: still paired.
        store.save(legacy, ServiceToken("new.access", "new-secret"))
        assertEquals(phoneKey, store.current()!!.deviceKey!!.value)
        assertEquals("new.access", store.current()!!.token!!.clientId)

        dataStore.edit { it.remove(serverKey) }
        assertTrue(store.migrateServer())
        assertEquals(legacy, dataStore.data.first()[serverKey])
        assertEquals(watchKey, store.watchKey()!!.key.value)

        // A key alone (a server without Access) counts as set up too.
        val keyOnlyData = stores.create()
        keyOnlyData.edit { it[stringPreferencesKey("device-key")] = FakeCipher().encrypt(phoneKey) }
        val keyOnly = TokenStore(keyOnlyData, FakeCipher())
        assertEquals(legacy, keyOnly.current()!!.baseUrl)
        assertTrue(keyOnly.migrateServer())
    }

    @Test
    fun `the migration leaves a fresh install, a saved server and a signed-out phone alone`() = runBlocking {
        val fresh = TokenStore(stores.create(), FakeCipher())
        assertFalse(fresh.migrateServer())
        assertNull("never invents a server", fresh.serverUrl())

        val own = TokenStore(stores.create(), FakeCipher())
        own.save("https://own.example.org", token)
        assertFalse(own.migrateServer())
        assertEquals("https://own.example.org", own.current()!!.baseUrl)

        val signedOut = TokenStore(stores.create(), FakeCipher())
        signedOut.save("https://own.example.org", token)
        signedOut.clear()
        assertFalse(signedOut.migrateServer())
        assertEquals("the next sign-in starts from it", "https://own.example.org", signedOut.serverUrl())
        assertNull(signedOut.current())
        assertNull(signedOut.configuredServer())

        // A key queued to be signed out, or a pairing in the browser, is not a set-up phone either.
        val leaving = TokenStore(stores.create(), FakeCipher())
        leaving.savePending(PendingPairing("https://own.example.org", "state-abcdefgh", "verifier", startedAtMillis = 1_000))
        leaving.queueRevoke(Connection("https://own.example.org", null, DeviceKey.parse(phoneKey)))
        assertFalse(leaving.migrateServer())
        assertNull(leaving.serverUrl())
    }

    @Test
    fun `the network check's old token goes to the saved server, else the only server it knew`() = runBlocking {
        val cipher = FakeCipher()
        val sealedId = cipher.encrypt(token.clientId)
        val sealedSecret = cipher.encrypt(token.clientSecret)

        val fresh = TokenStore(stores.create(), cipher)
        assertTrue(fresh.adoptLegacyToken(sealedId, sealedSecret))
        assertEquals(legacy, fresh.current()!!.baseUrl)
        assertEquals(token.clientSecret, fresh.current()!!.token!!.clientSecret)

        val typedBefore = TokenStore(stores.create(), cipher)
        typedBefore.save("https://own.example.org", ServiceToken("own.access", "own-secret"))
        typedBefore.clear()
        assertTrue(typedBefore.adoptLegacyToken(sealedId, sealedSecret))
        assertEquals("https://own.example.org", typedBefore.current()!!.baseUrl)

        val setUp = TokenStore(stores.create(), cipher)
        setUp.save("https://own.example.org", ServiceToken("own.access", "own-secret"))
        assertFalse(setUp.adoptLegacyToken(sealedId, sealedSecret))
        assertEquals("own.access", setUp.current()!!.token!!.clientId)

        assertFalse("unreadable", TokenStore(stores.create(), cipher).adoptLegacyToken("garbage", "garbage"))
    }
}

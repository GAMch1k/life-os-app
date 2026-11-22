package com.gamch1k.lifeos.core.pairing

import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.gamch1k.lifeos.core.TestDataStores
import com.gamch1k.lifeos.core.api.FakeTransport
import com.gamch1k.lifeos.core.api.LifeOsClient
import com.gamch1k.lifeos.core.secrets.DeviceKey
import com.gamch1k.lifeos.core.secrets.SecretCipher
import com.gamch1k.lifeos.core.secrets.TokenStore
import com.gamch1k.lifeos.core.wearlink.CredentialsReply
import com.gamch1k.lifeos.core.wearlink.PhoneWearLinkHandler
import com.gamch1k.lifeos.core.wearlink.PhoneWearLinkHost
import com.gamch1k.lifeos.core.wearlink.RelayCodec
import com.gamch1k.lifeos.core.wearlink.RelayErrorCode
import com.gamch1k.lifeos.core.wearlink.RelayRequest
import com.gamch1k.lifeos.core.wearlink.WearCredentials
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * The watch's own key (life-os docs/api/devices.md, "The watch"): made once
 * through the child endpoint, handed to the watch, used for its relayed
 * requests; a refused one is not replaced by itself; Connect the watch
 * replaces it, revoking the old one.
 */
class WatchKeysTest {
    @get:Rule
    val stores = TestDataStores()

    private object PlainCipher : SecretCipher {
        override fun encrypt(plain: String) = "x$plain"
        override fun decrypt(sealed: String) = sealed.removePrefix("x")
    }

    private val server = ContractServer(access = FakeTransport.TOKEN)
    // Lazy: the DataStore needs the rule's folder, which exists only once the test runs.
    private val dataStore by lazy { stores.create() }
    private val tokens by lazy { TokenStore(dataStore, PlainCipher) }
    private val client by lazy { LifeOsClient(server, { tokens.current() }, "ua", updatesUrl = server.publicUrl, sleep = {}, keyListener = tokens) }
    private val keys by lazy { WatchKeys(tokens, client) { "Galaxy Watch8 (A1B2)" } }
    private val handler by lazy { PhoneWearLinkHost(keys, client).let { PhoneWearLinkHandler(it.wearTokenSource, it.wearRelay) } }

    private fun paired(): DeviceKey = runBlocking {
        tokens.save(server.publicUrl, FakeTransport.TOKEN)
        val device = client.pair(server.publicUrl, server.issueCode(), "Pixel 9", null)
        tokens.savePairing(server.publicUrl, device, "Pixel 9")
        tokens.current()!!.deviceKey!!
    }

    private fun credentials(id: String = "c-1"): CredentialsReply = runBlocking {
        RelayCodec.json.decodeFromString(CredentialsReply.serializer(), handler.handleCredentials("""{"v":1,"id":"$id"}""".toByteArray()).toString(Charsets.UTF_8))
    }

    private fun relayTasks() = runBlocking {
        RelayCodec.decodeResponse(handler.handleRelayMessage(RelayCodec.encodeRequest(RelayRequest(id = "r-1", method = "GET", path = "/api/tasks/v1/tasks?limit=1"))))
    }

    private fun childRequests() = server.requests.filter { it.url.endsWith("/api/auth/devices/child") }

    @Test
    fun `the watch's first ask makes its key once, with its name, and hands it over with the Access token`() {
        val phone = paired()
        val reply = credentials()
        val credentials = reply.credentials!!
        assertTrue(credentials.isComplete)
        assertEquals(server.publicUrl, credentials.baseUrl)
        assertEquals(FakeTransport.TOKEN.clientId, credentials.clientId)
        val watch = server.key(credentials.deviceKey!!)!!
        assertEquals("watch", watch.kind)
        assertEquals("Galaxy Watch8 (A1B2)", watch.name)
        assertEquals(server.key(phone.value)!!.id, watch.parentId)
        assertEquals(phone.bearer, childRequests().single().headers["Authorization"])

        // Asked again (another start, a credentials-changed ping): the same key, nothing new made.
        assertEquals(credentials, credentials("c-2").credentials)
        assertEquals(1, childRequests().size)
        assertEquals("Galaxy Watch8 (A1B2)", runBlocking { tokens.currentPairing()!!.watch!!.name })
    }

    @Test
    fun `two asks at once make one key`() = runBlocking {
        paired()
        val results = (1..4).map { async { keys.credential() } }.awaitAll()
        assertEquals(1, childRequests().size)
        assertEquals(1, results.map { (it as WatchKeys.Credential.Key).key }.toSet().size)
    }

    @Test
    fun `the watch's relayed requests carry the watch's key`() {
        paired()
        val reply = relayTasks()
        assertEquals(200, reply.header.status)
        val sent = server.requests.last()
        val watchKey = runBlocking { tokens.watchKey()!!.key }
        assertEquals(watchKey.bearer, sent.headers["Authorization"])
        assertEquals(FakeTransport.TOKEN.clientId, sent.headers["CF-Access-Client-Id"])
    }

    @Test
    fun `a revoked watch key is noted and never replaced by itself`() {
        paired()
        relayTasks()
        val watchKey = runBlocking { tokens.watchKey()!!.key }
        server.revoke(server.key(watchKey.value)!!)

        val refused = relayTasks()
        assertEquals("Life OS's answer goes to the watch as it is", 401, refused.header.status)
        assertTrue(runBlocking { tokens.currentPairing()!!.watch!!.refused })

        // The watch asks for its credentials again: nothing, and no new key behind the person's back.
        val reply = credentials("c-3")
        assertNull(reply.credentials)
        assertEquals(RelayErrorCode.NO_CREDENTIALS, reply.error?.code)
        assertEquals(RelayErrorCode.NO_CREDENTIALS, relayTasks().header.error?.code)
        assertEquals(1, childRequests().size)
    }

    @Test
    fun `a relayed upload that got there too late is no reason to doubt the watch's key`() {
        paired()
        relayTasks()
        server.identityExpiring = 1
        val late = relayTasks()
        assertEquals("the watch gets the answer, and sends it again", 401, late.header.status)
        assertFalse(runBlocking { tokens.currentPairing()!!.watch!!.refused })
        assertEquals(200, relayTasks().header.status)
    }

    @Test
    fun `Connect the watch makes a new key, which revokes the old one`() = runBlocking {
        paired()
        val first = (keys.credential() as WatchKeys.Credential.Key).key
        server.revoke(server.key(first.value)!!)
        relayTasks()
        assertTrue(tokens.currentPairing()!!.watch!!.refused)

        val second = keys.replace()
        assertNotEquals(first, second)
        assertFalse("a fresh key is not refused", tokens.currentPairing()!!.watch!!.refused)
        assertEquals(second.value, credentials("c-4").credentials!!.deviceKey)

        val third = keys.replace()
        assertTrue("one watch key per phone: the new one revokes the previous", server.key(second.value)!!.revoked)
        assertFalse(server.key(third.value)!!.revoked)
    }

    @Test
    fun `an unpaired phone hands over and relays with the service token alone, as before`() = runBlocking {
        tokens.save(server.publicUrl, FakeTransport.TOKEN)
        server.legacyAccess = true
        assertEquals(WearCredentials(server.publicUrl, FakeTransport.TOKEN.clientId, FakeTransport.TOKEN.clientSecret), credentials().credentials)
        assertEquals(200, relayTasks().header.status)
        val sent = server.requests.last()
        assertNull(sent.headers["Authorization"])
        assertEquals(FakeTransport.TOKEN.clientId, sent.headers["CF-Access-Client-Id"])
        assertTrue(childRequests().isEmpty())
    }

    @Test
    fun `a phone whose own key was refused has nothing for the watch`() = runBlocking {
        val phone = paired()
        server.revoke(server.key(phone.value)!!)
        tokens.refused(phone)
        assertEquals(WatchKeys.Credential.Missing(WatchKeys.PHONE_REFUSED), keys.credential())
        assertEquals(RelayErrorCode.NO_CREDENTIALS, credentials().error?.code)
        assertTrue(childRequests().isEmpty())
    }

    @Test
    fun `pairing again forgets the old watch key, and the next ask makes one for the new phone key`() = runBlocking {
        paired()
        val first = (keys.credential() as WatchKeys.Credential.Key).key
        val again = client.pair(server.publicUrl, server.issueCode(), "Pixel 9", null)
        tokens.savePairing(server.publicUrl, again, "Pixel 9")
        assertNull(tokens.watchKey())
        val second = (keys.credential() as WatchKeys.Credential.Key).key
        assertNotEquals(first, second)
        assertEquals(server.key(again.key)!!.id, server.key(second.value)!!.parentId)
    }

    @Test
    fun `a watch key made for a phone key that was meanwhile replaced is not kept`() = runBlocking {
        paired()
        val pairing = tokens.currentPairing()!!
        val child = client.createChildKey("Galaxy Watch8")
        tokens.clearPairing()
        tokens.saveWatchKey(child, parentKeyId = pairing.keyId, watchName = "Galaxy Watch8")
        assertNull(tokens.watchKey())
    }

    @Test
    fun `a phone set up before there was no default server hands the watch the server it kept`() = runBlocking {
        paired()
        val before = credentials().credentials!!
        // As a build with a default server would have left it: no saved address (P22-13).
        dataStore.edit { it.remove(stringPreferencesKey("server-url")) }
        val after = credentials("c-2").credentials!!
        assertEquals(TokenStore.LEGACY_DEFAULT_SERVER, after.baseUrl)
        assertEquals("the same key and token, only the server the phone has been using", before.deviceKey, after.deviceKey)
        assertEquals(before.clientSecret, after.clientSecret)
        assertTrue(after.isComplete)
        assertEquals(1, childRequests().size)
    }
}

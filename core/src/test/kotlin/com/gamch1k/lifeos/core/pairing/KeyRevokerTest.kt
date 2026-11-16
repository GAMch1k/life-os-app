package com.gamch1k.lifeos.core.pairing

import com.gamch1k.lifeos.core.TestDataStores
import com.gamch1k.lifeos.core.api.FakeTransport
import com.gamch1k.lifeos.core.api.HttpRequest
import com.gamch1k.lifeos.core.api.HttpResponse
import com.gamch1k.lifeos.core.api.HttpTransport
import com.gamch1k.lifeos.core.api.LifeOsClient
import com.gamch1k.lifeos.core.api.ResponseSink
import com.gamch1k.lifeos.core.api.TransportException
import com.gamch1k.lifeos.core.secrets.SecretCipher
import com.gamch1k.lifeos.core.secrets.ServiceToken
import com.gamch1k.lifeos.core.secrets.TokenStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * Unpairing and signing out sign the key out of Life OS; when Life OS does
 * not confirm, the key waits and is tried again when online, then the
 * person is asked to sign the phone out on the web.
 */
class KeyRevokerTest {
    @get:Rule
    val stores = TestDataStores()

    private object PlainCipher : SecretCipher {
        override fun encrypt(plain: String) = "x$plain"
        override fun decrypt(sealed: String) = sealed.removePrefix("x")
    }

    private val server = ContractServer(access = FakeTransport.TOKEN)

    /** The network: [ContractServer] while online, no connection otherwise. */
    private class Network(private val server: ContractServer) : HttpTransport {
        var online = true
        override suspend fun execute(request: HttpRequest, sink: ResponseSink?, onProgress: ((Long) -> Unit)?): HttpResponse {
            if (!online) throw TransportException.Network("ERR_INTERNET_DISCONNECTED (-106)", offline = true)
            return server.execute(request, sink, onProgress)
        }
    }

    private val network = Network(server)
    private var now = 0L
    private val tokens by lazy { TokenStore(stores.create(), PlainCipher) }
    private val client by lazy { LifeOsClient(network, { tokens.current() }, "ua", updatesUrl = server.publicUrl, sleep = {}, keyListener = tokens) }
    private val revoker by lazy { KeyRevoker(tokens, client, clock = { now }) }

    private fun paired(): String = runBlocking {
        tokens.save(server.publicUrl, FakeTransport.TOKEN)
        val device = client.pair(server.publicUrl, server.issueCode(), "Pixel 9", null)
        tokens.savePairing(server.publicUrl, device, "Pixel 9")
        device.key
    }

    @Test
    fun `online, the key is signed out at once and nothing waits`() = runBlocking {
        val key = paired()
        assertTrue(revoker.revoke(tokens.current()!!))
        assertTrue(server.key(key)!!.revoked)
        assertTrue(tokens.pendingRevokes().isEmpty())
    }

    @Test
    fun `offline, the key waits, outlives signing out, and goes out through Access once online`() = runBlocking {
        val key = paired()
        network.online = false
        assertFalse(revoker.revoke(tokens.current()!!))
        tokens.clear() // Sign out: the token and the key are gone from the connection.
        assertFalse(server.key(key)!!.revoked)
        assertEquals(1, tokens.revokes.first().size)

        assertEquals("still offline: later", KeyRevoker.Result.RETRY, revoker.retryPending())
        network.online = true
        assertEquals(KeyRevoker.Result.DONE, revoker.retryPending())
        assertTrue(server.key(key)!!.revoked)
        val signOut = server.requests.last()
        assertEquals("https://lifeos.example.com/api/auth/devices/sign-out", signOut.url)
        assertEquals("Bearer $key", signOut.headers["Authorization"])
        assertEquals("the kept token gets it through Access", FakeTransport.TOKEN.clientId, signOut.headers["CF-Access-Client-Id"])
        assertTrue(tokens.revokes.first().isEmpty())
    }

    @Test
    fun `a key revoked meanwhile in Settings → Devices counts as signed out`() = runBlocking {
        val key = paired()
        network.online = false
        revoker.revoke(tokens.current()!!)
        server.revoke(server.key(key)!!)
        network.online = true
        assertEquals(KeyRevoker.Result.DONE, revoker.retryPending())
        assertTrue(tokens.pendingRevokes().isEmpty())
    }

    @Test
    fun `refused by Access, or out of tries, the person is asked to sign the phone out on the web`() = runBlocking {
        paired()
        val refusing = KeyRevoker(tokens, LifeOsClient(network, { null }, "ua", sleep = {}), clock = { now })
        val withoutAccess = tokens.current()!!.let { com.gamch1k.lifeos.core.api.Connection(it.baseUrl, ServiceToken("wrong.access", "wrong"), it.deviceKey) }
        assertFalse(refusing.revoke(withoutAccess))
        assertTrue("Access refusing will not pass by itself", tokens.revokes.first().single().failed)
        tokens.dismissFailedRevokes()

        network.online = false
        revoker.revoke(tokens.current()!!)
        assertEquals(KeyRevoker.Result.RETRY, revoker.retryPending())
        now += KeyRevoker.MAX_AGE_MILLIS + 1
        assertEquals(KeyRevoker.Result.DONE, revoker.retryPending())
        assertTrue(tokens.revokes.first().single().failed)

        // Try again (the notice) once online.
        tokens.retryFailedRevokes(nowMillis = now)
        network.online = true
        assertEquals(KeyRevoker.Result.DONE, revoker.retryPending())
        assertTrue(tokens.revokes.first().isEmpty())
    }

    @Test
    fun `the worker's last try gives up what still does not go through`() = runBlocking {
        paired()
        network.online = false
        revoker.revoke(tokens.current()!!)
        assertEquals(KeyRevoker.Result.DONE, revoker.retryPending(giveUp = true))
        assertTrue(tokens.revokes.first().single().failed)
    }
}

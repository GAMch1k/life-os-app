package com.gamch1k.lifeos.core.wearlink

import com.gamch1k.lifeos.core.TestDataStores
import com.gamch1k.lifeos.core.api.FakeTransport
import com.gamch1k.lifeos.core.api.TransportException
import com.gamch1k.lifeos.core.secrets.SecretCipher
import com.gamch1k.lifeos.core.secrets.ServiceToken
import com.gamch1k.lifeos.core.secrets.TokenStore
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test

/** The phone's WearLinkHost, end to end through the real PhoneWearLinkHandler. */
class PhoneWearLinkHostTest {
    @get:Rule
    val stores = TestDataStores()

    private object PlainCipher : SecretCipher {
        override fun encrypt(plain: String) = "x$plain"
        override fun decrypt(sealed: String) = sealed.removePrefix("x")
    }

    private val transport = FakeTransport()

    private fun relayMessage(method: String, path: String, headers: Map<String, String> = emptyMap(), body: ByteArray = ByteArray(0)) =
        RelayCodec.encodeRequest(RelayRequest(id = "r-1", method = method, path = path, headers = headers), body)

    private fun handler(signedIn: Boolean = true): PhoneWearLinkHandler = runBlocking {
        val tokens = TokenStore(stores.create(), PlainCipher)
        if (signedIn) tokens.save("https://lifeos.example.com", FakeTransport.TOKEN)
        val client = FakeTransport.client(transport, connection = if (signedIn) FakeTransport.CONNECTION else null)
        val host = PhoneWearLinkHost(tokens, client)
        PhoneWearLinkHandler(host.wearTokenSource, host.wearRelay)
    }

    @Test
    fun `the token store hands the watch the phone's server and token`() = runBlocking {
        val reply = handler().handleCredentials("""{"v":1,"id":"c-1"}""".toByteArray())
        val decoded = RelayCodec.json.decodeFromString(CredentialsReply.serializer(), reply.toString(Charsets.UTF_8))
        assertEquals(WearCredentials("https://lifeos.example.com", FakeTransport.TOKEN.clientId, FakeTransport.TOKEN.clientSecret), decoded.credentials)
    }

    @Test
    fun `a signed-out phone has no credentials`() = runBlocking {
        val reply = handler(signedIn = false).handleCredentials("""{"v":1,"id":"c-2"}""".toByteArray())
        val decoded = RelayCodec.json.decodeFromString(CredentialsReply.serializer(), reply.toString(Charsets.UTF_8))
        assertEquals(RelayErrorCode.NO_CREDENTIALS, decoded.error?.code)
        assertNull(decoded.credentials)
    }

    @Test
    fun `a relayed write goes out with the phone's token and actor, and its answer comes back as it is`() = runBlocking {
        transport.json(202, """{"id":"j1","status":"queued"}""")
        val reply = RelayCodec.decodeResponse(
            handler().handleRelayMessage(
                relayMessage("POST", "/api/voice/v1/audio-jobs", mapOf("Content-Type" to "multipart/form-data; boundary=b", "Idempotency-Key" to "cap-1"), "--b--".toByteArray()),
            ),
        )
        assertEquals(202, reply.header.status)
        assertEquals("""{"id":"j1","status":"queued"}""", reply.body.toString(Charsets.UTF_8))
        val sent = transport.requests.single()
        assertEquals("https://lifeos.example.com/api/voice/v1/audio-jobs", sent.url)
        assertEquals(FakeTransport.TOKEN.clientId, sent.headers["CF-Access-Client-Id"])
        assertEquals("user", sent.headers["X-Life-OS-Actor"])
        assertEquals("cap-1", sent.headers["Idempotency-Key"])
        assertEquals("multipart/form-data; boundary=b", sent.body!!.contentType)
    }

    @Test
    fun `an Access redirect is an answer for the watch, not a relay error`() = runBlocking {
        transport.redirect(302, "https://team.cloudflareaccess.com/cdn-cgi/access/login")
        val reply = RelayCodec.decodeResponse(handler().handleRelayMessage(relayMessage("GET", "/api/tasks/v1/tasks?limit=1")))
        assertEquals(302, reply.header.status)
        assertNull(reply.header.error)
    }

    @Test
    fun `no token and no network become the documented relay errors`() = runBlocking {
        val signedOut = RelayCodec.decodeResponse(handler(signedIn = false).handleRelayMessage(relayMessage("GET", "/api/tasks/v1/tasks")))
        assertEquals(RelayErrorCode.NO_CREDENTIALS, signedOut.header.error?.code)

        transport.fail(TransportException.Network("ERR_CONNECTION_RESET (-101)", offline = false))
        val offline = RelayCodec.decodeResponse(handler().handleRelayMessage(relayMessage("GET", "/api/tasks/v1/tasks")))
        assertEquals(RelayErrorCode.UPSTREAM_UNREACHABLE, offline.header.error?.code)
        assertEquals(1, transport.requests.size)
    }

    @Test
    fun `a new token reaches the watch at once`() = runBlocking {
        val tokens = TokenStore(stores.create(), PlainCipher)
        tokens.save("https://lifeos.example.com", FakeTransport.TOKEN)
        tokens.save("https://lifeos.example.com", ServiceToken("new.access", "new-secret"))
        assertEquals("new.access", tokens.load()!!.clientId)
        tokens.clear()
        assertNull(tokens.load())
    }
}

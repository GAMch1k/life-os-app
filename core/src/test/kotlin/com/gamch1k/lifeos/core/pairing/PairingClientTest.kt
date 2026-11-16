package com.gamch1k.lifeos.core.pairing

import com.gamch1k.lifeos.core.api.Connection
import com.gamch1k.lifeos.core.api.DeviceKeyListener
import com.gamch1k.lifeos.core.api.FakeTransport
import com.gamch1k.lifeos.core.api.HttpRequest
import com.gamch1k.lifeos.core.api.HttpTransport
import com.gamch1k.lifeos.core.api.LifeOsClient
import com.gamch1k.lifeos.core.api.LifeOsException
import com.gamch1k.lifeos.core.api.ResponseSink
import com.gamch1k.lifeos.core.secrets.DeviceKey
import com.gamch1k.lifeos.core.secrets.ServiceToken
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.time.Instant

/**
 * The pairing client against [ContractServer] (life-os docs/api/devices.md),
 * the headers every call carries, and how 401 and 403 are told apart.
 */
class PairingClientTest {
    private val token = FakeTransport.TOKEN
    private val server = ContractServer(access = token)

    /** What the app holds: changes when the test pairs. */
    private var connection: Connection? = Connection(server.publicUrl, token)

    private val verdicts = mutableListOf<String>()
    private val listener = object : DeviceKeyListener {
        override suspend fun refused(key: DeviceKey) {
            verdicts += "refused"
        }

        override suspend fun accepted(key: DeviceKey) {
            verdicts += "accepted"
        }
    }

    private fun client(transport: HttpTransport = server, clock: () -> Long = { 0L }) =
        LifeOsClient(transport, { connection }, "LifeOS-Android/test", updatesUrl = server.publicUrl, sleep = {}, keyListener = listener, clockMillis = clock)

    private inline fun <reified E : LifeOsException> expect(noinline block: suspend () -> Unit): E {
        try {
            runBlocking { block() }
        } catch (error: LifeOsException) {
            if (error is E) return error
            fail("expected ${E::class.simpleName}, got ${error::class.simpleName}: ${error.message}")
        }
        fail("expected ${E::class.simpleName}, got success")
        throw AssertionError()
    }

    private fun pairNow(): DeviceKey = runBlocking {
        val paired = client().pair(server.publicUrl, server.issueCode(), "Pixel 9", verifier = null)
        DeviceKey.parse(paired.key)!!.also { connection = Connection(server.publicUrl, token, it) }
    }

    // ---- Pairing

    @Test
    fun `a QR code's code is redeemed once for the phone's key, through Access`() = runBlocking {
        val code = server.issueCode()
        val paired = client().pair(server.publicUrl, code.lowercase().replace("-", ""), "Pixel 9", verifier = null)
        assertTrue(DeviceKey.parse(paired.key) != null)
        assertEquals("phone", paired.kind)
        assertEquals("anna", paired.user?.username)
        assertEquals("Anna", paired.user?.name)
        assertFalse("the key never shows in toString", paired.toString().contains(paired.key))

        val request = server.requests.single()
        assertEquals("POST", request.method)
        assertEquals("https://lifeos.example.com/api/auth/devices/pair", request.url)
        assertEquals("the Access token lets it through", token.clientId, request.headers["CF-Access-Client-Id"])
        assertNull("no Life OS credential: the code is one", request.headers["Authorization"])
        assertNull(request.headers["X-Life-OS-Actor"])
        assertEquals("""{"code":"${code.lowercase().replace("-", "")}","name":"Pixel 9"}""", server.bodies.single())

        val again = expect<LifeOsException.Api> { client().pair(server.publicUrl, code, "Pixel 9", verifier = null) }
        assertEquals(400, again.status)
        assertEquals("pairing_code_invalid", again.code)
        assertTrue("Life OS's own words", again.detail.startsWith("That pairing code is wrong, used, or expired"))
        assertEquals("never retried: a code works once", 2, server.requests.size)
    }

    @Test
    fun `the Custom Tab's code works only with its verifier`() = runBlocking {
        val challenge = PairingChallenge.create()
        val code = server.issueCode(challenge.challenge)
        expect<LifeOsException.Api> { client().pair(server.publicUrl, code, "Pixel 9", verifier = null) }
        expect<LifeOsException.Api> { client().pair(server.publicUrl, code, "Pixel 9", verifier = PairingChallenge.create().verifier) }
        val paired = client().pair(server.publicUrl, code, "Pixel 9", verifier = challenge.verifier)
        assertTrue(DeviceKey.parse(paired.key) != null)
        assertTrue(server.bodies.last().contains("\"verifier\":\"${challenge.verifier}\""))
    }

    @Test
    fun `the service token goes only to its own server, so another server behind Access asks for its own`() {
        val other = ContractServer(publicUrl = "https://other.example.org", access = ServiceToken("other.access", "other-secret"))
        val redirect = expect<LifeOsException.AccessRedirect> {
            LifeOsClient(other, { connection }, "ua", sleep = {}).pair(other.publicUrl, other.issueCode(), "Pixel 9", null)
        }
        assertEquals("team.cloudflareaccess.com", redirect.host)
        assertNull("lifeos.example.com's token never went to other.example.org", other.requests.single().headers["CF-Access-Client-Id"])
    }

    @Test
    fun `a server from before pairing answers 404, which says so`() {
        val transport = FakeTransport().html(404, "<html>Not found</html>")
        expect<LifeOsException.PairingUnsupported> { client(transport).pair(server.publicUrl, "K7QM-2XRP-9VDA-H4TC", "Pixel 9", null) }
    }

    @Test
    fun `too many tries say how long to wait`() {
        val transport = FakeTransport().json(
            429,
            """{"error":{"code":"too_many_attempts","message":"Too many tries"}}""",
            mapOf("content-type" to listOf("application/json"), "retry-after" to listOf("42")),
        )
        val error = expect<LifeOsException.Api> { client(transport).pair(server.publicUrl, "K7QM-2XRP-9VDA-H4TC", "Pixel 9", null) }
        assertEquals(429, error.status)
        assertEquals(42L, error.retryAfterSeconds)
        assertEquals("not retried, though 429 may pass", 1, transport.requests.size)
    }

    @Test
    fun `an answer without a usable key is refused`() {
        val transport = FakeTransport().json(200, """{"key":"lo_dev_short","keyId":"k","kind":"phone"}""")
        expect<LifeOsException.Protocol> { client(transport).pair(server.publicUrl, "K7QM-2XRP-9VDA-H4TC", "Pixel 9", null) }
    }

    @Test
    fun `who am I, and signing the key out revokes it and its watch's`() = runBlocking {
        val phone = pairNow()
        val identity = client().deviceIdentity()
        assertEquals("phone", identity.kind)
        assertEquals("Anna Example", identity.user?.name)
        val watch = DeviceKey.parse(client().createChildKey("Galaxy Watch8").key)!!

        client().signOutDevice()
        assertTrue(server.key(phone.value)!!.revoked)
        assertTrue("the watch's key goes with its phone's", server.key(watch.value)!!.revoked)
        val signOut = server.requests.last()
        assertEquals("https://lifeos.example.com/api/auth/devices/sign-out", signOut.url)
        assertEquals(phone.bearer, signOut.headers["Authorization"])

        // Signed out already (another tap, a lost answer): fine.
        client().signOutDevice()
    }

    // ---- Headers on every native call

    @Test
    fun `a paired phone sends its key and the Access token, and no actor`() = runBlocking {
        val key = pairNow()
        client().openTasksDueBefore(Instant.EPOCH)
        val headers = server.requests.last().headers
        assertEquals("Bearer ${key.value}", headers["Authorization"])
        assertEquals(token.clientId, headers["CF-Access-Client-Id"])
        assertEquals(token.clientSecret, headers["CF-Access-Client-Secret"])
        assertNull("a key acts as the account's user", headers["X-Life-OS-Actor"])
        assertEquals(listOf("accepted"), verdicts)
    }

    @Test
    fun `a paired phone without Access sends only its key`() = runBlocking {
        val open = ContractServer()
        val paired = LifeOsClient(open, { null }, "ua", sleep = {}).pair(open.publicUrl, open.issueCode(), "Pixel 9", null)
        val key = DeviceKey.parse(paired.key)!!
        val client = LifeOsClient(open, { Connection(open.publicUrl, null, key) }, "ua", sleep = {})
        client.openTasksDueBefore(Instant.EPOCH)
        val headers = open.requests.last().headers
        assertEquals(key.bearer, headers["Authorization"])
        assertNull(headers["CF-Access-Client-Id"])
        assertNull(headers["X-Life-OS-Actor"])
    }

    @Test
    fun `before pairing, the service token and the actor as before`() = runBlocking {
        server.legacyAccess = true
        client().openTasksDueBefore(Instant.EPOCH)
        val headers = server.requests.single().headers
        assertNull(headers["Authorization"])
        assertEquals(token.clientId, headers["CF-Access-Client-Id"])
        assertEquals("user", headers["X-Life-OS-Actor"])
    }

    @Test
    fun `update checks carry the key to the update origin, and nothing to another`() = runBlocking {
        val key = DeviceKey.parse("lo_dev_" + "a".repeat(64))!!
        val transport = FakeTransport().json(200, """{"formatVersion":1,"apps":{}}""")
        LifeOsClient(transport, { Connection("https://lifeos.example.com", token, key) }, "ua", updatesUrl = "https://lifeos.example.com", sleep = {}).updateManifest()
        assertEquals(key.bearer, transport.requests.single().headers["Authorization"])

        val elsewhere = FakeTransport().json(200, """{"formatVersion":1,"apps":{}}""")
        LifeOsClient(elsewhere, { Connection("https://my-own.example.org", token, key) }, "ua", updatesUrl = "https://lifeos.example.com", updatesRepo = "GAMch1k/life-os-app", sleep = {}).updateManifest()
        val github = elsewhere.requests.single()
        assertTrue(github.url.startsWith("https://github.com/"))
        assertNull("no key to GitHub", github.headers["Authorization"])
        assertNull(github.headers["CF-Access-Client-Id"])
    }

    @Test
    fun `a relayed request carries the watch's key, never the phone's or one the watch made up`() = runBlocking {
        val phone = pairNow()
        val watch = DeviceKey.parse(client().createChildKey("Galaxy Watch8").key)!!
        val response = client().relay(
            "POST",
            "/api/voice/v1/audio-jobs",
            mapOf("Content-Type" to "multipart/form-data; boundary=b", "Idempotency-Key" to "cap-1", "X-Life-OS-Actor" to "user", "Authorization" to "Bearer forged"),
            "--b--".toByteArray(),
            25_000,
            watchKey = watch,
        )
        assertEquals(200, response.status)
        val headers = server.requests.last().headers
        assertEquals(watch.bearer, headers["Authorization"])
        assertTrue(headers["Authorization"] != phone.bearer)
        assertEquals(token.clientId, headers["CF-Access-Client-Id"])
        assertNull("ignored with a key, so not sent", headers["X-Life-OS-Actor"])
        assertEquals("cap-1", headers["Idempotency-Key"])
    }

    // ---- 401 and 403

    @Test
    fun `Life OS's 401 to the key means pair again, and is told once, never retried`() {
        val key = pairNow()
        server.revoke(server.key(key.value)!!)
        val error = expect<LifeOsException.SignInRequired> { client().openTasksDueBefore(Instant.EPOCH) }
        assertTrue(error.keySent)
        assertTrue(error.authProblem)
        assertFalse(error.retryable)
        assertTrue(error.message!!.contains("no longer connected"))
        assertEquals(listOf("refused"), verdicts)
        assertEquals("one request, though reads are retried on errors that may pass", 2, server.requests.size)
    }

    @Test
    fun `Life OS's 401 without a key means legacy access is off, so pair`() {
        server.legacyAccess = false
        val error = expect<LifeOsException.SignInRequired> { client().openTasksDueBefore(Instant.EPOCH) }
        assertFalse(error.keySent)
        assertTrue(verdicts.isEmpty())
    }

    @Test
    fun `Cloudflare's 401 and 403 stay the Access token's problem`() {
        val transport = FakeTransport().html(401).html(403).json(403, """{"error":{"code":"forbidden","message":"This request is not allowed here"}}""")
        connection = Connection(server.publicUrl, token, DeviceKey.parse("lo_dev_" + "b".repeat(64)))
        repeat(3) { expect<LifeOsException.Unauthorized> { client(transport).openTasksDueBefore(Instant.EPOCH) } }
        assertTrue("not the key's fault", verdicts.isEmpty())
    }

    @Test
    fun `403 module_not_allowed names the module and is no sign-in problem`() {
        pairNow()
        server.notAllowed += "voice"
        val error = expect<LifeOsException.ModuleNotAllowed> {
            client().createAudioJob(kotlin.io.path.createTempFile().toFile(), "audio/ogg", "web", "{}", idempotencyKey = "c-1")
        }
        assertEquals("Voice notes", error.module)
        assertEquals("Your account can't use Voice notes on this server.", error.message)
        assertFalse(error.authProblem)
        assertFalse(error.retryable)
        assertFalse("the key is fine", verdicts.contains("refused"))
    }

    @Test
    fun `other 403s with Life OS's code are refusals, not sign-in problems`() {
        val transport = FakeTransport().json(403, """{"error":{"code":"admin_only","message":"Only an admin's browser session"}}""")
        val error = expect<LifeOsException.Api> { client(transport).openTasksDueBefore(Instant.EPOCH) }
        assertEquals("admin_only", error.code)
        assertFalse(error.authProblem)
    }

    @Test
    fun `a very slow upload's 401 is tried once more, a second 401 is final`() {
        connection = Connection(server.publicUrl, token, DeviceKey.parse("lo_dev_" + "c".repeat(64)))
        val unauthenticated = """{"error":{"code":"unauthenticated","message":"Sign in to Life OS first"}}"""
        var now = 0L
        val slow = object : HttpTransport {
            val inner = FakeTransport().json(401, unauthenticated).json(202, """{"id":"job-1","status":"queued"}""")
            override suspend fun execute(request: HttpRequest, sink: ResponseSink?, onProgress: ((Long) -> Unit)?) =
                inner.execute(request, sink, onProgress).also { now += 5 * 60_000L }
        }
        val audio = kotlin.io.path.createTempFile().toFile()
        val job = runBlocking { client(slow, clock = { now }).createAudioJob(audio, "audio/ogg", "web", "{}", idempotencyKey = "c-1") }
        assertEquals("job-1", job.id)
        assertEquals(2, slow.inner.requests.size)
        assertEquals("the same Idempotency-Key", listOf("c-1", "c-1"), slow.inner.requests.map { it.headers["Idempotency-Key"] })

        val twice = FakeTransport().json(401, unauthenticated).json(401, unauthenticated)
        val slowTwice = object : HttpTransport {
            override suspend fun execute(request: HttpRequest, sink: ResponseSink?, onProgress: ((Long) -> Unit)?) =
                twice.execute(request, sink, onProgress).also { now += 5 * 60_000L }
        }
        expect<LifeOsException.SignInRequired> { client(slowTwice, clock = { now }).createAudioJob(audio, "audio/ogg", "web", "{}", idempotencyKey = "c-1") }
        assertEquals(2, twice.requests.size)
        assertEquals(listOf("accepted", "refused"), verdicts)
    }

    @Test
    fun `the sign-in check being down is retried and keeps the key`() = runBlocking {
        connection = Connection(server.publicUrl, token, DeviceKey.parse("lo_dev_" + "d".repeat(64)))
        val transport = FakeTransport()
            .json(503, """{"error":{"code":"auth_unavailable","message":"Signing in is unavailable right now"}}""")
            .json(200, """{"items":[]}""")
        client(transport).openTasksDueBefore(Instant.EPOCH)
        assertEquals(2, transport.requests.size)
        assertEquals(listOf("accepted"), verdicts)
    }
}

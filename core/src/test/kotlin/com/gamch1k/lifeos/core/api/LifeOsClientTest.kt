package com.gamch1k.lifeos.core.api

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.time.Instant
import java.time.ZoneId

class LifeOsClientTest {
    @get:Rule
    val temp = TemporaryFolder()

    private val transport = FakeTransport()
    private val sleeps = mutableListOf<Long>()
    private val client = FakeTransport.client(transport, sleeps = sleeps)

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

    private val taskJson = """{"id":"7b0c5a52-0f5e-4a56-9a3c-3d4b2f1e6a10","title":"Pay rent","dueAt":"2026-10-12T00:00:00Z","completed":false,"createdAt":"2026-10-01T10:00:00Z","updatedAt":"2026-10-01T10:00:00Z","futureField":42}"""

    @Test
    fun `every request carries the service token, the user actor and the user agent`() = runBlocking {
        transport.json(200, """{"items":[$taskJson]}""")
        client.openTasksDueBefore(Instant.parse("2026-10-12T21:59:59.999Z"))
        val headers = transport.requests.single().headers
        assertEquals(FakeTransport.TOKEN.clientId, headers["CF-Access-Client-Id"])
        assertEquals(FakeTransport.TOKEN.clientSecret, headers["CF-Access-Client-Secret"])
        assertEquals("user", headers["X-Life-OS-Actor"])
        assertEquals("application/json", headers["Accept"])
        assertEquals("LifeOS-Android/test", headers["User-Agent"])
    }

    @Test
    fun `tasks are read with the documented due filter and unknown fields are ignored`() = runBlocking {
        transport.json(200, """{"items":[$taskJson],"nextCursor":null}""")
        val tasks = client.openTasksDueBefore(Instant.parse("2026-10-12T21:59:59.999Z"))
        assertEquals("Pay rent", tasks.items.single().title)
        assertEquals(
            "https://lifeos.example.com/api/tasks/v1/tasks?completed=false&dueBefore=2026-10-12T21%3A59%3A59.999Z&includeUndated=false&limit=100",
            transport.requests.single().url,
        )
    }

    @Test
    fun `events follow the cursor and pass the time zone`() = runBlocking {
        val event = """{"id":"e1","title":"Gym","allDay":false,"startAt":"2026-10-12T16:00:00Z","endAt":"2026-10-12T17:00:00Z","status":"active","occurrence":{"seriesId":"e1","recurrenceId":"2026-10-12T16:00:00Z","modified":false}}"""
        transport.json(200, """{"items":[$event],"nextCursor":"abc"}""").json(200, """{"items":[$event]}""")
        val events = client.events(Instant.parse("2026-10-11T22:00:00Z"), Instant.parse("2026-10-13T22:00:00Z"), ZoneId.of("Europe/Berlin"))
        assertEquals(2, events.size)
        assertEquals("e1/2026-10-12T16:00:00Z", events.first().key)
        assertTrue(transport.requests[0].url.contains("timeZone=Europe%2FBerlin"))
        assertTrue(transport.requests[1].url.endsWith("&cursor=abc"))
    }

    @Test
    fun `completing a task posts to the completion action`() = runBlocking {
        transport.json(200, taskJson.replace("\"completed\":false", "\"completed\":true"))
        val task = client.completeTask("7b0c5a52-0f5e-4a56-9a3c-3d4b2f1e6a10")
        assertTrue(task.completed)
        val request = transport.requests.single()
        assertEquals("POST", request.method)
        assertEquals("https://lifeos.example.com/api/tasks/v1/tasks/7b0c5a52-0f5e-4a56-9a3c-3d4b2f1e6a10/complete", request.url)
    }

    @Test
    fun `a redirect to the Access login is an Access redirect, never retried`() {
        transport.redirect(302, "https://damp-surf-d6be.cloudflareaccess.com/cdn-cgi/access/login/lifeos.gamch1k.com?kid=x")
        val error = expect<LifeOsException.AccessRedirect> { client.openTasksDueBefore(Instant.EPOCH) }
        assertEquals("damp-surf-d6be.cloudflareaccess.com", error.host)
        assertTrue(error.authProblem)
        assertFalse(error.retryable)
        assertEquals(1, transport.requests.size)
        assertFalse("the query of the login URL is not shown", error.message!!.contains("kid="))
    }

    @Test
    fun `401 and 403 are a refused token`() {
        transport.html(401)
        val unauthorized = expect<LifeOsException.Unauthorized> { client.openTasksDueBefore(Instant.EPOCH) }
        assertEquals(401, unauthorized.status)
        assertTrue(unauthorized.message!!.contains(".access"))
        transport.json(403, """{"error":{"code":"forbidden","message":"Forbidden"}}""")
        assertEquals(403, expect<LifeOsException.Unauthorized> { client.openTasksDueBefore(Instant.EPOCH) }.status)
        assertEquals(2, transport.requests.size)
    }

    @Test
    fun `a 4xx carries the Life OS error envelope`() {
        transport.json(400, """{"error":{"code":"actor_required","message":"X-Life-OS-Actor is required"},"request_id":"r1"}""")
        val error = expect<LifeOsException.Api> {
            client.createInboxItem(CreateInboxItem(content = "x", source = "web", dedupeKey = "android-app:1"))
        }
        assertEquals(400, error.status)
        assertEquals("actor_required", error.code)
        assertEquals("X-Life-OS-Actor is required", error.detail)
        assertFalse(error.retryable)
        assertEquals(1, transport.requests.size)
    }

    @Test
    fun `a 4xx without an envelope still says its status`() {
        transport.html(413, "<html>413 Request Entity Too Large</html>")
        val error = expect<LifeOsException.Api> { client.openTasksDueBefore(Instant.EPOCH) }
        assertEquals("http_413", error.code)
        assertEquals("too large", error.detail)
    }

    @Test
    fun `an idempotent call is retried on 5xx and then succeeds`() = runBlocking {
        transport.html(502).json(503, """{"error":{"code":"service_unavailable","message":"Service Unavailable"}}""").json(200, """{"items":[]}""")
        val tasks = client.openTasksDueBefore(Instant.EPOCH)
        assertTrue(tasks.items.isEmpty())
        assertEquals(3, transport.requests.size)
        assertEquals(listOf(700L, 2_500L), sleeps)
    }

    @Test
    fun `retries stop after the last delay and the 5xx is reported`() {
        transport.html(502).html(502).html(502)
        val error = expect<LifeOsException.Server> { client.openTasksDueBefore(Instant.EPOCH) }
        assertEquals(502, error.status)
        assertTrue(error.retryable)
        assertEquals(3, transport.requests.size)
    }

    @Test
    fun `a voice upload is never retried by the client`() {
        val audio = temp.newFile("voice.ogg").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        transport.html(502)
        expect<LifeOsException.Server> { client.createAudioJob(audio, "audio/ogg", "web", JsonObject(emptyMap())) }
        assertEquals(1, transport.requests.size)
        assertTrue(sleeps.isEmpty())
    }

    @Test
    fun `no network becomes Offline and is retried for idempotent calls`() = runBlocking {
        transport.fail(TransportException.Network("ERR_INTERNET_DISCONNECTED (-106)", offline = true))
            .json(200, """{"items":[]}""")
        client.openTasksDueBefore(Instant.EPOCH)
        assertEquals(2, transport.requests.size)
    }

    @Test
    fun `a connection reset is Offline but not no network`() {
        repeat(3) { transport.fail(TransportException.Network("ERR_CONNECTION_RESET (-101)", offline = false)) }
        val error = expect<LifeOsException.Offline> { client.openTasksDueBefore(Instant.EPOCH) }
        assertFalse(error.noNetwork)
        assertTrue(error.message!!.contains("ERR_CONNECTION_RESET"))
    }

    @Test
    fun `a failure on the phone (disk full) is a local error, not a crash`() {
        transport.fail(TransportException.Local("could not save the answer: ENOSPC"))
        val error = expect<LifeOsException.Local> { client.updateManifest() }
        assertTrue(error.message!!.contains("ENOSPC"))
        assertFalse(error.retryable)
    }

    @Test
    fun `a stray IOException from the transport is mapped too`() {
        val failing = object : HttpTransport {
            override suspend fun execute(request: HttpRequest, sink: ResponseSink?, onProgress: ((Long) -> Unit)?): HttpResponse =
                throw java.io.FileNotFoundException("updates/x.part")
        }
        val client = LifeOsClient(failing, { FakeTransport.CONNECTION }, "ua", sleep = {})
        expect<LifeOsException.Local> { client.updateManifest() }
    }

    @Test
    fun `a timeout is reported in seconds`() {
        repeat(3) { transport.fail(TransportException.Timeout(30_000)) }
        assertEquals(30, expect<LifeOsException.Timeout> { client.openTasksDueBefore(Instant.EPOCH) }.seconds)
    }

    @Test
    fun `without a token nothing is sent`() {
        val signedOut = FakeTransport.client(transport, connection = null)
        expect<LifeOsException.NotSignedIn> { signedOut.openTasksDueBefore(Instant.EPOCH) }
        assertTrue(transport.requests.isEmpty())
    }

    @Test
    fun `a web page instead of JSON is a protocol error`() {
        transport.html(200, "<html>Sign in</html>")
        val error = expect<LifeOsException.Protocol> { client.openTasksDueBefore(Instant.EPOCH) }
        assertTrue(error.message!!.contains("text/html"))
    }

    @Test
    fun `test connection reads one task with the candidate token`() = runBlocking {
        transport.json(200, """{"items":[]}""")
        val signedOut = FakeTransport.client(transport, connection = null)
        signedOut.testConnection(Connection("https://other.example.com/", FakeTransport.TOKEN))
        val request = transport.requests.single()
        assertEquals("https://other.example.com/api/tasks/v1/tasks?limit=1", request.url)
        assertEquals(FakeTransport.TOKEN.clientId, request.headers["CF-Access-Client-Id"])
    }

    @Test
    fun `an inbox capture sends its dedupe key and metadata as JSON`() = runBlocking {
        transport.json(201, """{"id":"i1","content":"Buy milk","source":"web","state":"raw","processingMetadata":{},"correlationId":"i1","createdAt":"2026-10-12T10:00:00Z","updatedAt":"2026-10-12T10:00:00Z","mediaReferences":[]}""")
        val item = client.createInboxItem(
            CreateInboxItem(
                content = "Buy milk",
                source = "web",
                sourceMetadata = JsonObject(mapOf("via" to JsonPrimitive("android-app"))),
                dedupeKey = "android-app:42",
            ),
        )
        assertEquals("raw", item.state)
        val body = transport.bodies.single()!!.text()
        assertEquals("""{"content":"Buy milk","source":"web","sourceMetadata":{"via":"android-app"},"dedupeKey":"android-app:42"}""", body)
        assertEquals("application/json", transport.requests.single().body!!.contentType)
    }

    @Test
    fun `an image upload is multipart with the images field`() = runBlocking {
        val photo = temp.newFile("photo.jpg").apply { writeBytes(byteArrayOf(-1, -40, -1, 0)) }
        transport.json(201, """{"items":[{"reference":"inbox-media:${"a".repeat(64)}","sha256":"${"a".repeat(64)}","contentType":"image/jpeg","byteSize":4}]}""")
        val media = client.uploadImage(photo)
        assertEquals("inbox-media:${"a".repeat(64)}", media.reference)
        val body = transport.bodies.single()!!.text()
        assertTrue(body.contains("Content-Disposition: form-data; name=\"images\"; filename=\"photo.jpg\""))
        assertTrue(transport.requests.single().body!!.contentType.startsWith("multipart/form-data; boundary="))
    }

    @Test
    fun `the update manifest is read from android updates`() = runBlocking {
        transport.json(200, """{"formatVersion":1,"updatedAt":"2026-10-12T14:03:00Z","apps":{"phone":{"versionCode":3,"versionName":"0.3.0","minSdk":34,"sha256":"${"0".repeat(64)}","size":10,"url":"/android/lifeos-phone-0.3.0-3.apk","previous":[]}}}""")
        val manifest = client.updateManifest()
        assertEquals(3L, manifest.apps.phone!!.versionCode)
        assertNull(manifest.apps.wear)
        assertEquals("https://lifeos.example.com/android/updates", transport.requests.single().url)
    }

    @Test
    fun `a download asks for the rest of a partial file`() = runBlocking {
        transport.json(206, "rest", mapOf("content-range" to listOf("bytes 10-13/14")))
        val out = java.io.ByteArrayOutputStream()
        val response = client.download("/android/lifeos-phone-0.3.0-3.apk", { _, _ -> out }, rangeStart = 10)
        assertEquals(206, response.status)
        assertEquals("rest", out.text())
        assertEquals("bytes=10-", transport.requests.single().headers["Range"])
    }
}

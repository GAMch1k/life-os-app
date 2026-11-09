package com.gamch1k.lifeos.core.api

import com.gamch1k.lifeos.core.config.LifeOsEndpoints
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
        transport.redirect(302, "https://example-team.cloudflareaccess.com/cdn-cgi/access/login/lifeos.example.com?kid=x")
        val error = expect<LifeOsException.AccessRedirect> { client.openTasksDueBefore(Instant.EPOCH) }
        assertEquals("example-team.cloudflareaccess.com", error.host)
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
    fun `a voice upload without a key is never retried by the client`() {
        val audio = temp.newFile("voice.ogg").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        transport.html(502)
        expect<LifeOsException.Server> { client.createAudioJob(audio, "audio/ogg", "web", "{}", idempotencyKey = null) }
        assertEquals(1, transport.requests.size)
        assertTrue(sleeps.isEmpty())
        assertFalse(transport.requests.single().headers.containsKey("Idempotency-Key"))
    }

    @Test
    fun `a voice upload with a key sends it and is retried with the same body fields`() = runBlocking {
        val audio = temp.newFile("voice.ogg").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        transport.html(502).json(202, """{"id":"j1","status":"queued","source":"web","attemptCount":0,"createdAt":"x","updatedAt":"x"}""")
        val metadata = """{"via":"android-app","timeZone":"Europe/Berlin","captureId":"c-1"}"""
        val job = client.createAudioJob(audio, "audio/ogg", "web", metadata, idempotencyKey = "c-1")
        assertEquals("j1", job.id)
        assertEquals(2, transport.requests.size)
        assertTrue(transport.requests.all { it.headers["Idempotency-Key"] == "c-1" })
        val fields = transport.bodies.map { body -> body!!.text().substringAfter("name=\"sourceMetadata\"\r\n\r\n").substringBefore("\r\n") }
        assertEquals(listOf(metadata, metadata), fields)
    }

    @Test
    fun `409 idempotency_conflict is a refusal, not retried`() {
        val audio = temp.newFile("voice.ogg").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        transport.json(409, """{"error":{"code":"idempotency_conflict","message":"The Idempotency-Key was already used with a different upload"}}""")
        val error = expect<LifeOsException.Api> { client.createAudioJob(audio, "audio/ogg", "web", "{}", idempotencyKey = "c-1") }
        assertEquals("idempotency_conflict", error.code)
        assertEquals(1, transport.requests.size)
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
    fun `a relayed request returns every answer as it is, with the phone's token`() = runBlocking {
        transport.redirect(302, "https://team.cloudflareaccess.com/cdn-cgi/access/login").json(500, """{"error":{"code":"internal_error","message":"x"}}""")
        val redirect = client.relay(
            "POST",
            "/api/inbox/v1/inbox-items?x=1",
            mapOf("X-Life-OS-Actor" to "user", "Content-Type" to "application/json", "CF-Access-Client-Id" to "forged"),
            "{}".toByteArray(),
            timeoutMillis = 30_000,
        )
        assertEquals(302, redirect.status)
        assertEquals(500, client.relay("GET", "/api/tasks/v1/tasks", emptyMap(), null, 30_000).status)
        val sent = transport.requests.first()
        assertEquals("https://lifeos.example.com/api/inbox/v1/inbox-items?x=1", sent.url)
        assertEquals(FakeTransport.TOKEN.clientId, sent.headers["CF-Access-Client-Id"])
        assertEquals("user", sent.headers["X-Life-OS-Actor"])
        assertFalse(sent.headers.containsKey("Content-Type"))
        assertEquals("application/json", sent.body!!.contentType)
        assertEquals(2, transport.requests.size)
    }

    @Test
    fun `a relayed request without a token or a network says so`() {
        expect<LifeOsException.NotSignedIn> { FakeTransport.client(transport, connection = null).relay("GET", "/api/x", emptyMap(), null, 1_000) }
        transport.fail(TransportException.Network("ERR_CONNECTION_RESET (-101)", offline = false))
        expect<LifeOsException.Offline> { client.relay("GET", "/api/x", emptyMap(), null, 1_000) }
        assertEquals(1, transport.requests.size)
    }

    private val manifestJson = """{"formatVersion":1,"updatedAt":"2026-10-12T14:03:00Z","apps":{"phone":{"versionCode":3,"versionName":"0.3.0","minSdk":34,"sha256":"${"0".repeat(64)}","size":10,"url":"/android/lifeos-phone-0.3.0-3.apk","previous":[]}}}"""

    @Test
    fun `the update manifest is read from android updates`() = runBlocking {
        transport.json(200, manifestJson)
        val manifest = client.updateManifest()
        assertEquals(3L, manifest.apps.phone!!.versionCode)
        assertNull(manifest.apps.wear)
        val request = transport.requests.single()
        assertEquals("https://lifeos.example.com/android/updates", request.url)
        assertEquals("the update origin is the owner's server: the token goes along", FakeTransport.TOKEN.clientId, request.headers["CF-Access-Client-Id"])
    }

    @Test
    fun `switching servers never switches the update source, and the other server's token stays with it`() = runBlocking {
        var server = "https://lifeos.example.com"
        val client = LifeOsClient(transport, { Connection(server, FakeTransport.TOKEN) }, "ua", updatesUrl = "https://lifeos.example.com", sleep = {})
        transport.json(200, manifestJson)
        client.updateManifest()

        // Settings › Change server or token: the owner's own server.
        server = "https://my-own.example.org"
        transport.json(200, manifestJson).json(200, "apk").json(200, """{"items":[]}""")
        client.updateManifest()
        client.download("/android/lifeos-phone-0.3.0-3.apk", { _, _ -> java.io.ByteArrayOutputStream() })
        client.openTasksDueBefore(Instant.EPOCH)

        val (before, after, apk, tasks) = transport.requests
        assertEquals("https://lifeos.example.com/android/updates", before.url)
        assertEquals(before.url, after.url)
        assertEquals("https://lifeos.example.com/android/lifeos-phone-0.3.0-3.apk", apk.url)
        for (update in listOf(after, apk)) {
            assertNull(update.headers["CF-Access-Client-Id"])
            assertNull(update.headers["CF-Access-Client-Secret"])
            assertNull(update.headers["X-Life-OS-Actor"])
        }
        assertTrue("everything else goes to the new server", tasks.url.startsWith("https://my-own.example.org/api/tasks/v1/tasks?"))
        assertEquals(FakeTransport.TOKEN.clientId, tasks.headers["CF-Access-Client-Id"])
    }

    @Test
    fun `by default updates come from the build's update origin, not from the server`() = runBlocking {
        val client = LifeOsClient(transport, { Connection("https://my-own.example.org", FakeTransport.TOKEN) }, "ua", sleep = {})
        transport.json(200, manifestJson)
        client.updateManifest()
        assertEquals(LifeOsEndpoints.UPDATES_URL + "/android/updates", transport.requests.single().url)
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

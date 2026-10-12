package com.gamch1k.lifeos.wear.capture

import com.gamch1k.lifeos.wear.net.ApiRequest
import com.gamch1k.lifeos.wear.net.ApiResult
import com.gamch1k.lifeos.wear.net.ApiTransport
import com.gamch1k.lifeos.wear.net.LifeOsApi
import com.gamch1k.lifeos.wear.net.Route
import com.gamch1k.lifeos.wear.net.Unavailability
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class CaptureUploaderTest {
    @get:Rule
    val folder = TemporaryFolder()

    private var now = 1_000_000L

    /** A Life OS stand-in: answers uploads and job polls from scripts, records every request. */
    private inner class FakeLifeOs : ApiTransport {
        val requests = mutableListOf<ApiRequest>()
        var upload: () -> ApiResult = { json(202, """{"id":"job-1","status":"queued","source":"watch"}""") }
        var job: () -> ApiResult = { json(200, """{"id":"job-1","status":"processing"}""") }

        override suspend fun execute(request: ApiRequest): ApiResult {
            requests += request
            return if (request.method == "POST") upload() else job()
        }

        val uploads get() = requests.filter { it.method == "POST" }
    }

    private fun json(status: Int, body: String, route: Route = Route.PHONE) = ApiResult.Response(status, body.toByteArray(), route)

    private fun setUp(server: FakeLifeOs, authRefused: () -> Unit = {}): Pair<CaptureStore, CaptureUploader> {
        val store = CaptureStore(folder.newFolder("captures"), clock = { now })
        val uploader = CaptureUploader(store, LifeOsApi(server), onAuthRefused = { authRefused() }, clock = { now }, sleep = { now += it })
        return store to uploader
    }

    private suspend fun record(store: CaptureStore, id: String = "c1"): Capture {
        store.newAudioFile(id, "ogg").writeBytes("OggS-fake-audio".toByteArray())
        val capture = Capture(id, createdAt = now, durationMillis = 3_000, fileName = "$id.ogg", contentType = "audio/ogg", timeZone = "Europe/Berlin")
        store.add(capture)
        return capture
    }

    @Test
    fun `a recording is uploaded once as a watch capture with its id as idempotency key`() = runTest {
        val server = FakeLifeOs()
        val (store, uploader) = setUp(server)
        record(store)

        uploader.run(followFreshJobs = false)
        uploader.run(followFreshJobs = false)

        val upload = server.uploads.single()
        assertEquals("/api/voice/v1/audio-jobs", upload.path)
        assertEquals("c1", upload.headers["Idempotency-Key"])
        val body = upload.body!!.toString(Charsets.ISO_8859_1)
        assertTrue(body, "name=\"source\"\r\n\r\nwatch\r\n" in body)
        assertTrue(body, "\"via\":\"wear-app\"" in body && "\"timeZone\":\"Europe/Berlin\"" in body && "\"captureId\":\"c1\"" in body)
        assertTrue(body, "filename=\"c1.ogg\"\r\nContent-Type: audio/ogg\r\n\r\nOggS-fake-audio\r\n" in body)
        assertTrue(upload.headers["Content-Type"]!!.startsWith("multipart/form-data; boundary="))

        val sent = store.get("c1")!!
        assertEquals(CaptureState.SENT, sent.state)
        assertEquals("job-1", sent.jobId)
        assertEquals("phone", sent.route)
    }

    @Test
    fun `fresh jobs are followed until they reach the inbox, and the audio goes`() = runTest {
        val server = FakeLifeOs()
        var polls = 0
        server.job = {
            polls++
            if (polls < 3) json(200, """{"id":"job-1","status":"processing"}""") else json(200, """{"id":"job-1","status":"succeeded","inboxItemId":"inbox-9"}""")
        }
        val (store, uploader) = setUp(server)
        val capture = record(store)

        assertNull(uploader.run(followFreshJobs = true))

        val done = store.get("c1")!!
        assertEquals(CaptureState.IN_INBOX, done.state)
        assertEquals("inbox-9", done.inboxItemId)
        assertEquals(3, polls)
        assertFalse(store.audioFile(capture).exists())
    }

    @Test
    fun `a transcription without speech fails visibly and keeps the audio`() = runTest {
        val server = FakeLifeOs()
        server.job = { json(200, """{"id":"job-1","status":"failed","errorSummary":"No speech was heard in the recording. Record it again."}""") }
        val (store, uploader) = setUp(server)
        val capture = record(store)
        uploader.run(followFreshJobs = true)
        val failed = store.get("c1")!!
        assertEquals(CaptureState.FAILED, failed.state)
        assertEquals("No speech was heard in the recording. Record it again.", failed.message)
        assertTrue(store.audioFile(capture).exists())
    }

    @Test
    fun `without the phone and a direct connection it waits, then goes when one is back`() = runTest {
        val server = FakeLifeOs()
        server.upload = { ApiResult.Unavailable(Unavailability.NO_PHONE) }
        val (store, uploader) = setUp(server)
        record(store)

        val wakeUp = uploader.run(followFreshJobs = false)
        assertEquals(CaptureState.WAITING, store.get("c1")!!.state)
        assertEquals(now + 30_000, wakeUp)

        // Too early: nothing is sent.
        uploader.run(followFreshJobs = false)
        assertEquals(1, server.uploads.size)

        now = wakeUp!!
        server.upload = { json(202, """{"id":"job-2","status":"queued"}""", Route.DIRECT) }
        uploader.run(followFreshJobs = false)
        val sent = store.get("c1")!!
        assertEquals(CaptureState.SENT, sent.state)
        assertEquals("direct", sent.route)
        assertEquals(listOf("c1", "c1"), server.uploads.map { it.headers["Idempotency-Key"] })
    }

    @Test
    fun `server errors are retried with backoff, a refusal is final`() = runTest {
        val server = FakeLifeOs()
        server.upload = { json(503, "") }
        val (store, uploader) = setUp(server)
        record(store)
        uploader.run(followFreshJobs = false)
        val queued = store.get("c1")!!
        assertEquals(CaptureState.QUEUED, queued.state)
        assertEquals(1, queued.attempts)

        now = queued.nextAttemptAt
        server.upload = { json(413, """{"error":{"code":"audio_too_large","message":"Audio exceeds the limit"}}""") }
        uploader.run(followFreshJobs = false)
        val failed = store.get("c1")!!
        assertEquals(CaptureState.FAILED, failed.state)
        assertEquals("The recording is too long for Voice.", failed.message)
    }

    @Test
    fun `a refused token asks the phone for a new one and retries later`() = runTest {
        val server = FakeLifeOs()
        server.upload = { json(403, "") }
        var asked = 0
        val (store, uploader) = setUp(server) { asked++ }
        record(store)
        uploader.run(followFreshJobs = false)
        assertEquals(1, asked)
        assertEquals(CaptureState.QUEUED, store.get("c1")!!.state)
    }

    @Test
    fun `an accepted upload with an unreadable answer is not sent again`() = runTest {
        val server = FakeLifeOs()
        server.upload = { json(202, "<html>") }
        val (store, uploader) = setUp(server)
        record(store)
        uploader.run(followFreshJobs = false)
        now += 3_600_000
        uploader.run(followFreshJobs = false)
        assertEquals(1, server.uploads.size)
        assertEquals(CaptureState.FAILED, store.get("c1")!!.state)
    }

    @Test
    fun `the queue survives a restart`() = runTest {
        val server = FakeLifeOs()
        server.upload = { ApiResult.Unavailable(Unavailability.NETWORK) }
        val (store, uploader) = setUp(server)
        record(store)
        uploader.run(followFreshJobs = false)
        val reloaded = CaptureStore(store.directory, clock = { now })
        assertEquals(store.captures.value, reloaded.captures.value)
        assertEquals(CaptureState.WAITING, reloaded.get("c1")!!.state)
    }

    @Test
    fun `every retry sends the same key and byte-identical metadata, also after a restart`() = runTest {
        val server = FakeLifeOs()
        server.upload = { json(503, "") }
        val (store, uploader) = setUp(server)
        record(store)
        uploader.run(followFreshJobs = false)
        now = store.get("c1")!!.nextAttemptAt
        // The watch restarts in between: the queue comes back from disk.
        val reloaded = CaptureStore(store.directory, clock = { now })
        val again = CaptureUploader(reloaded, LifeOsApi(server), onAuthRefused = {}, clock = { now }, sleep = { now += it })
        server.upload = { json(202, """{"id":"job-1","status":"queued"}""") }
        again.run(followFreshJobs = false)

        fun metadata(request: ApiRequest) = request.body!!.toString(Charsets.ISO_8859_1)
            .substringAfter("name=\"sourceMetadata\"\r\n\r\n").substringBefore("\r\n")
        assertEquals(2, server.uploads.size)
        assertEquals(setOf("c1"), server.uploads.map { it.headers["Idempotency-Key"] }.toSet())
        assertEquals(1, server.uploads.map(::metadata).toSet().size)
        assertTrue(metadata(server.uploads.first()), "\"recordedAt\"" in metadata(server.uploads.first()))
        assertEquals(CaptureState.SENT, reloaded.get("c1")!!.state)
    }

    @Test
    fun `409 idempotency_conflict is a final refusal with a clear message`() = runTest {
        val server = FakeLifeOs()
        server.upload = { json(409, """{"error":{"code":"idempotency_conflict","message":"The Idempotency-Key was already used with a different upload"}}""") }
        val (store, uploader) = setUp(server)
        record(store)
        uploader.run(followFreshJobs = false)
        val failed = store.get("c1")!!
        assertEquals(CaptureState.FAILED, failed.state)
        assertEquals("Voice holds a different recording under this capture's id, so it was not sent again.", failed.message)
        assertEquals(1, server.uploads.size)
    }

    @Test
    fun `the owner's retry sends a failed recording again`() = runTest {
        val server = FakeLifeOs()
        server.upload = { json(400, """{"error":{"code":"unsupported_audio","message":"Audio must be a WAV, WebM, Ogg, or MP4 recording"}}""") }
        val (store, uploader) = setUp(server)
        record(store)
        uploader.run(followFreshJobs = false)
        assertEquals("Audio must be a WAV, WebM, Ogg, or MP4 recording", store.get("c1")!!.message)
        store.update("c1") { CaptureMachine.retry(it) }
        server.upload = { json(202, """{"id":"job-3","status":"queued"}""") }
        uploader.run(followFreshJobs = false)
        assertEquals("job-3", store.get("c1")!!.jobId)
    }
}

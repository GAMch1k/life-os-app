package com.gamch1k.lifeos.core.capture

import com.gamch1k.lifeos.core.TestDataStores
import com.gamch1k.lifeos.core.api.AudioJob
import com.gamch1k.lifeos.core.api.CreateInboxItem
import com.gamch1k.lifeos.core.api.InboxItem
import com.gamch1k.lifeos.core.api.LifeOsException
import com.gamch1k.lifeos.core.api.ProcessingAttempt
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.time.Duration
import java.time.Instant

class CaptureSenderTest {
    @get:Rule
    val stores = TestDataStores()

    /** Records every call; each call takes the next scripted failure, if any. */
    private class FakeApi : CaptureApi {
        val uploads = mutableListOf<String>()
        val captures = mutableListOf<CreateInboxItem>()
        /** Every voice upload attempt: file, metadata JSON as sent, Idempotency-Key. */
        val audioUploads = mutableListOf<Triple<String, String, String>>()
        val audioAttempts = mutableListOf<Triple<String, String, String>>()
        val failures = ArrayDeque<LifeOsException?>()
        var job = AudioJob(id = "job-1", status = "queued")
        var item = InboxItem(id = "item-1", state = "raw")
        var attempts = emptyList<ProcessingAttempt>()

        private fun maybeFail() {
            failures.removeFirstOrNull()?.let { throw it }
        }

        override suspend fun uploadImage(file: File): String {
            maybeFail()
            uploads += file.name
            return "inbox-media:" + file.name.padEnd(64, '0').take(64)
        }

        override suspend fun createInboxItem(request: CreateInboxItem): InboxItem {
            maybeFail()
            captures += request
            return item
        }

        override suspend fun createAudioJob(file: File, sourceMetadataJson: String, idempotencyKey: String): AudioJob {
            audioAttempts += Triple(file.name, sourceMetadataJson, idempotencyKey)
            maybeFail()
            audioUploads += Triple(file.name, sourceMetadataJson, idempotencyKey)
            return job
        }

        override suspend fun audioJob(id: String) = job
        override suspend fun inboxItem(id: String) = item
        override suspend fun processingAttempts(itemId: String) = attempts
    }

    private val api = FakeApi()
    private val now = Instant.parse("2026-10-12T10:00:00Z")
    private lateinit var store: CaptureStore
    private lateinit var sender: CaptureSender

    private fun setUp() {
        store = CaptureStore(stores.create())
        sender = CaptureSender(store, api, clock = { now }, maxAttempts = 5)
    }

    private fun capture(kind: CaptureKind, text: String = "", files: List<File> = emptyList()) = Capture.create(
        id = "c-${kind.name.lowercase()}",
        kind = kind,
        createdAt = now.toString(),
        timeZone = "Europe/Berlin",
        text = text,
        files = files.map { it.path },
    )

    private val offline = LifeOsException.Offline("ERR_INTERNET_DISCONNECTED (-106)", noNetwork = true)

    @Test
    fun `a text capture is sent once with its dedupe key, also across retries`() = runBlocking {
        setUp()
        store.add(capture(CaptureKind.TEXT, "Buy milk"))
        api.failures += LifeOsException.Server(502, null, "bad gateway")

        val first = sender.send("c-text")
        assertTrue(first is SendOutcome.Retry)
        assertEquals(CaptureStatus.PENDING, store.get("c-text")!!.status)
        assertTrue(store.get("c-text")!!.error!!.contains("502"))

        assertEquals(SendOutcome.Sent, sender.send("c-text"))
        val sent = store.get("c-text")!!
        assertEquals(CaptureStatus.SENT, sent.status)
        assertEquals("item-1", sent.inboxItemId)
        assertEquals("raw", sent.inboxState)
        assertNull(sent.error)
        assertEquals(2, sent.attempts)

        // A third run (e.g. WorkManager after a lost result) sends nothing.
        assertEquals(SendOutcome.Sent, sender.send("c-text"))
        val request = api.captures.single()
        assertEquals("android-app:c-text", request.dedupeKey)
        assertEquals("web", request.source)
        assertEquals("Buy milk", request.content)
        assertEquals(JsonPrimitive("android-app"), request.sourceMetadata!!["via"])
        assertEquals(JsonPrimitive("Europe/Berlin"), request.sourceMetadata["timeZone"])
        assertNull(request.mediaReferences)
    }

    @Test
    fun `photos already uploaded are not uploaded again`() = runBlocking {
        setUp()
        val photos = listOf(stores.newFile("a.jpg"), stores.newFile("b.jpg"), stores.newFile("c.jpg"))
        store.add(capture(CaptureKind.PHOTO, files = photos))
        api.failures += null // a.jpg uploads
        api.failures += offline // b.jpg fails

        assertTrue(sender.send("c-photo") is SendOutcome.Retry)
        assertEquals(1, store.get("c-photo")!!.mediaReferences.size)

        assertEquals(SendOutcome.Sent, sender.send("c-photo"))
        assertEquals(listOf("a.jpg", "b.jpg", "c.jpg"), api.uploads)
        val request = api.captures.single()
        assertEquals(3, request.mediaReferences!!.size)
        assertTrue(request.mediaReferences[0].startsWith("inbox-media:a.jpg"))
        assertEquals("Photo", request.content)
        assertEquals("android-app:c-photo", request.dedupeKey)
    }

    @Test
    fun `a voice note is uploaded once, and Voice creates the Inbox item`() = runBlocking {
        setUp()
        store.add(capture(CaptureKind.VOICE, files = listOf(stores.newFile("voice.ogg"))))
        assertEquals(SendOutcome.Sent, sender.send("c-voice"))
        assertEquals(SendOutcome.Sent, sender.send("c-voice"))
        assertEquals(1, api.audioUploads.size)
        assertEquals("""{"via":"android-app","timeZone":"Europe/Berlin","captureId":"c-voice"}""", api.audioUploads.single().second)
        assertEquals("c-voice", api.audioUploads.single().third)
        assertTrue(api.captures.isEmpty())
        val sent = store.get("c-voice")!!
        assertEquals("job-1", sent.voiceJobId)
        assertEquals("Sent · transcribing…", CaptureStatusText.describe(sent))
    }

    @Test
    fun `a refusal fails at once, a bad token is marked so a new token resends it`() = runBlocking {
        setUp()
        store.add(capture(CaptureKind.TEXT, "x"))
        api.failures += LifeOsException.Unauthorized(401)
        assertTrue(sender.send("c-text") is SendOutcome.Failed)
        val failed = store.get("c-text")!!
        assertEquals(CaptureStatus.FAILED, failed.status)
        assertTrue(failed.authProblem)

        assertEquals(listOf("c-text"), sender.resetAuthFailures())
        assertEquals(CaptureStatus.PENDING, store.get("c-text")!!.status)
        assertEquals(SendOutcome.Sent, sender.send("c-text"))
        assertEquals("android-app:c-text", api.captures.single().dedupeKey)
    }

    @Test
    fun `a 4xx refusal is not retried and keeps the server's reason`() = runBlocking {
        setUp()
        store.add(capture(CaptureKind.VOICE, files = listOf(stores.newFile("voice.ogg"))))
        api.failures += LifeOsException.Api(413, "audio_too_large", "the audio is larger than 25 MiB")
        val outcome = sender.send("c-voice")
        assertTrue(outcome is SendOutcome.Failed)
        assertFalse(store.get("c-voice")!!.authProblem)
        assertTrue(CaptureStatusText.describe(store.get("c-voice")!!).contains("audio_too_large"))
    }

    @Test
    fun `retryable failures give up after the attempt limit`() = runBlocking {
        setUp()
        store.add(capture(CaptureKind.TEXT, "x"))
        repeat(5) { api.failures += offline }
        repeat(4) { assertTrue(sender.send("c-text") is SendOutcome.Retry) }
        assertTrue(sender.send("c-text") is SendOutcome.Failed)
        assertEquals(CaptureStatus.FAILED, store.get("c-text")!!.status)
    }

    @Test
    fun `a missing recording fails without calling Life OS`() = runBlocking {
        setUp()
        store.add(capture(CaptureKind.VOICE, files = listOf(File(stores.root, "gone.ogg"))))
        assertTrue(sender.send("c-voice") is SendOutcome.Failed)
        assertTrue(api.audioUploads.isEmpty())
    }

    @Test
    fun `a job cancelled right after the voice upload keeps the job id, so it is not uploaded again`() = runBlocking {
        setUp()
        store.add(capture(CaptureKind.VOICE, files = listOf(stores.newFile("voice.ogg"))))
        val cancelling = object : CaptureApi by api {
            override suspend fun createAudioJob(file: File, sourceMetadataJson: String, idempotencyKey: String): AudioJob {
                val job = api.createAudioJob(file, sourceMetadataJson, idempotencyKey)
                currentCoroutineContext().cancel()
                return job
            }
        }
        val worker = launch { CaptureSender(store, cancelling, clock = { now }).send("c-voice") }
        worker.join()
        assertEquals("job-1", store.get("c-voice")!!.voiceJobId)
        assertEquals(SendOutcome.Sent, sender.send("c-voice"))
        assertEquals(1, api.audioUploads.size)
    }

    @Test
    fun `a job cancelled mid-send goes back to the queue instead of staying SENDING`() = runBlocking {
        setUp()
        store.add(capture(CaptureKind.TEXT, "x"))
        val hanging = object : CaptureApi by api {
            override suspend fun createInboxItem(request: CreateInboxItem): InboxItem = awaitCancellation()
        }
        val worker = launch { CaptureSender(store, hanging, clock = { now }).send("c-text") }
        delay(200)
        assertEquals(CaptureStatus.SENDING, store.get("c-text")!!.status)
        worker.cancelAndJoin()
        assertEquals(CaptureStatus.PENDING, store.get("c-text")!!.status)
    }

    @Test
    fun `every voice retry sends the capture id as key and byte-identical metadata`() = runBlocking {
        setUp()
        store.add(capture(CaptureKind.VOICE, files = listOf(stores.newFile("voice.ogg"))))
        api.failures += LifeOsException.Offline("ERR_CONNECTION_RESET (-101)", noNetwork = false)
        api.failures += LifeOsException.Server(503, null, "unavailable")
        assertTrue(sender.send("c-voice") is SendOutcome.Retry)
        assertTrue(sender.send("c-voice") is SendOutcome.Retry)
        // The phone moved to another time zone meanwhile: the stored metadata still wins.
        val before = java.util.TimeZone.getDefault()
        java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("America/New_York"))
        try {
            assertEquals(SendOutcome.Sent, sender.send("c-voice"))
        } finally {
            java.util.TimeZone.setDefault(before)
        }
        assertEquals(3, api.audioAttempts.size)
        assertEquals(1, api.audioAttempts.map { it.second }.toSet().size)
        assertEquals(setOf("c-voice"), api.audioAttempts.map { it.third }.toSet())
        assertEquals(store.get("c-voice")!!.sourceMetadataJson, api.audioAttempts.first().second)
    }

    @Test
    fun `a stored capture keeps its metadata JSON exactly`() = runBlocking {
        setUp()
        val made = Capture.create("c-1", CaptureKind.TEXT, "2026-10-12T10:00:00Z", "Europe/Berlin", text = "x")
        store.add(made)
        assertEquals(made.sourceMetadataJson, store.get("c-1")!!.sourceMetadataJson)
        assertEquals("""{"via":"android-app","timeZone":"Europe/Berlin","captureId":"c-1"}""", made.metadataJson())
        // A capture queued by 0.2.0 (no stored JSON) encodes the same fields.
        assertEquals(made.metadataJson(), made.copy(sourceMetadataJson = null).metadataJson())
    }

    @Test
    fun `409 idempotency_conflict fails for good with a clear message`() = runBlocking {
        setUp()
        store.add(capture(CaptureKind.VOICE, files = listOf(stores.newFile("voice.ogg"))))
        api.failures += LifeOsException.Api(409, "idempotency_conflict", "The Idempotency-Key was already used with a different upload")
        assertTrue(sender.send("c-voice") is SendOutcome.Failed)
        val failed = store.get("c-voice")!!
        assertEquals(CaptureStatus.FAILED, failed.status)
        assertEquals(CaptureSender.CONFLICT_MESSAGE, failed.error)
    }

    @Test
    fun `retry after the Voice job failed asks for a new job under a new key with its own metadata`() = runBlocking {
        setUp()
        val checker = CaptureStatusChecker(store, api)
        store.add(capture(CaptureKind.VOICE, files = listOf(stores.newFile("voice.ogg"))))
        sender.send("c-voice")
        api.job = AudioJob(id = "job-1", status = "failed", errorSummary = "No speech was heard in the recording. Record it again.")
        checker.refresh("c-voice")
        val failed = store.get("c-voice")!!
        assertTrue(failed.voiceJobFailed)
        assertTrue(failed.retryable)
        assertEquals("Transcription failed: No speech was heard in the recording. Record it again.", CaptureStatusText.describe(failed))
        // Not swept away with the sent ones: it waits for Retry or Delete.
        assertEquals(0, store.pruneSent(now.plus(Duration.ofDays(30)), Duration.ZERO))

        val retried = sender.reset("c-voice")!!
        assertEquals(CaptureStatus.PENDING, retried.status)
        assertEquals("c-voice-r1", retried.voiceIdempotencyKey)
        assertNull(retried.voiceJobId)
        assertNull(retried.voiceStatus)
        assertEquals("""{"via":"android-app","timeZone":"Europe/Berlin","captureId":"c-voice","attempt":1}""", retried.sourceMetadataJson)

        // The new attempt's own tries repeat its key and bytes, also in another time zone.
        api.job = AudioJob(id = "job-2", status = "queued")
        api.failures += LifeOsException.Server(503, null, "unavailable")
        assertTrue(sender.send("c-voice") is SendOutcome.Retry)
        val before = java.util.TimeZone.getDefault()
        java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("America/New_York"))
        try {
            assertEquals(SendOutcome.Sent, sender.send("c-voice"))
        } finally {
            java.util.TimeZone.setDefault(before)
        }
        assertEquals(listOf("c-voice", "c-voice-r1", "c-voice-r1"), api.audioAttempts.map { it.third })
        assertEquals(retried.sourceMetadataJson, api.audioAttempts[1].second)
        assertEquals(api.audioAttempts[1].second, api.audioAttempts[2].second)
        assertEquals("job-2", store.get("c-voice")!!.voiceJobId)

        // That job fails as well: the next Retry is attempt 2.
        api.job = AudioJob(id = "job-2", status = "expired")
        checker.refresh("c-voice")
        assertEquals("c-voice-r2", sender.reset("c-voice")!!.voiceIdempotencyKey)
    }

    @Test
    fun `retry of a voice note Voice never accepted keeps its key and metadata`() = runBlocking {
        setUp()
        store.add(capture(CaptureKind.VOICE, files = listOf(stores.newFile("voice.ogg"))))
        repeat(5) { api.failures += LifeOsException.Server(502, null, "bad gateway") }
        repeat(5) { sender.send("c-voice") }
        val failed = store.get("c-voice")!!
        assertEquals(CaptureStatus.FAILED, failed.status)
        assertTrue(failed.retryable)

        val retried = sender.reset("c-voice")!!
        assertEquals(0, retried.jobAttempt)
        assertEquals(failed.sourceMetadataJson, retried.sourceMetadataJson)
        assertEquals(SendOutcome.Sent, sender.send("c-voice"))
        assertEquals(setOf("c-voice"), api.audioAttempts.map { it.third }.toSet())
        assertEquals(1, api.audioAttempts.map { it.second }.toSet().size)
    }

    @Test
    fun `retry after 409 sends the recording under a new key`() = runBlocking {
        setUp()
        store.add(capture(CaptureKind.VOICE, files = listOf(stores.newFile("voice.ogg"))))
        api.failures += LifeOsException.Api(409, "idempotency_conflict", "The Idempotency-Key was already used with a different upload")
        sender.send("c-voice")
        assertTrue(store.get("c-voice")!!.keyConflict)
        assertEquals("c-voice-r1", sender.reset("c-voice")!!.voiceIdempotencyKey)
        assertEquals(SendOutcome.Sent, sender.send("c-voice"))
        assertEquals(listOf("c-voice", "c-voice-r1"), api.audioAttempts.map { it.third })
    }

    @Test
    fun `retry leaves a sent capture alone`() = runBlocking {
        setUp()
        store.add(capture(CaptureKind.VOICE, files = listOf(stores.newFile("voice.ogg"))))
        sender.send("c-voice")
        val sent = store.get("c-voice")!!
        assertFalse(sent.retryable)
        assertEquals(sent, sender.reset("c-voice"))
    }

    @Test
    fun `a deleted capture is gone`() = runBlocking {
        setUp()
        assertEquals(SendOutcome.Gone, sender.send("nope"))
    }

    @Test
    fun `the status checker follows the voice job to the processed Inbox item`() = runBlocking {
        setUp()
        val checker = CaptureStatusChecker(store, api)
        store.add(capture(CaptureKind.VOICE, files = listOf(stores.newFile("voice.ogg"))))
        sender.send("c-voice")

        api.job = AudioJob(id = "job-1", status = "succeeded", inboxItemId = "item-1")
        api.item = InboxItem(id = "item-1", state = "resolved")
        api.attempts = listOf(
            ProcessingAttempt(id = "a1", status = "retryable", createdAt = "2026-10-12T10:00:01Z"),
            ProcessingAttempt(
                id = "a2",
                status = "processed",
                createdAt = "2026-10-12T10:00:05Z",
                actions = listOf(JsonObject(mapOf("tool" to JsonPrimitive("task_create")))),
            ),
        )
        checker.refresh("c-voice")
        val done = store.get("c-voice")!!
        assertEquals("item-1", done.inboxItemId)
        assertEquals("Processed: created a Task", CaptureStatusText.describe(done))
        assertTrue(done.settled)
    }

    @Test
    fun `old sent captures are pruned with their files, pending ones stay`() = runBlocking {
        setUp()
        val photo = stores.newFile("p.jpg")
        store.add(capture(CaptureKind.PHOTO, files = listOf(photo)))
        store.add(capture(CaptureKind.TEXT, "pending"))
        sender.send("c-photo")
        assertEquals(1, store.pruneSent(now.plus(Duration.ofDays(8)), Duration.ofDays(7)))
        assertFalse(photo.exists())
        assertEquals(listOf("c-text"), store.all().map { it.id })
    }
}

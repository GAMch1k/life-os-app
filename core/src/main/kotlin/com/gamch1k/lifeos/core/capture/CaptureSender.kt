package com.gamch1k.lifeos.core.capture

import com.gamch1k.lifeos.core.api.AudioJob
import com.gamch1k.lifeos.core.api.CreateInboxItem
import com.gamch1k.lifeos.core.api.InboxItem
import com.gamch1k.lifeos.core.api.LifeOsClient
import com.gamch1k.lifeos.core.api.LifeOsException
import com.gamch1k.lifeos.core.api.ProcessingAttempt
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonObject
import java.io.File
import java.io.IOException
import java.time.Instant

/** The calls the queue makes; [LifeOsClient] in the app, a fake in tests. */
interface CaptureApi {
    suspend fun uploadImage(file: File): String
    suspend fun createInboxItem(request: CreateInboxItem): InboxItem
    suspend fun createAudioJob(file: File, sourceMetadata: JsonObject): AudioJob
    suspend fun audioJob(id: String): AudioJob
    suspend fun inboxItem(id: String): InboxItem
    suspend fun processingAttempts(itemId: String): List<ProcessingAttempt>
}

fun LifeOsClient.asCaptureApi(): CaptureApi = object : CaptureApi {
    override suspend fun uploadImage(file: File) = this@asCaptureApi.uploadImage(file, "image/jpeg").reference
    override suspend fun createInboxItem(request: CreateInboxItem) = this@asCaptureApi.createInboxItem(request)
    override suspend fun createAudioJob(file: File, sourceMetadata: JsonObject) =
        this@asCaptureApi.createAudioJob(file, "audio/ogg", Capture.SOURCE, sourceMetadata)
    override suspend fun audioJob(id: String) = this@asCaptureApi.audioJob(id)
    override suspend fun inboxItem(id: String) = this@asCaptureApi.inboxItem(id)
    override suspend fun processingAttempts(itemId: String) = this@asCaptureApi.processingAttempts(itemId)
}

sealed interface SendOutcome {
    data object Sent : SendOutcome

    /** Try again later (WorkManager backs off): offline, timeout, 5xx, 429. */
    data class Retry(val error: String) : SendOutcome

    /** Retrying alone will not help: a refusal, a bad token, a lost file. */
    data class Failed(val error: String) : SendOutcome

    /** The capture was deleted meanwhile. */
    data object Gone : SendOutcome
}

/**
 * Sends one capture, idempotently: each step's result is stored before the
 * next, so a send that dies halfway resumes where it stopped.
 *
 * - Text: one Inbox capture with the dedupe key `android-app:<id>`; Inbox
 *   returns the existing item for a repeat.
 * - Photos: each JPEG uploaded once (references are content-addressed and
 *   kept as they arrive), then the capture with the references and the key.
 * - Voice: one Voice job, stored as soon as it exists so it is never uploaded
 *   twice; Voice creates the Inbox item after transcription. Voice has no
 *   idempotency key, so only an upload whose answer was lost can repeat.
 */
class CaptureSender(
    private val store: CaptureStore,
    private val api: CaptureApi,
    private val clock: () -> Instant = Instant::now,
    /** After this many tries a retryable failure becomes FAILED. */
    private val maxAttempts: Int = 25,
) {
    suspend fun send(id: String): SendOutcome {
        val start = store.update(id) { capture ->
            if (capture.status == CaptureStatus.SENT) capture
            else capture.copy(status = CaptureStatus.SENDING, attempts = capture.attempts + 1)
        } ?: return SendOutcome.Gone
        if (start.status == CaptureStatus.SENT) return SendOutcome.Sent

        return try {
            when (start.kind) {
                CaptureKind.TEXT -> sendCapture(start, emptyList())
                CaptureKind.PHOTO -> sendCapture(start, uploadPhotos(start))
                CaptureKind.VOICE -> sendVoice(start)
            }
            SendOutcome.Sent
        } catch (cancelled: CancellationException) {
            store.update(id) { it.copy(status = CaptureStatus.PENDING) }
            throw cancelled
        } catch (error: LifeOsException) {
            val giveUp = !error.retryable || start.attempts >= maxAttempts
            store.update(id) {
                it.copy(
                    status = if (giveUp) CaptureStatus.FAILED else CaptureStatus.PENDING,
                    error = error.message,
                    authProblem = error.authProblem,
                )
            }
            if (giveUp) SendOutcome.Failed(error.message.orEmpty()) else SendOutcome.Retry(error.message.orEmpty())
        } catch (error: IOException) {
            // The recording or a photo cannot be read any more.
            val message = "The capture's file cannot be read: ${error.message}"
            store.update(id) { it.copy(status = CaptureStatus.FAILED, error = message, authProblem = false) }
            SendOutcome.Failed(message)
        }
    }

    private suspend fun uploadPhotos(capture: Capture): List<String> {
        var references = capture.mediaReferences
        for (index in references.size until capture.files.size) {
            val file = File(capture.files[index])
            if (!file.isFile) throw IOException("${file.name} is missing")
            val reference = api.uploadImage(file)
            references = references + reference
            store.update(capture.id) { it.copy(mediaReferences = references) }
        }
        return references
    }

    private suspend fun sendCapture(capture: Capture, references: List<String>) {
        val content = capture.text.trim().ifEmpty { if (capture.kind == CaptureKind.PHOTO) PHOTO_PLACEHOLDER else "" }
        val item = api.createInboxItem(
            CreateInboxItem(
                content = content,
                source = Capture.SOURCE,
                sourceMetadata = capture.sourceMetadata(),
                mediaReferences = references.takeIf { it.isNotEmpty() },
                dedupeKey = capture.dedupeKey,
            ),
        )
        store.update(capture.id) {
            it.copy(status = CaptureStatus.SENT, error = null, authProblem = false, inboxItemId = item.id, inboxState = item.state, sentAt = clock().toString())
        }
    }

    private suspend fun sendVoice(capture: Capture) {
        if (capture.voiceJobId == null) {
            val file = capture.files.firstOrNull()?.let(::File)
            if (file == null || !file.isFile) throw IOException("the recording is missing")
            val job = api.createAudioJob(file, capture.sourceMetadata())
            store.update(capture.id) {
                it.copy(status = CaptureStatus.SENT, error = null, authProblem = false, voiceJobId = job.id, voiceStatus = job.status, sentAt = clock().toString())
            }
        } else {
            store.update(capture.id) { it.copy(status = CaptureStatus.SENT, error = null, authProblem = false) }
        }
    }

    /** After a new token: the captures that failed on the old one go back to the queue. Returns their ids. */
    suspend fun resetAuthFailures(): List<String> {
        val ids = store.all().filter { it.status == CaptureStatus.FAILED && it.authProblem }.map { it.id }
        ids.forEach { id -> store.update(id) { it.copy(status = CaptureStatus.PENDING, attempts = 0, error = null, authProblem = false) } }
        return ids
    }

    /** The queue screen's Retry. */
    suspend fun reset(id: String): Capture? =
        store.update(id) { if (it.status == CaptureStatus.SENT) it else it.copy(status = CaptureStatus.PENDING, attempts = 0, error = null, authProblem = false) }

    companion object {
        /** Inbox needs some content; a photo-only capture says what it is, as the Dashboard's share does. */
        const val PHOTO_PLACEHOLDER = "Photo"
    }
}

/**
 * Follows a sent capture on the server: the Voice job until it has an Inbox
 * item, then the Inbox item's state and, once resolved, what processing did.
 */
class CaptureStatusChecker(private val store: CaptureStore, private val api: CaptureApi) {
    /** Refreshes one capture; errors are swallowed (the screen just tries again later). */
    suspend fun refresh(id: String) {
        val capture = store.get(id) ?: return
        if (capture.status != CaptureStatus.SENT || capture.settled) return
        try {
            var itemId = capture.inboxItemId
            if (itemId == null) {
                val jobId = capture.voiceJobId ?: return
                val job = api.audioJob(jobId)
                itemId = job.inboxItemId
                store.update(id) { it.copy(voiceStatus = job.status, voiceError = job.errorSummary, inboxItemId = job.inboxItemId) }
                if (itemId == null) return
            }
            val item = api.inboxItem(itemId)
            val outcome = if (item.state == "resolved") ProcessingOutcome.describe(api.processingAttempts(itemId)) else null
            store.update(id) { it.copy(inboxState = item.state, outcome = outcome ?: it.outcome) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: LifeOsException) {
        }
    }
}

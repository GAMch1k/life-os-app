package com.gamch1k.lifeos.wear.capture

import com.gamch1k.lifeos.core.wearlink.HttpOutcome
import com.gamch1k.lifeos.wear.net.ApiResult
import com.gamch1k.lifeos.wear.net.AudioJobDto
import com.gamch1k.lifeos.wear.net.AudioUpload
import com.gamch1k.lifeos.wear.net.LifeOsApi
import com.gamch1k.lifeos.wear.net.Route
import kotlinx.coroutines.delay
import java.time.Instant

/**
 * Runs the capture queue: uploads every due recording, then follows the
 * fresh Voice jobs for a little while so the watch can say "In Inbox".
 * [CaptureWorker] calls [run]; it is safe to call again at any time.
 */
class CaptureUploader(
    private val store: CaptureStore,
    private val api: LifeOsApi,
    /** Called when Life OS refused the token: the watch asks the phone for a new one. */
    private val onAuthRefused: suspend () -> Unit = {},
    private val clock: () -> Long = System::currentTimeMillis,
    private val sleep: suspend (Long) -> Unit = { delay(it) },
) {
    /** One pass; returns when the queue should look again (epoch millis), or null when nothing waits. */
    suspend fun run(followFreshJobs: Boolean = true): Long? {
        uploadDue()
        if (followFreshJobs) followFreshJobs() else pollSentOnce()
        return CaptureMachine.nextWakeUp(store.captures.value, clock())
    }

    suspend fun uploadDue() {
        // Oldest first, one at a time: the link and the watch are small.
        store.captures.value.sortedBy { it.createdAt }.forEach { candidate -> upload(candidate.id) }
    }

    /** Polls the jobs sent in the last few minutes until they are done, about 2.5 minutes at most. */
    suspend fun followFreshJobs() {
        for (pause in POLL_PAUSES_MILLIS) {
            val fresh = store.captures.value.filter {
                CaptureMachine.shouldPoll(it, clock()) && clock() - (it.sentAt ?: 0) < FOLLOW_MILLIS
            }
            if (fresh.isEmpty()) break
            sleep(pause)
            fresh.forEach { poll(it.id) }
        }
        pollSentOnce()
    }

    /** One look at every job still being transcribed (older ones too). */
    suspend fun pollSentOnce() {
        store.captures.value.filter { CaptureMachine.shouldPoll(it, clock()) }.forEach { poll(it.id) }
    }

    private suspend fun upload(id: String) {
        // Claim it under the store's lock, so two runners never upload the same capture.
        var claimed: Capture? = null
        store.update(id) { current -> CaptureMachine.startUpload(current, clock())?.also { claimed = it } ?: current }
        val started = claimed ?: return
        val file = store.audioFile(started)
        val bytes = try {
            file.readBytes()
        } catch (_: Exception) {
            store.update(id) { CaptureMachine.onUploadOutcome(it, UploadOutcome.Rejected("The recording file is missing."), clock()) }
            return
        }
        val result = api.uploadAudio(
            AudioUpload(
                captureId = started.id,
                bytes = bytes,
                fileName = file.name,
                contentType = started.contentType,
                timeZone = started.timeZone,
                recordedAt = Instant.ofEpochMilli(started.createdAt),
                durationMillis = started.durationMillis,
            ),
        )
        val outcome = uploadOutcome(result)
        if (result is ApiResult.Response && result.outcome == HttpOutcome.AUTH) onAuthRefused()
        store.update(id) { CaptureMachine.onUploadOutcome(it, outcome, clock()) }
    }

    private suspend fun poll(id: String) {
        val capture = store.get(id) ?: return
        val jobId = capture.jobId ?: return
        val outcome = jobOutcome(api.audioJob(jobId)) ?: return
        store.update(id) { CaptureMachine.onJobOutcome(it, outcome) }
    }

    companion object {
        /** Pauses between polls of a fresh job: about 2.5 minutes in all (Whisper takes seconds to a minute). */
        val POLL_PAUSES_MILLIS = listOf(3_000L, 4_000L, 5_000L, 8_000L, 10_000L, 15_000L, 20_000L, 30_000L, 30_000L, 30_000L)
        const val FOLLOW_MILLIS = 5 * 60_000L

        fun uploadOutcome(result: ApiResult): UploadOutcome = when (result) {
            is ApiResult.Unavailable -> UploadOutcome.Unreachable(LifeOsApi.describe(result))
            is ApiResult.Response -> when (result.outcome) {
                HttpOutcome.SUCCESS -> {
                    val job = parseJob(result)
                    if (job != null) {
                        UploadOutcome.Accepted(job.id, if (result.route == Route.PHONE) "phone" else "direct")
                    } else {
                        // Accepted but unreadable: retrying could create a second job, so stop here.
                        UploadOutcome.Rejected("Voice accepted the recording but its answer was unreadable.")
                    }
                }
                HttpOutcome.AUTH, HttpOutcome.RETRYABLE -> UploadOutcome.Retryable(LifeOsApi.describe(result))
                HttpOutcome.PERMANENT -> UploadOutcome.Rejected(
                    if (result.status == 413) "The recording is too long for Voice." else LifeOsApi.describe(result),
                )
            }
        }

        /** Null when the job could not be read this time (try again later). */
        fun jobOutcome(result: ApiResult): JobOutcome? {
            if (result !is ApiResult.Response) return null
            if (result.status == 404) return JobOutcome.Failed("Voice no longer knows this recording.")
            if (result.outcome != HttpOutcome.SUCCESS) return null
            val job = parseJob(result) ?: return null
            return when (job.status) {
                "succeeded" -> JobOutcome.Succeeded(job.inboxItemId)
                "failed" -> JobOutcome.Failed(job.errorSummary ?: "Transcription failed.")
                "expired" -> JobOutcome.Failed("The recording expired before it was transcribed.")
                else -> JobOutcome.Pending // queued, processing, retryable
            }
        }

        private fun parseJob(response: ApiResult.Response): AudioJobDto? = try {
            LifeOsApi.json.decodeFromString(AudioJobDto.serializer(), response.text).takeIf { it.id.isNotBlank() }
        } catch (_: Exception) {
            null
        }
    }
}

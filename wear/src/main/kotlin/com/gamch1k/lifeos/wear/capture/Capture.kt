package com.gamch1k.lifeos.wear.capture

import com.gamch1k.lifeos.wear.net.SourceMetadata
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.time.Instant

/**
 * Where a recording is, after it was recorded:
 *
 * ```
 * QUEUED ──upload──▶ SENDING ──202──▶ SENT ──job succeeded──▶ IN_INBOX
 *   ▲                   │                │
 *   │ retry, backoff    ├─no phone/net─▶ WAITING ──later──▶ SENDING
 *   └───────────────────┤                └─job failed──▶ FAILED
 *                       └─refused / too many tries──▶ FAILED ──Retry──▶ QUEUED
 * ```
 */
@Serializable
enum class CaptureState {
    /** Waiting for its (next) upload attempt. */
    @SerialName("queued")
    QUEUED,

    /** An upload is running. */
    @SerialName("sending")
    SENDING,

    /** Neither the phone nor a direct connection reached Life OS; tried again later. */
    @SerialName("waiting")
    WAITING,

    /** Voice accepted it (a job exists); it is being transcribed. Never uploaded again. */
    @SerialName("sent")
    SENT,

    /** Transcribed into an Inbox item. */
    @SerialName("in_inbox")
    IN_INBOX,

    /** Refused, out of retries, or the transcription failed. The owner may retry. */
    @SerialName("failed")
    FAILED,
}

/** One recording and its upload state; persisted by [CaptureStore]. */
@Serializable
data class Capture(
    /** A UUID made when recording starts: the file name, `sourceMetadata.captureId`, and the first job attempt's Idempotency-Key. */
    val id: String,
    val createdAt: Long,
    val durationMillis: Long,
    val fileName: String,
    val contentType: String,
    val timeZone: String,
    val state: CaptureState = CaptureState.QUEUED,
    /** Upload attempts since it was queued (or last retried by the owner). */
    val attempts: Int = 0,
    /** Attempts that found no way to Life OS; they space out the next try but use up no retries. */
    val waits: Int = 0,
    val nextAttemptAt: Long = 0,
    val uploadStartedAt: Long? = null,
    /** The Voice job, once accepted. */
    val jobId: String? = null,
    val sentAt: Long? = null,
    val inboxItemId: String? = null,
    /** How it got there: "phone" or "direct". */
    val route: String? = null,
    /** The latest problem, shown to the owner. */
    val message: String? = null,
    /**
     * Which Voice job this capture asks for: 0 at first, plus 1 for every
     * Retry after Voice failed the job or refused the key ([idempotencyKey]).
     */
    val jobAttempt: Int = 0,
    /**
     * The `sourceMetadata` JSON of this job attempt, written once when the
     * attempt starts and sent byte for byte on each of its uploads: Voice
     * answers 409 to a repeat of a key whose metadata differs. Null for
     * captures queued by 0.3.0, whose first attempt builds the same JSON
     * from the fields above.
     */
    val sourceMetadataJson: String? = null,
    /** Voice answered 409 `idempotency_conflict`: the key is taken, so Retry needs a new one. */
    val keyConflict: Boolean = false,
) {
    /**
     * `Idempotency-Key` of the current job attempt: the capture id, then
     * `<id>-r1`, `<id>-r2`… Retries within an attempt repeat it, so a lost
     * reply is answered with the job it created; a new attempt gets a new job.
     */
    val idempotencyKey: String get() = if (jobAttempt == 0) id else "$id-r$jobAttempt"

    /** The metadata to upload: the stored JSON, or (0.3.0 captures) the same fields encoded as 0.3.0 did. */
    fun metadataJson(): String = sourceMetadataJson ?: buildSourceMetadataJson(jobAttempt)

    /**
     * Only persisted fields, never the time of the upload: every upload of an
     * attempt sends the same bytes. The first attempt's JSON has no `attempt`
     * field, exactly as 0.3.0 sent it; later ones carry `"attempt": n`.
     */
    fun buildSourceMetadataJson(attempt: Int): String = SourceMetadata(
        via = SourceMetadata.VIA,
        timeZone = timeZone,
        captureId = id,
        recordedAt = Instant.ofEpochMilli(createdAt).toString(),
        durationMs = durationMillis,
        attempt = attempt.takeIf { it > 0 },
    ).toJson()

    companion object {
        /** A new recording, its first attempt's metadata fixed now. */
        fun create(id: String, createdAt: Long, durationMillis: Long, fileName: String, contentType: String, timeZone: String): Capture {
            val capture = Capture(id, createdAt, durationMillis, fileName, contentType, timeZone)
            return capture.copy(sourceMetadataJson = capture.buildSourceMetadataJson(0))
        }
    }
}

package com.gamch1k.lifeos.wear.capture

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

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
    /** A UUID made when recording starts: the file name, the Idempotency-Key and `sourceMetadata.captureId`. */
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
)

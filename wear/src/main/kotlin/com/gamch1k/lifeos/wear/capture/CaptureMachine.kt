package com.gamch1k.lifeos.wear.capture

/** The result of one upload attempt, as the queue sees it. */
sealed interface UploadOutcome {
    /** Voice answered 202 with a job. */
    data class Accepted(val jobId: String, val route: String) : UploadOutcome

    /** No path reached Life OS (no phone, no network, no token yet). */
    data class Unreachable(val message: String) : UploadOutcome

    /** Life OS answered, but a retry may work (5xx, 429, a refused token). */
    data class Retryable(val message: String) : UploadOutcome

    /**
     * Life OS refused this recording (400 unsupported audio, 413 too large),
     * or ([keyTaken]) Voice holds another upload under its key (409).
     */
    data class Rejected(val message: String, val keyTaken: Boolean = false) : UploadOutcome
}

/** What polling the Voice job found. */
sealed interface JobOutcome {
    data object Pending : JobOutcome

    data class Succeeded(val inboxItemId: String?) : JobOutcome

    data class Failed(val message: String) : JobOutcome
}

/**
 * The upload queue's state machine, without Android: every transition is a
 * pure function from a [Capture] to the next one. [CaptureUploader] runs it.
 *
 * Idempotency (docs/wear.md, "Idempotency"): a capture with a job is never
 * uploaded again, a stale outcome (for a capture that moved on meanwhile)
 * changes nothing, and every upload of one job attempt sends the same
 * `Idempotency-Key` and metadata, so Voice (and the phone's RelayReplayCache)
 * answer a retry whose first reply got lost with the job it created.
 */
object CaptureMachine {
    /** Retryable failures before a capture is FAILED; "unreachable" never counts against it. */
    const val MAX_ATTEMPTS = 6

    /** An upload "running" longer than this was cut off (the process died): it may start again. */
    const val STALE_SENDING_MILLIS = 5 * 60_000L

    /** Sent jobs are polled at most this long; after it the capture stays SENT. */
    const val POLL_WINDOW_MILLIS = 24 * 60 * 60_000L

    /** After a retryable failure: 30 s, 1, 2, 4, 8, 16 min, then every 30 min. */
    fun backoffMillis(attempts: Int): Long =
        (30_000L shl (attempts - 1).coerceIn(0, 6)).coerceAtMost(30 * 60_000L)

    /** While waiting for the phone or a network: 30 s, 1, 2, 4, 8, then every 10 min (a reconnect also wakes the queue). */
    fun waitMillis(waits: Int): Long =
        (30_000L shl (waits - 1).coerceIn(0, 6)).coerceAtMost(10 * 60_000L)

    fun isDue(capture: Capture, now: Long): Boolean = capture.jobId == null && when (capture.state) {
        CaptureState.QUEUED, CaptureState.WAITING -> now >= capture.nextAttemptAt
        CaptureState.SENDING -> now - (capture.uploadStartedAt ?: 0) > STALE_SENDING_MILLIS
        else -> false
    }

    /** QUEUED/WAITING (or a stale SENDING) → SENDING; null when it must not be uploaded. */
    fun startUpload(capture: Capture, now: Long): Capture? {
        if (!isDue(capture, now)) return null
        return capture.copy(state = CaptureState.SENDING, attempts = capture.attempts + 1, uploadStartedAt = now)
    }

    fun onUploadOutcome(capture: Capture, outcome: UploadOutcome, now: Long): Capture {
        if (capture.state != CaptureState.SENDING || capture.jobId != null) return capture
        return when (outcome) {
            is UploadOutcome.Accepted -> capture.copy(
                state = CaptureState.SENT,
                jobId = outcome.jobId,
                route = outcome.route,
                sentAt = now,
                uploadStartedAt = null,
                message = null,
            )
            is UploadOutcome.Unreachable -> capture.copy(
                state = CaptureState.WAITING,
                // Waiting for the phone doesn't use up retries.
                attempts = capture.attempts - 1,
                waits = capture.waits + 1,
                nextAttemptAt = now + waitMillis(capture.waits + 1),
                uploadStartedAt = null,
                message = outcome.message,
            )
            is UploadOutcome.Retryable -> if (capture.attempts >= MAX_ATTEMPTS) {
                capture.copy(state = CaptureState.FAILED, uploadStartedAt = null, message = outcome.message)
            } else {
                capture.copy(
                    state = CaptureState.QUEUED,
                    nextAttemptAt = now + backoffMillis(capture.attempts),
                    uploadStartedAt = null,
                    message = outcome.message,
                )
            }
            is UploadOutcome.Rejected -> capture.copy(
                state = CaptureState.FAILED,
                uploadStartedAt = null,
                message = outcome.message,
                keyConflict = outcome.keyTaken,
            )
        }
    }

    fun shouldPoll(capture: Capture, now: Long): Boolean =
        capture.state == CaptureState.SENT && capture.jobId != null && now - (capture.sentAt ?: now) < POLL_WINDOW_MILLIS

    fun onJobOutcome(capture: Capture, outcome: JobOutcome): Capture {
        if (capture.state != CaptureState.SENT) return capture
        return when (outcome) {
            JobOutcome.Pending -> capture
            is JobOutcome.Succeeded -> capture.copy(state = CaptureState.IN_INBOX, inboxItemId = outcome.inboxItemId, message = null)
            is JobOutcome.Failed -> capture.copy(state = CaptureState.FAILED, message = outcome.message)
        }
    }

    /**
     * The owner's Retry: FAILED (or WAITING) → QUEUED, with fresh retries.
     *
     * - Voice made a job and it failed (no speech, transcription failed,
     *   expired, gone), or Voice holds another upload under the key (409):
     *   a new job attempt, with the next key (`<id>-r<n>`) and its own
     *   metadata, written now. The old key would only bring back the failed
     *   job, or the 409.
     * - Voice never accepted it (no way to Life OS, 5xx, a refused token, a
     *   refusal): the same key and metadata, so if an earlier upload did
     *   reach Voice and only its reply was lost, the retry gets that job
     *   instead of a second one.
     */
    fun retry(capture: Capture): Capture? {
        if (capture.state != CaptureState.FAILED && capture.state != CaptureState.WAITING) return null
        val again = capture.copy(
            state = CaptureState.QUEUED,
            attempts = 0,
            waits = 0,
            nextAttemptAt = 0,
            uploadStartedAt = null,
            message = null,
        )
        if (capture.state != CaptureState.FAILED || (capture.jobId == null && !capture.keyConflict)) return again
        val attempt = capture.jobAttempt + 1
        return again.copy(
            jobAttempt = attempt,
            sourceMetadataJson = capture.buildSourceMetadataJson(attempt),
            keyConflict = false,
            jobId = null,
            sentAt = null,
            route = null,
            inboxItemId = null,
        )
    }

    /** The audio can go once Life OS holds the transcript. */
    fun keepsAudio(capture: Capture): Boolean = capture.state != CaptureState.IN_INBOX

    /** When the queue should look again (the earliest due attempt or stale upload), or null when nothing waits. */
    fun nextWakeUp(captures: List<Capture>, now: Long): Long? = captures.mapNotNull { capture ->
        when {
            capture.jobId != null -> null
            capture.state == CaptureState.QUEUED || capture.state == CaptureState.WAITING -> maxOf(capture.nextAttemptAt, now)
            capture.state == CaptureState.SENDING -> (capture.uploadStartedAt ?: now) + STALE_SENDING_MILLIS
            else -> null
        }
    }.minOrNull()
}

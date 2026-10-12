package com.gamch1k.lifeos.wear.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class CaptureMachineTest {
    private val queued = Capture(
        id = "c1",
        createdAt = 1_000,
        durationMillis = 4_000,
        fileName = "c1.ogg",
        contentType = "audio/ogg",
        timeZone = "Europe/Berlin",
    )

    private fun sending(now: Long = 10_000) = CaptureMachine.startUpload(queued, now)!!

    @Test
    fun `a queued capture starts uploading and counts the attempt`() {
        val started = sending()
        assertEquals(CaptureState.SENDING, started.state)
        assertEquals(1, started.attempts)
        assertEquals(10_000L, started.uploadStartedAt)
    }

    @Test
    fun `accepted means sent with its job, and a sent capture is never uploaded again`() {
        val sent = CaptureMachine.onUploadOutcome(sending(), UploadOutcome.Accepted("job-1", "phone"), 11_000)
        assertEquals(CaptureState.SENT, sent.state)
        assertEquals("job-1", sent.jobId)
        assertEquals("phone", sent.route)
        assertEquals(11_000L, sent.sentAt)
        assertFalse(CaptureMachine.isDue(sent, Long.MAX_VALUE))
        assertNull(CaptureMachine.startUpload(sent, Long.MAX_VALUE))
        // A capture with a job is never due, whatever its state says.
        assertFalse(CaptureMachine.isDue(sent.copy(state = CaptureState.QUEUED), Long.MAX_VALUE))
    }

    @Test
    fun `a duplicate or late outcome changes nothing`() {
        val sent = CaptureMachine.onUploadOutcome(sending(), UploadOutcome.Accepted("job-1", "phone"), 11_000)
        assertSame(sent, CaptureMachine.onUploadOutcome(sent, UploadOutcome.Accepted("job-2", "direct"), 12_000))
        assertSame(sent, CaptureMachine.onUploadOutcome(sent, UploadOutcome.Rejected("late"), 12_000))
        assertSame(queued, CaptureMachine.onUploadOutcome(queued, UploadOutcome.Accepted("job-3", "phone"), 12_000))
    }

    @Test
    fun `a running upload is not started twice, but a stale one is`() {
        val running = sending(now = 10_000)
        assertNull(CaptureMachine.startUpload(running, 10_500))
        val restarted = CaptureMachine.startUpload(running, 10_000 + CaptureMachine.STALE_SENDING_MILLIS + 1)!!
        assertEquals(2, restarted.attempts)
        assertEquals("c1", restarted.id) // same capture id, so the same Idempotency-Key
    }

    @Test
    fun `no way to Life OS means waiting, spaced out, without using up retries`() {
        var capture = queued
        var now = 0L
        val delays = mutableListOf<Long>()
        repeat(8) {
            capture = CaptureMachine.onUploadOutcome(CaptureMachine.startUpload(capture, now)!!, UploadOutcome.Unreachable("no phone"), now)
            assertEquals(CaptureState.WAITING, capture.state)
            assertEquals(0, capture.attempts)
            delays += capture.nextAttemptAt - now
            assertFalse(CaptureMachine.isDue(capture, now))
            now = capture.nextAttemptAt
            assertTrue(CaptureMachine.isDue(capture, now))
        }
        assertEquals(listOf(30_000L, 60_000L, 120_000L, 240_000L, 480_000L, 600_000L, 600_000L, 600_000L), delays)
        assertEquals("no phone", capture.message)
    }

    @Test
    fun `retryable failures back off and end in failed after the last attempt`() {
        var capture = queued
        var now = 0L
        repeat(CaptureMachine.MAX_ATTEMPTS - 1) { attempt ->
            capture = CaptureMachine.onUploadOutcome(CaptureMachine.startUpload(capture, now)!!, UploadOutcome.Retryable("503"), now)
            assertEquals(CaptureState.QUEUED, capture.state)
            assertEquals(CaptureMachine.backoffMillis(attempt + 1), capture.nextAttemptAt - now)
            now = capture.nextAttemptAt
        }
        capture = CaptureMachine.onUploadOutcome(CaptureMachine.startUpload(capture, now)!!, UploadOutcome.Retryable("503"), now)
        assertEquals(CaptureState.FAILED, capture.state)
        assertEquals(CaptureMachine.MAX_ATTEMPTS, capture.attempts)
        assertEquals("503", capture.message)
    }

    @Test
    fun `a refused recording fails at once`() {
        val failed = CaptureMachine.onUploadOutcome(sending(), UploadOutcome.Rejected("too long"), 11_000)
        assertEquals(CaptureState.FAILED, failed.state)
        assertEquals("too long", failed.message)
    }

    @Test
    fun `the job decides between in inbox and failed`() {
        val sent = CaptureMachine.onUploadOutcome(sending(), UploadOutcome.Accepted("job-1", "direct"), 11_000)
        assertSame(sent, CaptureMachine.onJobOutcome(sent, JobOutcome.Pending))
        val inbox = CaptureMachine.onJobOutcome(sent, JobOutcome.Succeeded("inbox-1"))
        assertEquals(CaptureState.IN_INBOX, inbox.state)
        assertEquals("inbox-1", inbox.inboxItemId)
        assertFalse(CaptureMachine.keepsAudio(inbox))
        val noSpeech = CaptureMachine.onJobOutcome(sent, JobOutcome.Failed("No speech was heard in the recording. Record it again."))
        assertEquals(CaptureState.FAILED, noSpeech.state)
        assertTrue(CaptureMachine.keepsAudio(noSpeech))
        // Polling stops once the job is settled, and after a day.
        assertTrue(CaptureMachine.shouldPoll(sent, 12_000))
        assertFalse(CaptureMachine.shouldPoll(inbox, 12_000))
        assertFalse(CaptureMachine.shouldPoll(sent, 11_000 + CaptureMachine.POLL_WINDOW_MILLIS))
    }

    @Test
    fun `retry starts a failed capture from scratch, and only a failed or waiting one`() {
        val failedJob = CaptureMachine.onJobOutcome(
            CaptureMachine.onUploadOutcome(sending(), UploadOutcome.Accepted("job-1", "phone"), 11_000),
            JobOutcome.Failed("Transcription failed."),
        )
        val retried = CaptureMachine.retry(failedJob)!!
        assertEquals(CaptureState.QUEUED, retried.state)
        assertNull(retried.jobId)
        assertEquals(0, retried.attempts)
        assertTrue(CaptureMachine.isDue(retried, 0))
        assertNull(CaptureMachine.retry(queued))
        assertNull(CaptureMachine.retry(sending()))
    }

    @Test
    fun `the queue wakes up for the earliest due attempt`() {
        val waiting = queued.copy(id = "a", state = CaptureState.WAITING, nextAttemptAt = 50_000)
        val later = queued.copy(id = "b", state = CaptureState.QUEUED, nextAttemptAt = 90_000)
        val sent = queued.copy(id = "c", state = CaptureState.SENT, jobId = "j")
        assertEquals(50_000L, CaptureMachine.nextWakeUp(listOf(later, waiting, sent), 10_000))
        assertNull(CaptureMachine.nextWakeUp(listOf(sent), 10_000))
        assertEquals(10_000L, CaptureMachine.nextWakeUp(listOf(queued), 10_000))
    }
}

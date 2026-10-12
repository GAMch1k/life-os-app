package com.gamch1k.lifeos.core.capture

import com.gamch1k.lifeos.core.api.ProcessingAttempt
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CaptureTextTest {
    private fun action(tool: String) = JsonObject(mapOf("tool" to JsonPrimitive(tool)))

    private fun processed(vararg tools: String, at: String = "2026-10-12T10:00:00Z") =
        ProcessingAttempt(id = at, status = "processed", createdAt = at, actions = tools.map(::action))

    @Test
    fun `the outcome names what the last processed attempt did`() {
        assertEquals("created a Task", ProcessingOutcome.describe(listOf(processed("task_create"))))
        assertEquals("created 2 Tasks, created an event", ProcessingOutcome.describe(listOf(processed("task_create", "task_create", "calendar_create"))))
        assertEquals("recorded a transaction", ProcessingOutcome.describe(listOf(processed("finance_create_manual_transaction"))))
        assertEquals("updated Food", ProcessingOutcome.describe(listOf(processed("food_add_shopping_items", "food_mark_bought"))))
        assertEquals("nothing to do", ProcessingOutcome.describe(listOf(processed("none"))))
        assertEquals("ran something_new", ProcessingOutcome.describe(listOf(processed("something_new"))))
    }

    @Test
    fun `only processed attempts count, the latest wins`() {
        val attempts = listOf(
            processed("task_create", at = "2026-10-12T10:00:00Z"),
            ProcessingAttempt(id = "r", status = "retryable", createdAt = "2026-10-12T11:00:00Z", actions = listOf(action("calendar_create"))),
            processed("task_complete", at = "2026-10-12T10:30:00Z"),
        )
        assertEquals("completed a Task", ProcessingOutcome.describe(attempts))
        assertNull(ProcessingOutcome.describe(listOf(attempts[1])))
    }

    private val base = Capture(id = "c", kind = CaptureKind.TEXT, createdAt = "2026-10-12T10:00:00Z", timeZone = "UTC", text = "Call mom\nabout Sunday")

    @Test
    fun `status lines follow the capture from the queue to the processed item`() {
        assertEquals("Waiting to send", CaptureStatusText.describe(base))
        assertEquals("Will retry: offline", CaptureStatusText.describe(base.copy(error = "offline")))
        assertEquals("Sending…", CaptureStatusText.describe(base.copy(status = CaptureStatus.SENDING)))
        assertEquals("Not sent: refused", CaptureStatusText.describe(base.copy(status = CaptureStatus.FAILED, error = "refused")))
        val sent = base.copy(status = CaptureStatus.SENT, inboxItemId = "i")
        assertEquals("In Inbox", CaptureStatusText.describe(sent.copy(inboxState = "raw")))
        assertEquals("In Inbox · processing…", CaptureStatusText.describe(sent.copy(inboxState = "processing")))
        assertEquals("Needs your review in the Inbox", CaptureStatusText.describe(sent.copy(inboxState = "human_review")))
        assertEquals("Processed: created a Task", CaptureStatusText.describe(sent.copy(inboxState = "resolved", outcome = "created a Task")))
        assertEquals("Discarded", CaptureStatusText.describe(sent.copy(inboxState = "discarded")))
    }

    @Test
    fun `a voice note that had no speech says so`() {
        val voice = base.copy(kind = CaptureKind.VOICE, status = CaptureStatus.SENT, voiceJobId = "j", voiceStatus = "failed", voiceError = "No speech was heard in the recording. Record it again.")
        assertEquals("Transcription failed: No speech was heard in the recording. Record it again.", CaptureStatusText.describe(voice))
        assertEquals(true, voice.settled)
    }

    @Test
    fun `titles are short`() {
        assertEquals("Call mom", CaptureStatusText.title(base))
        assertEquals("Voice note · 1:05", CaptureStatusText.title(base.copy(kind = CaptureKind.VOICE, durationMillis = 65_400)))
        assertEquals("3 photos · receipt", CaptureStatusText.title(base.copy(kind = CaptureKind.PHOTO, text = "receipt", files = listOf("a", "b", "c"))))
        assertEquals("Photo", CaptureStatusText.title(base.copy(kind = CaptureKind.PHOTO, text = "", files = listOf("a"))))
    }
}

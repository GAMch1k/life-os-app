package com.gamch1k.lifeos.core.capture

import com.gamch1k.lifeos.core.api.ProcessingAttempt
import kotlinx.serialization.json.JsonPrimitive

/**
 * What the Inbox processor did with a capture, in words, from the actions of
 * its last processed attempt (`GET /v1/inbox-items/{id}/processing-attempts`;
 * each action names the MCP tool it called, see the life-os MCP processor).
 */
object ProcessingOutcome {
    private class Phrase(val one: String, val many: (Int) -> String)

    private val phrases = mapOf(
        "task_create" to Phrase("created a Task") { "created $it Tasks" },
        "task_update" to Phrase("updated a Task") { "updated $it Tasks" },
        "task_complete" to Phrase("completed a Task") { "completed $it Tasks" },
        "task_reopen" to Phrase("reopened a Task") { "reopened $it Tasks" },
        "calendar_create" to Phrase("created an event") { "created $it events" },
        "calendar_update" to Phrase("changed an event") { "changed $it events" },
        "calendar_cancel" to Phrase("cancelled an event") { "cancelled $it events" },
        "finance_create_manual_transaction" to Phrase("recorded a transaction") { "recorded $it transactions" },
        "recipe_import" to Phrase("saved a recipe") { "saved $it recipes" },
    )

    fun describe(attempts: List<ProcessingAttempt>): String? {
        val processed = attempts.filter { it.status == "processed" }.maxByOrNull { it.completedAt ?: it.createdAt ?: "" } ?: return null
        val tools = processed.actions.mapNotNull { (it["tool"] as? JsonPrimitive)?.content }
        val counts = linkedMapOf<String, Int>()
        for (tool in tools) {
            val key = when {
                tool == "none" -> continue
                tool.startsWith("food_") -> "food"
                else -> tool
            }
            counts[key] = (counts[key] ?: 0) + 1
        }
        if (counts.isEmpty()) return "nothing to do"
        return counts.entries.joinToString(", ") { (tool, count) ->
            when {
                tool == "food" -> "updated Food"
                else -> phrases[tool]?.let { if (count == 1) it.one else it.many(count) } ?: "ran $tool"
            }
        }
    }
}

/** One line for the queue and Home saying where a capture is. */
object CaptureStatusText {
    fun describe(capture: Capture): String = when (capture.status) {
        CaptureStatus.PENDING -> if (capture.error == null) "Waiting to send" else "Will retry: ${capture.error}"
        CaptureStatus.SENDING -> "Sending…"
        CaptureStatus.FAILED -> "Not sent: ${capture.error ?: "unknown error"}"
        CaptureStatus.SENT -> sent(capture)
    }

    private fun sent(capture: Capture): String {
        if (capture.inboxItemId == null && capture.kind == CaptureKind.VOICE) {
            return when (capture.voiceStatus) {
                "failed" -> "Transcription failed" + (capture.voiceError?.let { ": $it" } ?: "")
                "expired" -> "The recording expired before it was transcribed"
                "succeeded" -> "Transcribed"
                else -> "Sent · transcribing…"
            }
        }
        return when (capture.inboxState) {
            "raw" -> "In Inbox"
            "processing" -> "In Inbox · processing…"
            "human_review" -> "Needs your review in the Inbox"
            "failed" -> "In Inbox · processing failed"
            "resolved" -> capture.outcome?.let { "Processed: $it" } ?: "Processed"
            "discarded" -> "Discarded"
            else -> "Sent"
        }
    }

    /** A short title: the text's first line, "Voice note · 0:42", or "3 photos · caption". */
    fun title(capture: Capture): String = when (capture.kind) {
        CaptureKind.TEXT -> capture.text.lineSequence().firstOrNull { it.isNotBlank() }?.trim()?.take(120) ?: "Text"
        CaptureKind.VOICE -> "Voice note" + (capture.durationMillis?.let { " · " + duration(it) } ?: "")
        CaptureKind.PHOTO -> {
            val count = capture.files.size
            val photos = if (count == 1) "Photo" else "$count photos"
            capture.text.trim().takeIf { it.isNotEmpty() }?.let { "$photos · ${it.lineSequence().first().take(100)}" } ?: photos
        }
    }

    fun duration(millis: Long): String {
        val seconds = millis / 1000
        return "%d:%02d".format(seconds / 60, seconds % 60)
    }
}

package com.gamch1k.lifeos.core.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/**
 * The parts of the Life OS APIs the app uses, as their OpenAPI contracts
 * (`services/<name>/api/openapi.yaml` in the life-os repository) describe
 * them. Unknown fields are ignored, so the services may grow. Times stay ISO
 * 8601 strings; the code that needs them parses them with java.time.
 */
val LifeOsJson = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    coerceInputValues = true
}

// ---- Errors: every service answers {"error": {"code", "message"}, "request_id"}

@Serializable
data class ErrorEnvelope(val error: ErrorBody? = null, @SerialName("request_id") val requestId: String? = null)

@Serializable
data class ErrorBody(val code: String? = null, val message: String? = null)

// ---- Tasks (/api/tasks)

@Serializable
data class Task(
    val id: String,
    val title: String,
    val notes: String? = null,
    val dueAt: String? = null,
    val completed: Boolean = false,
    val completedAt: String? = null,
    val recurrence: Recurrence? = null,
)

@Serializable
data class Recurrence(val frequency: String, val interval: Int = 1)

@Serializable
data class TaskList(val items: List<Task> = emptyList(), val nextCursor: String? = null)

// ---- Calendar (/api/calendar)

@Serializable
data class CalendarEvent(
    val id: String,
    val title: String,
    val location: String? = null,
    val allDay: Boolean = false,
    val startAt: String? = null,
    val endAt: String? = null,
    val startDate: String? = null,
    val endDate: String? = null,
    val status: String = "active",
    val occurrence: EventOccurrence? = null,
    val source: EventSource? = null,
) {
    /** Unique among listed items: an occurrence shares its series' id. */
    val key: String get() = occurrence?.let { "$id/${it.recurrenceId}" } ?: id
}

@Serializable
data class EventOccurrence(val seriesId: String, val recurrenceId: String, val modified: Boolean = false)

@Serializable
data class EventSource(val kind: String = "manual", val name: String? = null, val provider: String? = null)

@Serializable
data class EventList(val items: List<CalendarEvent> = emptyList(), val nextCursor: String? = null)

// ---- Inbox (/api/inbox)

@Serializable
data class InboxItem(
    val id: String,
    val content: String = "",
    val source: String = "",
    val state: String,
    val mediaReferences: List<String> = emptyList(),
    val processingMetadata: JsonObject? = null,
    val createdAt: String? = null,
    val updatedAt: String? = null,
)

@Serializable
data class CreateInboxItem(
    val content: String,
    val source: String,
    val sourceMetadata: JsonObject? = null,
    val mediaReferences: List<String>? = null,
    val dedupeKey: String? = null,
)

@Serializable
data class ProcessingAttempt(
    val id: String,
    val status: String,
    val actions: List<JsonObject> = emptyList(),
    val errorSummary: String? = null,
    val createdAt: String? = null,
    val completedAt: String? = null,
)

@Serializable
data class ProcessingAttemptList(val items: List<ProcessingAttempt> = emptyList())

@Serializable
data class Media(val reference: String, val sha256: String, val contentType: String, val byteSize: Long)

@Serializable
data class MediaList(val items: List<Media> = emptyList())

// ---- Voice (/api/voice)

@Serializable
data class AudioJob(
    val id: String,
    val status: String,
    val source: String? = null,
    val transcript: String? = null,
    val inboxItemId: String? = null,
    val errorSummary: String? = null,
    val attemptCount: Int = 0,
)

// ---- Notifications: Web Push (/api/notifications/v1/push)

@Serializable
data class PushConfig(val configured: Boolean = false, val publicKey: String? = null, val delivering: Boolean = false)

@Serializable
data class PushSubscriptionRequest(val endpoint: String, val keys: PushKeys)

@Serializable
data class PushKeys(val p256dh: String, val auth: String)

@Serializable
data class PushSubscription(val id: String, val deviceLabel: String? = null, val endpoint: String, val pushService: String? = null)

// ---- Android releases (/android/updates, docs/operations/android-releases.md)

@Serializable
data class UpdateManifest(val formatVersion: Int, val updatedAt: String? = null, val apps: UpdateApps = UpdateApps())

@Serializable
data class UpdateApps(val phone: AppRelease? = null, val wear: AppRelease? = null)

@Serializable
data class AppRelease(
    val versionCode: Long,
    val versionName: String,
    val minSdk: Int,
    val sha256: String,
    val size: Long,
    val url: String,
    val publishedAt: String? = null,
    val commit: String? = null,
    val notes: String? = null,
)

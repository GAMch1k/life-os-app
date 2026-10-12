package com.gamch1k.lifeos.wear.net

import com.gamch1k.lifeos.core.wearlink.HttpOutcome
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.net.URLEncoder
import java.time.Instant
import java.time.ZoneId

/** The Life OS API as the watch uses it (contracts in the Life OS repository, services/…/api/openapi.yaml). */
class LifeOsApi(private val transport: ApiTransport) {
    suspend fun execute(request: ApiRequest): ApiResult = transport.execute(request)

    /** `POST /api/voice/v1/audio-jobs`; the capture id is the Idempotency-Key, unchanged across retries. */
    suspend fun uploadAudio(upload: AudioUpload): ApiResult {
        val multipart = Multipart.build(
            fields = listOf(
                "source" to "watch",
                "sourceMetadata" to upload.sourceMetadataJson(),
            ),
            file = Multipart.File(name = "audio", fileName = upload.fileName, contentType = upload.contentType, bytes = upload.bytes),
        )
        return transport.execute(
            ApiRequest(
                method = "POST",
                path = "/api/voice/v1/audio-jobs",
                headers = mapOf(
                    "Content-Type" to multipart.contentType,
                    "Accept" to "application/json",
                    "Idempotency-Key" to upload.captureId,
                ),
                body = multipart.body,
                timeoutMillis = 120_000,
            ),
        )
    }

    /** `GET /api/voice/v1/audio-jobs/{id}`. */
    suspend fun audioJob(jobId: String): ApiResult =
        transport.execute(ApiRequest("GET", "/api/voice/v1/audio-jobs/$jobId", mapOf("Accept" to "application/json")))

    /** `GET /api/calendar/v1/events`: events overlapping [from, to), recurring ones expanded, cancelled ones left out. */
    suspend fun events(from: Instant, to: Instant, zone: ZoneId, limit: Int = 30): ApiResult =
        transport.execute(
            ApiRequest(
                "GET",
                "/api/calendar/v1/events" + query("from" to from.toString(), "to" to to.toString(), "timeZone" to zone.id, "limit" to "$limit"),
                mapOf("Accept" to "application/json"),
            ),
        )

    /** `GET /api/tasks/v1/tasks`: open tasks due before [dueBefore], soonest first, without undated ones. */
    suspend fun tasksDueBefore(dueBefore: Instant, limit: Int = 20): ApiResult =
        transport.execute(
            ApiRequest(
                "GET",
                "/api/tasks/v1/tasks" + query("completed" to "false", "dueBefore" to dueBefore.toString(), "includeUndated" to "false", "limit" to "$limit"),
                mapOf("Accept" to "application/json"),
            ),
        )

    /**
     * `POST /api/tasks/v1/tasks/{id}/complete`. [actionId] names this one tap,
     * so the phone replays a lost reply instead of completing twice, while a
     * later tap on a reopened task still completes it.
     */
    suspend fun completeTask(taskId: String, actionId: String): ApiResult =
        transport.execute(
            ApiRequest("POST", "/api/tasks/v1/tasks/$taskId/complete", mapOf("Accept" to "application/json", "Idempotency-Key" to "complete:$actionId")),
        )

    companion object {
        val json = Json {
            ignoreUnknownKeys = true
            explicitNulls = false
            coerceInputValues = true
        }

        fun query(vararg pairs: Pair<String, String>): String =
            "?" + pairs.joinToString("&") { (name, value) -> "$name=${URLEncoder.encode(value, "UTF-8")}" }

        /** The message of a Life OS error body (`{"error":{"code","message"}}`), if there is one. */
        fun errorMessage(response: ApiResult.Response): String? = try {
            json.decodeFromString(ErrorEnvelope.serializer(), response.text).error?.message?.takeIf { it.isNotBlank() }
        } catch (_: Exception) {
            null
        }

        /** A short text for the owner about a response that wasn't a success. */
        fun describe(response: ApiResult.Response): String = when (response.outcome) {
            HttpOutcome.SUCCESS -> "OK"
            HttpOutcome.AUTH -> "Life OS refused the token (${response.status}). Update it on the phone."
            HttpOutcome.RETRYABLE -> "Life OS is busy or down (${response.status})."
            HttpOutcome.PERMANENT -> errorMessage(response) ?: "Life OS refused the request (${response.status})."
        }

        fun describe(unavailable: ApiResult.Unavailable): String = when (unavailable.reason) {
            Unavailability.NO_PHONE -> "Phone not connected, and no direct connection."
            Unavailability.PHONE_NO_RELAY -> "The phone app doesn't relay, and no direct connection."
            Unavailability.NO_CREDENTIALS -> "No token yet. Open Life OS on the phone."
            Unavailability.NETWORK -> "No connection to Life OS."
            Unavailability.PROTOCOL -> "The phone and watch apps don't match. Update both."
        }
    }
}

/** One recording to upload. */
class AudioUpload(
    val captureId: String,
    val bytes: ByteArray,
    val fileName: String,
    val contentType: String,
    val timeZone: String,
    val recordedAt: Instant,
    val durationMillis: Long,
) {
    /** The multipart field `sourceMetadata`: a JSON object, kept with the job (Voice stores it as given). */
    fun sourceMetadataJson(): String = LifeOsApi.json.encodeToString(
        SourceMetadata.serializer(),
        SourceMetadata(via = "wear-app", timeZone = timeZone, captureId = captureId, recordedAt = recordedAt.toString(), durationMs = durationMillis),
    )
}

@Serializable
class SourceMetadata(val via: String, val timeZone: String, val captureId: String, val recordedAt: String, val durationMs: Long)

@Serializable
class ErrorEnvelope(val error: ErrorBody? = null)

@Serializable
class ErrorBody(val code: String? = null, val message: String? = null)

/** `AudioJob` (services/voice/api/openapi.yaml), the fields the watch reads. */
@Serializable
data class AudioJobDto(
    val id: String,
    val status: String,
    val inboxItemId: String? = null,
    val errorSummary: String? = null,
)

/** `Task` (services/tasks/api/openapi.yaml). */
@Serializable
data class TaskDto(
    val id: String,
    val title: String,
    val dueAt: String? = null,
    val completed: Boolean = false,
)

@Serializable
data class TaskListDto(val items: List<TaskDto> = emptyList())

/** `CalendarEvent` (services/calendar/api/openapi.yaml). */
@Serializable
data class EventDto(
    val id: String,
    val title: String,
    val allDay: Boolean = false,
    val startAt: String? = null,
    val endAt: String? = null,
    val startDate: String? = null,
    val endDate: String? = null,
    val status: String = "active",
    val location: String? = null,
    val occurrence: OccurrenceDto? = null,
)

@Serializable
data class OccurrenceDto(val recurrenceId: String? = null)

@Serializable
data class EventListDto(val items: List<EventDto> = emptyList())

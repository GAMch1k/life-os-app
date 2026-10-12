package com.gamch1k.lifeos.core.capture

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.gamch1k.lifeos.core.api.LifeOsJson
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.File
import java.time.Duration
import java.time.Instant

@Serializable
enum class CaptureKind { TEXT, VOICE, PHOTO }

@Serializable
enum class CaptureStatus {
    /** Waiting for a network, or for its next try. */
    PENDING,

    /** A send is running. */
    SENDING,

    /** Life OS has it: an Inbox item (text, photos) or a Voice job (voice). */
    SENT,

    /** Gave up; the queue screen offers Retry. */
    FAILED,
}

/**
 * One capture in the offline queue, from the moment it is made until Life OS
 * has it. [id] is generated on the phone and never changes, so every retry
 * sends the same Inbox dedupe key ([dedupeKey]) and Inbox keeps one item. A
 * voice note's Voice job is keyed by [voiceIdempotencyKey].
 */
@Serializable
data class Capture(
    val id: String,
    val kind: CaptureKind,
    val createdAt: String,
    /** The phone's time zone when it was captured (IANA id). */
    val timeZone: String,
    /** The text, or a photo's caption. */
    val text: String = "",
    /** Absolute paths of the recording or the prepared JPEGs, in order. */
    val files: List<String> = emptyList(),
    val durationMillis: Long? = null,
    val status: CaptureStatus = CaptureStatus.PENDING,
    val attempts: Int = 0,
    val error: String? = null,
    val authProblem: Boolean = false,
    /** References of the photos already uploaded, in the order of [files]. */
    val mediaReferences: List<String> = emptyList(),
    val voiceJobId: String? = null,
    val voiceStatus: String? = null,
    val voiceError: String? = null,
    val inboxItemId: String? = null,
    val inboxState: String? = null,
    /** What processing did, e.g. "created a Task". */
    val outcome: String? = null,
    val sentAt: String? = null,
    /**
     * The sourceMetadata JSON, written once when the capture is made (and
     * again when Retry starts a new Voice job attempt) and sent byte for byte
     * on every try: Voice refuses a retry whose metadata differs (409
     * `idempotency_conflict`). Null only for captures queued by 0.2.0, which
     * use [sourceMetadata].
     */
    val sourceMetadataJson: String? = null,
    /** Which Voice job this voice note asks for: 0 at first, plus 1 for every Retry after the job failed or the key was refused. */
    val jobAttempt: Int = 0,
    /** Voice answered 409 `idempotency_conflict`: the key is taken, so Retry needs a new one. */
    val keyConflict: Boolean = false,
) {
    val dedupeKey: String get() = "$DEDUPE_PREFIX$id"

    /**
     * The voice upload's `Idempotency-Key`: the capture id, then `<id>-r1`,
     * `<id>-r2`… for later job attempts. Every try of one attempt repeats
     * it, so a lost reply is answered with the job it created.
     */
    val voiceIdempotencyKey: String get() = if (jobAttempt == 0) id else "$id-r$jobAttempt"

    /** A voice note whose Voice job failed or expired before it reached the Inbox; Retry asks for a new job. */
    val voiceJobFailed: Boolean
        get() = kind == CaptureKind.VOICE && status == CaptureStatus.SENT && voiceJobId != null &&
            inboxItemId == null && voiceStatus in FINAL_VOICE_FAILURES

    /** What the queue screen offers Retry for. */
    val retryable: Boolean get() = status == CaptureStatus.FAILED || voiceJobFailed

    /** Sent and nothing more will change on the server side that the app shows. */
    val settled: Boolean
        get() = status == CaptureStatus.SENT && (
            inboxState in FINAL_INBOX_STATES ||
                (inboxItemId == null && voiceStatus in FINAL_VOICE_FAILURES)
            )

    /** The metadata as sent: the stored JSON, or (0.2.0 captures) the same fields encoded now. */
    fun metadataJson(): String = sourceMetadataJson ?: LifeOsJson.encodeToString(JsonObject.serializer(), sourceMetadata())

    /**
     * What the Inbox item's sourceMetadata says about the capture; only
     * stable fields, no retry counters or times. A later Voice job attempt
     * adds `"attempt": n`.
     */
    fun sourceMetadata(): JsonObject = JsonObject(
        buildMap {
            put("via", JsonPrimitive(VIA))
            put("timeZone", JsonPrimitive(timeZone))
            put("captureId", JsonPrimitive(id))
            if (jobAttempt > 0) put("attempt", JsonPrimitive(jobAttempt))
        },
    )

    /**
     * Retry of a voice note whose job failed, or whose key Voice refused:
     * the next job attempt, with its own key and metadata (written now), and
     * nothing left of the old job.
     */
    fun nextJobAttempt(): Capture {
        val next = copy(
            jobAttempt = jobAttempt + 1,
            keyConflict = false,
            status = CaptureStatus.PENDING,
            attempts = 0,
            error = null,
            authProblem = false,
            voiceJobId = null,
            voiceStatus = null,
            voiceError = null,
            inboxItemId = null,
            inboxState = null,
            outcome = null,
            sentAt = null,
        )
        return next.copy(sourceMetadataJson = LifeOsJson.encodeToString(JsonObject.serializer(), next.sourceMetadata()))
    }

    companion object {
        /** A new capture with its metadata fixed now, as every attempt will send it. */
        fun create(
            id: String,
            kind: CaptureKind,
            createdAt: String,
            timeZone: String,
            text: String = "",
            files: List<String> = emptyList(),
            durationMillis: Long? = null,
        ): Capture {
            val capture = Capture(id = id, kind = kind, createdAt = createdAt, timeZone = timeZone, text = text, files = files, durationMillis = durationMillis)
            return capture.copy(sourceMetadataJson = LifeOsJson.encodeToString(JsonObject.serializer(), capture.sourceMetadata()))
        }

        const val VIA = "android-app"
        const val DEDUPE_PREFIX = "android-app:"

        /** The phone sends as the `web` source, telling itself apart in sourceMetadata. */
        const val SOURCE = "web"
        val FINAL_INBOX_STATES = setOf("resolved", "discarded", "failed", "human_review")
        val FINAL_VOICE_FAILURES = setOf("failed", "expired")
    }
}

/**
 * The queue, as JSON in a DataStore. Every change is one atomic DataStore
 * edit, so the worker and the screens never overwrite each other.
 */
class CaptureStore(private val store: DataStore<Preferences>) {
    val captures: Flow<List<Capture>> = store.data.map(::decode)

    suspend fun all(): List<Capture> = captures.first()

    suspend fun get(id: String): Capture? = all().firstOrNull { it.id == id }

    suspend fun add(capture: Capture) = change { list -> list.filterNot { it.id == capture.id } + capture }

    /** Applies [transform] to the capture with [id]; returns the new value, or null if it is gone. */
    suspend fun update(id: String, transform: (Capture) -> Capture): Capture? {
        var result: Capture? = null
        change { list ->
            list.map { capture ->
                if (capture.id == id) transform(capture).also { result = it } else capture
            }
        }
        return result
    }

    suspend fun remove(id: String): Capture? {
        var removed: Capture? = null
        change { list -> list.filterNot { (it.id == id).also { match -> if (match) removed = it } } }
        removed?.deleteFiles()
        return removed
    }

    /**
     * Removes the sent captures older than [age] (all sent ones for zero) and
     * their files. A voice note whose job failed stays, like a failed
     * capture, until it is retried or deleted.
     */
    suspend fun pruneSent(now: Instant, age: Duration): Int {
        val removed = mutableListOf<Capture>()
        change { list ->
            list.filterNot { capture ->
                val old = capture.status == CaptureStatus.SENT && !capture.voiceJobFailed &&
                    Duration.between(Instant.parse(capture.sentAt ?: capture.createdAt), now) >= age
                old.also { if (it) removed += capture }
            }
        }
        removed.forEach { it.deleteFiles() }
        return removed.size
    }

    /** Sign out: drops every capture and its files. */
    suspend fun clear() {
        val all = all()
        store.edit { it.remove(KEY) }
        all.forEach { it.deleteFiles() }
    }

    private suspend fun change(transform: (List<Capture>) -> List<Capture>) {
        store.edit { preferences ->
            preferences[KEY] = LifeOsJson.encodeToString(SERIALIZER, transform(decode(preferences)))
        }
    }

    private fun decode(preferences: Preferences): List<Capture> {
        val text = preferences[KEY] ?: return emptyList()
        return runCatching { LifeOsJson.decodeFromString(SERIALIZER, text) }.getOrDefault(emptyList())
    }

    private fun Capture.deleteFiles() {
        files.forEach { path -> File(path).delete() }
        files.firstOrNull()?.let { File(it).parentFile }?.takeIf { it.name == id }?.delete()
    }

    private companion object {
        val KEY = stringPreferencesKey("captures")
        val SERIALIZER = ListSerializer(Capture.serializer())
    }
}

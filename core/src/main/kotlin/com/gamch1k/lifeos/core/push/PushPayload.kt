package com.gamch1k.lifeos.core.push

import com.gamch1k.lifeos.core.api.LifeOsJson
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import java.net.URI
import java.net.URLDecoder

/**
 * A Life OS notification as the `webpush` channel sends it (life-os
 * docs/services/notifications.md, "Web push"): JSON, decrypted on the phone
 * by the UnifiedPush connector.
 */
@Serializable
data class PushPayload(
    val title: String,
    val body: String? = null,
    /** A Life OS path to open, such as `/calendar?date=2026-10-10`. */
    val link: String? = null,
    /** `lifeos-<id>`: a notification with the tag of one already shown replaces it. */
    val tag: String? = null,
    val importance: String = "medium",
    val category: String = "",
    val id: String? = null,
    /** createdAt in milliseconds. */
    val timestamp: Long? = null,
) {
    enum class Importance { HIGH, MEDIUM, LOW }

    val level: Importance
        get() = when (importance.lowercase()) {
            "high" -> Importance.HIGH
            "low" -> Importance.LOW
            else -> Importance.MEDIUM
        }

    /** The Android notification tag: the payload's, so a repeated delivery replaces the first. */
    val notificationTag: String get() = tag?.takeIf { it.isNotBlank() } ?: "lifeos-${id ?: title.hashCode()}"

    /** WearableExtender dismissal id (docs/wear.md): dismissing on the phone or the watch dismisses both. */
    val dismissalId: String get() = "lifeos:${id ?: notificationTag.removePrefix("lifeos-")}"

    /** WearableExtender bridge tag (docs/wear.md), e.g. `lifeos.tasks.due`. */
    val bridgeTag: String get() = "lifeos." + (category.takeIf { it.isNotBlank() } ?: "notification")

    /** The Task this notification is about, when its link names one: it gets a Done action. */
    val taskId: String? get() = PushPayloads.taskIdFromLink(link)

    /** Tasks or events changed (or are due): the watch's Today tile and the widgets refresh. */
    val touchesToday: Boolean get() = category.startsWith("tasks.") || category.startsWith("calendar.") || category.startsWith("summary.")
}

object PushPayloads {
    private val UUID = Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")
    private val TASK_QUERY_KEYS = setOf("task", "taskid", "task_id", "id", "selected", "open")

    /** The decrypted push, or null when it is not a Life OS notification (no JSON, no title). */
    fun parse(bytes: ByteArray): PushPayload? {
        val text = bytes.toString(Charsets.UTF_8).trim()
        if (!text.startsWith("{")) return null
        val payload = try {
            LifeOsJson.decodeFromString(PushPayload.serializer(), text)
        } catch (_: SerializationException) {
            return null
        } catch (_: IllegalArgumentException) {
            return null
        }
        return payload.takeIf { it.title.isNotBlank() }
    }

    /**
     * The Task id a link names, or null. Only links into `/tasks` count, and
     * only a full UUID: `/tasks/<id>`, `/tasks?task=<id>` (also `taskId`,
     * `id`, `selected`), or `/tasks#<id>` / `#task-<id>`. A full URL on any
     * host works too; anything else (`/tasks`, `/calendar?…`, garbage) is null.
     */
    fun taskIdFromLink(link: String?): String? {
        val raw = link?.trim()?.takeIf { it.isNotEmpty() && it.none(Char::isWhitespace) } ?: return null
        val uri = runCatching { URI(raw) }.getOrNull() ?: return null
        val segments = (uri.rawPath ?: "").split('/').filter { it.isNotEmpty() }.map(::decode)
        if (segments.firstOrNull()?.lowercase() != "tasks") return null
        segments.getOrNull(1)?.let { candidate -> uuidOrNull(candidate)?.let { return it } }
        uri.rawQuery?.split('&')?.forEach { pair ->
            val name = decode(pair.substringBefore('=')).lowercase()
            val value = decode(pair.substringAfter('=', ""))
            if (name in TASK_QUERY_KEYS) uuidOrNull(value)?.let { return it }
        }
        uri.rawFragment?.let(::decode)?.removePrefix("task-")?.removePrefix("task=")?.let { fragment -> uuidOrNull(fragment)?.let { return it } }
        return null
    }

    private fun uuidOrNull(value: String): String? = value.takeIf { UUID.matches(it) }?.lowercase()

    private fun decode(value: String): String = runCatching { URLDecoder.decode(value, Charsets.UTF_8) }.getOrDefault(value)
}

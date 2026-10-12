package com.gamch1k.lifeos.wear.today

import com.gamch1k.lifeos.wear.net.EventDto
import com.gamch1k.lifeos.wear.net.TaskDto
import kotlinx.serialization.Serializable
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * The last fetched day, cached on the watch for the tile, the complication
 * and the Today screen: the events of today and tomorrow and the open tasks
 * due by the end of today (overdue ones included).
 */
@Serializable
data class TodaySnapshot(
    val fetchedAt: Long,
    /** The zone the day was fetched in (the watch's). */
    val zone: String,
    /** The watch's date when fetched, ISO yyyy-mm-dd. */
    val day: String,
    val events: List<TodayEvent>,
    val tasks: List<TodayTask>,
)

@Serializable
data class TodayEvent(
    /** The event id plus, for an occurrence of a recurring event, its recurrence id. */
    val key: String,
    val id: String,
    val title: String,
    val allDay: Boolean,
    /** Timed events: the start and end instants, epoch millis. */
    val start: Long? = null,
    val end: Long? = null,
    /** All-day events: the first and last day (inclusive), ISO yyyy-mm-dd. */
    val startDate: String? = null,
    val endDate: String? = null,
)

@Serializable
data class TodayTask(
    val id: String,
    val title: String,
    /** Epoch millis; never null here (the list asks for dated tasks only). */
    val dueAt: Long,
)

object TodayParser {
    fun snapshot(events: List<EventDto>, tasks: List<TaskDto>, fetchedAt: Instant, zone: ZoneId): TodaySnapshot =
        TodaySnapshot(
            fetchedAt = fetchedAt.toEpochMilli(),
            zone = zone.id,
            day = fetchedAt.atZone(zone).toLocalDate().toString(),
            events = events.mapNotNull(::event).distinctBy { it.key },
            tasks = tasks.mapNotNull(::task).sortedBy { it.dueAt },
        )

    fun event(dto: EventDto): TodayEvent? {
        if (dto.status != "active" || dto.title.isBlank()) return null
        val key = dto.id + (dto.occurrence?.recurrenceId?.let { "#$it" } ?: "")
        return if (dto.allDay) {
            val startDate = dto.startDate?.let(::date) ?: return null
            val endDate = dto.endDate?.let(::date) ?: startDate
            TodayEvent(key, dto.id, dto.title.oneLine(), allDay = true, startDate = startDate.toString(), endDate = endDate.toString())
        } else {
            val start = dto.startAt?.let(::instant) ?: return null
            val end = dto.endAt?.let(::instant) ?: start
            TodayEvent(key, dto.id, dto.title.oneLine(), allDay = false, start = start.toEpochMilli(), end = end.toEpochMilli())
        }
    }

    fun task(dto: TaskDto): TodayTask? {
        if (dto.completed || dto.title.isBlank()) return null
        val due = dto.dueAt?.let(::instant) ?: return null
        return TodayTask(dto.id, dto.title.oneLine(), due.toEpochMilli())
    }

    private fun instant(text: String): Instant? = try {
        OffsetDateTime.parse(text).toInstant()
    } catch (_: Exception) {
        null
    }

    private fun date(text: String): LocalDate? = try {
        LocalDate.parse(text)
    } catch (_: Exception) {
        null
    }

    private fun String.oneLine() = replace(Regex("\\s+"), " ").trim()
}

/** Time rules shared by the tile, the complication and the Today screen. */
object TodayRules {
    /**
     * Whether a task's `dueAt` stands for a whole day (the rule of Life OS's
     * Tasks service): owner-local midnight, or a UTC midnight on the same
     * calendar day for the owner.
     */
    fun isDateOnly(dueAt: Instant, zone: ZoneId): Boolean {
        val local = dueAt.atZone(zone)
        if (local.toLocalTime() == LocalTime.MIDNIGHT) return true
        val utc = dueAt.atOffset(ZoneOffset.UTC)
        return utc.toLocalTime() == LocalTime.MIDNIGHT && utc.toLocalDate() == local.toLocalDate()
    }

    /** The calendar day a date-only task is due on. */
    fun dueDate(dueAt: Instant, zone: ZoneId): LocalDate {
        val local = dueAt.atZone(zone)
        if (local.toLocalTime() == LocalTime.MIDNIGHT) return local.toLocalDate()
        return dueAt.atOffset(ZoneOffset.UTC).toLocalDate()
    }

    fun isOverdue(task: TodayTask, now: Instant, zone: ZoneId): Boolean {
        val due = Instant.ofEpochMilli(task.dueAt)
        return if (isDateOnly(due, zone)) dueDate(due, zone) < now.atZone(zone).toLocalDate() else due < now
    }

    /** The open tasks still due by the end of [now]'s day (the snapshot may be from yesterday). */
    fun tasksDue(snapshot: TodaySnapshot, now: Instant, zone: ZoneId): List<TodayTask> {
        val endOfToday = now.atZone(zone).toLocalDate().plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        return snapshot.tasks.filter { it.dueAt < endOfToday }
    }

    /** Whether an all-day event covers [day]. */
    fun covers(event: TodayEvent, day: LocalDate): Boolean {
        val start = event.startDate?.let(LocalDate::parse) ?: return false
        val end = event.endDate?.let(LocalDate::parse) ?: start
        return !day.isBefore(start) && !day.isAfter(end)
    }
}

package com.gamch1k.lifeos.core.today

import com.gamch1k.lifeos.core.api.CalendarEvent
import com.gamch1k.lifeos.core.api.Task
import kotlinx.serialization.Serializable
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

/** What Home last read from Life OS; cached so Home opens instantly and offline. */
@Serializable
data class TodaySnapshot(
    val fetchedAt: String,
    val timeZone: String,
    val tasks: List<Task> = emptyList(),
    val events: List<CalendarEvent> = emptyList(),
)

data class TaskRow(val id: String, val title: String, val detail: String, val recurring: Boolean)

data class EventRow(
    val key: String,
    val title: String,
    val time: String,
    val location: String?,
    val sourceName: String?,
    /** The day it is listed under, for the Dashboard link `/calendar?date=…`. */
    val date: LocalDate,
)

data class TodayView(
    val overdue: List<TaskRow>,
    val dueToday: List<TaskRow>,
    val eventsToday: List<EventRow>,
    val eventsTomorrow: List<EventRow>,
    val today: LocalDate,
)

/**
 * Home's "Today", computed on the phone from the Tasks and Calendar lists,
 * by the Dashboard's rules: a Task is overdue when it was due before today
 * began, due today otherwise; a date-only Task (owner-local midnight, or a
 * UTC midnight on the same day) shows no time. Events are listed under each
 * day they overlap.
 */
object TodayBuilder {
    private val time = DateTimeFormatter.ofPattern("HH:mm", Locale.ENGLISH)
    private val day = DateTimeFormatter.ofPattern("EEE d MMM", Locale.ENGLISH)

    /** `[start of today, start of the day after tomorrow)` in [zone]: what Home asks Calendar for. */
    fun eventRange(now: Instant, zone: ZoneId): Pair<Instant, Instant> {
        val today = now.atZone(zone).toLocalDate()
        return today.atStartOfDay(zone).toInstant() to today.plusDays(2).atStartOfDay(zone).toInstant()
    }

    /** The last millisecond of today in [zone]: Home's `dueBefore`. */
    fun endOfToday(now: Instant, zone: ZoneId): Instant =
        now.atZone(zone).toLocalDate().plusDays(1).atStartOfDay(zone).toInstant().minusMillis(1)

    fun build(snapshot: TodaySnapshot, now: Instant, zone: ZoneId, hiddenTaskIds: Set<String> = emptySet()): TodayView {
        val today = now.atZone(zone).toLocalDate()
        val startOfToday = today.atStartOfDay(zone).toInstant()
        val endOfToday = today.plusDays(1).atStartOfDay(zone).toInstant()
        val overdue = mutableListOf<TaskRow>()
        val dueToday = mutableListOf<TaskRow>()
        snapshot.tasks
            .filter { !it.completed && it.id !in hiddenTaskIds }
            .mapNotNull { task -> task.dueAt?.let(::parseInstant)?.let { task to it } }
            .filter { (_, due) -> due < endOfToday }
            .sortedBy { (_, due) -> due }
            .forEach { (task, due) ->
                val dateOnly = isDateOnly(due, zone)
                val local = due.atZone(zone)
                if (due < startOfToday) {
                    val since = if (dateOnly || local.toLocalDate() != today) day.format(local) else time.format(local)
                    overdue += TaskRow(task.id, task.title, "Overdue since $since", task.recurrence != null)
                } else {
                    val detail = if (dateOnly) "Today" else time.format(local)
                    dueToday += TaskRow(task.id, task.title, detail, task.recurrence != null)
                }
            }
        val active = snapshot.events.filter { it.status != "cancelled" }
        return TodayView(
            overdue = overdue,
            dueToday = dueToday,
            eventsToday = eventsOn(active, today, zone),
            eventsTomorrow = eventsOn(active, today.plusDays(1), zone),
            today = today,
        )
    }

    /** Whether [due] stands for a whole day (the Dashboard's isDateOnly). */
    fun isDateOnly(due: Instant, zone: ZoneId): Boolean {
        val local = due.atZone(zone).toLocalDate()
        if (local.atStartOfDay(zone).toInstant() == due) return true
        val utc = due.atOffset(ZoneOffset.UTC)
        return utc.toLocalTime().toSecondOfDay() == 0 && utc.nano == 0 && utc.toLocalDate() == local
    }

    private fun eventsOn(events: List<CalendarEvent>, date: LocalDate, zone: ZoneId): List<EventRow> {
        val dayStart = date.atStartOfDay(zone).toInstant()
        val dayEnd = date.plusDays(1).atStartOfDay(zone).toInstant()
        return events.mapNotNull { event ->
            if (event.allDay) {
                val first = event.startDate?.let(::parseDate) ?: return@mapNotNull null
                val last = event.endDate?.let(::parseDate) ?: first
                if (date < first || date > last) return@mapNotNull null
                val label = if (first == last) "All day" else "All day · until ${day.format(last)}"
                row(event, label, date) to dayStart
            } else {
                val start = event.startAt?.let(::parseInstant) ?: return@mapNotNull null
                val end = event.endAt?.let(::parseInstant) ?: start
                if (!(start < dayEnd && (end > dayStart || (end == start && start >= dayStart)))) return@mapNotNull null
                val startsToday = start >= dayStart
                val endsToday = end <= dayEnd
                val label = when {
                    startsToday && endsToday -> if (end == start) time.format(start.atZone(zone)) else "${time.format(start.atZone(zone))}–${time.format(end.atZone(zone))}"
                    startsToday -> "${time.format(start.atZone(zone))}–${day.format(end.atZone(zone))} ${time.format(end.atZone(zone))}"
                    endsToday -> "until ${time.format(end.atZone(zone))}"
                    else -> "All day"
                }
                row(event, label, date) to maxOf(start, dayStart)
            }
        }.sortedWith(compareBy({ it.second }, { it.first.title })).map { it.first }
    }

    private fun row(event: CalendarEvent, time: String, date: LocalDate) = EventRow(
        key = event.key,
        title = event.title,
        time = time,
        location = event.location?.takeIf { it.isNotBlank() },
        sourceName = event.source?.takeIf { it.kind != "manual" }?.name,
        date = date,
    )

    private fun parseInstant(text: String): Instant? = runCatching { Instant.parse(text) }.getOrNull()
        ?: runCatching { java.time.OffsetDateTime.parse(text).toInstant() }.getOrNull()

    private fun parseDate(text: String): LocalDate? = runCatching { LocalDate.parse(text) }.getOrNull()
}

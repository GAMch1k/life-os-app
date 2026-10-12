package com.gamch1k.lifeos.wear.today

import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

/** One line of the Today tile or screen. */
sealed interface TodayRow {
    val label: String

    /** An event: [time] such as "14:30", "now", "All day", "Tue 09:00". */
    data class Event(val key: String, val time: String, val title: String, val tomorrow: Boolean) : TodayRow {
        override val label get() = "$time $title"
    }

    /** A task due today or overdue; tapping it offers to mark it done. */
    data class Task(val id: String, val title: String, val due: String, val overdue: Boolean) : TodayRow {
        override val label get() = if (overdue) "! $title" else title
    }
}

/** What the Today tile shows at one moment. */
data class TodayTileModel(
    val rows: List<TodayRow>,
    /** Shown instead of rows when there are none ("Nothing left today"). */
    val emptyText: String?,
    /** Under the rows: "+2 more", or the age of old data. */
    val footer: String?,
)

/**
 * The Today tile's layout logic, without ProtoLayout: what to show, in which
 * order, within how many rows. Events still ahead come first, then tasks;
 * each list gives up its unused rows to the other.
 */
object TodayLayout {
    /** Rows that fit the tile: three on the Galaxy Watch 8's 44 mm screen, two on smaller ones. */
    fun maxRows(screenHeightDp: Int): Int = if (screenHeightDp >= 225) 3 else 2

    /** Data older than this is labelled with its time. */
    val STALE_AFTER: Duration = Duration.ofHours(2)

    /** Shown when nothing has been fetched yet. */
    const val NOT_LOADED = "Open Life OS to load Today"

    /** Shown when the tile could not be built at all; tapping it opens the app. */
    const val BROKEN = "Today couldn't be shown. Tap to open Life OS."

    /**
     * What the tile shows at [now]. [error] is the last refresh's problem,
     * shown under [NOT_LOADED] while there is no cached day.
     */
    fun model(snapshot: TodaySnapshot?, now: Instant, zone: ZoneId, maxRows: Int, use24Hour: Boolean = true, error: String? = null): TodayTileModel {
        if (snapshot == null) return TodayTileModel(emptyList(), NOT_LOADED, error?.let { elide(it, 60) })
        val events = upcomingEvents(snapshot, now, zone, use24Hour)
        val tasks = tasks(snapshot, now, zone, use24Hour)
        if (events.isEmpty() && tasks.isEmpty()) {
            return TodayTileModel(emptyList(), "Nothing left today", staleFooter(snapshot, now, zone, use24Hour))
        }
        val (eventCount, taskCount) = split(events.size, tasks.size, maxRows)
        val rows = events.take(eventCount) + tasks.take(taskCount)
        val hidden = events.size + tasks.size - rows.size
        val footer = staleFooter(snapshot, now, zone, use24Hour) ?: if (hidden > 0) "+$hidden more" else null
        return TodayTileModel(rows, null, footer)
    }

    /** How many rows go to events and to tasks: about half each, the rest to whichever has more. */
    fun split(events: Int, tasks: Int, maxRows: Int): Pair<Int, Int> {
        val eventShare = minOf(events, maxOf(maxRows - tasks, (maxRows + 1) / 2))
        val taskShare = minOf(tasks, maxRows - eventShare)
        return eventShare to taskShare
    }

    /** Timed events not over yet (today and tomorrow), and all-day events of today, soonest first. */
    fun upcomingEvents(snapshot: TodaySnapshot, now: Instant, zone: ZoneId, use24Hour: Boolean = true): List<TodayRow.Event> {
        val today = now.atZone(zone).toLocalDate()
        val nowMillis = now.toEpochMilli()
        val allDay = snapshot.events
            .filter { it.allDay && TodayRules.covers(it, today) }
            .map { TodayRow.Event(it.key, "All day", it.title, tomorrow = false) }
        val timed = snapshot.events
            .filter { !it.allDay && it.start != null && (it.end ?: it.start) > nowMillis }
            .sortedBy { it.start }
            .map { event ->
                val start = Instant.ofEpochMilli(event.start!!).atZone(zone)
                val tomorrow = start.toLocalDate().isAfter(today)
                val time = when {
                    event.start <= nowMillis -> "now"
                    tomorrow -> start.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.getDefault()) + " " + clock(start.toInstant(), zone, use24Hour)
                    else -> clock(start.toInstant(), zone, use24Hour)
                }
                TodayRow.Event(event.key, time, event.title, tomorrow)
            }
        return allDay + timed
    }

    /** Tasks due today and overdue ones, overdue first, then by due time. */
    fun tasks(snapshot: TodaySnapshot, now: Instant, zone: ZoneId, use24Hour: Boolean = true): List<TodayRow.Task> =
        TodayRules.tasksDue(snapshot, now, zone)
            .map { task ->
                val due = Instant.ofEpochMilli(task.dueAt)
                val overdue = TodayRules.isOverdue(task, now, zone)
                val dueText = when {
                    TodayRules.isDateOnly(due, zone) -> if (overdue) "Overdue" else "Today"
                    else -> clock(due, zone, use24Hour)
                }
                TodayRow.Task(task.id, task.title, dueText, overdue)
            }
            .sortedWith(compareByDescending<TodayRow.Task> { it.overdue })

    /**
     * The moments the tile's content changes by itself (an event starts or
     * ends), within the next [horizon]: the tile gets one timeline entry per
     * moment, so a past event leaves the tile without a refresh.
     */
    fun changeTimes(snapshot: TodaySnapshot?, now: Instant, horizon: Duration = Duration.ofHours(12), max: Int = 8): List<Instant> {
        if (snapshot == null) return emptyList()
        val until = now.plus(horizon).toEpochMilli()
        val nowMillis = now.toEpochMilli()
        return snapshot.events
            .filter { !it.allDay }
            .flatMap { listOfNotNull(it.start, it.end) }
            .filter { it in (nowMillis + 1)..until }
            .distinct()
            .sorted()
            .take(max)
            .map(Instant::ofEpochMilli)
    }

    /** What Today highlights when opened from the complication or the tile (Launch.EXTRA_HIGHLIGHT): an event by its key. */
    fun eventHighlight(key: String) = "event:$key"

    /** A task by its id. */
    fun taskHighlight(id: String) = "task:$id"

    /** [text] cut to [max] characters, with an ellipsis when cut. */
    fun elide(text: String, max: Int): String =
        if (text.length <= max) text else text.take(maxOf(1, max - 1)).trimEnd() + "…"

    fun clock(instant: Instant, zone: ZoneId, use24Hour: Boolean): String =
        instant.atZone(zone).format(if (use24Hour) H24 else H12)

    private fun staleFooter(snapshot: TodaySnapshot, now: Instant, zone: ZoneId, use24Hour: Boolean): String? {
        val fetched = Instant.ofEpochMilli(snapshot.fetchedAt)
        if (Duration.between(fetched, now) < STALE_AFTER) return null
        val sameDay = fetched.atZone(zone).toLocalDate() == now.atZone(zone).toLocalDate()
        return if (sameDay) "As of ${clock(fetched, zone, use24Hour)}" else "As of ${fetched.atZone(zone).toLocalDate()}"
    }

    private val H24 = DateTimeFormatter.ofPattern("HH:mm")
    private val H12 = DateTimeFormatter.ofPattern("h:mm a", Locale.US)
}

package com.gamch1k.lifeos.wear.today

import java.time.Instant
import java.time.ZoneId
import java.time.format.TextStyle
import java.util.Locale

/** What the "Life OS next" complication points at. */
sealed interface NextItem {
    /** A timed event that hasn't started, today or tomorrow. */
    data class Event(
        val key: String,
        val title: String,
        val start: Instant,
        /** "14:30", or "Tue 09:00" when it is tomorrow. */
        val time: String,
        /** Just the clock time: "14:30". */
        val clock: String,
        /** "Tue" when it is tomorrow, else null. */
        val day: String?,
    ) : NextItem

    /** An open task, overdue or due today; [due] is "15:00", "Today" or "Overdue". */
    data class Task(val id: String, val title: String, val due: String, val overdue: Boolean) : NextItem

    /** Nothing ahead: the complication becomes a microphone. */
    data object Free : NextItem
}

/**
 * The complication types "Life OS next" answers, without the complication API.
 * Text and images only: 0.4.2 also offered ranged value and goal progress, and
 * Samsung's edge slots then drew an arc or a line around the text (P19-07).
 */
enum class NextKind {
    SHORT_TEXT,
    LONG_TEXT,
    MONOCHROMATIC_IMAGE,
    SMALL_IMAGE,
}

enum class NextIcon { EVENT, TASK, MIC }

/** One complication type's fields for one item. */
data class NextFace(
    /** The main text; always set for SHORT_TEXT and LONG_TEXT. */
    val text: String?,
    val title: String?,
    val description: String,
    val icon: NextIcon,
    /** Tapping opens Today with this item highlighted; null: tapping records. */
    val highlight: String?,
)

/** One stretch of the complication's timeline. */
data class NextSlice(val from: Instant, val until: Instant, val item: NextItem)

/** The complication's timeline: [slices] in order, then [default] once they are over. */
data class NextPlan(val slices: List<NextSlice>, val default: NextItem)

/**
 * The "Life OS next" complication's rules, without the complication API.
 *
 * - **What**: the next event (timed, not started, today or tomorrow); else
 *   the first task due (overdue first, then by due time); else nothing, shown
 *   as a microphone that records.
 * - **Over time**: one slice per event until it starts, so the watch face
 *   moves on by itself; after the last slice comes the default, the state at
 *   that moment.
 */
object NextUp {
    /** Events in one timeline; the system asks again at least every 30 minutes. */
    const val MAX_EVENTS = 4

    /** Titles in the small slots and in the long one. */
    const val SHORT_TITLE = 10
    const val LONG_TEXT = 40

    fun itemAt(snapshot: TodaySnapshot?, at: Instant, zone: ZoneId, use24Hour: Boolean = true): NextItem {
        if (snapshot == null) return NextItem.Free
        upcomingEvents(snapshot, at, zone, use24Hour, max = 1).firstOrNull()?.let { return it }
        TodayLayout.tasks(snapshot, at, zone, use24Hour).firstOrNull()?.let { return NextItem.Task(it.id, it.title, it.due, it.overdue) }
        return NextItem.Free
    }

    /** Timed events starting after [at], soonest first. */
    fun upcomingEvents(snapshot: TodaySnapshot, at: Instant, zone: ZoneId, use24Hour: Boolean = true, max: Int = MAX_EVENTS): List<NextItem.Event> {
        val today = at.atZone(zone).toLocalDate()
        val atMillis = at.toEpochMilli()
        return snapshot.events
            .filter { !it.allDay && it.start != null && it.start > atMillis }
            .sortedBy { it.start }
            .take(max)
            .map { event ->
                val start = Instant.ofEpochMilli(event.start!!)
                val local = start.atZone(zone)
                val clock = TodayLayout.clock(start, zone, use24Hour)
                val day = if (local.toLocalDate().isAfter(today)) local.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.getDefault()) else null
                NextItem.Event(event.key, event.title, start, if (day != null) "$day $clock" else clock, clock, day)
            }
    }

    /** The timeline from [now]: each next event until it starts, then the default. */
    fun plan(snapshot: TodaySnapshot?, now: Instant, zone: ZoneId, use24Hour: Boolean = true): NextPlan {
        val events = snapshot?.let { upcomingEvents(it, now, zone, use24Hour) }.orEmpty()
        val slices = mutableListOf<NextSlice>()
        var from = now
        for (event in events) {
            if (!event.start.isAfter(from)) continue // starts with the one before
            slices += NextSlice(from, event.start, event)
            from = event.start
        }
        return NextPlan(slices, itemAt(snapshot, from, zone, use24Hour))
    }

    /** The item the watch face shows at [at]: the slice holding it, else the default. */
    fun select(plan: NextPlan, at: Instant): NextItem =
        plan.slices.firstOrNull { !at.isBefore(it.from) && at.isBefore(it.until) }?.item ?: plan.default

    /** The fields [kind] shows for [item]. */
    fun face(kind: NextKind, item: NextItem): NextFace = when (item) {
        is NextItem.Event -> {
            val short = TodayLayout.elide(listOfNotNull(item.day, item.title).joinToString(" "), SHORT_TITLE)
            val description = "Next: ${item.title} at ${item.time}"
            val highlight = TodayLayout.eventHighlight(item.key)
            when (kind) {
                NextKind.LONG_TEXT -> NextFace(
                    text = TodayLayout.elide("${item.time} ${item.title}", LONG_TEXT),
                    title = if (item.day != null) "Tomorrow" else "Next",
                    description = description,
                    icon = NextIcon.EVENT,
                    highlight = highlight,
                )
                NextKind.MONOCHROMATIC_IMAGE, NextKind.SMALL_IMAGE -> NextFace(null, null, description, NextIcon.EVENT, highlight)
                NextKind.SHORT_TEXT -> NextFace(item.clock, short, description, NextIcon.EVENT, highlight)
            }
        }
        is NextItem.Task -> {
            val description = if (item.overdue) "${item.title}, overdue" else "${item.title}, due ${item.due.lowercase(Locale.ROOT)}"
            val highlight = TodayLayout.taskHighlight(item.id)
            when (kind) {
                NextKind.LONG_TEXT -> NextFace(TodayLayout.elide(item.title, LONG_TEXT), item.due, description, NextIcon.TASK, highlight)
                NextKind.MONOCHROMATIC_IMAGE, NextKind.SMALL_IMAGE -> NextFace(null, null, description, NextIcon.TASK, highlight)
                NextKind.SHORT_TEXT -> NextFace(item.due, TodayLayout.elide(item.title, SHORT_TITLE), description, NextIcon.TASK, highlight)
            }
        }
        NextItem.Free -> {
            val description = "Nothing ahead. Tap to record a voice note."
            when (kind) {
                NextKind.SHORT_TEXT -> NextFace("Free", null, description, NextIcon.MIC, null)
                NextKind.LONG_TEXT -> NextFace("Nothing ahead", null, description, NextIcon.MIC, null)
                NextKind.MONOCHROMATIC_IMAGE, NextKind.SMALL_IMAGE -> NextFace(null, null, description, NextIcon.MIC, null)
            }
        }
    }
}

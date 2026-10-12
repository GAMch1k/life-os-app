package com.gamch1k.lifeos.wear.today

import java.time.Duration
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

/** The complication types "Life OS next" answers, without the complication API. */
enum class NextKind {
    SHORT_TEXT,
    LONG_TEXT,
    RANGED_VALUE,
    GOAL_PROGRESS,
    MONOCHROMATIC_IMAGE,
    SMALL_IMAGE,
    ;

    /** The types with a value: their arc fills as the next event approaches. */
    val fills: Boolean get() = this == RANGED_VALUE || this == GOAL_PROGRESS
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
    /** RANGED_VALUE (0 to [max]) and GOAL_PROGRESS (target [max]). */
    val value: Float = 0f,
    val max: Float = NextUp.WINDOW_MINUTES.toFloat(),
)

/** One stretch of the complication's timeline. */
data class NextSlice(val from: Instant, val until: Instant, val item: NextItem, val progress: Int)

/** The complication's timeline: [slices] in order, then [default] once they are over. */
data class NextPlan(val slices: List<NextSlice>, val default: NextItem, val defaultProgress: Int)

/**
 * The "Life OS next" complication's rules, without the complication API.
 *
 * - **What**: the next event (timed, not started, today or tomorrow); else
 *   the first task due (overdue first, then by due time); else nothing, shown
 *   as a microphone that records.
 * - **The value** (RANGED_VALUE, GOAL_PROGRESS), in minutes of a 60-minute
 *   window: for an event it fills in 5-minute steps over the hour before it
 *   starts (0 until then, 60 in its last 5 minutes); a task is due already,
 *   so it is full; nothing ahead is empty. The tasks done today would be a
 *   nicer measure, but the watch only fetches the open ones.
 * - **Over time**: one slice per event until it starts (and per 5-minute
 *   step for the value types), so the watch face moves on by itself; after
 *   the last slice comes the default, the state at that moment.
 */
object NextUp {
    const val WINDOW_MINUTES = 60
    const val STEP_MINUTES = 5

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

    /**
     * The value at [at] in minutes of the window: an event's fills in
     * [STEP_MINUTES] steps over the [WINDOW_MINUTES] before it starts, a task
     * is full, nothing is empty.
     */
    fun progress(item: NextItem, at: Instant): Int = when (item) {
        is NextItem.Event -> {
            val secondsLeft = Duration.between(at, item.start).seconds
            val window = WINDOW_MINUTES * 60L
            when {
                secondsLeft > window -> 0
                secondsLeft <= 0 -> WINDOW_MINUTES
                else -> minOf(WINDOW_MINUTES, (((window - secondsLeft) / (STEP_MINUTES * 60L)).toInt() + 1) * STEP_MINUTES)
            }
        }
        is NextItem.Task -> WINDOW_MINUTES
        NextItem.Free -> 0
    }

    /** The timeline from [now]; [steps] adds the 5-minute steps of the value types. */
    fun plan(snapshot: TodaySnapshot?, now: Instant, zone: ZoneId, use24Hour: Boolean = true, steps: Boolean = false): NextPlan {
        val events = snapshot?.let { upcomingEvents(it, now, zone, use24Hour) }.orEmpty()
        val slices = mutableListOf<NextSlice>()
        var from = now
        for (event in events) {
            if (!event.start.isAfter(from)) continue // starts with the one before
            val cuts = if (steps) windowSteps(event.start).filter { it.isAfter(from) } else emptyList()
            (listOf(from) + cuts + event.start).zipWithNext { start, end ->
                slices += NextSlice(start, end, event, progress(event, start))
            }
            from = event.start
        }
        val default = itemAt(snapshot, from, zone, use24Hour)
        return NextPlan(slices, default, progress(default, from))
    }

    /** The item and value the watch face shows at [at]: the slice holding it, else the default. */
    fun select(plan: NextPlan, at: Instant): Pair<NextItem, Int> =
        plan.slices.firstOrNull { !at.isBefore(it.from) && at.isBefore(it.until) }?.let { it.item to it.progress }
            ?: (plan.default to plan.defaultProgress)

    /** The fields [kind] shows for [item] with value [progress]. */
    fun face(kind: NextKind, item: NextItem, progress: Int = 0): NextFace {
        val value = progress.coerceIn(0, WINDOW_MINUTES).toFloat()
        return when (item) {
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
                    else -> NextFace(item.clock, short, description, NextIcon.EVENT, highlight, value = value)
                }
            }
            is NextItem.Task -> {
                val description = if (item.overdue) "${item.title}, overdue" else "${item.title}, due ${item.due.lowercase(Locale.ROOT)}"
                val highlight = TodayLayout.taskHighlight(item.id)
                when (kind) {
                    NextKind.LONG_TEXT -> NextFace(TodayLayout.elide(item.title, LONG_TEXT), item.due, description, NextIcon.TASK, highlight)
                    NextKind.MONOCHROMATIC_IMAGE, NextKind.SMALL_IMAGE -> NextFace(null, null, description, NextIcon.TASK, highlight)
                    else -> NextFace(item.due, TodayLayout.elide(item.title, SHORT_TITLE), description, NextIcon.TASK, highlight, value = value)
                }
            }
            NextItem.Free -> {
                val description = "Nothing ahead. Tap to record a voice note."
                when (kind) {
                    NextKind.SHORT_TEXT -> NextFace("Free", null, description, NextIcon.MIC, null)
                    NextKind.LONG_TEXT -> NextFace("Nothing ahead", null, description, NextIcon.MIC, null)
                    else -> NextFace(null, null, description, NextIcon.MIC, null, value = value)
                }
            }
        }
    }

    /** The starts of the 5-minute steps in the hour before [start]. */
    private fun windowSteps(start: Instant): List<Instant> {
        val windowStart = start.minus(Duration.ofMinutes(WINDOW_MINUTES.toLong()))
        return (0 until WINDOW_MINUTES / STEP_MINUTES).map { windowStart.plus(Duration.ofMinutes(it.toLong() * STEP_MINUTES)) }
    }
}

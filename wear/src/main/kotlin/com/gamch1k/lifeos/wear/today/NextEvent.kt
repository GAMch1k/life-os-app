package com.gamch1k.lifeos.wear.today

import java.time.Instant
import java.time.ZoneId

/**
 * The next-event complication's content over time, without the complication
 * API: for each upcoming timed event, the interval in which it is "next"
 * (from the previous event's start, or now, until its own start).
 */
object NextEvent {
    data class Entry(
        val validFrom: Instant,
        val validUntil: Instant,
        val title: String,
        val start: Instant,
        /** "14:30", or "Tue 09:00" when it is tomorrow. */
        val time: String,
        /** Just the clock time, for the short complication. */
        val shortTime: String,
        val tomorrow: Boolean,
    )

    fun timeline(snapshot: TodaySnapshot?, now: Instant, zone: ZoneId, use24Hour: Boolean = true, max: Int = 10): List<Entry> {
        if (snapshot == null) return emptyList()
        val today = now.atZone(zone).toLocalDate()
        var from = now
        return snapshot.events
            .filter { !it.allDay && it.start != null && it.start > now.toEpochMilli() }
            .sortedBy { it.start }
            .take(max)
            .map { event ->
                val start = Instant.ofEpochMilli(event.start!!)
                val tomorrow = start.atZone(zone).toLocalDate().isAfter(today)
                val clock = TodayLayout.clock(start, zone, use24Hour)
                val day = start.atZone(zone).dayOfWeek.getDisplayName(java.time.format.TextStyle.SHORT, java.util.Locale.getDefault())
                Entry(from, start, event.title, start, if (tomorrow) "$day $clock" else clock, clock, tomorrow).also { from = start }
            }
            .filter { it.validUntil.isAfter(it.validFrom) }
    }
}

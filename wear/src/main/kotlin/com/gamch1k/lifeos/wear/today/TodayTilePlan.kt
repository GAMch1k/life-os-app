package com.gamch1k.lifeos.wear.today

import java.time.Instant
import java.time.ZoneId

/**
 * The Today tile's timeline, without ProtoLayout: which [TodayTileModel] the
 * tile shows from when to when.
 *
 * The tile renderer picks the entry whose validity holds now (the shortest if
 * several do) and falls back to the one entry without validity. An entry with
 * a start but no end has an end of 0 on the wire, so it is never valid and is
 * skipped even by the renderer's "closest entry" fallback: 0.4.1 built its
 * last (often only) entry that way, so the tile showed nothing, pure black,
 * whenever no event started or ended in the next 12 hours (an empty cache, a
 * day of tasks only, the evening). Here every timed entry is closed, and the
 * last state is the default entry, so something is valid at every instant.
 */
object TodayTilePlan {
    data class Entry(
        /** Null with [until] null: the default entry, shown when no timed entry holds. */
        val from: Instant?,
        val until: Instant?,
        val model: TodayTileModel,
    ) {
        val isDefault get() = from == null && until == null
    }

    /**
     * The tile from [now] on: one timed entry per stretch between the moments
     * the content changes by itself (an event starts or ends), the first one
     * reaching back to the epoch (a renderer clock a little behind still finds
     * it), then the default entry with the state after the last change.
     */
    fun plan(
        snapshot: TodaySnapshot?,
        now: Instant,
        zone: ZoneId,
        maxRows: Int,
        use24Hour: Boolean = true,
        error: String? = null,
    ): List<Entry> {
        val changes = TodayLayout.changeTimes(snapshot, now)
        val starts = listOf(Instant.EPOCH) + changes
        val timed = changes.mapIndexed { index, until ->
            val from = starts[index]
            val at = if (index == 0) now else from
            Entry(from, until, TodayLayout.model(snapshot, at, zone, maxRows, use24Hour, error))
        }
        val last = changes.lastOrNull() ?: now
        return timed + Entry(null, null, TodayLayout.model(snapshot, last, zone, maxRows, use24Hour, error))
    }

    /**
     * The entry the renderer shows at [at], by its rule: the shortest timed
     * entry valid then (start ≤ at < end, end > start), else the default.
     */
    fun select(entries: List<Entry>, at: Instant): Entry? {
        val millis = at.toEpochMilli()
        return entries
            .filter { !it.isDefault }
            .filter { entry ->
                val start = entry.from?.toEpochMilli() ?: 0
                val end = entry.until?.toEpochMilli() ?: 0
                end > start && millis in start until end
            }
            .minByOrNull { (it.until?.toEpochMilli() ?: 0) - (it.from?.toEpochMilli() ?: 0) }
            ?: entries.firstOrNull { it.isDefault }
    }
}

package com.gamch1k.lifeos.wear.tile

import androidx.wear.protolayout.LayoutElementBuilders
import androidx.wear.protolayout.TimelineBuilders
import com.gamch1k.lifeos.wear.net.EventDto
import com.gamch1k.lifeos.wear.net.TaskDto
import com.gamch1k.lifeos.wear.today.TodayLayout
import com.gamch1k.lifeos.wear.today.TodayParser
import com.gamch1k.lifeos.wear.today.TodayTilePlan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.util.Locale

/** The real ProtoLayout timeline the tile sends, read back the way the tile renderer picks entries. */
class TodayTimelineTest {
    private val berlin = ZoneId.of("Europe/Berlin")
    private val now = Instant.parse("2026-10-12T08:00:00Z")

    @Before
    fun englishDayNames() {
        Locale.setDefault(Locale.US)
    }

    private fun text(label: String) = LayoutElementBuilders.Text.Builder().setText(label).build()

    private fun timeline(plan: List<TodayTilePlan.Entry>) =
        todayTimeline(plan) { model -> text((model.rows.map { it.label } + listOfNotNull(model.emptyText)).joinToString(" | ")) }

    /** The tile renderer's choice (TilesTimelineCacheInternal): the shortest valid entry, else the one without validity. */
    private fun shown(timeline: TimelineBuilders.Timeline, at: Instant): String? {
        val millis = at.toEpochMilli()
        val valid = timeline.timelineEntries.filter { entry ->
            val validity = entry.validity ?: return@filter false
            validity.endMillis > validity.startMillis && millis >= validity.startMillis && millis < validity.endMillis
        }
        val entry = valid.minByOrNull { it.validity!!.endMillis - it.validity!!.startMillis }
            ?: timeline.timelineEntries.firstOrNull { it.validity == null }
        return (entry?.layout?.root as? LayoutElementBuilders.Text)?.text?.value
    }

    private fun assertRenderable(timeline: TimelineBuilders.Timeline) {
        assertEquals(1, timeline.timelineEntries.count { it.validity == null })
        timeline.timelineEntries.mapNotNull { it.validity }.forEach { assertTrue(it.endMillis > it.startMillis) }
        var at = now.minus(Duration.ofHours(1))
        while (at.isBefore(now.plus(Duration.ofHours(36)))) {
            assertNotNull("nothing shown at $at", shown(timeline, at))
            at = at.plus(Duration.ofMinutes(5))
        }
    }

    @Test
    fun `an empty cache gives one entry without validity`() {
        val timeline = timeline(TodayTilePlan.plan(null, now, berlin, 3))
        assertEquals(1, timeline.timelineEntries.size)
        assertRenderable(timeline)
        assertEquals(TodayLayout.NOT_LOADED, shown(timeline, now))
    }

    @Test
    fun `a full day's entries are closed and the default comes last`() {
        val day = TodayParser.snapshot(
            events = listOf(
                EventDto(id = "e1", title = "Dentist", startAt = "2026-10-12T12:30:00Z", endAt = "2026-10-12T13:30:00Z"),
                EventDto(id = "e2", title = "Dinner", startAt = "2026-10-12T17:00:00Z", endAt = "2026-10-12T19:00:00Z"),
            ),
            tasks = listOf(TaskDto("t1", "Pay rent", dueAt = "2026-10-12T00:00:00+02:00")),
            fetchedAt = now,
            zone = berlin,
        )
        val timeline = timeline(TodayTilePlan.plan(day, now, berlin, 3))
        assertEquals(5, timeline.timelineEntries.size) // 4 changes, then the default
        assertRenderable(timeline)
        assertEquals("14:30 Dentist | 19:00 Dinner | Pay rent", shown(timeline, now))
        assertEquals("now Dentist | 19:00 Dinner | Pay rent", shown(timeline, Instant.parse("2026-10-12T12:30:00Z")))
        assertEquals("Pay rent", shown(timeline, Instant.parse("2026-10-12T20:00:00Z")))
    }
}

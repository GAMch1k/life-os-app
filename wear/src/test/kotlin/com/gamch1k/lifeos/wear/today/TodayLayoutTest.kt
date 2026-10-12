package com.gamch1k.lifeos.wear.today

import com.gamch1k.lifeos.wear.net.EventDto
import com.gamch1k.lifeos.wear.net.OccurrenceDto
import com.gamch1k.lifeos.wear.net.TaskDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.Instant
import java.time.ZoneId
import java.util.Locale

class TodayLayoutTest {
    private val berlin = ZoneId.of("Europe/Berlin")

    /** Monday 12 October 2026, 10:00 in Berlin (CEST, UTC+2). */
    private val now = Instant.parse("2026-10-12T08:00:00Z")

    @Before
    fun englishDayNames() {
        Locale.setDefault(Locale.US)
    }

    private fun timed(id: String, title: String, start: String, end: String) =
        EventDto(id = id, title = title, startAt = start, endAt = end)

    private fun snapshot(events: List<EventDto> = emptyList(), tasks: List<TaskDto> = emptyList(), fetchedAt: Instant = now) =
        TodayParser.snapshot(events, tasks, fetchedAt, berlin)

    @Test
    fun `the API's events and tasks become the cached day`() {
        val day = snapshot(
            events = listOf(
                timed("e1", "Dentist", "2026-10-12T14:30:00+02:00", "2026-10-12T15:30:00+02:00"),
                EventDto(id = "e2", title = "Gone", startAt = "2026-10-12T12:00:00Z", endAt = "2026-10-12T13:00:00Z", status = "cancelled"),
                EventDto(id = "e3", title = "Birthday", allDay = true, startDate = "2026-10-12", endDate = "2026-10-12"),
                EventDto(id = "e4", title = "Gym", startAt = "2026-10-12T16:00:00Z", endAt = "2026-10-12T17:00:00Z", occurrence = OccurrenceDto("2026-10-12T16:00:00Z")),
                EventDto(id = "e4", title = "Gym", startAt = "2026-10-12T16:00:00Z", endAt = "2026-10-12T17:00:00Z", occurrence = OccurrenceDto("2026-10-12T16:00:00Z")),
                EventDto(id = "e5", title = "Broken", startAt = "yesterday"),
            ),
            tasks = listOf(
                TaskDto("t2", "Call   mom\n", dueAt = "2026-10-12T15:00:00Z"),
                TaskDto("t1", "Pay rent", dueAt = "2026-10-06T00:00:00+02:00"),
                TaskDto("t3", "Done already", dueAt = "2026-10-12T09:00:00Z", completed = true),
                TaskDto("t4", "Someday"),
            ),
        )
        assertEquals("2026-10-12", day.day)
        assertEquals(listOf("e1", "e3", "e4#2026-10-12T16:00:00Z"), day.events.map { it.key })
        assertEquals(Instant.parse("2026-10-12T12:30:00Z").toEpochMilli(), day.events[0].start)
        assertEquals(listOf("Pay rent", "Call mom"), day.tasks.map { it.title })
    }

    @Test
    fun `date-only tasks follow the Tasks service's rule`() {
        assertTrue(TodayRules.isDateOnly(Instant.parse("2026-10-11T22:00:00Z"), berlin)) // Berlin midnight
        assertTrue(TodayRules.isDateOnly(Instant.parse("2026-10-12T00:00:00Z"), berlin)) // UTC midnight, same day in Berlin
        assertFalse(TodayRules.isDateOnly(Instant.parse("2026-10-12T00:00:00Z"), ZoneId.of("America/New_York"))) // the evening before there
        assertFalse(TodayRules.isDateOnly(Instant.parse("2026-10-12T13:00:00Z"), berlin))
        assertEquals("2026-10-12", TodayRules.dueDate(Instant.parse("2026-10-12T00:00:00Z"), berlin).toString())
    }

    @Test
    fun `the tile shows events ahead first, then tasks, in three rows`() {
        val day = snapshot(
            events = listOf(
                timed("past", "Standup", "2026-10-12T07:00:00Z", "2026-10-12T07:15:00Z"),
                timed("now", "Workshop", "2026-10-12T07:30:00Z", "2026-10-12T09:00:00Z"),
                timed("e1", "Dentist", "2026-10-12T12:30:00Z", "2026-10-12T13:30:00Z"),
                timed("e2", "Dinner", "2026-10-12T17:00:00Z", "2026-10-12T19:00:00Z"),
                timed("tmrw", "Flight", "2026-10-13T06:00:00Z", "2026-10-13T08:00:00Z"),
            ),
            tasks = listOf(
                TaskDto("t1", "Pay rent", dueAt = "2026-10-06T00:00:00+02:00"),
                TaskDto("t2", "Call mom", dueAt = "2026-10-12T13:00:00Z"),
            ),
        )
        val model = TodayLayout.model(day, now, berlin, maxRows = 3)
        assertEquals(listOf("now Workshop", "14:30 Dentist", "! Pay rent"), model.rows.map { it.label })
        assertEquals("+3 more", model.footer)
        assertNull(model.emptyText)

        val all = TodayLayout.upcomingEvents(day, now, berlin)
        assertEquals(listOf("now", "14:30", "19:00", "Tue 08:00"), all.map { it.time })
        assertTrue(all.last().tomorrow)

        val tasks = TodayLayout.tasks(day, now, berlin)
        assertEquals(listOf("Pay rent" to "Overdue", "Call mom" to "15:00"), tasks.map { it.title to it.due })
        assertEquals(listOf(true, false), tasks.map { it.overdue })
    }

    @Test
    fun `rows go where there is something to show`() {
        assertEquals(2 to 1, TodayLayout.split(events = 5, tasks = 5, maxRows = 3))
        assertEquals(3 to 0, TodayLayout.split(events = 5, tasks = 0, maxRows = 3))
        assertEquals(0 to 3, TodayLayout.split(events = 0, tasks = 5, maxRows = 3))
        assertEquals(1 to 2, TodayLayout.split(events = 1, tasks = 5, maxRows = 3))
        assertEquals(1 to 1, TodayLayout.split(events = 4, tasks = 4, maxRows = 2))
        assertEquals(3, TodayLayout.maxRows(screenHeightDp = 240))
        assertEquals(2, TodayLayout.maxRows(screenHeightDp = 192))
    }

    @Test
    fun `all-day events of today are shown, tomorrow's are not`() {
        val day = snapshot(
            events = listOf(
                EventDto(id = "a", title = "Birthday", allDay = true, startDate = "2026-10-12", endDate = "2026-10-12"),
                EventDto(id = "b", title = "Trip", allDay = true, startDate = "2026-10-10", endDate = "2026-10-14"),
                EventDto(id = "c", title = "Holiday", allDay = true, startDate = "2026-10-13", endDate = "2026-10-13"),
            ),
        )
        assertEquals(listOf("All day Birthday", "All day Trip"), TodayLayout.model(day, now, berlin, 3).rows.map { it.label })
    }

    @Test
    fun `empty, missing and old data say so`() {
        assertEquals("Open Life OS to load Today", TodayLayout.model(null, now, berlin, 3).emptyText)
        assertEquals("Phone not reachable", TodayLayout.model(null, now, berlin, 3, error = "Phone not reachable").footer)
        val empty = TodayLayout.model(snapshot(), now, berlin, 3)
        assertEquals("Nothing left today", empty.emptyText)
        assertNull(empty.footer)
        val old = TodayLayout.model(snapshot(fetchedAt = now.minusSeconds(3 * 3600)), now, berlin, 3)
        assertEquals("As of 07:00", old.footer)
        val yesterday = snapshot(tasks = listOf(TaskDto("t", "Tomorrow's task", dueAt = "2026-10-12T10:00:00Z")), fetchedAt = now.minusSeconds(86_400))
        assertEquals("As of 2026-10-11", TodayLayout.model(yesterday, now, berlin, 3).footer)
    }

    @Test
    fun `a task due tomorrow in a day-old snapshot is not shown before its day`() {
        val fetched = Instant.parse("2026-10-11T08:00:00Z")
        val day = TodayParser.snapshot(emptyList(), listOf(TaskDto("t", "Later", dueAt = "2026-10-13T08:00:00Z")), fetched, berlin)
        assertTrue(TodayLayout.tasks(day, now, berlin).isEmpty())
    }

    @Test
    fun `12-hour clocks are respected`() {
        val day = snapshot(events = listOf(timed("e", "Dentist", "2026-10-12T12:30:00Z", "2026-10-12T13:30:00Z")))
        assertEquals("2:30 PM Dentist", TodayLayout.model(day, now, berlin, 3, use24Hour = false).rows.single().label)
    }

    @Test
    fun `the tile changes at each start and end ahead`() {
        val day = snapshot(
            events = listOf(
                timed("e1", "Dentist", "2026-10-12T12:30:00Z", "2026-10-12T13:30:00Z"),
                timed("e2", "Walk", "2026-10-12T13:30:00Z", "2026-10-12T14:00:00Z"),
                timed("past", "Standup", "2026-10-12T07:00:00Z", "2026-10-12T07:15:00Z"),
                timed("far", "Flight", "2026-10-13T06:00:00Z", "2026-10-13T08:00:00Z"),
            ),
        )
        assertEquals(
            listOf("2026-10-12T12:30:00Z", "2026-10-12T13:30:00Z", "2026-10-12T14:00:00Z").map(Instant::parse),
            TodayLayout.changeTimes(day, now),
        )
        // At 14:30 local the dentist is in progress ("now"); after 15:30 it is gone.
        assertEquals("now Dentist", TodayLayout.model(day, Instant.parse("2026-10-12T12:30:00Z"), berlin, 3).rows.first().label)
        assertEquals("now Walk", TodayLayout.model(day, Instant.parse("2026-10-12T13:30:00Z"), berlin, 3).rows.first().label)
    }

    @Test
    fun `the complication shows each next event until it starts`() {
        val day = snapshot(
            events = listOf(
                timed("e2", "Dinner", "2026-10-12T17:00:00Z", "2026-10-12T19:00:00Z"),
                timed("e1", "Dentist", "2026-10-12T12:30:00Z", "2026-10-12T13:30:00Z"),
                timed("same", "Call", "2026-10-12T17:00:00Z", "2026-10-12T17:30:00Z"),
                timed("now", "Workshop", "2026-10-12T07:30:00Z", "2026-10-12T09:00:00Z"),
                EventDto(id = "a", title = "Birthday", allDay = true, startDate = "2026-10-12", endDate = "2026-10-12"),
                timed("tmrw", "Flight", "2026-10-13T06:00:00Z", "2026-10-13T08:00:00Z"),
            ),
        )
        val timeline = NextEvent.timeline(day, now, berlin)
        assertEquals(listOf("Dentist", "Dinner", "Flight"), timeline.map { it.title })
        assertEquals(now, timeline[0].validFrom)
        assertEquals(Instant.parse("2026-10-12T12:30:00Z"), timeline[0].validUntil)
        assertEquals(timeline[0].validUntil, timeline[1].validFrom)
        assertEquals("14:30", timeline[0].time)
        assertEquals("Tue 08:00", timeline[2].time)
        assertEquals("08:00", timeline[2].shortTime)
        assertTrue(timeline[2].tomorrow)
        assertTrue(NextEvent.timeline(null, now, berlin).isEmpty())
    }
}

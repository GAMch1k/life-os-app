package com.gamch1k.lifeos.core.today

import com.gamch1k.lifeos.core.api.CalendarEvent
import com.gamch1k.lifeos.core.api.EventSource
import com.gamch1k.lifeos.core.api.Recurrence
import com.gamch1k.lifeos.core.api.Task
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

class TodayBuilderTest {
    private val berlin = ZoneId.of("Europe/Berlin")

    // Monday 12 October 2026, 14:00 in Berlin (CEST, UTC+2).
    private val now = Instant.parse("2026-10-12T12:00:00Z")

    private fun snapshot(tasks: List<Task> = emptyList(), events: List<CalendarEvent> = emptyList()) =
        TodaySnapshot(fetchedAt = now.toString(), timeZone = berlin.id, tasks = tasks, events = events)

    @Test
    fun `the ranges Home asks for are local days`() {
        val (from, to) = TodayBuilder.eventRange(now, berlin)
        assertEquals(Instant.parse("2026-10-11T22:00:00Z"), from)
        assertEquals(Instant.parse("2026-10-13T22:00:00Z"), to)
        assertEquals(Instant.parse("2026-10-12T21:59:59.999Z"), TodayBuilder.endOfToday(now, berlin))
    }

    @Test
    fun `tasks split into overdue and due today, oldest first`() {
        val view = TodayBuilder.build(
            snapshot(
                tasks = listOf(
                    Task("t1", "Call mom", dueAt = "2026-10-12T13:00:00Z"),
                    Task("t2", "Pay rent", dueAt = "2026-10-05T22:00:00Z", recurrence = Recurrence("monthly")),
                    Task("t3", "Renew passport", dueAt = "2026-10-11T22:00:00Z"),
                    Task("t4", "Take pills", dueAt = "2026-10-12T06:30:00Z"),
                    Task("t5", "Tomorrow", dueAt = "2026-10-13T08:00:00Z"),
                    Task("t6", "Done already", dueAt = "2026-10-12T08:00:00Z", completed = true),
                    Task("t7", "Undated"),
                ),
            ),
            now,
            berlin,
        )
        assertEquals(listOf("t2"), view.overdue.map { it.id })
        assertEquals("Overdue since Tue 6 Oct", view.overdue.single().detail)
        assertTrue(view.overdue.single().recurring)
        // Today: the date-only one (local midnight) first, then by time; a time
        // earlier today is still "today", as on the Dashboard's Home.
        assertEquals(listOf("t3", "t4", "t1"), view.dueToday.map { it.id })
        assertEquals(listOf("Today", "08:30", "15:00"), view.dueToday.map { it.detail })
    }

    @Test
    fun `a UTC midnight on the same local day is date-only`() {
        assertTrue(TodayBuilder.isDateOnly(Instant.parse("2026-10-12T00:00:00Z"), berlin))
        assertTrue(TodayBuilder.isDateOnly(Instant.parse("2026-10-11T22:00:00Z"), berlin))
        assertFalse(TodayBuilder.isDateOnly(Instant.parse("2026-10-12T00:00:00Z"), ZoneId.of("America/New_York")))
        assertFalse(TodayBuilder.isDateOnly(Instant.parse("2026-10-12T09:00:00Z"), berlin))
    }

    @Test
    fun `hidden tasks (just completed) are left out`() {
        val view = TodayBuilder.build(snapshot(tasks = listOf(Task("t1", "A", dueAt = "2026-10-12T13:00:00Z"))), now, berlin, hiddenTaskIds = setOf("t1"))
        assertTrue(view.dueToday.isEmpty())
    }

    @Test
    fun `events are listed under each local day they overlap`() {
        val events = listOf(
            CalendarEvent("e1", "Dentist", location = "Dr. Weber", startAt = "2026-10-12T07:00:00Z", endAt = "2026-10-12T08:00:00Z"),
            CalendarEvent("e2", "Party", startAt = "2026-10-12T20:00:00Z", endAt = "2026-10-12T23:30:00Z"),
            CalendarEvent("e3", "Papier", allDay = true, startDate = "2026-10-13", endDate = "2026-10-13", source = EventSource("external", name = "ZAW")),
            CalendarEvent("e4", "Trip", allDay = true, startDate = "2026-10-12", endDate = "2026-10-14"),
            CalendarEvent("e5", "Skipped", startAt = "2026-10-12T09:00:00Z", endAt = "2026-10-12T10:00:00Z", status = "cancelled"),
        )
        val view = TodayBuilder.build(snapshot(events = events), now, berlin)
        assertEquals(listOf("Trip", "Dentist", "Party"), view.eventsToday.map { it.title })
        assertEquals(listOf("All day · until Wed 14 Oct", "09:00–10:00", "22:00–Tue 13 Oct 01:30"), view.eventsToday.map { it.time })
        assertEquals("Dr. Weber", view.eventsToday[1].location)
        // All three start at (or before) tomorrow's midnight, so they sort by title.
        assertEquals(listOf("Papier", "Party", "Trip"), view.eventsTomorrow.map { it.title })
        val party = view.eventsTomorrow.first { it.title == "Party" }
        assertEquals("until 01:30", party.time)
        assertEquals(LocalDate.of(2026, 10, 13), party.date)
        assertEquals("ZAW", view.eventsTomorrow.first { it.title == "Papier" }.sourceName)
    }
}

package com.gamch1k.lifeos.wear.today

import com.gamch1k.lifeos.wear.net.EventDto
import com.gamch1k.lifeos.wear.net.TaskDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.util.Locale

class TodayTilePlanTest {
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

    private val fullDay = snapshot(
        events = listOf(
            timed("ws", "Workshop", "2026-10-12T07:30:00Z", "2026-10-12T09:00:00Z"),
            timed("e1", "Dentist", "2026-10-12T12:30:00Z", "2026-10-12T13:30:00Z"),
            timed("e2", "Walk", "2026-10-12T13:30:00Z", "2026-10-12T14:00:00Z"),
            timed("tmrw", "Flight", "2026-10-13T06:00:00Z", "2026-10-13T08:00:00Z"),
        ),
        tasks = listOf(TaskDto("t1", "Pay rent", dueAt = "2026-10-06T00:00:00+02:00")),
    )

    private fun labels(entry: TodayTilePlan.Entry?) = entry!!.model.rows.map { it.label }

    /** One default entry; every other entry has a start and a later end. */
    private fun assertWellFormed(plan: List<TodayTilePlan.Entry>) {
        assertEquals(1, plan.count { it.isDefault })
        plan.filterNot { it.isDefault }.forEach { entry ->
            assertNotNull(entry.from)
            assertNotNull(entry.until)
            assertTrue("$entry ends before it starts", entry.until!!.isAfter(entry.from))
        }
    }

    /** Every minute from an hour before [now] (a renderer clock behind) to a day and a half after. */
    private fun assertAlwaysShowsSomething(plan: List<TodayTilePlan.Entry>) {
        var at = now.minus(Duration.ofHours(1))
        while (at.isBefore(now.plus(Duration.ofHours(36)))) {
            assertNotNull("nothing to show at $at", TodayTilePlan.select(plan, at))
            at = at.plus(Duration.ofMinutes(1))
        }
    }

    @Test
    fun `an entry with a start and no end is never shown, which made 0_4_1's tile black`() {
        // 0.4.1 built its last entry with setStartMillis only; on the wire the end is 0.
        val v041 = listOf(TodayTilePlan.Entry(Instant.EPOCH, null, TodayLayout.model(fullDay, now, berlin, 3)))
        assertNull(TodayTilePlan.select(v041, now))
    }

    @Test
    fun `without a cached day the tile asks to open the app, at every instant`() {
        val plan = TodayTilePlan.plan(null, now, berlin, 3)
        assertWellFormed(plan)
        assertEquals(1, plan.size)
        assertEquals(TodayLayout.NOT_LOADED, plan.single().model.emptyText)
        assertAlwaysShowsSomething(plan)

        val failed = TodayTilePlan.plan(null, now, berlin, 3, error = "Phone not connected")
        assertEquals("Phone not connected", failed.single().model.footer)
    }

    @Test
    fun `an empty day says so, at every instant`() {
        val plan = TodayTilePlan.plan(snapshot(), now, berlin, 3)
        assertWellFormed(plan)
        assertEquals(listOf(true), plan.map { it.isDefault })
        assertEquals("Nothing left today", plan.single().model.emptyText)
        assertAlwaysShowsSomething(plan)
    }

    @Test
    fun `with every event over, nothing would be valid now, so the default shows the tasks`() {
        val day = snapshot(
            events = listOf(timed("past", "Standup", "2026-10-12T06:00:00Z", "2026-10-12T06:15:00Z")),
            tasks = listOf(TaskDto("t1", "Pay rent", dueAt = "2026-10-12T00:00:00+02:00")),
        )
        val plan = TodayTilePlan.plan(day, now, berlin, 3)
        assertWellFormed(plan)
        assertEquals(1, plan.size)
        assertEquals(listOf("Pay rent"), labels(TodayTilePlan.select(plan, now)))
        assertAlwaysShowsSomething(plan)
    }

    @Test
    fun `yesterday's day shows what is still due, with its age`() {
        val yesterday = Instant.parse("2026-10-11T08:00:00Z")
        val day = snapshot(
            events = listOf(timed("old", "Yesterday's lunch", "2026-10-11T10:00:00Z", "2026-10-11T11:00:00Z")),
            tasks = listOf(TaskDto("t1", "Old task", dueAt = "2026-10-11T00:00:00+02:00")),
            fetchedAt = yesterday,
        )
        val plan = TodayTilePlan.plan(day, now, berlin, 3)
        assertWellFormed(plan)
        assertEquals(1, plan.size)
        assertEquals(listOf("! Old task"), labels(plan.single()))
        assertEquals("As of 2026-10-11", plan.single().model.footer)
        assertAlwaysShowsSomething(plan)
    }

    @Test
    fun `a full day changes at each start and end, then rests on the default`() {
        val plan = TodayTilePlan.plan(fullDay, now, berlin, 3)
        assertWellFormed(plan)
        assertEquals(
            listOf(null, "2026-10-12T09:00:00Z", "2026-10-12T12:30:00Z", "2026-10-12T13:30:00Z", null),
            plan.map { it.from?.takeIf { from -> from != Instant.EPOCH }?.toString() },
        )
        assertEquals(Instant.EPOCH, plan.first().from)
        assertTrue(plan.last().isDefault)

        assertEquals(listOf("now Workshop", "14:30 Dentist", "! Pay rent"), labels(TodayTilePlan.select(plan, now)))
        // A renderer clock behind still finds the first entry.
        assertEquals(listOf("now Workshop", "14:30 Dentist", "! Pay rent"), labels(TodayTilePlan.select(plan, now.minusSeconds(600))))
        assertEquals(listOf("14:30 Dentist", "15:30 Walk", "! Pay rent"), labels(TodayTilePlan.select(plan, Instant.parse("2026-10-12T09:00:00Z"))))
        assertEquals(listOf("now Dentist", "15:30 Walk", "! Pay rent"), labels(TodayTilePlan.select(plan, Instant.parse("2026-10-12T12:45:00Z"))))
        assertEquals(listOf("now Walk", "Tue 08:00 Flight", "! Pay rent"), labels(TodayTilePlan.select(plan, Instant.parse("2026-10-12T13:30:00Z"))))
        // After the last change, until the next request: the default.
        val evening = TodayTilePlan.select(plan, Instant.parse("2026-10-12T19:00:00Z"))
        assertTrue(evening!!.isDefault)
        assertEquals(listOf("Tue 08:00 Flight", "! Pay rent"), labels(evening))
        assertAlwaysShowsSomething(plan)

        // At every minute of the next 12 hours the tile shows what the rules say for that minute.
        var at = now
        while (at.isBefore(now.plus(Duration.ofHours(12)))) {
            assertEquals("at $at", TodayLayout.model(fullDay, at, berlin, 3).rows, TodayTilePlan.select(plan, at)!!.model.rows)
            at = at.plus(Duration.ofMinutes(1))
        }
    }
}

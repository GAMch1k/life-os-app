package com.gamch1k.lifeos.wear.today

import com.gamch1k.lifeos.wear.net.EventDto
import com.gamch1k.lifeos.wear.net.TaskDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.util.Locale

class NextUpTest {
    private val berlin = ZoneId.of("Europe/Berlin")

    /** Monday 12 October 2026, 10:00 in Berlin (CEST, UTC+2). */
    private val now = Instant.parse("2026-10-12T08:00:00Z")

    @Before
    fun englishDayNames() {
        Locale.setDefault(Locale.US)
    }

    private fun timed(id: String, title: String, start: String, end: String) =
        EventDto(id = id, title = title, startAt = start, endAt = end)

    private fun snapshot(events: List<EventDto> = emptyList(), tasks: List<TaskDto> = emptyList()) =
        TodayParser.snapshot(events, tasks, now, berlin)

    private val payRent = TaskDto("t1", "Pay rent", dueAt = "2026-10-06T00:00:00+02:00") // overdue, date-only
    private val callMom = TaskDto("t2", "Call mom", dueAt = "2026-10-12T13:00:00Z") // 15:00 today
    private val water = TaskDto("t3", "Water plants", dueAt = "2026-10-12T00:00:00+02:00") // today, date-only

    private val day = snapshot(
        events = listOf(
            timed("ws", "Workshop", "2026-10-12T07:30:00Z", "2026-10-12T09:00:00Z"), // in progress: not next
            timed("e1", "Dentist", "2026-10-12T12:30:00Z", "2026-10-12T13:30:00Z"),
            timed("e2", "Dinner", "2026-10-12T17:00:00Z", "2026-10-12T19:00:00Z"),
            timed("same", "Call", "2026-10-12T17:00:00Z", "2026-10-12T17:30:00Z"),
            EventDto(id = "a", title = "Birthday", allDay = true, startDate = "2026-10-12", endDate = "2026-10-12"),
            timed("tmrw", "Flight", "2026-10-13T06:00:00Z", "2026-10-13T08:00:00Z"),
        ),
        tasks = listOf(callMom, payRent),
    )

    private val dentist = NextItem.Event("e1", "Dentist", Instant.parse("2026-10-12T12:30:00Z"), "14:30", "14:30", day = null)

    @Test
    fun `the next event comes first, with its time`() {
        assertEquals(dentist, NextUp.itemAt(day, now, berlin))
        val flight = NextUp.itemAt(day, Instant.parse("2026-10-12T18:00:00Z"), berlin) as NextItem.Event
        assertEquals("Tue 08:00", flight.time)
        assertEquals("08:00", flight.clock)
        assertEquals("Tue", flight.day)
        assertEquals("2:30 PM", (NextUp.itemAt(day, now, berlin, use24Hour = false) as NextItem.Event).clock)
    }

    @Test
    fun `without an event ahead, the next task, overdue first, then by due time`() {
        assertEquals(NextItem.Task("t1", "Pay rent", "Overdue", overdue = true), NextUp.itemAt(snapshot(tasks = listOf(callMom, payRent)), now, berlin))
        assertEquals(NextItem.Task("t2", "Call mom", "15:00", overdue = false), NextUp.itemAt(snapshot(tasks = listOf(callMom)), now, berlin))
        assertEquals(NextItem.Task("t3", "Water plants", "Today", overdue = false), NextUp.itemAt(snapshot(tasks = listOf(water)), now, berlin))
        // After the day's events, the tasks.
        assertEquals("t1", (NextUp.itemAt(day, Instant.parse("2026-10-13T07:00:00Z"), berlin) as NextItem.Task).id)
    }

    @Test
    fun `nothing ahead, or nothing loaded, is the microphone`() {
        assertEquals(NextItem.Free, NextUp.itemAt(null, now, berlin))
        assertEquals(NextItem.Free, NextUp.itemAt(snapshot(), now, berlin))
        val allDayOnly = snapshot(events = listOf(EventDto(id = "a", title = "Birthday", allDay = true, startDate = "2026-10-12", endDate = "2026-10-12")))
        assertEquals(NextItem.Free, NextUp.itemAt(allDayOnly, now, berlin))
    }

    @Test
    fun `each type shows an event`() {
        val description = "Next: Dentist at 14:30"
        val highlight = "event:e1"
        assertEquals(NextFace("14:30", "Dentist", description, NextIcon.EVENT, highlight, value = 40f), NextUp.face(NextKind.SHORT_TEXT, dentist, 40))
        assertEquals(NextFace("14:30 Dentist", "Next", description, NextIcon.EVENT, highlight), NextUp.face(NextKind.LONG_TEXT, dentist, 40))
        assertEquals(NextFace("14:30", "Dentist", description, NextIcon.EVENT, highlight, value = 40f, max = 60f), NextUp.face(NextKind.RANGED_VALUE, dentist, 40))
        assertEquals(NextFace("14:30", "Dentist", description, NextIcon.EVENT, highlight, value = 40f, max = 60f), NextUp.face(NextKind.GOAL_PROGRESS, dentist, 40))
        assertEquals(NextFace(null, null, description, NextIcon.EVENT, highlight), NextUp.face(NextKind.MONOCHROMATIC_IMAGE, dentist, 40))
        assertEquals(NextFace(null, null, description, NextIcon.EVENT, highlight), NextUp.face(NextKind.SMALL_IMAGE, dentist, 40))
    }

    @Test
    fun `tomorrow's event and long titles stay short`() {
        val flight = NextItem.Event("f", "Flight", Instant.parse("2026-10-13T06:00:00Z"), "Tue 08:00", "08:00", day = "Tue")
        assertEquals("08:00", NextUp.face(NextKind.SHORT_TEXT, flight).text)
        assertEquals("Tue Flight", NextUp.face(NextKind.SHORT_TEXT, flight).title)
        assertEquals("Tue 08:00 Flight", NextUp.face(NextKind.LONG_TEXT, flight).text)
        assertEquals("Tomorrow", NextUp.face(NextKind.LONG_TEXT, flight).title)
        assertEquals("Next: Flight at Tue 08:00", NextUp.face(NextKind.RANGED_VALUE, flight).description)

        val long = dentist.copy(title = "Quarterly planning with the whole team and the board")
        assertEquals("Quarterly…", NextUp.face(NextKind.SHORT_TEXT, long).title)
        assertEquals("Quarterly…", NextUp.face(NextKind.RANGED_VALUE, long).title)
        val longText = NextUp.face(NextKind.LONG_TEXT, long).text!!
        assertEquals(NextUp.LONG_TEXT, longText.length)
        assertTrue(longText.startsWith("14:30 Quarterly planning") && longText.endsWith("…"))
        // The full title stays in the description, for the screen reader.
        assertEquals("Next: ${long.title} at 14:30", NextUp.face(NextKind.SHORT_TEXT, long).description)
    }

    @Test
    fun `each type shows a task, full arc and a check`() {
        val overdue = NextItem.Task("t1", "Pay rent", "Overdue", overdue = true)
        val highlight = "task:t1"
        assertEquals(NextFace("Overdue", "Pay rent", "Pay rent, overdue", NextIcon.TASK, highlight, value = 60f), NextUp.face(NextKind.SHORT_TEXT, overdue, 60))
        assertEquals(NextFace("Pay rent", "Overdue", "Pay rent, overdue", NextIcon.TASK, highlight), NextUp.face(NextKind.LONG_TEXT, overdue, 60))
        assertEquals(60f, NextUp.face(NextKind.RANGED_VALUE, overdue, NextUp.progress(overdue, now)).value)
        assertEquals(60f, NextUp.face(NextKind.GOAL_PROGRESS, overdue, NextUp.progress(overdue, now)).value)
        assertEquals(NextIcon.TASK, NextUp.face(NextKind.MONOCHROMATIC_IMAGE, overdue).icon)
        assertEquals(NextIcon.TASK, NextUp.face(NextKind.SMALL_IMAGE, overdue).icon)

        val today = NextItem.Task("t3", "Water plants", "Today", overdue = false)
        assertEquals("Today", NextUp.face(NextKind.SHORT_TEXT, today).text)
        assertEquals("Water plants, due today", NextUp.face(NextKind.SHORT_TEXT, today).description)
        assertEquals("Water plants, due 15:00", NextUp.face(NextKind.SHORT_TEXT, today.copy(due = "15:00")).description)
    }

    @Test
    fun `each type shows nothing ahead as a microphone that records`() {
        val faces = NextKind.entries.associateWith { NextUp.face(it, NextItem.Free, NextUp.progress(NextItem.Free, now)) }
        faces.values.forEach { face ->
            assertEquals(NextIcon.MIC, face.icon)
            assertNull("a microphone records", face.highlight)
            assertEquals(0f, face.value)
        }
        assertEquals("Free", faces.getValue(NextKind.SHORT_TEXT).text)
        assertEquals("Nothing ahead", faces.getValue(NextKind.LONG_TEXT).text)
        assertNull(faces.getValue(NextKind.RANGED_VALUE).text)
        assertNull(faces.getValue(NextKind.GOAL_PROGRESS).text)
    }

    @Test
    fun `the arc fills in 5-minute steps over the hour before the event`() {
        val start = dentist.start
        fun at(minutesBefore: Long, secondsBefore: Long = 0) = NextUp.progress(dentist, start.minus(Duration.ofMinutes(minutesBefore)).minusSeconds(secondsBefore))
        assertEquals(0, at(90))
        assertEquals(0, at(60, 1))
        assertEquals(5, at(60))
        assertEquals(5, at(55, 1))
        assertEquals(10, at(55))
        assertEquals(35, at(30))
        assertEquals(55, at(5, 1))
        assertEquals(60, at(5))
        assertEquals(60, at(0, 1))
        assertEquals(60, NextUp.progress(dentist, start))
        assertTrue(NextKind.RANGED_VALUE.fills && NextKind.GOAL_PROGRESS.fills && !NextKind.SHORT_TEXT.fills)
    }

    @Test
    fun `the timeline moves to each next event at its start, then to the tasks`() {
        val plan = NextUp.plan(day, now, berlin)
        assertEquals(listOf("Dentist", "Dinner", "Flight"), plan.slices.map { (it.item as NextItem.Event).title })
        assertEquals(now, plan.slices.first().from)
        plan.slices.zipWithNext { a, b -> assertEquals(a.until, b.from) }
        assertEquals(Instant.parse("2026-10-13T06:00:00Z"), plan.slices.last().until)
        assertEquals(NextItem.Task("t1", "Pay rent", "Overdue", overdue = true), plan.default)
        assertEquals(60, plan.defaultProgress)

        fun shown(at: String) = NextUp.select(plan, Instant.parse(at)).first
        assertEquals("Dentist", (shown("2026-10-12T12:29:59Z") as NextItem.Event).title)
        assertEquals("Dinner", (shown("2026-10-12T12:30:00Z") as NextItem.Event).title)
        assertEquals("Flight", (shown("2026-10-12T17:00:00Z") as NextItem.Event).title)
        assertTrue(shown("2026-10-13T06:00:00Z") is NextItem.Task)
    }

    @Test
    fun `the value types step every 5 minutes in the last hour`() {
        val onlyDentist = snapshot(events = listOf(timed("e1", "Dentist", "2026-10-12T12:30:00Z", "2026-10-12T13:30:00Z")))
        val plan = NextUp.plan(onlyDentist, now, berlin, steps = true)
        assertEquals(13, plan.slices.size)
        assertEquals(Instant.parse("2026-10-12T11:30:00Z"), plan.slices[0].until)
        assertEquals(listOf(0, 5, 10, 15, 20, 25, 30, 35, 40, 45, 50, 55, 60), plan.slices.map { it.progress })
        plan.slices.forEach { assertEquals(NextUp.progress(it.item, it.from), it.progress) }
        plan.slices.zipWithNext { a, b -> assertEquals(a.until, b.from) }
        assertEquals(NextItem.Free, plan.default)
        assertEquals(20, NextUp.select(plan, Instant.parse("2026-10-12T11:47:00Z")).second)
        assertEquals(60, NextUp.select(plan, Instant.parse("2026-10-12T12:27:00Z")).second)

        // Asked inside the window: the first slice runs to the next step.
        val late = NextUp.plan(onlyDentist, Instant.parse("2026-10-12T12:02:00Z"), berlin, steps = true)
        assertEquals(listOf(35, 40, 45, 50, 55, 60), late.slices.map { it.progress })
        assertEquals(Instant.parse("2026-10-12T12:05:00Z"), late.slices.first().until)

        // The text types switch at the start only.
        assertEquals(1, NextUp.plan(onlyDentist, now, berlin, steps = false).slices.size)
    }

    @Test
    fun `a busy day is cut at four events, the fifth waits in the default`() {
        val busy = snapshot(events = (1..6).map { hour -> timed("e$hour", "Meeting $hour", "2026-10-12T1$hour:00:00Z", "2026-10-12T1$hour:30:00Z") })
        val plan = NextUp.plan(busy, now, berlin)
        assertEquals(NextUp.MAX_EVENTS, plan.slices.size)
        assertEquals("Meeting 5", (plan.default as NextItem.Event).title)
        assertTrue(NextUp.plan(null, now, berlin).slices.isEmpty())
        assertEquals(NextItem.Free, NextUp.plan(null, now, berlin).default)
    }
}

package com.gamch1k.lifeos.core.push

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.gamch1k.lifeos.core.TestDataStores
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.time.Duration
import java.time.Instant
import java.time.ZoneId

/** Snooze's rules (P18-05): scheduling, the stored notifications, Done, and the lengths. */
class SnoozeTest {
    @get:Rule
    val stores = TestDataStores()

    /** Android's alarm, as the phone uses it: one time, replaced by each set. */
    private class FakeAlarm : SnoozeAlarm {
        var at: Instant? = null
        override fun set(at: Instant) {
            this.at = at
        }

        override fun cancel() {
            at = null
        }
    }

    private val berlin = ZoneId.of("Europe/Berlin")

    /** Friday 16 October 2026, 10:00 in Berlin. */
    private val start = Instant.parse("2026-10-16T08:00:00Z")
    private val taskId = "0f8fad5b-d9cb-469f-a165-70867728950e"

    private fun payload(name: String, link: String? = null, category: String = "calendar.reminder") = PushPayload(
        title = "Reminder $name",
        body = "In 30 minutes\nRoom 2",
        link = link,
        tag = "lifeos-$name",
        importance = "high",
        category = category,
        id = name,
        timestamp = 1_760_600_000_000,
    )

    private inner class Phone(store: DataStore<Preferences> = stores.create(), val alarm: FakeAlarm = FakeAlarm()) {
        var now: Instant = start
        val shown = mutableListOf<PushPayload>()
        val book = SnoozeBook(store)
        val snoozer = Snoozer(book, alarm, repost = { shown += it }, clock = { now }, zone = { berlin })

        fun at(offset: Duration) {
            now = start.plus(offset)
        }
    }

    private fun minutes(n: Long) = Duration.ofMinutes(n)

    // ---- Scheduling

    @Test
    fun `snooze stores the notification and sets the alarm an hour ahead by default`() = runBlocking {
        val phone = Phone()
        val reminder = payload("a")
        val record = phone.snoozer.snooze(reminder)
        assertEquals(start.plus(minutes(60)), record.due)
        assertEquals(start, Instant.ofEpochMilli(record.snoozedAt))
        assertEquals("lifeos-a", record.tag)
        assertEquals(start.plus(minutes(60)), phone.alarm.at)
        assertEquals(listOf(record), phone.book.all())
        assertEquals(SnoozeLength.HOUR_1, phone.snoozer.length.first())
    }

    @Test
    fun `the length chosen in Settings applies to the next snooze`() = runBlocking {
        val phone = Phone()
        phone.snoozer.setLength(SnoozeLength.MINUTES_15)
        assertEquals(start.plus(minutes(15)), phone.snoozer.snooze(payload("a")).due)
        phone.snoozer.setLength(SnoozeLength.HOURS_3)
        assertEquals(start.plus(minutes(180)), phone.snoozer.snooze(payload("b")).due)
        phone.snoozer.setLength(SnoozeLength.TOMORROW_MORNING)
        // Friday 10:00 in Berlin: Saturday 09:00 CEST.
        assertEquals(Instant.parse("2026-10-17T07:00:00Z"), phone.snoozer.snooze(payload("c")).due)
        assertEquals(start.plus(minutes(15)), phone.alarm.at)
    }

    @Test
    fun `nothing comes back early, then it comes back once with the same payload`() = runBlocking {
        val phone = Phone()
        val reminder = payload("a", link = "/tasks?task=$taskId", category = "tasks.due")
        phone.snoozer.snooze(reminder)

        phone.at(minutes(58))
        assertTrue(phone.snoozer.wake().isEmpty())
        assertTrue(phone.shown.isEmpty())
        assertEquals(start.plus(minutes(60)), phone.alarm.at)

        phone.at(minutes(60))
        assertEquals(1, phone.snoozer.wake().size)
        assertEquals(listOf(reminder), phone.shown)
        assertTrue(phone.book.all().isEmpty())
        assertNull(phone.alarm.at)

        phone.at(minutes(61))
        assertTrue(phone.snoozer.wake().isEmpty())
        assertEquals(1, phone.shown.size)
    }

    @Test
    fun `a notification that came back can be snoozed again`() = runBlocking {
        val phone = Phone()
        val reminder = payload("a")
        phone.snoozer.snooze(reminder)
        phone.at(minutes(60))
        phone.snoozer.wake()
        phone.snoozer.snooze(phone.shown.single())
        assertEquals(start.plus(minutes(120)), phone.alarm.at)
        phone.at(minutes(120))
        phone.snoozer.wake()
        assertEquals(listOf(reminder, reminder), phone.shown)
        assertNull(phone.alarm.at)
    }

    @Test
    fun `the alarm follows the earliest snooze and moves on to the next`() = runBlocking {
        val phone = Phone()
        phone.snoozer.setLength(SnoozeLength.HOURS_3)
        phone.snoozer.snooze(payload("late"))
        phone.snoozer.setLength(SnoozeLength.MINUTES_15)
        phone.snoozer.snooze(payload("early"))
        assertEquals(start.plus(minutes(15)), phone.alarm.at)

        phone.at(minutes(15))
        phone.snoozer.wake()
        assertEquals(listOf("lifeos-early"), phone.shown.map { it.notificationTag })
        assertEquals(start.plus(minutes(180)), phone.alarm.at)

        phone.at(minutes(181))
        phone.snoozer.wake()
        assertEquals(listOf("lifeos-early", "lifeos-late"), phone.shown.map { it.notificationTag })
        assertNull(phone.alarm.at)
    }

    @Test
    fun `notifications due within a minute of each other come back with one alarm, in order`() = runBlocking {
        val phone = Phone()
        phone.snoozer.snooze(payload("first"))
        phone.now = start.plusSeconds(40)
        phone.snoozer.snooze(payload("second"))
        phone.at(minutes(60))
        assertEquals(2, phone.snoozer.wake().size)
        assertEquals(listOf("lifeos-first", "lifeos-second"), phone.shown.map { it.notificationTag })
        assertNull(phone.alarm.at)
    }

    @Test
    fun `snoozing the same notification again replaces its return`() = runBlocking {
        val phone = Phone()
        phone.snoozer.snooze(payload("a"))
        phone.at(minutes(30))
        phone.snoozer.snooze(payload("a"))
        assertEquals(1, phone.book.all().size)
        assertEquals(start.plus(minutes(90)), phone.alarm.at)
        phone.at(minutes(60))
        assertTrue(phone.snoozer.wake().isEmpty())
    }

    @Test
    fun `a notification is shown before it is forgotten, so a failure brings it back later`() = runBlocking {
        val store = stores.create()
        val alarm = FakeAlarm()
        var now = start
        val failing = Snoozer(SnoozeBook(store), alarm, repost = { error("notification manager died") }, clock = { now }, zone = { berlin })
        failing.snooze(payload("a"))
        now = start.plus(minutes(60))
        try {
            failing.wake()
            fail("the failure should surface")
        } catch (_: IllegalStateException) {
        }
        val phone = Phone(store, alarm)
        phone.at(minutes(61))
        phone.snoozer.wake()
        assertEquals(listOf("lifeos-a"), phone.shown.map { it.notificationTag })
    }

    // ---- Done

    @Test
    fun `Done drops the pending return of its tag and of every snooze about the same Task`() = runBlocking {
        val phone = Phone()
        phone.snoozer.snooze(payload("due", link = "/tasks?task=$taskId", category = "tasks.due"))
        phone.snoozer.snooze(payload("again", link = "/tasks/${taskId.uppercase()}", category = "tasks.due"))
        phone.at(minutes(10))
        phone.snoozer.snooze(payload("other"))

        phone.snoozer.done("lifeos-due", taskId)
        assertEquals(listOf("lifeos-other"), phone.book.all().map { it.tag })
        assertEquals(start.plus(minutes(70)), phone.alarm.at)

        phone.at(minutes(70))
        phone.snoozer.wake()
        assertEquals(listOf("lifeos-other"), phone.shown.map { it.notificationTag })
    }

    @Test
    fun `Done on the last snoozed notification cancels the alarm`() = runBlocking {
        val phone = Phone()
        phone.snoozer.snooze(payload("a", link = "/tasks/$taskId", category = "tasks.due"))
        phone.snoozer.done("lifeos-a", taskId)
        assertTrue(phone.book.all().isEmpty())
        assertNull(phone.alarm.at)
        phone.at(minutes(60))
        assertTrue(phone.snoozer.wake().isEmpty())
        assertTrue(phone.shown.isEmpty())
    }

    @Test
    fun `Done by tag alone keeps other notifications, and Done on something not snoozed changes nothing`() = runBlocking {
        val phone = Phone()
        phone.snoozer.snooze(payload("a", link = "/tasks/$taskId", category = "tasks.due"))
        phone.snoozer.snooze(payload("b", link = "/tasks/$taskId", category = "tasks.due"))
        phone.snoozer.done("lifeos-a", null)
        assertEquals(listOf("lifeos-b"), phone.book.all().map { it.tag })
        phone.snoozer.done("lifeos-unknown", null)
        assertEquals(listOf("lifeos-b"), phone.book.all().map { it.tag })
        assertEquals(start.plus(minutes(60)), phone.alarm.at)
    }

    @Test
    fun `completing the Task on Home drops its snoozed reminders`() = runBlocking {
        val phone = Phone()
        phone.snoozer.snooze(payload("task", link = "/tasks?task=$taskId", category = "tasks.due"))
        phone.at(minutes(5))
        phone.snoozer.snooze(payload("event"))
        phone.snoozer.taskDone(taskId.uppercase())
        assertEquals(listOf("lifeos-event"), phone.book.all().map { it.tag })
        assertEquals(start.plus(minutes(65)), phone.alarm.at)
    }

    // ---- Persistence

    @Test
    fun `snoozed notifications outlive the app's process and a reboot`() = runBlocking {
        val file = File(stores.root, "settings.preferences_pb")
        val reminder = payload("a", link = "/tasks?task=$taskId", category = "tasks.due")

        val firstProcess = CoroutineScope(Dispatchers.IO + SupervisorJob())
        val before = Phone(PreferenceDataStoreFactory.create(scope = firstProcess, produceFile = { file }))
        before.snoozer.setLength(SnoozeLength.MINUTES_15)
        before.snoozer.snooze(reminder)
        before.snoozer.setLength(SnoozeLength.HOURS_3)
        before.snoozer.snooze(payload("b"))
        firstProcess.coroutineContext.job.cancelAndJoin()

        // A new process after a reboot: the same file, and no alarm any more.
        val secondProcess = CoroutineScope(Dispatchers.IO + SupervisorJob())
        try {
            val after = Phone(PreferenceDataStoreFactory.create(scope = secondProcess, produceFile = { file }))
            assertEquals(SnoozeLength.HOURS_3, after.snoozer.length.first())
            assertEquals(listOf("lifeos-a", "lifeos-b"), after.book.all().map { it.tag })
            assertNull(after.alarm.at)

            // Started before anything is due: the alarm is set again, nothing is shown.
            after.at(minutes(5))
            assertTrue(after.snoozer.wake().isEmpty())
            assertEquals(start.plus(minutes(15)), after.alarm.at)

            // Started late (the alarm was lost to a force stop): the late one comes back at once.
            after.at(minutes(40))
            after.snoozer.wake()
            assertEquals(listOf(reminder), after.shown)
            assertEquals(start.plus(minutes(180)), after.alarm.at)
        } finally {
            secondProcess.coroutineContext.job.cancelAndJoin()
        }
    }

    @Test
    fun `sign-out forgets snoozed notifications and keeps the length`() = runBlocking {
        val phone = Phone()
        phone.snoozer.setLength(SnoozeLength.HOURS_3)
        phone.snoozer.snooze(payload("a"))
        phone.snoozer.clear()
        assertTrue(phone.book.all().isEmpty())
        assertNull(phone.alarm.at)
        assertEquals(SnoozeLength.HOURS_3, phone.snoozer.length.first())
        phone.at(minutes(200))
        assertTrue(phone.snoozer.wake().isEmpty())
    }

    @Test
    fun `a damaged store reads as nothing snoozed, an unknown length as the default`() = runBlocking {
        val store = stores.create()
        store.edit {
            it[stringPreferencesKey("snoozed-notifications")] = "{not json"
            it[stringPreferencesKey("snooze-length")] = "2h"
        }
        val phone = Phone(store)
        assertTrue(phone.book.all().isEmpty())
        assertEquals(SnoozeLength.HOUR_1, phone.snoozer.length.first())
        assertTrue(phone.snoozer.wake().isEmpty())
        assertNull(phone.alarm.at)
        // Snoozing writes a clean list over it.
        phone.snoozer.snooze(payload("a"))
        assertEquals(listOf("lifeos-a"), phone.book.all().map { it.tag })
    }

    // ---- Lengths

    @Test
    fun `lengths are stored by key and unknown keys are an hour`() {
        assertEquals(SnoozeLength.HOUR_1, SnoozeLength.DEFAULT)
        assertEquals(SnoozeLength.HOUR_1, SnoozeLength.fromKey(null))
        assertEquals(SnoozeLength.HOUR_1, SnoozeLength.fromKey("2h"))
        SnoozeLength.entries.forEach { assertEquals(it, SnoozeLength.fromKey(it.key)) }
        assertEquals(SnoozeLength.entries.size, SnoozeLength.entries.map { it.key }.toSet().size)
    }

    @Test
    fun `fixed lengths are real time, even across the clocks going back`() {
        // 02:30 CEST on 25 October 2026, half an hour before the clocks go back.
        val now = Instant.parse("2026-10-25T00:30:00Z")
        assertEquals(Instant.parse("2026-10-25T00:45:00Z"), SnoozeLength.MINUTES_15.dueAt(now, berlin))
        assertEquals(Instant.parse("2026-10-25T01:30:00Z"), SnoozeLength.HOUR_1.dueAt(now, berlin))
        assertEquals(Instant.parse("2026-10-25T03:30:00Z"), SnoozeLength.HOURS_3.dueAt(now, berlin))
    }

    // ---- Tomorrow at 09:00

    private fun tomorrow(now: String, zone: String) = SnoozeLength.TOMORROW_MORNING.dueAt(Instant.parse(now), ZoneId.of(zone))

    @Test
    fun `tomorrow at 09 is the next day's 09 00 on the wall clock`() {
        // Friday 14:00 CEST -> Saturday 09:00 CEST.
        assertEquals(Instant.parse("2026-10-17T07:00:00Z"), tomorrow("2026-10-16T12:00:00Z", "Europe/Berlin"))
        // 08:00 is not before the night ends: tomorrow, not in an hour.
        assertEquals(Instant.parse("2026-10-17T07:00:00Z"), tomorrow("2026-10-16T06:00:00Z", "Europe/Berlin"))
        // 23:30 on New Year's Eve, in UTC.
        assertEquals(Instant.parse("2027-01-01T09:00:00Z"), tomorrow("2026-12-31T23:30:00Z", "UTC"))
    }

    @Test
    fun `tomorrow at 09 stays 09 00 when the clocks change overnight in Berlin`() {
        // Saturday 24 October, 22:00 CEST; the clocks go back at 03:00: Sunday 09:00 CET, 12 hours later.
        val autumn = tomorrow("2026-10-24T20:00:00Z", "Europe/Berlin")
        assertEquals(Instant.parse("2026-10-25T08:00:00Z"), autumn)
        assertEquals(Duration.ofHours(12), Duration.between(Instant.parse("2026-10-24T20:00:00Z"), autumn))
        // Saturday 28 March, 21:00 CET; the clocks go forward at 02:00: Sunday 09:00 CEST, 11 hours later.
        val spring = tomorrow("2026-03-28T20:00:00Z", "Europe/Berlin")
        assertEquals(Instant.parse("2026-03-29T07:00:00Z"), spring)
        assertEquals(Duration.ofHours(11), Duration.between(Instant.parse("2026-03-28T20:00:00Z"), spring))
    }

    @Test
    fun `tomorrow at 09 in other time zones`() {
        // New York: the clocks go back on 1 November and forward on 8 March 2026.
        assertEquals(Instant.parse("2026-11-01T14:00:00Z"), tomorrow("2026-11-01T00:00:00Z", "America/New_York"))
        assertEquals(Instant.parse("2026-03-08T13:00:00Z"), tomorrow("2026-03-08T04:00:00Z", "America/New_York"))
        // Sydney: the clocks go forward on 4 October 2026 (AEST +10 to AEDT +11).
        assertEquals(Instant.parse("2026-10-03T22:00:00Z"), tomorrow("2026-10-03T08:00:00Z", "Australia/Sydney"))
        // Half-hour and far-east offsets without DST.
        assertEquals(Instant.parse("2026-10-17T03:30:00Z"), tomorrow("2026-10-16T04:30:00Z", "Asia/Kolkata"))
        assertEquals(Instant.parse("2026-10-16T19:00:00Z"), tomorrow("2026-10-15T20:00:00Z", "Pacific/Kiritimati"))
    }

    @Test
    fun `after midnight, tomorrow at 09 is the coming morning`() {
        // 00:30 CEST on the night the clocks go back: 09:00 CET the same day.
        assertEquals(Instant.parse("2026-10-25T08:00:00Z"), tomorrow("2026-10-24T22:30:00Z", "Europe/Berlin"))
        // 04:59 is still night; 05:00 is morning, so the next day.
        assertEquals(Instant.parse("2026-10-16T07:00:00Z"), tomorrow("2026-10-16T02:59:00Z", "Europe/Berlin"))
        assertEquals(Instant.parse("2026-10-17T07:00:00Z"), tomorrow("2026-10-16T03:00:00Z", "Europe/Berlin"))
    }

    @Test
    fun `a time in a DST gap moves later, one that happens twice is the first, and it is never in the past`() {
        val berlin = ZoneId.of("Europe/Berlin")
        // 02:30 does not exist on 29 March 2026 in Berlin: 03:30 CEST.
        assertEquals(
            Instant.parse("2026-03-29T01:30:00Z"),
            SnoozeTimes.tomorrowAt(Instant.parse("2026-03-28T12:00:00Z"), berlin, at = java.time.LocalTime.of(2, 30)),
        )
        // 02:30 happens twice on 25 October 2026: the first, in CEST.
        assertEquals(
            Instant.parse("2026-10-25T00:30:00Z"),
            SnoozeTimes.tomorrowAt(Instant.parse("2026-10-24T12:00:00Z"), berlin, at = java.time.LocalTime.of(2, 30)),
        )
        // 04:00 at night with a target of 03:00: that is past, so the next day's.
        assertEquals(
            Instant.parse("2026-10-17T01:00:00Z"),
            SnoozeTimes.tomorrowAt(Instant.parse("2026-10-16T02:00:00Z"), berlin, at = java.time.LocalTime.of(3, 0)),
        )
    }

    @Test
    fun `tomorrow at 09 is always in the future`() {
        val zones = listOf("Europe/Berlin", "America/New_York", "Australia/Sydney", "Asia/Kolkata", "Pacific/Chatham", "UTC")
        var now = Instant.parse("2026-01-01T00:00:00Z")
        val end = Instant.parse("2027-01-01T00:00:00Z")
        while (now.isBefore(end)) {
            for (zone in zones) {
                val due = tomorrow(now.toString(), zone)
                assertTrue("$now in $zone", due.isAfter(now))
                assertFalse("$now in $zone", Duration.between(now, due) > Duration.ofHours(34))
                assertEquals("$now in $zone", java.time.LocalTime.of(9, 0), due.atZone(ZoneId.of(zone)).toLocalTime())
            }
            now = now.plus(Duration.ofMinutes(47))
        }
    }
}

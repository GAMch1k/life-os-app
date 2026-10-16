package com.gamch1k.lifeos.core.push

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.gamch1k.lifeos.core.api.LifeOsJson
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import java.time.Duration
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * Snooze (P18-05): how long a snoozed notification stays away. Settings ›
 * Notifications picks one; [DEFAULT] until then. [key] is what is stored.
 */
enum class SnoozeLength(val key: String, val label: String) {
    MINUTES_15("15m", "15 minutes"),
    HOUR_1("1h", "1 hour"),
    HOURS_3("3h", "3 hours"),
    TOMORROW_MORNING("tomorrow-09:00", "Tomorrow at 09:00"),
    ;

    /** When a notification snoozed at [now] comes back; [zone] is the phone's, for "tomorrow at 09:00". */
    fun dueAt(now: Instant, zone: ZoneId): Instant = when (this) {
        MINUTES_15 -> now.plus(Duration.ofMinutes(15))
        HOUR_1 -> now.plus(Duration.ofHours(1))
        HOURS_3 -> now.plus(Duration.ofHours(3))
        TOMORROW_MORNING -> SnoozeTimes.tomorrowAt(now, zone)
    }

    companion object {
        val DEFAULT = HOUR_1

        /** The stored choice; anything unknown (or nothing) is [DEFAULT]. */
        fun fromKey(key: String?): SnoozeLength = entries.firstOrNull { it.key == key } ?: DEFAULT
    }
}

object SnoozeTimes {
    val MORNING: LocalTime = LocalTime.of(9, 0)

    /**
     * Until this time the night still belongs to the day before: a snooze to
     * "tomorrow" at 00:30 means the coming morning, not the one after.
     */
    val NIGHT_ENDS: LocalTime = LocalTime.of(5, 0)

    /**
     * [at] on the next day in [zone], as a wall-clock time, so DST never
     * shifts it: the day after [now]'s local date, or [now]'s own date
     * before [nightEnds]. A time that does not exist that day (a DST gap)
     * moves later by the gap; one that exists twice (the clocks going back)
     * is the first. Always after [now].
     */
    fun tomorrowAt(now: Instant, zone: ZoneId, at: LocalTime = MORNING, nightEnds: LocalTime = NIGHT_ENDS): Instant {
        val local = now.atZone(zone)
        var day = if (local.toLocalTime() < nightEnds) local.toLocalDate() else local.toLocalDate().plusDays(1)
        var due = ZonedDateTime.of(day, at, zone).toInstant()
        while (!due.isAfter(now)) {
            day = day.plusDays(1)
            due = ZonedDateTime.of(day, at, zone).toInstant()
        }
        return due
    }
}

/** A notification put away by Snooze: the payload it is shown again with, and when. */
@Serializable
data class SnoozedNotification(
    val payload: PushPayload,
    /** When it comes back, in epoch milliseconds. */
    val dueAt: Long,
    /** When Snooze was tapped, in epoch milliseconds. */
    val snoozedAt: Long,
) {
    /** The notification tag; one snoozed copy per tag. */
    val tag: String get() = payload.notificationTag

    val due: Instant get() = Instant.ofEpochMilli(dueAt)
}

/**
 * The snooze length and the snoozed notifications, in a preference
 * DataStore: they outlive the app's process, and a reboot when the app
 * re-arms its alarm ([Snoozer.wake]).
 */
class SnoozeBook(private val store: DataStore<Preferences>) {
    val length: Flow<SnoozeLength> = store.data.map { SnoozeLength.fromKey(it[LENGTH]) }

    suspend fun setLength(length: SnoozeLength) {
        store.edit { it[LENGTH] = length.key }
    }

    suspend fun all(): List<SnoozedNotification> = decode(store.data.first())

    /** Snoozes [payload] until [dueAt]; a snoozed copy with the same tag is replaced. */
    suspend fun put(payload: PushPayload, dueAt: Instant, now: Instant): SnoozedNotification {
        val record = SnoozedNotification(payload, dueAt.toEpochMilli(), now.toEpochMilli())
        change { list -> list.filterNot { it.tag == record.tag } + record }
        return record
    }

    /** Removes the snoozed copy with [tag]; true when there was one. */
    suspend fun remove(tag: String): Boolean = removeAll { it.tag == tag } > 0

    /** Removes every snoozed notification about the Task [taskId]; how many there were. */
    suspend fun removeTask(taskId: String): Int = removeAll { it.payload.taskId == taskId.lowercase() }

    /** Removes exactly these records (one that was snoozed again meanwhile stays). */
    suspend fun removeExactly(records: Collection<SnoozedNotification>) {
        if (records.isEmpty()) return
        val gone = records.toSet()
        change { list -> list.filterNot { it in gone } }
    }

    /** The earliest time something comes back, or null when nothing is snoozed. */
    suspend fun nextDue(): Instant? = all().minOfOrNull { it.dueAt }?.let(Instant::ofEpochMilli)

    /** Sign-out: the snoozed notifications go (they hold Life OS text); the length stays. */
    suspend fun clear() {
        store.edit { it.remove(RECORDS) }
    }

    private suspend fun removeAll(predicate: (SnoozedNotification) -> Boolean): Int {
        var removed = 0
        change { list -> list.filterNot { predicate(it).also { match -> if (match) removed++ } } }
        return removed
    }

    private suspend fun change(transform: (List<SnoozedNotification>) -> List<SnoozedNotification>) {
        store.edit { preferences ->
            val next = transform(decode(preferences))
            if (next.isEmpty()) preferences.remove(RECORDS) else preferences[RECORDS] = LifeOsJson.encodeToString(SERIALIZER, next)
        }
    }

    private fun decode(preferences: Preferences): List<SnoozedNotification> {
        val text = preferences[RECORDS] ?: return emptyList()
        return runCatching { LifeOsJson.decodeFromString(SERIALIZER, text) }.getOrDefault(emptyList())
    }

    private companion object {
        val LENGTH = stringPreferencesKey("snooze-length")
        val RECORDS = stringPreferencesKey("snoozed-notifications")
        val SERIALIZER = ListSerializer(SnoozedNotification.serializer())
    }
}

/**
 * The one wake-up for snoozed notifications. On the phone it is an inexact
 * `AlarmManager.setAndAllowWhileIdle` alarm (no permission, fires in Doze);
 * [set] replaces the previous time.
 */
interface SnoozeAlarm {
    fun set(at: Instant)

    fun cancel()
}

/**
 * Snooze's rules: the notification goes, comes back after the chosen
 * length with the same payload, and Done drops a pending return. There is
 * one alarm, at the earliest snoozed notification; when it goes off, every
 * notification due within [TOLERANCE] comes back and the alarm moves on.
 */
class Snoozer(
    private val book: SnoozeBook,
    private val alarm: SnoozeAlarm,
    /** Shows a snoozed notification again (marked "Snoozed"). */
    private val repost: (PushPayload) -> Unit,
    private val clock: () -> Instant = Instant::now,
    private val zone: () -> ZoneId = ZoneId::systemDefault,
) {
    private val mutex = Mutex()

    val length: Flow<SnoozeLength> get() = book.length

    suspend fun setLength(length: SnoozeLength) = book.setLength(length)

    /** Snooze on [payload]'s notification (the caller removed it): it comes back after the chosen length. */
    suspend fun snooze(payload: PushPayload): SnoozedNotification = mutex.withLock {
        val now = clock()
        val record = book.put(payload, book.length.first().dueAt(now, zone()), now)
        arm()
        record
    }

    /**
     * Done on the notification with [tag]: its snoozed copy never comes back,
     * nor any other snoozed notification about the same Task ([taskId]).
     */
    suspend fun done(tag: String, taskId: String?) = mutex.withLock {
        book.remove(tag)
        taskId?.let { book.removeTask(it) }
        arm()
    }

    /** The Task [taskId] was completed in the app (Home): its snoozed reminders are dropped. */
    suspend fun taskDone(taskId: String) = mutex.withLock {
        if (book.removeTask(taskId) > 0) arm()
    }

    /**
     * The alarm went off, the phone restarted, or the app started: what is due
     * (or late, after a force stop) is shown again, then the alarm goes to the
     * next one. Shown first, removed after, so a crash in between shows it
     * twice rather than never. Returns what came back.
     */
    suspend fun wake(): List<SnoozedNotification> = mutex.withLock {
        val until = clock().plus(TOLERANCE)
        val due = book.all().filter { !it.due.isAfter(until) }.sortedBy { it.dueAt }
        due.forEach { repost(it.payload) }
        book.removeExactly(due)
        arm()
        due
    }

    /** Sign-out: nothing snoozed comes back. */
    suspend fun clear() = mutex.withLock {
        book.clear()
        alarm.cancel()
    }

    private suspend fun arm() {
        val next = book.nextDue()
        if (next == null) alarm.cancel() else alarm.set(next)
    }

    companion object {
        /**
         * Notifications due this close together come back with one alarm:
         * Android lets an allow-while-idle alarm fire only every few minutes in
         * Doze, so a second alarm seconds later could wait much longer.
         */
        val TOLERANCE: Duration = Duration.ofMinutes(1)
    }
}

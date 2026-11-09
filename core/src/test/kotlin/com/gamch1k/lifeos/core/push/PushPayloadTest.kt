package com.gamch1k.lifeos.core.push

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PushPayloadTest {
    private val id = "0f8fad5b-d9cb-469f-a165-70867728950e"

    @Test
    fun `the webpush channel's payload is read in full`() {
        val payload = PushPayloads.parse(
            """{"title":"Dentist","body":"In 30 minutes · 15:00–16:00","link":"/calendar?date=2026-10-12","tag":"lifeos-n1","importance":"high","category":"calendar.reminder","id":"n1","timestamp":1760277600000,"future":true}""".toByteArray(),
        )!!
        assertEquals("Dentist", payload.title)
        assertEquals("In 30 minutes · 15:00–16:00", payload.body)
        assertEquals("/calendar?date=2026-10-12", payload.link)
        assertEquals(PushPayload.Importance.HIGH, payload.level)
        assertEquals("lifeos-n1", payload.notificationTag)
        assertEquals("lifeos:n1", payload.dismissalId)
        assertEquals("lifeos.calendar.reminder", payload.bridgeTag)
        assertEquals(1760277600000, payload.timestamp)
        assertTrue(payload.touchesToday)
        assertNull(payload.taskId)
    }

    @Test
    fun `missing fields fall back, unknown importance is medium`() {
        val payload = PushPayloads.parse("""{"title":"Hi","importance":"urgent"}""".toByteArray())!!
        assertEquals(PushPayload.Importance.MEDIUM, payload.level)
        assertEquals("lifeos.notification", payload.bridgeTag)
        assertTrue(payload.notificationTag.startsWith("lifeos-"))
        assertFalse(payload.touchesToday)
        assertEquals(PushPayload.Importance.LOW, PushPayloads.parse("""{"title":"x","importance":"LOW"}""".toByteArray())!!.level)
    }

    @Test
    fun `anything that is not a Life OS notification is ignored`() {
        assertNull(PushPayloads.parse("".toByteArray()))
        assertNull(PushPayloads.parse("hello".toByteArray()))
        assertNull(PushPayloads.parse("{not json".toByteArray()))
        assertNull(PushPayloads.parse("""{"body":"no title"}""".toByteArray()))
        assertNull(PushPayloads.parse("""{"title":"  "}""".toByteArray()))
        assertNull(PushPayloads.parse(byteArrayOf(0x1f, -117, 8, 0)))
    }

    @Test
    fun `task ids come out of every reasonable task link`() {
        assertEquals(id, PushPayloads.taskIdFromLink("/tasks/$id"))
        assertEquals(id, PushPayloads.taskIdFromLink("/tasks/$id/"))
        assertEquals(id, PushPayloads.taskIdFromLink("/tasks?task=$id"))
        assertEquals(id, PushPayloads.taskIdFromLink("/tasks?view=list&taskId=$id"))
        assertEquals(id, PushPayloads.taskIdFromLink("/tasks?id=${id.uppercase()}"))
        assertEquals(id, PushPayloads.taskIdFromLink("/tasks#$id"))
        assertEquals(id, PushPayloads.taskIdFromLink("/tasks#task-$id"))
        assertEquals(id, PushPayloads.taskIdFromLink("https://lifeos.example.com/tasks/$id"))
        assertEquals(id, PushPayloads.taskIdFromLink("/tasks?task=${id.replace("-", "%2D")}"))
    }

    @Test
    fun `links that do not name a task give no id`() {
        assertNull(PushPayloads.taskIdFromLink(null))
        assertNull(PushPayloads.taskIdFromLink(""))
        assertNull(PushPayloads.taskIdFromLink("/tasks"))
        assertNull(PushPayloads.taskIdFromLink("/tasks?date=2026-10-12"))
        assertNull(PushPayloads.taskIdFromLink("/tasks/not-a-uuid"))
        assertNull(PushPayloads.taskIdFromLink("/tasks/${id}x"))
        assertNull(PushPayloads.taskIdFromLink("/calendar/$id"))
        assertNull(PushPayloads.taskIdFromLink("/inbox?task=$id"))
        assertNull(PushPayloads.taskIdFromLink("/tasks?task=$id extra"))
        assertNull(PushPayloads.taskIdFromLink("::::"))
        assertEquals(id, PushPayloads.parse("""{"title":"Pay rent","link":"/tasks?task=$id","category":"tasks.due"}""".toByteArray())!!.taskId)
    }

    @Test
    fun `every notification gets Snooze but the summaries`() {
        fun snoozable(category: String) = PushPayload(title = "x", category = category).snoozable
        listOf(
            "calendar.reminder", "tasks.due", "tasks.morning", "tasks.evening", "inbox.human_review", "inbox.failed",
            "food.expiry", "finance.subscription", "finance.large_debit", "status.processing", "test.push", "", "summaryish.thing",
        ).forEach { assertTrue(it, snoozable(it)) }
        listOf("summary.morning", "summary.evening", "Summary.Morning", "finance.budget.summary", "summary").forEach { assertFalse(it, snoozable(it)) }
    }
}

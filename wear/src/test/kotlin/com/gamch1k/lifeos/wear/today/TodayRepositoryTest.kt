package com.gamch1k.lifeos.wear.today

import com.gamch1k.lifeos.wear.net.ApiRequest
import com.gamch1k.lifeos.wear.net.ApiResult
import com.gamch1k.lifeos.wear.net.ApiTransport
import com.gamch1k.lifeos.wear.net.LifeOsApi
import com.gamch1k.lifeos.wear.net.Route
import com.gamch1k.lifeos.wear.net.Unavailability
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.time.Duration
import java.time.Instant
import java.time.ZoneId

class TodayRepositoryTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val berlin = ZoneId.of("Europe/Berlin")
    private var now = Instant.parse("2026-10-12T08:00:00Z")

    private class Server : ApiTransport {
        val requests = mutableListOf<ApiRequest>()
        var down = false
        override suspend fun execute(request: ApiRequest): ApiResult {
            requests += request
            if (down) return ApiResult.Unavailable(Unavailability.NO_PHONE)
            val body = when {
                request.path.startsWith("/api/calendar/v1/events") ->
                    """{"items":[{"id":"e1","title":"Dentist","allDay":false,"startAt":"2026-10-12T12:30:00Z","endAt":"2026-10-12T13:30:00Z","status":"active","reminders":[],"source":{"kind":"manual"}}]}"""
                request.path.startsWith("/api/tasks/v1/tasks?") ->
                    """{"items":[{"id":"0f8fad5b-d9cb-469f-a165-70867728950e","title":"Pay rent","dueAt":"2026-10-12T00:00:00Z","completed":false}]}"""
                request.path.endsWith("/complete") -> """{"id":"0f8fad5b-d9cb-469f-a165-70867728950e","title":"Pay rent","completed":true}"""
                else -> return ApiResult.Response(404, ByteArray(0), Route.PHONE)
            }
            return ApiResult.Response(200, body.toByteArray(), Route.PHONE)
        }
    }

    private fun repository(server: Server, onChanged: () -> Unit = {}) =
        TodayRepository(folder.root.resolve("today.json"), LifeOsApi(server), onChanged = onChanged, clock = { now }, zone = { berlin })

    @Test
    fun `a refresh asks for today and tomorrow's events and today's tasks, and caches them`() = runTest {
        val server = Server()
        var changes = 0
        val repository = repository(server) { changes++ }
        assertTrue(repository.isStale(Duration.ofMinutes(10)))

        assertEquals(RefreshResult.Updated, repository.refresh())

        val paths = server.requests.map { it.path }.sorted()
        assertEquals(
            listOf(
                "/api/calendar/v1/events?from=2026-10-11T22%3A00%3A00Z&to=2026-10-13T22%3A00%3A00Z&timeZone=Europe%2FBerlin&limit=30",
                "/api/tasks/v1/tasks?completed=false&dueBefore=2026-10-12T22%3A00%3A00Z&includeUndated=false&limit=20",
            ),
            paths,
        )
        assertEquals(listOf("Dentist"), repository.snapshot.value!!.events.map { it.title })
        assertEquals(1, changes)
        assertTrue(!repository.isStale(Duration.ofMinutes(10)))

        // The cache outlives the process.
        assertEquals(repository.snapshot.value, repository(Server()).snapshot.value)
    }

    @Test
    fun `a failed refresh keeps the last day and says why`() = runTest {
        val server = Server()
        val repository = repository(server)
        repository.refresh()
        server.down = true
        val result = repository.refresh()
        assertTrue(result is RefreshResult.Failed)
        assertEquals("Phone not connected, and no direct connection.", repository.lastError.value)
        assertEquals(1, repository.snapshot.value!!.tasks.size)
    }

    @Test
    fun `completing a task drops it at once and refreshes`() = runTest {
        val server = Server()
        val repository = repository(server)
        repository.refresh()
        server.requests.clear()
        assertEquals(RefreshResult.Updated, repository.completeTask("0f8fad5b-d9cb-469f-a165-70867728950e", "tap-1"))
        val complete = server.requests.first()
        assertEquals("POST", complete.method)
        assertEquals("/api/tasks/v1/tasks/0f8fad5b-d9cb-469f-a165-70867728950e/complete", complete.path)
        assertEquals("complete:tap-1", complete.headers["Idempotency-Key"])
        assertNull(repository.lastError.value)
    }

    @Test
    fun `yesterday's cache is stale`() = runTest {
        val repository = repository(Server())
        repository.refresh()
        now = Instant.parse("2026-10-12T22:30:00Z") // 00:30 the next day in Berlin
        assertTrue(repository.isStale(Duration.ofHours(6)))
    }
}

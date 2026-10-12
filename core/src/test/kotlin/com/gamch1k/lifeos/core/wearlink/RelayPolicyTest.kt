package com.gamch1k.lifeos.core.wearlink

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class RelayPolicyTest {
    private val job = "0f8fad5b-d9cb-469f-a165-70867728950e"

    private fun request(method: String, path: String, headers: Map<String, String> = emptyMap()) =
        RelayRequest(id = "r", method = method, path = path, headers = headers)

    @Test
    fun `the watch app's own calls pass`() {
        listOf(
            request("POST", "/api/voice/v1/audio-jobs", mapOf("Content-Type" to "multipart/form-data; boundary=b", "Idempotency-Key" to "k")),
            request("GET", "/api/voice/v1/audio-jobs/$job"),
            request("GET", "/api/tasks/v1/tasks?completed=false&dueBefore=2026-10-12T22%3A00%3A00Z&includeUndated=false&limit=20"),
            request("POST", "/api/tasks/v1/tasks/$job/complete", mapOf("Idempotency-Key" to "k")),
            request("GET", "/api/calendar/v1/events?from=2026-10-11T22%3A00%3A00Z&to=2026-10-13T22%3A00%3A00Z&timeZone=Europe%2FBerlin&limit=30"),
        ).forEach { assertNull(it.path, RelayPolicy.check(it)) }
    }

    @Test
    fun `anything else is forbidden`() {
        listOf(
            request("DELETE", "/api/tasks/v1/tasks/$job"),
            request("PATCH", "/api/tasks/v1/tasks/$job"),
            request("POST", "/api/tasks/v1/tasks"),
            request("GET", "/api/finance/v1/accounts"),
            request("GET", "/api/voice/v1/audio-jobs/not-a-uuid"),
            request("GET", "/api/voice/v1/audio-jobs/$job/../../finance"),
            request("GET", "/android/updates"),
            request("GET", "https://evil.example/api/tasks/v1/tasks"),
            request("GET", "/api/tasks/v1/tasks?query=a b"),
            request("GET", "/api/tasks/v1/tasks?x=<script>"),
            request("GET", "/api/tasks/v1/tasks#frag"),
            request("GET", "/api/tasks/v1/tasks\r\nHost: x"),
        ).forEach { assertEquals(it.path, RelayErrorCode.FORBIDDEN, RelayPolicy.check(it)?.code) }
    }

    @Test
    fun `the watch cannot set the token, the actor or other headers`() {
        listOf("CF-Access-Client-Id", "CF-Access-Client-Secret", "X-Life-OS-Actor", "Cookie", "Host").forEach { name ->
            val refused = RelayPolicy.check(request("GET", "/api/tasks/v1/tasks", mapOf(name to "x")))
            assertEquals(name, RelayErrorCode.FORBIDDEN, refused?.code)
        }
        val injected = RelayPolicy.check(request("GET", "/api/tasks/v1/tasks", mapOf("Accept" to "a\r\nX: y")))
        assertEquals(RelayErrorCode.FORBIDDEN, injected?.code)
    }

    @Test
    fun `writes go out as the owner, reads without an actor`() {
        val write = RelayPolicy.outgoingHeaders(request("POST", "/api/tasks/v1/tasks/$job/complete", mapOf("idempotency-key" to "k")))
        assertEquals(mapOf("idempotency-key" to "k", "X-Life-OS-Actor" to "user"), write)
        val read = RelayPolicy.outgoingHeaders(request("GET", "/api/tasks/v1/tasks", mapOf("Accept" to "application/json")))
        assertEquals(mapOf("Accept" to "application/json"), read)
        assertFalse(RelayPolicy.isWrite("GET"))
    }

    @Test
    fun `HTTP statuses map to what the caller should do`() {
        assertEquals(HttpOutcome.SUCCESS, HttpOutcome.of(200))
        assertEquals(HttpOutcome.SUCCESS, HttpOutcome.of(202))
        assertEquals(HttpOutcome.AUTH, HttpOutcome.of(302)) // Access login redirect
        assertEquals(HttpOutcome.AUTH, HttpOutcome.of(401))
        assertEquals(HttpOutcome.AUTH, HttpOutcome.of(403))
        assertEquals(HttpOutcome.PERMANENT, HttpOutcome.of(400))
        assertEquals(HttpOutcome.PERMANENT, HttpOutcome.of(404))
        assertEquals(HttpOutcome.PERMANENT, HttpOutcome.of(413))
        assertEquals(HttpOutcome.RETRYABLE, HttpOutcome.of(408))
        assertEquals(HttpOutcome.RETRYABLE, HttpOutcome.of(429))
        assertEquals(HttpOutcome.RETRYABLE, HttpOutcome.of(502))
        assertEquals(HttpOutcome.RETRYABLE, HttpOutcome.of(524))
    }

    @Test
    fun `only errors about the request itself keep the watch from going direct`() {
        assertFalse(RelayErrorCode.FORBIDDEN.watchMayGoDirect)
        assertFalse(RelayErrorCode.BAD_REQUEST.watchMayGoDirect)
        RelayErrorCode.entries.filter { it != RelayErrorCode.FORBIDDEN && it != RelayErrorCode.BAD_REQUEST }
            .forEach { assert(it.watchMayGoDirect) { it.name } }
    }
}

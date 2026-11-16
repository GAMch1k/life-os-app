package com.gamch1k.lifeos.core.wearlink

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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
    fun `for the watch, Life OS's 403 module_not_allowed is a refusal, Access's 403 and any 401 need new credentials`() {
        fun body(code: String) = """{"error":{"code":"$code","message":"…"}}""".toByteArray()
        assertEquals(HttpOutcome.PERMANENT, HttpOutcome.of(403, body("module_not_allowed")))
        assertEquals(HttpOutcome.PERMANENT, HttpOutcome.of(403, body("admin_only")))
        assertEquals("the gateway's forbidden: no Access token", HttpOutcome.AUTH, HttpOutcome.of(403, body("forbidden")))
        assertEquals("Cloudflare's page", HttpOutcome.AUTH, HttpOutcome.of(403, "<html>Forbidden</html>".toByteArray()))
        assertEquals(HttpOutcome.AUTH, HttpOutcome.of(401, body("unauthenticated")))
        assertEquals("a slow upload's expired identity token may pass", HttpOutcome.RETRYABLE, HttpOutcome.of(401, body("identity_expired")))
        assertEquals("another coded 401 is a refusal, not new credentials", HttpOutcome.PERMANENT, HttpOutcome.of(401, body("some_new_code")))
        assertEquals(HttpOutcome.PERMANENT, HttpOutcome.of(403, body("credential_not_allowed")))
        assertEquals(HttpOutcome.PERMANENT, HttpOutcome.of(403, body("insufficient_scope")))
        assertEquals(HttpOutcome.PERMANENT, HttpOutcome.of(403, body("forbidden_path")))
        assertEquals("Cloudflare's 401", HttpOutcome.AUTH, HttpOutcome.of(401, "<html>401</html>".toByteArray()))
        assertEquals(HttpOutcome.AUTH, HttpOutcome.of(403, null))
        assertEquals(HttpOutcome.SUCCESS, HttpOutcome.of(200, body("module_not_allowed")))
    }

    @Test
    fun `the watch's credentials carry its key, the Access token, and the actor only without a key`() {
        val key = "lo_dev_" + "W".repeat(64)
        val both = WearCredentials("https://lifeos.example.com", "id.access", "secret", key)
        assertTrue(both.isComplete)
        assertEquals(
            mapOf("Authorization" to "Bearer $key", "CF-Access-Client-Id" to "id.access", "CF-Access-Client-Secret" to "secret"),
            both.headers(actor = true),
        )
        val keyOnly = WearCredentials("https://lifeos.example.com", deviceKey = key)
        assertTrue("a server without Access", keyOnly.isComplete)
        assertEquals(mapOf("Authorization" to "Bearer $key"), keyOnly.headers(actor = true))
        val tokenOnly = WearCredentials("https://lifeos.example.com", "id.access", "secret")
        assertEquals("user", tokenOnly.headers(actor = true)["X-Life-OS-Actor"])
        assertFalse(tokenOnly.headers(actor = false).containsKey("X-Life-OS-Actor"))
        assertFalse(WearCredentials("https://lifeos.example.com").isComplete)
        assertFalse(both.toString().contains(key))

        // A 0.4 watch reads a 0.5 phone's credentials: the key is a field it ignores.
        val json = RelayCodec.json.encodeToString(WearCredentials.serializer(), both)
        assertTrue(json.contains("\"clientId\":\"id.access\""))
        assertEquals(both, RelayCodec.json.decodeFromString(WearCredentials.serializer(), json))
    }

    @Test
    fun `only errors about the request itself keep the watch from going direct`() {
        assertFalse(RelayErrorCode.FORBIDDEN.watchMayGoDirect)
        assertFalse(RelayErrorCode.BAD_REQUEST.watchMayGoDirect)
        RelayErrorCode.entries.filter { it != RelayErrorCode.FORBIDDEN && it != RelayErrorCode.BAD_REQUEST }
            .forEach { assert(it.watchMayGoDirect) { it.name } }
    }
}

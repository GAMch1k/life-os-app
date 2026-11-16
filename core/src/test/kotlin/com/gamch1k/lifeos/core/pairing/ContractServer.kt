package com.gamch1k.lifeos.core.pairing

import com.gamch1k.lifeos.core.api.HttpRequest
import com.gamch1k.lifeos.core.api.HttpResponse
import com.gamch1k.lifeos.core.api.HttpTransport
import com.gamch1k.lifeos.core.api.ResponseSink
import com.gamch1k.lifeos.core.secrets.ServiceToken
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.util.UUID

/**
 * A small Life OS that follows life-os `docs/api/devices.md` for the calls
 * the app makes, behind an optional Cloudflare Access: pairing codes (once,
 * case and dashes ignored, bound to a challenge in the Custom Tab flow),
 * phone and watch keys (one watch key per phone, a new one revokes the old,
 * revoking a phone revokes its watch's), `me`, `sign-out`, and the Tasks and
 * Voice routes a key may use. Everything it was asked is in [requests].
 */
class ContractServer(
    val publicUrl: String = "https://lifeos.example.com",
    /** Cloudflare Access in front: requests without this token go to the Access login. */
    private val access: ServiceToken? = null,
    /** Requests without `Authorization` act as the owner. */
    var legacyAccess: Boolean = false,
) : HttpTransport {
    class Key(val value: String, val id: String, val kind: String, val name: String, val parentId: String?) {
        var revoked = false
    }

    private class Code(val challenge: String?) {
        var used = false
    }

    val requests = mutableListOf<HttpRequest>()
    val bodies = mutableListOf<String>()
    val keys = mutableListOf<Key>()
    private val codes = mutableMapOf<String, Code>()

    /** Modules the account may not use (403 module_not_allowed), by route segment, such as "voice". */
    val notAllowed = mutableSetOf<String>()

    /** Makes a pairing code as Settings → Devices does; with [challenge] as the Custom Tab page does. */
    fun issueCode(challenge: String? = null): String {
        val alphabet = PairingCode.ALPHABET
        val raw = (1..16).map { alphabet[(Math.random() * alphabet.length).toInt()] }.joinToString("")
        val code = raw.chunked(4).joinToString("-")
        codes[raw] = Code(challenge)
        return code
    }

    fun key(value: String): Key? = keys.firstOrNull { it.value == value }

    fun revoke(key: Key) {
        key.revoked = true
        keys.filter { it.parentId == key.id }.forEach { it.revoked = true }
    }

    override suspend fun execute(request: HttpRequest, sink: ResponseSink?, onProgress: ((Long) -> Unit)?): HttpResponse {
        requests += request
        val body = request.body?.open()?.use { it.readBytes() }?.toString(Charsets.UTF_8).orEmpty()
        bodies += body
        require(request.url.startsWith(publicUrl)) { "a request left for ${request.url}" }
        if (access != null && (request.headers["CF-Access-Client-Id"] != access.clientId || request.headers["CF-Access-Client-Secret"] != access.clientSecret)) {
            return HttpResponse(302, mapOf("location" to listOf(LOGIN)), ByteArray(0), LOGIN)
        }
        val path = request.url.removePrefix(publicUrl).substringBefore('?')
        return when {
            request.method == "POST" && path == "/api/auth/devices/pair" -> pair(body)
            path.startsWith("/api/auth/devices/") -> withKey(request) { key -> devices(request.method, path, key, body) }
            path.startsWith("/api/") -> {
                val module = path.removePrefix("/api/").substringBefore('/')
                withKey(request, allowLegacy = true) {
                    if (module in notAllowed) error(403, "module_not_allowed", "Your account may not use this module")
                    else json(200, if (module == "voice") """{"id":"job-1","status":"queued"}""" else """{"items":[]}""")
                }
            }
            else -> json(404, """{"error":{"code":"not_found","message":"not found"}}""")
        }
    }

    private fun pair(body: String): HttpResponse {
        val fields = try {
            Json.parseToJsonElement(body) as JsonObject
        } catch (_: Exception) {
            return error(400, "bad_request", "The body is not JSON")
        }
        val code = fields["code"]?.jsonPrimitive?.contentOrNull
        val name = fields["name"]?.jsonPrimitive?.contentOrNull
        val verifier = fields["verifier"]?.jsonPrimitive?.contentOrNull
        if (code == null || name == null || name.length !in 1..64 || name.any { it.isISOControl() }) return error(400, "bad_request", "code and name are required")
        val stored = codes[code.uppercase().replace("-", "")]
        val verified = stored?.challenge == null || (verifier != null && PairingChallenge.challengeOf(verifier) == stored.challenge)
        if (stored == null || stored.used || !verified) {
            return error(400, "pairing_code_invalid", "That pairing code is wrong, used, or expired. Make a new one in Settings → Devices.")
        }
        stored.used = true
        val key = newKey("phone", name, parentId = null)
        return json(
            200,
            """{"key":"${key.value}","keyId":"${key.id}","name":"$name","kind":"phone","createdAt":"2026-11-14T18:02:11.000Z",""" +
                """"user":{"id":"00000000-0000-4000-8000-000000000001","username":"anna","name":"Anna"},"publicUrl":"$publicUrl"}""",
        )
    }

    private fun devices(method: String, path: String, key: Key, body: String): HttpResponse = when {
        method == "POST" && path == "/api/auth/devices/child" -> {
            if (key.kind != "phone") {
                error(403, "not_a_phone_key", "Only a phone's key can make a watch key")
            } else {
                // One watch per phone: a new one revokes the previous.
                keys.filter { it.parentId == key.id && !it.revoked }.forEach { it.revoked = true }
                val name = (Json.parseToJsonElement(body) as JsonObject)["name"]!!.jsonPrimitive.content
                val child = newKey("watch", name, parentId = key.id)
                json(200, """{"key":"${child.value}","keyId":"${child.id}","name":"$name","kind":"watch","parentKeyId":"${key.id}","createdAt":"2026-11-14T18:05:40.000Z"}""")
            }
        }
        method == "GET" && path == "/api/auth/devices/me" -> json(
            200,
            """{"keyId":"${key.id}","name":"${key.name}","kind":"${key.kind}","parentKeyId":${key.parentId?.let { "\"$it\"" } ?: "null"},""" +
                """"createdAt":"2026-11-14T18:02:11.000Z","user":{"id":"00000000-0000-4000-8000-000000000001","username":"anna","name":"Anna Example"}}""",
        )
        method == "POST" && path == "/api/auth/devices/sign-out" -> {
            revoke(key)
            HttpResponse(204, emptyMap(), ByteArray(0))
        }
        else -> error(404, "not_found", "not found")
    }

    /** The gateway's check: a bearer is judged alone; without one, legacy access (if on) or 401. */
    private fun withKey(request: HttpRequest, allowLegacy: Boolean = false, handle: (Key) -> HttpResponse): HttpResponse {
        val authorization = request.headers["Authorization"]
        if (authorization == null) {
            return if (allowLegacy && legacyAccess) handle(Key("", "legacy", "legacy", "", null)) else unauthenticated()
        }
        val found = this.key(authorization.removePrefix("Bearer "))?.takeIf { authorization.startsWith("Bearer ") && !it.revoked } ?: return unauthenticated()
        return handle(found)
    }

    private fun newKey(kind: String, name: String, parentId: String?): Key {
        val letters = ('A'..'Z') + ('a'..'z')
        val value = "lo_dev_" + (1..64).map { letters[(Math.random() * letters.size).toInt()] }.joinToString("")
        return Key(value, UUID.randomUUID().toString(), kind, name, parentId).also { keys += it }
    }

    private fun unauthenticated() = error(401, "unauthenticated", "Sign in to Life OS first")

    private fun error(status: Int, code: String, message: String) = json(status, """{"error":{"code":"$code","message":"$message"}}""")

    private fun json(status: Int, body: String) = HttpResponse(status, mapOf("content-type" to listOf("application/json")), body.toByteArray())

    companion object {
        const val LOGIN = "https://team.cloudflareaccess.com/cdn-cgi/access/login/lifeos.example.com"
    }
}

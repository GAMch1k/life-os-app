package com.gamch1k.lifeos.core.wearlink

/**
 * What the phone lets through for the watch: exactly the calls the watch app
 * makes, nothing else. Only our own signed watch app can reach the phone
 * listener, but the phone still never becomes a general proxy with its token.
 */
object RelayPolicy {
    private const val UUID = "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}"

    private class Rule(val method: String, val path: Regex)

    private val rules = listOf(
        // Voice capture: upload, then poll the job (services/voice/api/openapi.yaml).
        Rule("POST", Regex("^/api/voice/v1/audio-jobs$")),
        Rule("GET", Regex("^/api/voice/v1/audio-jobs/$UUID$")),
        // Today: the tasks due and the next events; mark a task done.
        Rule("GET", Regex("^/api/tasks/v1/tasks$")),
        Rule("POST", Regex("^/api/tasks/v1/tasks/$UUID/complete$")),
        Rule("GET", Regex("^/api/calendar/v1/events$")),
    )

    /** Query strings must be already percent-encoded and plain. */
    private val query = Regex("^[A-Za-z0-9._~%&=+-]*$")

    /** Request headers the watch may set; everything else (the token above all) is the phone's. */
    val FORWARDED_HEADERS = setOf("accept", "content-type", "idempotency-key")

    /** The actor of every write: the watch acts as the owner. */
    const val ACTOR_HEADER = "X-Life-OS-Actor"
    const val ACTOR = "user"

    fun isWrite(method: String) = method != "GET" && method != "HEAD"

    /** Null when the request may pass, else the error to answer with. */
    fun check(request: RelayRequest): RelayError? {
        val path = request.path.substringBefore('?')
        val queryString = request.path.substringAfter('?', "")
        if (request.path.length > 2048 || request.path.any { it.isISOControl() || it.isWhitespace() } || "#" in request.path) {
            return RelayError(RelayErrorCode.FORBIDDEN, "malformed path")
        }
        if (rules.none { it.method == request.method && it.path.matches(path) }) {
            return RelayError(RelayErrorCode.FORBIDDEN, "${request.method} $path is not relayed")
        }
        if (!query.matches(queryString)) {
            return RelayError(RelayErrorCode.FORBIDDEN, "malformed query")
        }
        val refused = request.headers.keys.filter { it.lowercase() !in FORWARDED_HEADERS }
        if (refused.isNotEmpty()) {
            return RelayError(RelayErrorCode.FORBIDDEN, "headers not relayed: ${refused.joinToString()}")
        }
        if (request.headers.values.any { value -> value.any { it == '\r' || it == '\n' } }) {
            return RelayError(RelayErrorCode.FORBIDDEN, "malformed header value")
        }
        return null
    }

    /** The headers the phone sends on: the watch's allowed ones, plus the actor on writes. */
    fun outgoingHeaders(request: RelayRequest): Map<String, String> = buildMap {
        request.headers.forEach { (name, value) -> if (name.lowercase() in FORWARDED_HEADERS) put(name, value) }
        if (isWrite(request.method)) put(ACTOR_HEADER, ACTOR)
    }
}

package com.gamch1k.lifeos.core.api

import com.gamch1k.lifeos.core.secrets.ServiceToken
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import java.io.File
import java.net.URI
import java.net.URLEncoder
import java.time.Instant
import java.time.ZoneId

/** Where Life OS is and how to get past Cloudflare Access. */
class Connection(val baseUrl: String, val token: ServiceToken) {
    override fun toString() = "Connection($baseUrl, …)"
}

/**
 * The app's Life OS API client.
 *
 * Every request carries the Cloudflare Access service token
 * (`CF-Access-Client-Id`, `CF-Access-Client-Secret`) and
 * `X-Life-OS-Actor: user`, so the app acts as the owner. Redirects are never
 * followed: Access answers a request it does not let in with a redirect to
 * its login page, which becomes [LifeOsException.AccessRedirect].
 *
 * Idempotent calls (reads, completing a Task, an Inbox capture with its
 * dedupe key, content-addressed media) are retried a few times on errors
 * that may pass ([LifeOsException.retryable]); the rest is left to the
 * capture queue.
 */
class LifeOsClient(
    private val transport: HttpTransport,
    private val connection: suspend () -> Connection?,
    private val userAgent: String,
    private val retryDelaysMillis: List<Long> = listOf(700, 2_500),
    private val sleep: suspend (Long) -> Unit = { delay(it) },
) {
    // ---- Tasks

    /** Open Tasks due before [dueBefore], dated ones only (Home's overdue and due today). */
    suspend fun openTasksDueBefore(dueBefore: Instant, limit: Int = 100): TaskList = get(
        "/api/tasks/v1/tasks",
        listOf("completed" to "false", "dueBefore" to dueBefore.toString(), "includeUndated" to "false", "limit" to "$limit"),
        TaskList.serializer(),
    )

    /** `POST /v1/tasks/{id}/complete`: idempotent (a completed Task keeps its completion time). */
    suspend fun completeTask(id: String): Task =
        send("POST", "/api/tasks/v1/tasks/${segment(id)}/complete", null, Task.serializer(), idempotent = true)

    /** `POST /v1/tasks/{id}/reopen`: the undo of [completeTask]. */
    suspend fun reopenTask(id: String): Task =
        send("POST", "/api/tasks/v1/tasks/${segment(id)}/reopen", null, Task.serializer(), idempotent = true)

    // ---- Calendar

    /**
     * Events overlapping `[from, to)`, recurring ones expanded into their
     * occurrences by Calendar, all-day dates compared in [zone]. Follows
     * `nextCursor` for at most [maxPages] pages.
     */
    suspend fun events(from: Instant, to: Instant, zone: ZoneId, pageSize: Int = 100, maxPages: Int = 3): List<CalendarEvent> {
        val items = mutableListOf<CalendarEvent>()
        var cursor: String? = null
        repeat(maxPages) {
            val query = buildList {
                add("from" to from.toString())
                add("to" to to.toString())
                add("timeZone" to zone.id)
                add("limit" to "$pageSize")
                cursor?.let { add("cursor" to it) }
            }
            val page = get("/api/calendar/v1/events", query, EventList.serializer())
            items += page.items
            cursor = page.nextCursor ?: return items
        }
        return items
    }

    // ---- Inbox

    /** Idempotent when [CreateInboxItem.dedupeKey] is set: Inbox returns the item already captured with it. */
    suspend fun createInboxItem(request: CreateInboxItem): InboxItem = send(
        "POST",
        "/api/inbox/v1/inbox-items",
        jsonBody(CreateInboxItem.serializer(), request),
        InboxItem.serializer(),
        idempotent = request.dedupeKey != null,
    )

    suspend fun inboxItem(id: String): InboxItem = get("/api/inbox/v1/inbox-items/${segment(id)}", emptyList(), InboxItem.serializer())

    suspend fun processingAttempts(itemId: String): List<ProcessingAttempt> =
        get("/api/inbox/v1/inbox-items/${segment(itemId)}/processing-attempts", emptyList(), ProcessingAttemptList.serializer()).items

    /**
     * Uploads one image (`POST /v1/media`, field `images`). Content-addressed:
     * the same bytes always get the same reference, so a retry is harmless.
     */
    suspend fun uploadImage(file: File, contentType: String = "image/jpeg"): Media {
        val body = MultipartBody(listOf(MultipartBody.Part.file("images", file.name, contentType, file)))
        val list = send("POST", "/api/inbox/v1/media", body, MediaList.serializer(), idempotent = true, timeoutMillis = uploadTimeout(body.contentLength))
        return list.items.singleOrNull() ?: throw LifeOsException.Protocol("expected one stored image, got ${list.items.size}")
    }

    // ---- Voice

    /**
     * `POST /v1/audio-jobs`: stores the recording and queues its
     * transcription. With an [idempotencyKey] (Voice honours
     * `Idempotency-Key`, BUG-40) a repeat of the same upload returns the job
     * the first one created, so the call is retried like any idempotent one.
     * [sourceMetadataJson] is sent byte for byte as given: Voice answers 409
     * `idempotency_conflict` when a retry's metadata differs.
     */
    suspend fun createAudioJob(file: File, contentType: String, source: String, sourceMetadataJson: String, idempotencyKey: String?): AudioJob {
        val body = MultipartBody(
            listOf(
                MultipartBody.Part.file("audio", file.name, contentType, file),
                MultipartBody.Part.text("source", source),
                MultipartBody.Part.text("sourceMetadata", sourceMetadataJson),
            ),
        )
        val response = exchange(
            "POST",
            "/api/voice/v1/audio-jobs",
            emptyList(),
            body,
            idempotent = idempotencyKey != null,
            extraHeaders = idempotencyKey?.let { mapOf("Idempotency-Key" to it) } ?: emptyMap(),
            timeoutMillis = uploadTimeout(body.contentLength),
        )
        return decode("/api/voice/v1/audio-jobs", response, AudioJob.serializer())
    }

    suspend fun audioJob(id: String): AudioJob = get("/api/voice/v1/audio-jobs/${segment(id)}", emptyList(), AudioJob.serializer())

    // ---- Connection and updates

    /** Onboarding's "Test connection": one cheap authenticated read with [candidate]. */
    suspend fun testConnection(candidate: Connection) {
        exchange("GET", "/api/tasks/v1/tasks", listOf("limit" to "1"), null, idempotent = false, candidate = candidate)
    }

    /** The update manifest, `GET /android/updates`. */
    suspend fun updateManifest(): UpdateManifest = get("/android/updates", emptyList(), UpdateManifest.serializer())

    /**
     * Downloads [path] (same host) into [sink]. [rangeStart] asks for the rest
     * of a partial file (`Range: bytes=n-`); the server may still answer 200.
     */
    suspend fun download(path: String, sink: ResponseSink, rangeStart: Long? = null, onProgress: ((Long) -> Unit)? = null): HttpResponse =
        exchange(
            "GET",
            path,
            emptyList(),
            null,
            idempotent = false,
            accept = "*/*",
            extraHeaders = rangeStart?.let { mapOf("Range" to "bytes=$it-") } ?: emptyMap(),
            sink = sink,
            onProgress = onProgress,
            timeoutMillis = 15 * 60_000L,
        )

    // ---- Relay (the watch's requests through the phone)

    /**
     * Sends a request on behalf of another client, the watch (docs/wear.md):
     * [pathAndQuery] as given (already encoded), [headers] as given, the
     * service token added, redirects not followed, and every HTTP answer
     * returned as it is, whatever its status. Throws
     * [LifeOsException.NotSignedIn] without a token, and [LifeOsException.Offline],
     * [LifeOsException.Timeout] or [LifeOsException.Local] when no answer came.
     * Never retried: the caller decides.
     */
    suspend fun relay(method: String, pathAndQuery: String, headers: Map<String, String>, body: ByteArray?, timeoutMillis: Long): HttpResponse {
        val target = connection() ?: throw LifeOsException.NotSignedIn()
        require(pathAndQuery.startsWith("/")) { "the path must start with /" }
        val contentType = headers.entries.firstOrNull { it.key.equals("Content-Type", ignoreCase = true) }?.value
        val request = HttpRequest(
            method = method,
            url = target.baseUrl.trimEnd('/') + pathAndQuery,
            headers = buildMap {
                put("User-Agent", userAgent)
                headers.forEach { (name, value) ->
                    // The body carries its own Content-Type; the token is always the phone's.
                    if (!name.equals("Content-Type", ignoreCase = true) && !name.startsWith("CF-Access-", ignoreCase = true)) put(name, value)
                }
                put("CF-Access-Client-Id", target.token.clientId)
                put("CF-Access-Client-Secret", target.token.clientSecret)
            },
            body = body?.let { RequestBody.Bytes(contentType ?: "application/octet-stream", it) },
            timeoutMillis = timeoutMillis,
        )
        return try {
            transport.execute(request)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: TransportException) {
            throw mapTransport(error)
        } catch (error: java.io.IOException) {
            throw LifeOsException.Local(error.message ?: error.javaClass.simpleName, error)
        }
    }

    // ---- Plumbing

    private suspend fun <T> get(path: String, query: List<Pair<String, String>>, serializer: KSerializer<T>): T =
        decode(path, exchange("GET", path, query, null, idempotent = true), serializer)

    private suspend fun <T> send(
        method: String,
        path: String,
        body: RequestBody?,
        serializer: KSerializer<T>,
        idempotent: Boolean,
        timeoutMillis: Long = DEFAULT_TIMEOUT_MILLIS,
    ): T = decode(path, exchange(method, path, emptyList(), body, idempotent, timeoutMillis = timeoutMillis), serializer)

    private suspend fun exchange(
        method: String,
        path: String,
        query: List<Pair<String, String>>,
        body: RequestBody?,
        idempotent: Boolean,
        candidate: Connection? = null,
        accept: String = "application/json",
        extraHeaders: Map<String, String> = emptyMap(),
        sink: ResponseSink? = null,
        onProgress: ((Long) -> Unit)? = null,
        timeoutMillis: Long = DEFAULT_TIMEOUT_MILLIS,
    ): HttpResponse {
        val target = candidate ?: connection() ?: throw LifeOsException.NotSignedIn()
        val request = HttpRequest(
            method = method,
            url = url(target.baseUrl, path, query),
            headers = buildMap {
                put("CF-Access-Client-Id", target.token.clientId)
                put("CF-Access-Client-Secret", target.token.clientSecret)
                put("X-Life-OS-Actor", "user")
                put("Accept", accept)
                put("User-Agent", userAgent)
                putAll(extraHeaders)
            },
            body = body,
            timeoutMillis = timeoutMillis,
        )
        var attempt = 0
        while (true) {
            val failure = try {
                return checked(transport.execute(request, sink, onProgress))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: LifeOsException) {
                error
            } catch (error: TransportException) {
                mapTransport(error)
            } catch (error: java.io.IOException) {
                LifeOsException.Local(error.message ?: error.javaClass.simpleName, error)
            }
            if (!idempotent || !failure.retryable || attempt >= retryDelaysMillis.size) throw failure
            sleep(retryDelaysMillis[attempt++])
        }
    }

    private fun <T> decode(path: String, response: HttpResponse, serializer: KSerializer<T>): T {
        val text = response.body.toString(Charsets.UTF_8)
        return try {
            LifeOsJson.decodeFromString(serializer, text)
        } catch (error: SerializationException) {
            throw LifeOsException.Protocol("$path did not answer with the expected JSON (${response.contentType ?: "no content type"})", error)
        } catch (error: IllegalArgumentException) {
            throw LifeOsException.Protocol("$path did not answer with the expected JSON (${response.contentType ?: "no content type"})", error)
        }
    }

    companion object {
        const val DEFAULT_TIMEOUT_MILLIS = 30_000L

        /** An upload gets 30 s plus a second for every 32 KiB, at most 10 minutes. */
        fun uploadTimeout(bytes: Long): Long = (DEFAULT_TIMEOUT_MILLIS + bytes / 32).coerceAtMost(10 * 60_000L)

        private fun <T> jsonBody(serializer: KSerializer<T>, value: T): RequestBody =
            RequestBody.Bytes("application/json", LifeOsJson.encodeToString(serializer, value).toByteArray(Charsets.UTF_8))

        fun segment(value: String): String = URLEncoder.encode(value, Charsets.UTF_8).replace("+", "%20")

        fun url(baseUrl: String, path: String, query: List<Pair<String, String>>): String {
            val base = baseUrl.trimEnd('/')
            val encoded = query.joinToString("&") { (name, value) -> "${segment(name)}=${segment(value)}" }
            return if (encoded.isEmpty()) "$base$path" else "$base$path?$encoded"
        }

        /** Turns an HTTP answer that is not a success into the matching [LifeOsException]. */
        fun checked(response: HttpResponse): HttpResponse {
            val status = response.status
            if (status in 200..299) return response
            if (status in 300..399) {
                val host = response.redirectLocation?.let { runCatching { URI(it).host }.getOrNull() } ?: "unknown host"
                if (host == "cloudflareaccess.com" || host.endsWith(".cloudflareaccess.com")) throw LifeOsException.AccessRedirect(host)
                throw LifeOsException.Protocol("a redirect ($status) to $host")
            }
            if (status == 401 || status == 403) throw LifeOsException.Unauthorized(status)
            val envelope = errorEnvelope(response)
            val code = envelope?.error?.code?.takeIf { it.isNotBlank() }
            val message = envelope?.error?.message?.takeIf { it.isNotBlank() }
            if (status in 400..499) {
                throw LifeOsException.Api(status, code ?: "http_$status", message ?: defaultMessage(status))
            }
            throw LifeOsException.Server(status, code, message ?: defaultMessage(status))
        }

        private fun errorEnvelope(response: HttpResponse): ErrorEnvelope? {
            if (response.body.isEmpty()) return null
            return try {
                LifeOsJson.decodeFromString(ErrorEnvelope.serializer(), response.body.toString(Charsets.UTF_8))
            } catch (_: SerializationException) {
                null
            } catch (_: IllegalArgumentException) {
                null
            }
        }

        private fun defaultMessage(status: Int): String = when (status) {
            404 -> "not found"
            408 -> "the request took too long"
            413 -> "too large"
            429 -> "too many requests, try again shortly"
            502 -> "the service behind the gateway is not answering"
            503 -> "the service is unavailable"
            504 -> "the service did not answer in time"
            530 -> "Cloudflare cannot reach the Life OS server"
            else -> "HTTP $status"
        }

        fun mapTransport(error: TransportException): LifeOsException = when (error) {
            is TransportException.Network -> LifeOsException.Offline(error.detail, error.offline, error)
            is TransportException.Timeout -> LifeOsException.Timeout(error.millis / 1000)
            is TransportException.TooLarge -> LifeOsException.Protocol(error.message ?: "too large", error)
            is TransportException.Local -> LifeOsException.Local(error.message ?: "unknown error", error)
        }
    }
}

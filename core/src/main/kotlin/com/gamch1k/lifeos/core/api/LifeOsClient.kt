package com.gamch1k.lifeos.core.api

import com.gamch1k.lifeos.core.config.LifeOsEndpoints
import com.gamch1k.lifeos.core.secrets.DeviceKey
import com.gamch1k.lifeos.core.secrets.ServiceToken
import com.gamch1k.lifeos.core.update.FetchedManifest
import com.gamch1k.lifeos.core.update.GitHubReleases
import com.gamch1k.lifeos.core.update.ReleaseApp
import com.gamch1k.lifeos.core.update.UpdatePolicy
import com.gamch1k.lifeos.core.update.UpdateSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import java.io.File
import java.net.URI
import java.net.URLEncoder
import java.time.Instant
import java.time.ZoneId

/**
 * Where Life OS is, and the phone's credentials there: its device key once
 * it is paired with an account (P21-06, life-os `docs/api/devices.md`), and
 * the Cloudflare Access service token when the server is behind Access. At
 * least one of the two is set.
 */
class Connection(val baseUrl: String, val token: ServiceToken?, val deviceKey: DeviceKey? = null) {
    override fun toString() = "Connection($baseUrl, …)"

    /**
     * The headers every native call carries (docs/api/devices.md, "Headers
     * on every native call"): `Authorization: Bearer <device key>` once
     * paired, Access's `CF-Access-Client-*` when there is a service token,
     * and `X-Life-OS-Actor: user` only without a key (with one, Life OS
     * ignores it: a device key acts as the account's user).
     */
    fun authHeaders(): Map<String, String> = buildMap {
        deviceKey?.let { put("Authorization", it.bearer) }
        token?.let {
            put("CF-Access-Client-Id", it.clientId)
            put("CF-Access-Client-Secret", it.clientSecret)
        }
        if (deviceKey == null) put(ACTOR_HEADER, ACTOR)
    }

    companion object {
        const val ACTOR_HEADER = "X-Life-OS-Actor"
        const val ACTOR = "user"
    }
}

/**
 * What Life OS said about the device key the client sent: [refused] on its
 * 401, [accepted] on any success. The token store keeps the verdict, so the
 * app can say "This phone is no longer connected" and offer pairing again.
 */
interface DeviceKeyListener {
    suspend fun refused(key: DeviceKey)
    suspend fun accepted(key: DeviceKey)
}

/**
 * The app's Life OS API client.
 *
 * Every request carries [Connection.authHeaders]: the device key once the
 * phone is paired (`Authorization: Bearer`), the Cloudflare Access service
 * token when there is one, and `X-Life-OS-Actor: user` only without a key.
 * Redirects are never followed: Access answers a request it does not let in
 * with a redirect to its login page, which becomes
 * [LifeOsException.AccessRedirect].
 *
 * A 401 from Life OS itself (its JSON error) is
 * [LifeOsException.SignInRequired], told to [keyListener] when a key was
 * sent; only a very slow idempotent upload, whose identity token may have run
 * out on the way, is tried once more first. A 403 `module_not_allowed` is
 * [LifeOsException.ModuleNotAllowed]. Any other 401 or 403 (Cloudflare's)
 * stays [LifeOsException.Unauthorized].
 *
 * Idempotent calls (reads, completing a Task, an Inbox capture with its
 * dedupe key, content-addressed media, a voice upload with its
 * `Idempotency-Key`) are retried a few times on errors that may pass
 * ([LifeOsException.retryable]); the rest is left to the capture queue.
 *
 * App updates ([updateManifest], [download]) come from [updateSource]:
 * [updatesUrl], the update origin, with the token, while the app is
 * connected to that server; otherwise GitHub Releases of [updatesRepo],
 * with no token and redirects followed only to GitHub's own hosts. Never
 * from another server.
 */
class LifeOsClient(
    private val transport: HttpTransport,
    private val connection: suspend () -> Connection?,
    private val userAgent: String,
    private val updatesUrl: String = LifeOsEndpoints.UPDATES_URL,
    private val updatesRepo: String = LifeOsEndpoints.UPDATES_REPO,
    private val retryDelaysMillis: List<Long> = listOf(700, 2_500),
    private val sleep: suspend (Long) -> Unit = { delay(it) },
    private val keyListener: DeviceKeyListener? = null,
    /** A monotonic clock in milliseconds, for the slow upload's second try. */
    private val clockMillis: () -> Long = { System.nanoTime() / 1_000_000 },
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

    // ---- Notifications: Web Push

    /** `GET /v1/push/config`: the server's VAPID public key, whether web push is set up and delivering. */
    suspend fun pushConfig(): PushConfig = get("/api/notifications/v1/push/config", emptyList(), PushConfig.serializer())

    /** `POST /v1/push/subscriptions`: idempotent by endpoint (a known endpoint gets its keys updated). */
    suspend fun subscribePush(endpoint: String, p256dh: String, auth: String): PushSubscription = send(
        "POST",
        "/api/notifications/v1/push/subscriptions",
        jsonBody(PushSubscriptionRequest.serializer(), PushSubscriptionRequest(endpoint, PushKeys(p256dh, auth))),
        PushSubscription.serializer(),
        idempotent = true,
    )

    /** `DELETE /v1/push/subscriptions/{id}`; a device already gone (404) counts as removed. */
    suspend fun unsubscribePush(subscriptionId: String) {
        try {
            exchange("DELETE", "/api/notifications/v1/push/subscriptions/${segment(subscriptionId)}", emptyList(), null, idempotent = true)
        } catch (error: LifeOsException.Api) {
            if (error.status != 404) throw error
        }
    }

    // ---- Connection and updates

    /** Onboarding's "Test connection": one cheap authenticated read with [candidate]. */
    suspend fun testConnection(candidate: Connection) {
        exchange("GET", "/api/tasks/v1/tasks", listOf("limit" to "1"), null, idempotent = false, candidate = candidate)
    }

    /** Where updates come from now ([UpdateSource.choose] for the server in use). */
    suspend fun updateSource(): UpdateSource = UpdateSource.choose(connection()?.baseUrl, updatesUrl, updatesRepo)

    /**
     * The update manifest and its source: `GET /android/updates` on the update
     * origin, or the latest GitHub release's `updates.json`. A 404 (as
     * [LifeOsException.Api]) means nothing is published there yet.
     */
    suspend fun updateManifest(): FetchedManifest {
        val source = updateSource()
        val (address, response) = when (source) {
            UpdateSource.UpdateOrigin ->
                "/android/updates" to exchange("GET", "/android/updates", emptyList(), null, idempotent = true, update = true)
            is UpdateSource.GitHubRepo -> {
                val url = GitHubReleases.manifestUrl(source.repo)
                url to fromGitHub(source.repo, url, idempotent = true, accept = "application/json")
            }
        }
        return FetchedManifest(source, decode(address, response, UpdateManifest.serializer()))
    }

    /**
     * Downloads [path], an APK of the manifest, into [sink]: a path under
     * `/android/` from the update origin, or an asset of a GitHub release of
     * the update repository. Any other address is refused
     * ([LifeOsException.Protocol]) without a request. [rangeStart] asks for
     * the rest of a partial file (`Range: bytes=n-`); the server may still
     * answer 200.
     */
    suspend fun download(path: String, sink: ResponseSink, rangeStart: Long? = null, onProgress: ((Long) -> Unit)? = null): HttpResponse {
        val range = rangeStart?.let { mapOf("Range" to "bytes=$it-") } ?: emptyMap()
        return when {
            ReleaseApp.entries.any { UpdatePolicy.isApkPath(it, path) } -> exchange(
                "GET",
                path,
                emptyList(),
                null,
                idempotent = false,
                update = true,
                accept = "*/*",
                extraHeaders = range,
                sink = sink,
                onProgress = onProgress,
                timeoutMillis = DOWNLOAD_TIMEOUT_MILLIS,
            )
            GitHubReleases.isApkUrl(updatesRepo, path) -> fromGitHub(
                updatesRepo,
                path,
                idempotent = false,
                accept = "*/*",
                extraHeaders = range,
                sink = sink,
                onProgress = onProgress,
                timeoutMillis = DOWNLOAD_TIMEOUT_MILLIS,
            )
            else -> throw LifeOsException.Protocol("$path is not an address updates may come from")
        }
    }

    // ---- Devices (P21-06, life-os docs/api/devices.md)

    /**
     * `POST /api/auth/devices/pair` at [server]: redeems a pairing [code]
     * (with the Custom Tab flow's [verifier]) for this phone's device key,
     * named [name] in Settings → Devices. No Life OS credential: the code is
     * one. The service token goes along only when it belongs to [server]
     * (Cloudflare Access lets the request through). Never retried: a code
     * works once. A server from before pairing answers 404, which is
     * [LifeOsException.PairingUnsupported]; a refused code is
     * [LifeOsException.Api] `pairing_code_invalid` with Life OS's message.
     */
    suspend fun pair(server: String, code: String, name: String, verifier: String?): PairedDevice {
        val current = connection()
        val token = current?.token?.takeIf { LifeOsEndpoints.sameOrigin(current.baseUrl, server) }
        val request = HttpRequest(
            method = "POST",
            url = url(server, PAIR_PATH, emptyList()),
            headers = buildMap {
                token?.let {
                    put("CF-Access-Client-Id", it.clientId)
                    put("CF-Access-Client-Secret", it.clientSecret)
                }
                put("Accept", "application/json")
                put("User-Agent", userAgent)
            },
            body = jsonBody(PairRequest.serializer(), PairRequest(code, name, verifier)),
            timeoutMillis = DEFAULT_TIMEOUT_MILLIS,
        )
        val response = try {
            perform(request, idempotent = false, sink = null, onProgress = null)
        } catch (error: LifeOsException.Api) {
            if (error.status == 404) throw LifeOsException.PairingUnsupported()
            throw error
        }
        val paired = decode(PAIR_PATH, response, PairedDevice.serializer())
        if (DeviceKey.parse(paired.key) == null) throw LifeOsException.Protocol("$PAIR_PATH answered without a usable device key")
        return paired
    }

    /**
     * `POST /api/auth/devices/child` with the phone's key: a key of its own
     * for the phone's watch, named [name]. A phone has at most one: this
     * revokes the previous one (and its push subscriptions). Not retried.
     */
    suspend fun createChildKey(name: String): ChildKey {
        val child = send("POST", CHILD_PATH, jsonBody(ChildKeyRequest.serializer(), ChildKeyRequest(name)), ChildKey.serializer(), idempotent = false)
        if (DeviceKey.parse(child.key) == null) throw LifeOsException.Protocol("$CHILD_PATH answered without a usable device key")
        return child
    }

    /** `GET /api/auth/devices/me`: who the phone's key is, without touching any data. */
    suspend fun deviceIdentity(): DeviceIdentity = get(ME_PATH, emptyList(), DeviceIdentity.serializer())

    /**
     * `POST /api/auth/devices/sign-out` with [key] (the phone's own by
     * default): Life OS revokes it, a phone's together with its watch's, and
     * their push subscriptions. A key that no longer works (401) counts as
     * signed out already. Only ever sent to the phone's own server.
     */
    suspend fun signOutDevice(key: DeviceKey? = null) {
        val target = connection() ?: throw LifeOsException.NotSignedIn()
        signOutKey(Connection(target.baseUrl, target.token, key ?: target.deviceKey ?: return))
    }

    /**
     * `POST /api/auth/devices/sign-out` with [target]'s key, at its server,
     * through Access with its service token: also after the phone forgot
     * them (a queued revocation). A key that no longer works counts as
     * signed out already.
     */
    suspend fun signOutKey(target: Connection) {
        if (target.deviceKey == null) return
        try {
            exchange("POST", SIGN_OUT_PATH, emptyList(), null, idempotent = true, candidate = target)
        } catch (_: LifeOsException.SignInRequired) {
            // Revoked already.
        }
    }

    // ---- Relay (the watch's requests through the phone)

    /**
     * Sends a request on behalf of another client, the watch (docs/wear.md):
     * [pathAndQuery] as given (already encoded), [headers] as given, the
     * phone's service token added when there is one, and [watchKey], the
     * watch's own device key, as `Authorization: Bearer` (without a key, the
     * service token alone, as before pairing). Redirects are not followed,
     * and every HTTP answer is returned as it is, whatever its status. Throws
     * [LifeOsException.NotSignedIn] without any credential, and
     * [LifeOsException.Offline], [LifeOsException.Timeout] or
     * [LifeOsException.Local] when no answer came. Never retried: the caller
     * decides.
     */
    suspend fun relay(
        method: String,
        pathAndQuery: String,
        headers: Map<String, String>,
        body: ByteArray?,
        timeoutMillis: Long,
        watchKey: DeviceKey? = null,
    ): HttpResponse {
        val target = connection() ?: throw LifeOsException.NotSignedIn()
        if (target.token == null && watchKey == null) throw LifeOsException.NotSignedIn()
        require(pathAndQuery.startsWith("/")) { "the path must start with /" }
        val contentType = headers.entries.firstOrNull { it.key.equals("Content-Type", ignoreCase = true) }?.value
        val request = HttpRequest(
            method = method,
            url = target.baseUrl.trimEnd('/') + pathAndQuery,
            headers = buildMap {
                put("User-Agent", userAgent)
                headers.forEach { (name, value) ->
                    // The body carries its own Content-Type; the credentials are always the phone's to set.
                    val credential = name.startsWith("CF-Access-", ignoreCase = true) || name.equals("Authorization", ignoreCase = true)
                    // With a key, Life OS ignores the actor: the key acts as the account's user.
                    val actor = watchKey != null && name.equals(Connection.ACTOR_HEADER, ignoreCase = true)
                    if (!name.equals("Content-Type", ignoreCase = true) && !credential && !actor) put(name, value)
                }
                target.token?.let {
                    put("CF-Access-Client-Id", it.clientId)
                    put("CF-Access-Client-Secret", it.clientSecret)
                }
                watchKey?.let { put("Authorization", it.bearer) }
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
        /** An app update: sent to [updatesUrl], with the token only if that is the owner's server. */
        update: Boolean = false,
        accept: String = "application/json",
        extraHeaders: Map<String, String> = emptyMap(),
        sink: ResponseSink? = null,
        onProgress: ((Long) -> Unit)? = null,
        timeoutMillis: Long = DEFAULT_TIMEOUT_MILLIS,
    ): HttpResponse {
        val target = candidate ?: connection() ?: throw LifeOsException.NotSignedIn()
        val base = if (update) updatesUrl else target.baseUrl
        // Credentials belong to their server: the update origin gets them only when it is that server.
        val withCredentials = !update || LifeOsEndpoints.sameOrigin(target.baseUrl, updatesUrl)
        val request = HttpRequest(
            method = method,
            url = url(base, path, query),
            headers = buildMap {
                if (withCredentials) putAll(target.authHeaders())
                put("Accept", accept)
                put("User-Agent", userAgent)
                putAll(extraHeaders)
            },
            body = body,
            timeoutMillis = timeoutMillis,
        )
        return perform(request, idempotent, sink, onProgress, key = target.deviceKey.takeIf { withCredentials })
    }

    /**
     * A GET of [url] on GitHub for [repo]'s releases: no token and no Life OS
     * header, only `Accept`, `User-Agent` and [extraHeaders] (a `Range`), on
     * every hop. Redirects are followed, at most [GitHubReleases.MAX_REDIRECTS],
     * and only to [GitHubReleases.mayRedirectTo]; any other is
     * [LifeOsException.Protocol] before a request goes there.
     */
    private suspend fun fromGitHub(
        repo: String,
        url: String,
        idempotent: Boolean,
        accept: String,
        extraHeaders: Map<String, String> = emptyMap(),
        sink: ResponseSink? = null,
        onProgress: ((Long) -> Unit)? = null,
        timeoutMillis: Long = DEFAULT_TIMEOUT_MILLIS,
    ): HttpResponse {
        var address = url
        var redirects = 0
        while (true) {
            val request = HttpRequest(
                method = "GET",
                url = address,
                headers = buildMap {
                    put("Accept", accept)
                    put("User-Agent", userAgent)
                    putAll(extraHeaders)
                },
                timeoutMillis = timeoutMillis,
            )
            val response = perform(request, idempotent, sink, onProgress, redirects = true)
            if (response.status !in 300..399) return response
            val next = response.redirectLocation?.let { GitHubReleases.resolve(address, it) }
            if (next == null || !GitHubReleases.mayRedirectTo(repo, next)) {
                throw LifeOsException.Protocol("GitHub sent the update to ${GitHubReleases.host(next)}, which is not GitHub's download of $repo")
            }
            if (++redirects > GitHubReleases.MAX_REDIRECTS) throw LifeOsException.Protocol("GitHub redirected the update more than ${GitHubReleases.MAX_REDIRECTS} times")
            address = next
        }
    }

    /**
     * Sends [request], retrying an [idempotent] one on errors that may pass,
     * and turns an answer that is not a success into a [LifeOsException]; a
     * 3xx is returned as it is when [redirects] (the caller follows it).
     * [key] is the device key the request carries, if any: Life OS's verdict
     * on it goes to [keyListener].
     */
    private suspend fun perform(
        request: HttpRequest,
        idempotent: Boolean,
        sink: ResponseSink?,
        onProgress: ((Long) -> Unit)?,
        redirects: Boolean = false,
        key: DeviceKey? = null,
    ): HttpResponse {
        var attempt = 0
        var slowRetried = false
        while (true) {
            val started = clockMillis()
            val failure = try {
                val response = transport.execute(request, sink, onProgress)
                val result = if (redirects && response.status in 300..399) response else checked(response, keySent = key != null, url = request.url)
                if (key != null && result.status in 200..299) keyListener?.accepted(key)
                return result
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: LifeOsException) {
                error
            } catch (error: TransportException) {
                mapTransport(error)
            } catch (error: java.io.IOException) {
                LifeOsException.Local(error.message ?: error.javaClass.simpleName, error)
            }
            if (failure is LifeOsException.SignInRequired && key != null) {
                // The gateway checked the key when the headers arrived; the identity token it gave the
                // service lasts at least 4 minutes. A slower upload can see the service's 401: once more.
                if (idempotent && !slowRetried && clockMillis() - started >= SLOW_REQUEST_MILLIS) {
                    slowRetried = true
                    continue
                }
                keyListener?.refused(key)
                throw failure
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

        /** One APK download, from the first byte to the last. */
        const val DOWNLOAD_TIMEOUT_MILLIS = 15 * 60_000L

        const val PAIR_PATH = "/api/auth/devices/pair"
        const val CHILD_PATH = "/api/auth/devices/child"
        const val ME_PATH = "/api/auth/devices/me"
        const val SIGN_OUT_PATH = "/api/auth/devices/sign-out"

        /**
         * A request that ran this long before Life OS answered 401 may have
         * outlived the identity token the gateway gave the service (at least
         * 4 minutes from the headers): it is tried once more.
         */
        const val SLOW_REQUEST_MILLIS = 3 * 60_000L

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

        /**
         * Turns an HTTP answer that is not a success into the matching
         * [LifeOsException] (life-os docs/api/devices.md, "When a request is
         * refused"). [keySent]: the request carried a device key. [url]
         * names the module a 403 `module_not_allowed` is about.
         */
        fun checked(response: HttpResponse, keySent: Boolean = false, url: String? = null): HttpResponse {
            val status = response.status
            if (status in 200..299) return response
            if (status in 300..399) {
                val host = response.redirectLocation?.let { runCatching { URI(it).host }.getOrNull() } ?: "unknown host"
                if (host == "cloudflareaccess.com" || host.endsWith(".cloudflareaccess.com")) throw LifeOsException.AccessRedirect(host)
                throw LifeOsException.Protocol("a redirect ($status) to $host")
            }
            val envelope = errorEnvelope(response)
            val code = envelope?.error?.code?.takeIf { it.isNotBlank() }
            val message = envelope?.error?.message?.takeIf { it.isNotBlank() }
            if (status == 401 || status == 403) {
                when {
                    // Life OS's own 401 always carries its JSON error; Cloudflare's never does.
                    status == 401 && code != null -> throw LifeOsException.SignInRequired(keySent)
                    status == 403 && code == MODULE_NOT_ALLOWED -> throw LifeOsException.ModuleNotAllowed(url?.let(::moduleOf))
                    // admin_only, not_a_phone_key, insufficient_scope…: a refusal, not a sign-in problem.
                    // The gateway's plain "forbidden" is what it answers tunnel traffic without Access's token.
                    status == 403 && code != null && code != "forbidden" ->
                        throw LifeOsException.Api(status, code, message ?: defaultMessage(status))
                    else -> throw LifeOsException.Unauthorized(status)
                }
            }
            if (status in 400..499) {
                val retryAfter = if (status == 429) response.header("retry-after")?.trim()?.toLongOrNull() else null
                throw LifeOsException.Api(status, code ?: "http_$status", message ?: defaultMessage(status), retryAfter)
            }
            throw LifeOsException.Server(status, code, message ?: defaultMessage(status))
        }

        const val MODULE_NOT_ALLOWED = "module_not_allowed"
        const val UNAUTHENTICATED = "unauthenticated"

        /** The `code` of a Life OS error body (`{"error":{"code","message"}}`), or null. */
        fun errorCode(body: ByteArray): String? {
            if (body.isEmpty() || body.size > 64 * 1024) return null
            return try {
                LifeOsJson.decodeFromString(ErrorEnvelope.serializer(), body.toString(Charsets.UTF_8)).error?.code?.takeIf { it.isNotBlank() }
            } catch (_: SerializationException) {
                null
            } catch (_: IllegalArgumentException) {
                null
            }
        }

        /** The module an API address belongs to, as the app names it ("Voice notes"), or null. */
        fun moduleOf(url: String): String? {
            val path = runCatching { URI(url).rawPath }.getOrNull() ?: url
            val segment = path.removePrefix("/api/").substringBefore('/')
            return when (segment) {
                "tasks" -> "Tasks"
                "calendar" -> "Calendar"
                "inbox" -> "Inbox"
                "voice" -> "Voice notes"
                "notifications" -> "Notifications"
                "food" -> "Food"
                "finance" -> "Finance"
                "activity" -> "the Event Log"
                else -> null
            }
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

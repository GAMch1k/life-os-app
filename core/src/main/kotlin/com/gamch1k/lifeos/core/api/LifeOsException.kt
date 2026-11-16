package com.gamch1k.lifeos.core.api

/**
 * Everything that can go wrong talking to Life OS, as the app shows it.
 * [message] is a sentence for the owner; it never contains the token.
 */
sealed class LifeOsException(message: String, cause: Throwable? = null) : Exception(message, cause) {
    /** Whether the same request may succeed later without anyone changing anything. */
    abstract val retryable: Boolean

    /** The service token is missing, wrong, or not let in: only the owner can fix it. */
    open val authProblem: Boolean get() = false

    /** Neither a device key nor a service token is set up on this phone. */
    class NotSignedIn : LifeOsException("This phone is not connected to Life OS yet: pair it with your account, or set up the service token.") {
        override val retryable get() = false
        override val authProblem get() = true
    }

    /**
     * Life OS itself (not Cloudflare Access) answered 401
     * `{"error":{"code":"unauthenticated"}}`. With [keySent] the phone's
     * device key no longer works: revoked in Settings → Devices, signed out
     * everywhere, or the account was disabled. Without a key, Life OS needs
     * one: legacy access is off. Either way the phone must be paired (again).
     */
    class SignInRequired(val keySent: Boolean) : LifeOsException(
        if (keySent) "This phone is no longer connected to Life OS (its key was signed out). Pair it again: Settings › Account."
        else "Life OS asks this phone to sign in: pair it with your account (Settings › Account › Pair this phone).",
    ) {
        override val retryable get() = false
        override val authProblem get() = true
    }

    /**
     * 401 `identity_expired` from a service: the gateway let the request
     * through, but the identity token it carried ran out before the service
     * read it (a very slow upload). Never the key's fault: an idempotent
     * request is sent once more at once, and anything else is retried later
     * like any failure that may pass (a capture stays queued).
     */
    class IdentityExpired : LifeOsException(
        "Life OS received the request too late to accept it (401 identity_expired, a very slow upload); it is sent again.",
    ) {
        override val retryable get() = true
    }

    /**
     * 403 `module_not_allowed`: the account may not use [module] on this
     * server (an admin turned it off for the person). Not a sign-in
     * problem: pairing again would not help.
     */
    class ModuleNotAllowed(val module: String?) : LifeOsException(
        "Your account can't use ${module ?: "this part of Life OS"} on this server.",
    ) {
        override val retryable get() = false
    }

    /** `POST /api/auth/devices/pair` answered 404: a server from before device pairing (P21-06). */
    class PairingUnsupported : LifeOsException(
        "This Life OS server is older than device pairing. The app keeps using its Cloudflare Access service token; update the server to pair.",
    ) {
        override val retryable get() = false
    }

    /** No HTTP answer: no network, the name did not resolve, or the connection was reset. */
    class Offline(val detail: String, val noNetwork: Boolean, cause: Throwable? = null) : LifeOsException(
        if (noNetwork) "No connection: the phone is offline or cannot resolve Life OS ($detail)." else "Could not reach Life OS ($detail).",
        cause,
    ) {
        override val retryable get() = true
    }

    class Timeout(val seconds: Long) : LifeOsException("Life OS did not answer within $seconds s.") {
        override val retryable get() = true
    }

    /**
     * Cloudflare Access (or the gateway behind it) refused the request: 401
     * means the Client ID or Secret is wrong, incomplete, or expired; 403 that
     * the token is valid but not allowed into the Life OS application.
     */
    class Unauthorized(val status: Int) : LifeOsException(
        when (status) {
            401 -> "Cloudflare Access refused the service token (401): the Client ID or Secret is wrong or incomplete (the ID ends in .access), or the token expired."
            else -> "Access denied ($status): the token reached Cloudflare but the Life OS application did not let it in. Check that a Service Auth policy includes this token."
        },
    ) {
        override val retryable get() = false
        override val authProblem get() = true
    }

    /** Access sent us to its login page: no Service Auth policy accepts this token. */
    class AccessRedirect(val host: String) : LifeOsException(
        "Cloudflare Access asked for a login ($host): no Service Auth policy of the Life OS application accepts this token.",
    ) {
        override val retryable get() = false
        override val authProblem get() = true
    }

    /**
     * A 4xx from Life OS, with its error envelope's code and message when it
     * sent one, and for a 429 how long to wait (`Retry-After`, in seconds).
     */
    class Api(val status: Int, val code: String, val detail: String, val retryAfterSeconds: Long? = null) :
        LifeOsException("Life OS refused the request ($status $code): $detail") {
        override val retryable get() = status == 408 || status == 429
    }

    /** A 5xx: Life OS, the gateway, or Cloudflare had a problem. */
    class Server(val status: Int, val code: String?, val detail: String) : LifeOsException("Life OS had a problem ($status): $detail") {
        override val retryable get() = true
    }

    /** A problem on the phone itself, e.g. no space left for a download. */
    class Local(detail: String, cause: Throwable? = null) : LifeOsException("On this phone: $detail", cause) {
        override val retryable get() = false
    }

    /** The answer was not what the API promises (e.g. a web page instead of JSON). */
    class Protocol(detail: String, cause: Throwable? = null) : LifeOsException("Unexpected answer from Life OS: $detail", cause) {
        override val retryable get() = false
    }
}

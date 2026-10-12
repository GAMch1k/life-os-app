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

    /** No service token is set up on this phone. */
    class NotSignedIn : LifeOsException("This phone is not connected to Life OS yet: set up the service token.") {
        override val retryable get() = false
        override val authProblem get() = true
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

    /** A 4xx from Life OS, with its error envelope's code and message when it sent one. */
    class Api(val status: Int, val code: String, val detail: String) : LifeOsException("Life OS refused the request ($status $code): $detail") {
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

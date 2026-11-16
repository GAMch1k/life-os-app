package com.gamch1k.lifeos.core.pairing

import com.gamch1k.lifeos.core.api.Connection
import com.gamch1k.lifeos.core.api.LifeOsClient
import com.gamch1k.lifeos.core.api.LifeOsException
import com.gamch1k.lifeos.core.secrets.TokenStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Signs a device key out of Life OS (`POST /api/auth/devices/sign-out`)
 * when the phone lets it go: unpairing, signing out, pairing again, another
 * server. The phone forgets the key at once either way; when Life OS does
 * not confirm (no network, a timeout, a 5xx), the key waits in the token
 * store ([TokenStore.queueRevoke]) and [retryPending] tries again when the
 * phone is online (the app's RevokeWorker). One Life OS or Cloudflare
 * refuses, or that is still not through after [MAX_AGE_MILLIS], is given up:
 * the app then asks the person to sign the phone out in Settings → Devices
 * on the web.
 */
class KeyRevoker(
    private val tokens: TokenStore,
    private val client: LifeOsClient,
    private val clock: () -> Long = System::currentTimeMillis,
    private val attemptMillis: Long = 10_000,
) {
    enum class Result {
        /** Nothing left to try. */
        DONE,

        /** Some keys may still go through: try again later. */
        RETRY,
    }

    private enum class Attempt { SIGNED_OUT, TRY_LATER, REFUSED }

    /**
     * Signs [target]'s key out now; true when Life OS confirmed (or the key
     * was gone already). Otherwise it is queued, and false.
     */
    suspend fun revoke(target: Connection): Boolean {
        if (target.deviceKey == null) return true
        return when (attempt(target)) {
            Attempt.SIGNED_OUT -> true
            Attempt.TRY_LATER -> {
                tokens.queueRevoke(target, nowMillis = clock())
                false
            }
            Attempt.REFUSED -> {
                tokens.queueRevoke(target, failed = true, nowMillis = clock())
                false
            }
        }
    }

    /** One pass over the queued keys. [giveUp]: the last try (the worker's attempts are used up). */
    suspend fun retryPending(giveUp: Boolean = false): Result {
        var left = false
        for (pending in tokens.pendingRevokes().filterNot { it.failed }) {
            when (attempt(pending.connection)) {
                Attempt.SIGNED_OUT -> tokens.revokeDone(pending.id)
                Attempt.REFUSED -> tokens.revokeFailed(pending.id)
                Attempt.TRY_LATER -> if (giveUp || clock() - pending.sinceMillis > MAX_AGE_MILLIS) {
                    tokens.revokeFailed(pending.id)
                } else {
                    left = true
                }
            }
        }
        return if (left) Result.RETRY else Result.DONE
    }

    private suspend fun attempt(target: Connection): Attempt = try {
        withTimeoutOrNull(attemptMillis) {
            client.signOutKey(target)
            Attempt.SIGNED_OUT
        } ?: Attempt.TRY_LATER
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: LifeOsException) {
        // Offline, a timeout, a 5xx or a 429 may pass; Access or Life OS refusing will not.
        if (error.retryable) Attempt.TRY_LATER else Attempt.REFUSED
    }

    companion object {
        /** How long a key is tried before the app asks the person to sign the phone out on the web. */
        const val MAX_AGE_MILLIS = 3 * 24 * 3_600_000L
    }
}

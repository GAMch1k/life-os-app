package com.gamch1k.lifeos.core.pairing

/**
 * Where a pairing code from a link or a QR code may lead. A code that comes
 * without this phone's own `state` (not from the Custom Tab it opened) can
 * be anyone's, and a single tap must not move a phone that is set up to
 * another server:
 *
 * - a phone not set up yet (no service token, no key) may pair with any
 *   https server, after "Pair with <server>?";
 * - a phone that is set up may pair only with its own server, or with one
 *   the person typed themselves for this pairing (the first run, or
 *   Settings › Edit connection);
 * - anything else is refused, with what to do instead.
 *
 * A typed code and the Custom Tab's code are redeemed at the server the app
 * chose itself from those two, so they need no verdict.
 */
object PairingTarget {
    sealed interface Verdict {
        /** Ask "Pair with <server>?", then pair. */
        data object Ask : Verdict

        data class Refuse(val message: String) : Verdict
    }

    /**
     * [server]: the link's. [configured]: the app's server when the phone is
     * set up, else null. [typed]: the server the person typed for this
     * pairing, if any.
     */
    fun forLink(server: String, configured: String?, typed: String?): Verdict = when {
        configured == null -> Verdict.Ask
        same(server, configured) || (typed != null && same(server, typed)) -> Verdict.Ask
        else -> Verdict.Refuse(
            "This code is for ${PairingLink.authorityOf(server)}; this phone uses ${PairingLink.authorityOf(configured)}. " +
                "Change the server in Settings first (Settings › Connection › Edit connection), then pair from there.",
        )
    }

    /** Whether a pairing at [server] may replace the phone's [configured] server ([forLink]'s rule, for the token store). */
    fun mayMoveTo(server: String, configured: String?, typed: String?): Boolean = forLink(server, configured, typed) == Verdict.Ask

    /** Both https URLs as [com.gamch1k.lifeos.core.secrets.ServerUrl] writes them; host names ignore case. */
    fun same(a: String, b: String): Boolean = a.trimEnd('/').equals(b.trimEnd('/'), ignoreCase = true)
}

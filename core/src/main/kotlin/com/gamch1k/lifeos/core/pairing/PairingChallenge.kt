package com.gamch1k.lifeos.core.pairing

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/**
 * The Custom Tab flow's proof (life-os `docs/api/devices.md`, "Flow 2"):
 * a random [verifier], its [challenge] `BASE64URL(SHA-256(verifier))`
 * without padding, as PKCE does, and a random [state]. The sign-in page
 * binds the code it makes to the challenge, so a code caught by another app
 * that registered `lifeos://` is useless without the verifier, which never
 * leaves the phone until the code is redeemed.
 */
class PairingChallenge(val verifier: String, val state: String) {
    val challenge: String = challengeOf(verifier)

    /** `<server>/login?pair=1&challenge=…&state=…`, the page the Custom Tab opens. */
    fun signInUrl(server: String): String = server.trimEnd('/') + "/login?pair=1&challenge=$challenge&state=$state"

    override fun toString() = "PairingChallenge(…)"

    companion object {
        private val encoder: Base64.Encoder = Base64.getUrlEncoder().withoutPadding()

        /** 48 random bytes: a 64-character verifier from `A–Z a–z 0–9 - _` (43 to 128 allowed). */
        fun create(random: SecureRandom = SecureRandom()): PairingChallenge = PairingChallenge(
            verifier = encoder.encodeToString(ByteArray(48).also(random::nextBytes)),
            // 24 random bytes: 32 characters (8 to 128 allowed).
            state = encoder.encodeToString(ByteArray(24).also(random::nextBytes)),
        )

        fun challengeOf(verifier: String): String =
            encoder.encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)))
    }
}

package com.gamch1k.lifeos.core.pairing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** A link or QR code without this phone's state never moves a set-up phone to another server. */
class PairingTargetTest {
    private val own = "https://lifeos.example.com"
    private val other = "https://other.example.org:8443"

    @Test
    fun `a fresh install may pair with any https server, after the question`() {
        assertEquals(PairingTarget.Verdict.Ask, PairingTarget.forLink(other, configured = null, typed = null))
        assertEquals(PairingTarget.Verdict.Ask, PairingTarget.forLink(other, configured = null, typed = own))
    }

    @Test
    fun `a set-up phone pairs with its own server`() {
        assertEquals(PairingTarget.Verdict.Ask, PairingTarget.forLink(own, configured = own, typed = null))
        assertEquals("host names ignore case", PairingTarget.Verdict.Ask, PairingTarget.forLink("https://LifeOS.example.com", configured = own, typed = null))
    }

    @Test
    fun `another server only when the person typed it`() {
        assertEquals(PairingTarget.Verdict.Ask, PairingTarget.forLink(other, configured = own, typed = other))
        val refused = PairingTarget.forLink(other, configured = own, typed = null) as PairingTarget.Verdict.Refuse
        assertEquals(
            "This code is for other.example.org:8443; this phone uses lifeos.example.com. " +
                "Change the server in Settings first (Settings › Connection › Edit connection), then pair from there.",
            refused.message,
        )
        assertTrue(PairingTarget.forLink(other, configured = own, typed = "https://third.example.net") is PairingTarget.Verdict.Refuse)
        assertTrue("the port is part of the server", PairingTarget.forLink("https://lifeos.example.com:8443", configured = own, typed = null) is PairingTarget.Verdict.Refuse)
    }
}

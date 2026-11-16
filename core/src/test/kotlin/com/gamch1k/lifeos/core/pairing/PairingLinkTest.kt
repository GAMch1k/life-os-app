package com.gamch1k.lifeos.core.pairing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.SecureRandom

class PairingLinkTest {
    private val host = "https%3A%2F%2Flifeos.example.com"

    @Test
    fun `the QR code's link gives the server and the code`() {
        val link = PairingLink.parse("lifeos://pair?host=$host&code=K7QM-2XRP-9VDA-H4TC") as PairingLink.Code
        assertEquals("https://lifeos.example.com", link.server)
        assertEquals("lifeos.example.com", link.authority)
        assertEquals("K7QM-2XRP-9VDA-H4TC", link.code)
        assertNull(link.state)
    }

    @Test
    fun `the Custom Tab's link carries the state back`() {
        val link = PairingLink.parse("lifeos://pair?host=$host&code=k7qm2xrp9vdah4tc&state=abcdEFGH_1234-xy") as PairingLink.Code
        assertEquals("case and dashes do not matter", "K7QM-2XRP-9VDA-H4TC", link.code)
        assertEquals("abcdEFGH_1234-xy", link.state)
    }

    @Test
    fun `Cancel on the sign-in page is a refusal for its state`() {
        assertEquals(PairingLink.Refused("access_denied", "abcdEFGH_1234-xy"), PairingLink.parse("lifeos://pair?error=access_denied&state=abcdEFGH_1234-xy"))
        assertNull("an error without the state it answers", PairingLink.parse("lifeos://pair?error=access_denied"))
        assertNull("an error and a code at once", PairingLink.parse("lifeos://pair?host=$host&code=K7QM-2XRP-9VDA-H4TC&error=access_denied&state=abcdEFGH"))
    }

    @Test
    fun `a port, a trailing slash, an unencoded host and an upper-case scheme are fine`() {
        val withPort = PairingLink.parse("lifeos://pair?host=https%3A%2F%2Flifeos.example.com%3A8443%2F&code=K7QM-2XRP-9VDA-H4TC") as PairingLink.Code
        assertEquals("https://lifeos.example.com:8443", withPort.server)
        assertEquals("the question shows the port too", "lifeos.example.com:8443", withPort.authority)
        assertEquals("https://lifeos.example.com", (PairingLink.parse("lifeos://pair?host=https://lifeos.example.com&code=K7QM-2XRP-9VDA-H4TC") as PairingLink.Code).server)
        assertEquals("https://lifeos.example.com", (PairingLink.parse("LIFEOS://PAIR?host=HTTPS%3A%2F%2Flifeos.example.com&code=K7QM-2XRP-9VDA-H4TC") as PairingLink.Code).server)
        assertTrue("unknown parameters are ignored", PairingLink.parse("lifeos://pair?host=$host&code=K7QM-2XRP-9VDA-H4TC&v=2") is PairingLink.Code)
    }

    @Test
    fun `only lifeos pair with an https host is a pairing link`() {
        val refused = listOf(
            // Another scheme or host, or extra parts.
            "https://lifeos.example.com/pair?host=$host&code=K7QM-2XRP-9VDA-H4TC",
            "lifeos://other?host=$host&code=K7QM-2XRP-9VDA-H4TC",
            "lifeos://pair:443?host=$host&code=K7QM-2XRP-9VDA-H4TC",
            "lifeos://user@pair?host=$host&code=K7QM-2XRP-9VDA-H4TC",
            "lifeos://pair/extra?host=$host&code=K7QM-2XRP-9VDA-H4TC",
            "lifeos://pair?host=$host&code=K7QM-2XRP-9VDA-H4TC#fragment",
            "lifeos:pair?host=$host&code=K7QM-2XRP-9VDA-H4TC",
            // Not https: plain http (even localhost), no scheme, javascript, a file.
            "lifeos://pair?host=http%3A%2F%2Flifeos.example.com&code=K7QM-2XRP-9VDA-H4TC",
            "lifeos://pair?host=http%3A%2F%2Flocalhost%3A3000&code=K7QM-2XRP-9VDA-H4TC",
            "lifeos://pair?host=lifeos.example.com&code=K7QM-2XRP-9VDA-H4TC",
            "lifeos://pair?host=javascript%3Aalert(1)&code=K7QM-2XRP-9VDA-H4TC",
            "lifeos://pair?host=file%3A%2F%2F%2Fetc%2Fpasswd&code=K7QM-2XRP-9VDA-H4TC",
            // A host with a user, a query, a fragment or a path: the public URL is an origin.
            "lifeos://pair?host=https%3A%2F%2Fevil%40lifeos.example.com&code=K7QM-2XRP-9VDA-H4TC",
            "lifeos://pair?host=https%3A%2F%2Flifeos.example.com%2Fsomewhere&code=K7QM-2XRP-9VDA-H4TC",
            "lifeos://pair?host=https%3A%2F%2Fshared.example.com%2Flifeos%2F&code=K7QM-2XRP-9VDA-H4TC",
            "lifeos://pair?host=https%3A%2F%2Flifeos.example.com%3Fx%3D1&code=K7QM-2XRP-9VDA-H4TC",
            // Missing, malformed or doubled parameters.
            "lifeos://pair?code=K7QM-2XRP-9VDA-H4TC",
            "lifeos://pair?host=$host",
            "lifeos://pair",
            "lifeos://pair?host=$host&code=K7QM-2XRP-9VDA",
            "lifeos://pair?host=$host&code=K7QM-2XRP-9VDA-H4T1",
            "lifeos://pair?host=$host&code=K7QM-2XRP-9VDA-H4TC&code=AAAA-BBBB-CCCC-DDDD",
            "lifeos://pair?host=$host&host=https%3A%2F%2Fevil.example.com&code=K7QM-2XRP-9VDA-H4TC",
            "lifeos://pair?host=$host&code=K7QM-2XRP-9VDA-H4TC&state=short",
            "lifeos://pair?host=$host&code=K7QM-2XRP-9VDA-H4TC&state=has%20space%20inside",
            "lifeos://pair?host=%ZZ&code=K7QM-2XRP-9VDA-H4TC",
            // Whitespace or control characters anywhere, and nonsense.
            "lifeos://pair?host=$host&code=K7QM-2XRP-9VDA-H4TC\n",
            " lifeos://pair?host=$host&code=K7QM-2XRP-9VDA-H4TC",
            "",
            "not a link",
        )
        refused.forEach { assertNull(it, PairingLink.parse(it)) }
    }

    @Test
    fun `a code never shows in the link's text`() {
        val link = PairingLink.parse("lifeos://pair?host=$host&code=K7QM-2XRP-9VDA-H4TC")!!
        assertFalse(link.toString().contains("K7QM"))
    }

    @Test
    fun `typed codes are normalized, anything else is refused`() {
        assertEquals("K7QM-2XRP-9VDA-H4TC", PairingCode.normalize(" k7qm 2xrp-9vda h4tc "))
        assertEquals("K7QM-2XRP-9VDA-H4TC", PairingCode.normalize("K7QM2XRP9VDAH4TC"))
        assertNull("O is not in the alphabet", PairingCode.normalize("K7QM-2XRP-9VDA-H4TO"))
        assertNull("nor 0 or 1", PairingCode.normalize("K7QM-2XRP-9VDA-H401"))
        assertNull(PairingCode.normalize("K7QM-2XRP-9VDA-H4TCX"))
        assertNull(PairingCode.normalize(""))
    }

    @Test
    fun `the challenge is PKCE's S256 of the verifier`() {
        // RFC 7636, appendix B.
        assertEquals("E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM", PairingChallenge.challengeOf("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"))
    }

    @Test
    fun `a new challenge has the lengths and characters the sign-in page accepts`() {
        val first = PairingChallenge.create(SecureRandom())
        val second = PairingChallenge.create(SecureRandom())
        assertTrue(first.verifier.length in 43..128 && first.verifier.all { it.isLetterOrDigit() || it in "-._~" })
        assertTrue(first.state.length in 8..128 && Regex("^[A-Za-z0-9_-]+$").matches(first.state))
        assertEquals(43, first.challenge.length)
        assertEquals(PairingChallenge.challengeOf(first.verifier), first.challenge)
        assertTrue(first.verifier != second.verifier && first.state != second.state)
        assertEquals(
            "https://lifeos.example.com/login?pair=1&challenge=${first.challenge}&state=${first.state}",
            first.signInUrl("https://lifeos.example.com/"),
        )
        assertFalse("the verifier never leaves in the URL", first.signInUrl("https://lifeos.example.com").contains(first.verifier))
        assertFalse(first.toString().contains(first.verifier))
    }

    @Test
    fun `device names are 1 to 64 visible characters`() {
        assertEquals("Galaxy S24", DeviceNames.clean("  Galaxy\tS24\n", DeviceNames.PHONE_FALLBACK))
        assertEquals("Galaxy Watch8 (A1B2)", DeviceNames.clean("Galaxy Watch8\u0000 (A1B2)​", DeviceNames.WATCH_FALLBACK))
        assertEquals(DeviceNames.PHONE_FALLBACK, DeviceNames.clean(null, DeviceNames.PHONE_FALLBACK))
        assertEquals(DeviceNames.WATCH_FALLBACK, DeviceNames.clean("\u0007 \n", DeviceNames.WATCH_FALLBACK))
        assertEquals(64, DeviceNames.clean("x".repeat(100), "f").length)
        val emoji = "a".repeat(63) + "😀"
        assertEquals("never half an emoji", "a".repeat(63), DeviceNames.clean(emoji, "f"))
    }
}

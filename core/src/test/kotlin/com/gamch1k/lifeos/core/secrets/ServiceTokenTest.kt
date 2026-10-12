package com.gamch1k.lifeos.core.secrets

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class ServiceTokenTest {
    private val token = ServiceToken(
        clientId = "0123456789abcdef0123456789abcdef.access",
        clientSecret = "f".repeat(64),
    )

    @Test
    fun `the masked client ID keeps only its start and the access suffix`() {
        assertEquals("0123••••.access", token.maskedClientId)
    }

    @Test
    fun `the masked secret shows no character of the secret`() {
        assertFalse(token.maskedClientSecret.contains("f"))
        assertEquals("•••••••• (64 characters)", token.maskedClientSecret)
    }

    @Test
    fun `toString never shows the values`() {
        assertFalse(token.toString().contains("0123456789"))
        assertFalse(token.toString().contains("ffff"))
    }

    @Test
    fun `a short value is mostly hidden`() {
        assertEquals("ab••••", ServiceToken.mask("abcdefgh", keepStart = 4, keepSuffix = ".access"))
    }
}

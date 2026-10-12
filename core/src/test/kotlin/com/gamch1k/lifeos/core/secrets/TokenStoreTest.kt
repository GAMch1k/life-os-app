package com.gamch1k.lifeos.core.secrets

import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.gamch1k.lifeos.core.TestDataStores
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.security.GeneralSecurityException
import java.util.Base64

class TokenStoreTest {
    @get:Rule
    val stores = TestDataStores()

    /** Reversible and visibly not the plain text; can be told to lose its key. */
    private class FakeCipher : SecretCipher {
        var keyLost = false
        override fun encrypt(plain: String) = "sealed:" + Base64.getEncoder().encodeToString(plain.reversed().toByteArray())
        override fun decrypt(sealed: String): String {
            if (keyLost) throw GeneralSecurityException("key is gone")
            require(sealed.startsWith("sealed:")) { "bad ciphertext" }
            return String(Base64.getDecoder().decode(sealed.removePrefix("sealed:"))).reversed()
        }
    }

    private val token = ServiceToken("0123456789abcdef0123456789abcdef.access", "cfast_" + "a".repeat(48))

    @Test
    fun `a saved token comes back, and only ciphertext is stored`() = runBlocking {
        val dataStore = stores.create()
        val store = TokenStore(dataStore, FakeCipher())
        store.save("lifeos.example.com/", token)
        val connection = store.current()!!
        assertEquals("https://lifeos.example.com", connection.baseUrl)
        assertEquals(token.clientId, connection.token.clientId)
        assertEquals(token.clientSecret, connection.token.clientSecret)
        val raw = dataStore.data.first().asMap().values.joinToString()
        assertFalse(raw.contains(token.clientId))
        assertFalse(raw.contains(token.clientSecret))
    }

    @Test
    fun `sign out wipes the token but keeps the server address`() = runBlocking {
        val store = TokenStore(stores.create(), FakeCipher())
        store.save("https://lifeos.example.com", token)
        store.clear()
        assertNull(store.current())
        assertEquals("https://lifeos.example.com", store.serverUrl())
    }

    @Test
    fun `a lost Keystore key means signed out, not a crash`() = runBlocking {
        val cipher = FakeCipher()
        val store = TokenStore(stores.create(), cipher)
        store.save(ServerUrl.DEFAULT, token)
        cipher.keyLost = true
        assertNull(store.current())
    }

    @Test
    fun `corrupt ciphertext means signed out`() = runBlocking {
        val dataStore = stores.create()
        val store = TokenStore(dataStore, FakeCipher())
        dataStore.edit {
            it[stringPreferencesKey("client-id")] = "garbage"
            it[stringPreferencesKey("client-secret")] = "garbage"
        }
        assertNull(store.current())
    }

    @Test
    fun `the default server is used until one is saved`() = runBlocking {
        assertEquals("https://lifeos.gamch1k.com", TokenStore(stores.create(), FakeCipher()).serverUrl())
    }

    @Test
    fun `server addresses are https only`() {
        assertEquals("https://lifeos.gamch1k.com", ServerUrl.normalize(" lifeos.gamch1k.com/ "))
        assertEquals("https://lifeos.gamch1k.com", ServerUrl.normalize("https://lifeos.gamch1k.com"))
        assertEquals("https://example.com:8443/lifeos", ServerUrl.normalize("https://example.com:8443/lifeos/"))
        assertNull(ServerUrl.normalize("http://10.66.66.1:3001"))
        assertNull(ServerUrl.normalize(""))
        assertNull(ServerUrl.normalize("https://"))
        assertNull(ServerUrl.normalize("https://example.com/?x=1"))
        assertNull(ServerUrl.normalize("https://user@example.com"))
        assertNull(ServerUrl.normalize("life os"))
    }

    @Test
    fun `a new token replaces the old one at once`() = runBlocking {
        val store = TokenStore(stores.create(), FakeCipher())
        store.save(ServerUrl.DEFAULT, token)
        assertEquals(token.clientId, store.current()!!.token.clientId)
        store.save(ServerUrl.DEFAULT, ServiceToken("other.access", "other-secret"))
        assertEquals("other.access", store.current()!!.token.clientId)
        assertEquals("other-secret", store.current()!!.token.clientSecret)
    }

    @Test
    fun `invisible characters around a paste are dropped, inside it they are refused`() {
        assertEquals("abc.access", ServiceToken.parse("\u200Babc.access\uFEFF", "s")!!.clientId)
        assertNull(ServiceToken.parse("abc\u200B.access", "s"))
        assertNull(ServiceToken.parse("abc.access", "sec\u0007ret"))
        assertNull(ServiceToken.parse("abc.access", "sécret"))
    }

    @Test
    fun `pasted values are trimmed, and a paste with spaces inside is refused`() {
        val parsed = ServiceToken.parse("  abc.access\n", "\tsecret ")!!
        assertEquals("abc.access", parsed.clientId)
        assertEquals("secret", parsed.clientSecret)
        assertNull(ServiceToken.parse("abc.access", ""))
        assertNull(ServiceToken.parse("abc .access", "secret"))
        assertTrue(ServiceToken.parse("a", "b") != null)
    }
}

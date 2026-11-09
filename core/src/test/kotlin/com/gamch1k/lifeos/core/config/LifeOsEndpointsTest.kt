package com.gamch1k.lifeos.core.config

import com.gamch1k.lifeos.core.secrets.ServerUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LifeOsEndpointsTest {
    @Test
    fun `the build's servers are https origins that onboarding takes as they are`() {
        for (origin in listOf(LifeOsEndpoints.SERVER_URL, LifeOsEndpoints.UPDATES_URL)) {
            assertEquals(origin, ServerUrl.normalize(origin))
            assertTrue(origin, Regex("https://[a-z0-9.-]+(:[0-9]+)?").matches(origin))
        }
        assertEquals("onboarding offers the build's server", LifeOsEndpoints.SERVER_URL, ServerUrl.DEFAULT)
    }

    @Test
    fun `the network check's addresses follow the server it is given`() {
        assertEquals("lifeos.example.com", LifeOsEndpoints.host("https://lifeos.example.com"))
        assertEquals("https://lifeos.example.com/cdn-cgi/trace", LifeOsEndpoints.traceUrl("https://lifeos.example.com"))
        assertEquals("https://lifeos.example.com/api/tasks/v1/tasks?limit=1", LifeOsEndpoints.tasksProbeUrl("https://lifeos.example.com"))
        // A server under a path: the trace is the edge's, at the origin; the API is under the path.
        assertEquals("https://example.com:8443/cdn-cgi/trace", LifeOsEndpoints.traceUrl("https://example.com:8443/lifeos"))
        assertEquals("https://example.com:8443/lifeos/api/tasks/v1/tasks?limit=1", LifeOsEndpoints.tasksProbeUrl("https://example.com:8443/lifeos/"))
    }

    @Test
    fun `a token's server and the update origin are the same origin only with the same scheme, host and port`() {
        assertTrue(LifeOsEndpoints.sameOrigin("https://lifeos.example.com", "https://LifeOS.example.com/"))
        assertTrue(LifeOsEndpoints.sameOrigin("https://lifeos.example.com", "https://lifeos.example.com:443"))
        assertTrue(LifeOsEndpoints.sameOrigin("https://lifeos.example.com/lifeos", "https://lifeos.example.com"))
        assertFalse(LifeOsEndpoints.sameOrigin("https://my-own.example.org", "https://lifeos.example.com"))
        assertFalse(LifeOsEndpoints.sameOrigin("https://lifeos.example.com", "https://lifeos.example.com.evil.example"))
        assertFalse(LifeOsEndpoints.sameOrigin("https://lifeos.example.com", "https://lifeos.example.com:8443"))
        assertFalse(LifeOsEndpoints.sameOrigin("https://lifeos.example.com", "http://lifeos.example.com"))
        assertFalse(LifeOsEndpoints.sameOrigin("https://lifeos.example.com", "not a url"))
    }
}

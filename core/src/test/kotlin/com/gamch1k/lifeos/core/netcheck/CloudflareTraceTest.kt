package com.gamch1k.lifeos.core.netcheck

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CloudflareTraceTest {
    // A real trace from https://lifeos.gamch1k.com/cdn-cgi/trace (client IP replaced).
    private val plaintextTrace = """
        fl=67f220
        h=lifeos.gamch1k.com
        ip=203.0.113.7
        ts=1791822760.000
        visit_scheme=https
        uag=curl/8.5.0
        colo=TXL
        sliver=none
        http=http/2
        loc=DE
        tls=TLSv1.3
        sni=plaintext
        warp=off
        gateway=off
        rbi=off
        kex=X25519
    """.trimIndent() + "\n"

    @Test
    fun `parses the fields of a plaintext trace`() {
        val trace = CloudflareTrace.parse(plaintextTrace)
        assertTrue(trace.isTrace)
        assertEquals("plaintext", trace.sni)
        assertEquals("http/2", trace.http)
        assertEquals("TLSv1.3", trace.tls)
        assertEquals("off", trace.warp)
        assertFalse(trace.echAccepted)
    }

    @Test
    fun `sni=encrypted means ECH was accepted`() {
        val trace = CloudflareTrace.parse(plaintextTrace.replace("sni=plaintext", "sni=encrypted"))
        assertTrue(trace.echAccepted)
    }

    @Test
    fun `summary lines are in a fixed order and never carry the client IP`() {
        val lines = CloudflareTrace.parse(plaintextTrace).summaryLines()
        assertEquals(listOf("sni=plaintext", "http=http/2", "tls=TLSv1.3", "warp=off", "kex=X25519", "colo=TXL"), lines)
        assertFalse(lines.any { it.contains("203.0.113.7") })
    }

    @Test
    fun `tolerates CRLF, blank lines, values with equals signs, and keeps the first duplicate`() {
        val trace = CloudflareTrace.parse("h=x\r\n\r\nsni=encrypted\r\nuag=a=b c\r\nsni=plaintext\r\n")
        assertEquals("encrypted", trace.sni)
        assertEquals("a=b c", trace.fields["uag"])
    }

    @Test
    fun `an HTML page is not a trace`() {
        val trace = CloudflareTrace.parse(
            "<!DOCTYPE html>\n<html><head><meta name=\"viewport\" content=\"width=device-width\"></head></html>",
        )
        assertFalse(trace.isTrace)
        assertNull(trace.sni)
        assertTrue(trace.fields.isEmpty())
    }
}

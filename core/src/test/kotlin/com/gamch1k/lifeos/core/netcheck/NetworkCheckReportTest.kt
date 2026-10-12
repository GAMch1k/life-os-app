package com.gamch1k.lifeos.core.netcheck

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class NetworkCheckReportTest {
    private fun trace(sni: String) = CloudflareTrace.parse(
        "h=lifeos.gamch1k.com\nip=203.0.113.7\ncolo=TXL\nhttp=http/2\ntls=TLSv1.3\nsni=$sni\nwarp=off\nkex=X25519MLKEM768\n",
    )

    private val url = "https://lifeos.gamch1k.com/cdn-cgi/trace"

    private val echRecord = HttpsRecord(1, ".", listOf("h3", "h2"), emptyList(), emptyList(), 71, listOf("cloudflare-ech.com"))

    private fun result(
        cronet: HttpProbeResult = HttpProbeResult(
            "Cronet 155.0.8059.16, embedded, built-in DNS", url, 200, "h2", 412, trace("encrypted"),
            ConnectionTimings(35, 120, 80, socketReused = false), null, null,
        ),
        platform: HttpProbeResult = HttpProbeResult(
            "Android platform stack (HttpURLConnection)", url, null, null, 812, null, null, null,
            "SSLHandshakeException: connection reset",
        ),
        privateDnsActive: Boolean? = false,
        dnsAnswer: DnsHttpsAnswer? = DnsHttpsAnswer(0, listOf(echRecord)),
        authenticated: HttpProbeResult? = null,
    ) = NetworkCheckResult(
        startedAt = ZonedDateTime.of(2026, 10, 12, 17, 3, 11, 0, ZoneId.of("Europe/Berlin")),
        appVersion = "0.1.0 (1)",
        quicAllowed = false,
        device = DeviceFacts(
            androidRelease = "16",
            sdkInt = 36,
            deviceModel = "Google Pixel 9",
            network = "Wi-Fi",
            networkValidated = true,
            privateDnsActive = privateDnsActive,
            privateDnsServerName = null,
            dnsServers = listOf("10.0.0.1"),
            cronetVersion = "155.0.8059.16",
        ),
        cronet = cronet,
        httpEngine = null,
        platform = platform,
        dns = DnsProbeResult("lifeos.gamch1k.com", dnsAnswer, 21, null),
        authenticated = authenticated,
    )

    @Test
    fun `sni=encrypted through Cronet is a GO`() {
        val result = result()
        assertEquals(Verdict.ECH_WORKS, NetworkCheckReport.verdict(result))
        assertTrue(NetworkCheckReport.verdictLines(result).first().startsWith("GO:"))
    }

    @Test
    fun `a plaintext trace through Cronet is no ECH, and Private DNS is named as the likely cause`() {
        val plaintext = result().cronet.copy(trace = trace("plaintext"))
        val result = result(cronet = plaintext, privateDnsActive = true)
        assertEquals(Verdict.ECH_NOT_USED, NetworkCheckReport.verdict(result))
        val lines = NetworkCheckReport.verdictLines(result)
        assertTrue(lines.first().startsWith("NO ECH:"))
        assertTrue(lines.any { it.startsWith("Private DNS is active") })
    }

    @Test
    fun `a Cronet failure is FAILED, and missing HTTPS records are pointed out`() {
        val failed = result().cronet.copy(statusCode = null, trace = null, timings = null, error = "ERR_CONNECTION_RESET (-101)")
        val result = result(cronet = failed, dnsAnswer = DnsHttpsAnswer(0, emptyList()))
        assertEquals(Verdict.CRONET_FAILED, NetworkCheckReport.verdict(result))
        val lines = NetworkCheckReport.verdictLines(result)
        assertTrue(lines.first().contains("ERR_CONNECTION_RESET"))
        assertTrue(lines.any { it.contains("no HTTPS record with an ECH key") })
    }

    @Test
    fun `the report has every section, the trace lines and the local time`() {
        val report = NetworkCheckReport.build(result())
        assertTrue(report.startsWith("Life OS network check\n"))
        assertTrue(report.contains("App 0.1.0 (1), 2026-10-12 17:03:11 CEST (Europe/Berlin)"))
        assertTrue(report.contains("GET /cdn-cgi/trace → HTTP 200 in 412 ms (h2)"))
        assertTrue(report.contains("sni=encrypted  http=http/2  tls=TLSv1.3  warp=off  kex=X25519MLKEM768  colo=TXL"))
        assertTrue(report.contains("connection: dns 35 ms, connect 120 ms, tls 80 ms"))
        assertTrue(report.contains("GET /cdn-cgi/trace failed after 812 ms: SSLHandshakeException: connection reset"))
        assertTrue(report.contains("Private DNS: not active"))
        assertTrue(report.contains("priority 1, target .  alpn=h3,h2  ech=yes (71 bytes, public name cloudflare-ech.com)"))
        assertTrue(report.contains("No service token stored, skipped."))
        assertEquals(7, NetworkCheckReport.sections(result()).size)
    }

    @Test
    fun `the report never contains the client IP`() {
        assertFalse(NetworkCheckReport.build(result()).contains("203.0.113.7"))
    }

    @Test
    fun `a service token sent to the Access login is explained, with values omitted`() {
        val redirected = HttpProbeResult(
            "Service token request", "https://lifeos.gamch1k.com/api/tasks/v1/tasks?limit=1", 302, "h2", 230, null, null,
            "damp-surf-d6be.cloudflareaccess.com/cdn-cgi/access/login/lifeos.gamch1k.com", null,
        )
        val lines = NetworkCheckReport.authLines(redirected)
        assertEquals("Service token: set (values omitted)", lines.first())
        assertTrue(lines.contains("GET /api/tasks/v1/tasks?limit=1 → HTTP 302 in 230 ms (h2)"))
        assertTrue(lines.any { it.startsWith("Sent to the Access login") })
    }

    @Test
    fun `a 200 with the service token says it works`() {
        val ok = HttpProbeResult(
            "Service token request", "https://lifeos.gamch1k.com/api/tasks/v1/tasks?limit=1", 200, "h2", 180, null, null, null, null,
        )
        assertTrue(NetworkCheckReport.authLines(ok).any { it.startsWith("The service token works") })
    }
}

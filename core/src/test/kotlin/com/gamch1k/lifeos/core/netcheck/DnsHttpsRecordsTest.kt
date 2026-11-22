package com.gamch1k.lifeos.core.netcheck

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DnsHttpsRecordsTest {
    // A real answer from 1.1.1.1 for a Cloudflare name with ECH (2026-10-12), with the
    // name and the address hints replaced by example ones, "lifeos.example.com HTTPS":
    // 1 . alpn=h3,h2 ipv4hint=192.0.2.1,198.51.100.2 ech=AEX+DQ... ipv6hint=2001:db8::1,2001:db8::2
    private val lifeosAnswer = hex(
        "000081800001000100000000066c6966656f73076578616d706c6503636f6d0000410001c00c00410001000001260088" +
            "0001000001000602683302683200040008c0000201c6336402000500470045fe0d0041a5002000208c34de32a8e07a45" +
            "c3ef89cf56f0d210a20113117481fee01ab373f39205175e0004000100010012636c6f7564666c6172652d6563682e63" +
            "6f6d00000006002020010db800000000000000000000000120010db8000000000000000000000002",
    )

    @Test
    fun `parses an HTTPS record with ECH`() {
        val answer = DnsHttpsRecords.parse(lifeosAnswer)
        assertEquals(0, answer.rcode)
        assertTrue(answer.hasEch)
        val record = answer.records.single()
        assertEquals(1, record.priority)
        assertEquals(".", record.targetName)
        assertEquals(listOf("h3", "h2"), record.alpn)
        assertEquals(listOf("192.0.2.1", "198.51.100.2"), record.ipv4Hints)
        assertEquals(2, record.ipv6Hints.size)
        assertEquals(71, record.echConfigListSize)
        assertEquals(listOf("cloudflare-ech.com"), record.echPublicNames)
    }

    @Test
    fun `an answer without records has no ECH`() {
        // Same question, ANCOUNT 0 (NODATA).
        val nodata = lifeosAnswer.copyOfRange(0, 36).also { it[7] = 0 }
        val answer = DnsHttpsRecords.parse(nodata)
        assertEquals(0, answer.rcode)
        assertTrue(answer.records.isEmpty())
        assertFalse(answer.hasEch)
    }

    @Test
    fun `reports the rcode of an NXDOMAIN answer`() {
        val nxdomain = lifeosAnswer.copyOfRange(0, 36).also {
            it[3] = 0x83.toByte()
            it[7] = 0
        }
        assertEquals(3, DnsHttpsRecords.parse(nxdomain).rcode)
    }

    @Test
    fun `a record without ech is reported as such`() {
        // priority 1, target ".", alpn=h2 only.
        val rdata = hex("0001" + "00" + "0001" + "0003" + "026832")
        val answer = DnsHttpsRecords.parse(message(rdata))
        val record = answer.records.single()
        assertEquals(listOf("h2"), record.alpn)
        assertNull(record.echConfigListSize)
        assertFalse(answer.hasEch)
    }

    @Test(expected = DnsParseException::class)
    fun `a truncated message is rejected`() {
        DnsHttpsRecords.parse(lifeosAnswer.copyOfRange(0, lifeosAnswer.size - 10))
    }

    @Test(expected = DnsParseException::class)
    fun `a name pointer loop is rejected`() {
        // Header with one question whose name is a pointer to itself (offset 12).
        DnsHttpsRecords.parse(hex("000081800001000000000000" + "c00c" + "00410001"))
    }

    @Test
    fun `unreadable ECH configs give no public names instead of failing`() {
        assertEquals(emptyList<String>(), DnsHttpsRecords.echPublicNames(hex("0010fe0d00ff")))
    }

    /** A response with one answer record of type HTTPS carrying [rdata], name compressed to the question. */
    private fun message(rdata: ByteArray): ByteArray {
        val head = lifeosAnswer.copyOfRange(0, 36) // header + question, ANCOUNT 1
        val answer = hex("c00c" + "0041" + "0001" + "0000012c") + byteArrayOf(0, rdata.size.toByte()) + rdata
        return head + answer
    }

    private fun hex(text: String): ByteArray = text.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
}

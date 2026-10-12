package com.gamch1k.lifeos.core.netcheck

import java.net.InetAddress

/** One DNS HTTPS record (type 65, RFC 9460), reduced to what the check shows. */
data class HttpsRecord(
    val priority: Int,
    val targetName: String,
    val alpn: List<String>,
    val ipv4Hints: List<String>,
    val ipv6Hints: List<String>,
    /** Size of the `ech=` ECHConfigList in bytes, or null when the record has none. */
    val echConfigListSize: Int?,
    /** The ECH public names (the name sent in clear instead of ours), e.g. cloudflare-ech.com. */
    val echPublicNames: List<String>,
)

/** The HTTPS records in one DNS response. */
data class DnsHttpsAnswer(val rcode: Int, val records: List<HttpsRecord>) {
    val hasEch: Boolean get() = records.any { it.echConfigListSize != null }
}

class DnsParseException(message: String) : Exception(message)

/** A minimal parser for DNS wire-format responses (RFC 1035) carrying HTTPS records. */
object DnsHttpsRecords {
    const val TYPE_HTTPS = 65

    private const val PARAM_ALPN = 1
    private const val PARAM_IPV4HINT = 4
    private const val PARAM_ECH = 5
    private const val PARAM_IPV6HINT = 6
    private const val ECH_VERSION_DRAFT_13 = 0xfe0d

    fun parse(message: ByteArray): DnsHttpsAnswer {
        val reader = Reader(message)
        reader.u16() // id
        val flags = reader.u16()
        val questions = reader.u16()
        val answers = reader.u16()
        reader.u16() // authority
        reader.u16() // additional
        repeat(questions) {
            reader.name()
            reader.skip(4) // type, class
        }
        val records = ArrayList<HttpsRecord>()
        repeat(answers) {
            reader.name()
            val type = reader.u16()
            reader.u16() // class
            reader.skip(4) // ttl
            val length = reader.u16()
            val end = reader.position + length
            reader.require(length)
            if (type == TYPE_HTTPS) records += parseHttpsData(reader, end)
            reader.position = end
        }
        return DnsHttpsAnswer(rcode = flags and 0x0f, records = records)
    }

    private fun parseHttpsData(reader: Reader, end: Int): HttpsRecord {
        val priority = reader.u16()
        val target = reader.name()
        val alpn = ArrayList<String>()
        val ipv4 = ArrayList<String>()
        val ipv6 = ArrayList<String>()
        var echSize: Int? = null
        var echNames = emptyList<String>()
        while (reader.position < end) {
            val key = reader.u16()
            val value = reader.bytes(reader.u16())
            when (key) {
                PARAM_ALPN -> {
                    val values = Reader(value)
                    while (values.position < value.size) alpn += String(values.bytes(values.u8()), Charsets.US_ASCII)
                }
                PARAM_IPV4HINT -> value.toList().chunked(4).forEach { ipv4 += address(it) }
                PARAM_IPV6HINT -> value.toList().chunked(16).forEach { ipv6 += address(it) }
                PARAM_ECH -> {
                    echSize = value.size
                    echNames = echPublicNames(value)
                }
            }
        }
        if (reader.position != end) throw DnsParseException("HTTPS record overruns its length")
        return HttpsRecord(priority, target, alpn, ipv4, ipv6, echSize, echNames)
    }

    private fun address(bytes: List<Byte>): String {
        if (bytes.size != 4 && bytes.size != 16) throw DnsParseException("bad address hint")
        return InetAddress.getByAddress(bytes.toByteArray()).hostAddress ?: "?"
    }

    /** Public names from an ECHConfigList (draft-ietf-tls-esni-13 / RFC 9849); empty if unreadable. */
    internal fun echPublicNames(echConfigList: ByteArray): List<String> = try {
        val reader = Reader(echConfigList)
        val end = 2 + reader.u16()
        val names = ArrayList<String>()
        while (reader.position + 4 <= end) {
            val version = reader.u16()
            val contents = reader.bytes(reader.u16())
            if (version != ECH_VERSION_DRAFT_13) continue
            val config = Reader(contents)
            config.skip(1) // config_id
            config.skip(2) // kem_id
            config.skip(config.u16()) // public_key
            config.skip(config.u16()) // cipher_suites
            config.skip(1) // maximum_name_length
            names += String(config.bytes(config.u8()), Charsets.US_ASCII)
        }
        names
    } catch (_: DnsParseException) {
        emptyList()
    }

    private class Reader(private val buffer: ByteArray) {
        var position = 0

        fun require(count: Int) {
            if (count < 0 || position + count > buffer.size) throw DnsParseException("truncated message at byte $position")
        }

        fun u8(): Int {
            require(1)
            return buffer[position++].toInt() and 0xff
        }

        fun u16(): Int = (u8() shl 8) or u8()

        fun skip(count: Int) {
            require(count)
            position += count
        }

        fun bytes(count: Int): ByteArray {
            require(count)
            return buffer.copyOfRange(position, position + count).also { position += count }
        }

        /** Reads a possibly compressed domain name; "." is the root. */
        fun name(): String {
            val labels = ArrayList<String>()
            var cursor = position
            var resumeAt = -1
            var jumps = 0
            while (true) {
                if (cursor >= buffer.size) throw DnsParseException("truncated name")
                val length = buffer[cursor].toInt() and 0xff
                when {
                    length == 0 -> {
                        cursor += 1
                        break
                    }
                    length and 0xc0 == 0xc0 -> {
                        if (cursor + 1 >= buffer.size) throw DnsParseException("truncated name pointer")
                        if (++jumps > 32) throw DnsParseException("name pointer loop")
                        if (resumeAt < 0) resumeAt = cursor + 2
                        cursor = ((length and 0x3f) shl 8) or (buffer[cursor + 1].toInt() and 0xff)
                    }
                    length and 0xc0 != 0 -> throw DnsParseException("unsupported label type")
                    else -> {
                        if (cursor + 1 + length > buffer.size) throw DnsParseException("truncated label")
                        labels += String(buffer, cursor + 1, length, Charsets.US_ASCII)
                        cursor += 1 + length
                    }
                }
            }
            position = if (resumeAt >= 0) resumeAt else cursor
            return if (labels.isEmpty()) "." else labels.joinToString(".")
        }
    }
}

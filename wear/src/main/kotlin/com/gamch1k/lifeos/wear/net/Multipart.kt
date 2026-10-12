package com.gamch1k.lifeos.wear.net

import java.io.ByteArrayOutputStream
import java.util.UUID

/** A `multipart/form-data` body (RFC 7578) with text fields and one file. */
object Multipart {
    class File(val name: String, val fileName: String, val contentType: String, val bytes: ByteArray)

    class Body(val contentType: String, val body: ByteArray)

    fun build(fields: List<Pair<String, String>>, file: File, boundary: String = "lifeos-" + UUID.randomUUID()): Body {
        require(fields.all { (name, _) -> name.isSafe() } && file.name.isSafe() && file.fileName.isSafe()) { "unsafe multipart name" }
        val out = ByteArrayOutputStream(file.bytes.size + 1024)
        fun line(text: String = "") = out.write((text + "\r\n").toByteArray(Charsets.UTF_8))
        fields.forEach { (name, value) ->
            line("--$boundary")
            line("Content-Disposition: form-data; name=\"$name\"")
            line()
            line(value)
        }
        line("--$boundary")
        line("Content-Disposition: form-data; name=\"${file.name}\"; filename=\"${file.fileName}\"")
        line("Content-Type: ${file.contentType}")
        line()
        out.write(file.bytes)
        line()
        line("--$boundary--")
        return Body("multipart/form-data; boundary=$boundary", out.toByteArray())
    }

    private fun String.isSafe() = isNotEmpty() && none { it == '"' || it == '\r' || it == '\n' }
}

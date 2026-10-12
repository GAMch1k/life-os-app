package com.gamch1k.lifeos.core.api

import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class MultipartBodyTest {
    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun `parts are framed as RFC 7578 and the length is exact`() {
        val file = temp.newFile("voice.ogg").apply { writeText("OggS") }
        val body = MultipartBody(
            listOf(
                MultipartBody.Part.file("audio", "voice.ogg", "audio/ogg", file),
                MultipartBody.Part.text("source", "web"),
            ),
            boundary = "b",
        )
        val bytes = body.open().use { it.readBytes() }
        val expected = "--b\r\n" +
            "Content-Disposition: form-data; name=\"audio\"; filename=\"voice.ogg\"\r\n" +
            "Content-Type: audio/ogg\r\n\r\n" +
            "OggS\r\n" +
            "--b\r\n" +
            "Content-Disposition: form-data; name=\"source\"\r\n\r\n" +
            "web\r\n" +
            "--b--\r\n"
        assertEquals(expected, bytes.text())
        assertEquals(bytes.size.toLong(), body.contentLength)
        assertEquals("multipart/form-data; boundary=b", body.contentType)
    }

    @Test
    fun `the body can be read again, as Cronet does when it rewinds`() {
        val body = MultipartBody(listOf(MultipartBody.Part.text("a", "1")), boundary = "x")
        val first = body.open().use { it.readBytes() }
        val second = body.open().use { it.readBytes() }
        assertEquals(first.text(), second.text())
    }

    @Test
    fun `quotes in names cannot break the header`() {
        val body = MultipartBody(listOf(MultipartBody.Part.bytes("images", "a\"b\r\n.jpg", "image/jpeg", byteArrayOf(1))), boundary = "x")
        val text = body.open().use { it.readBytes() }.text()
        assertEquals(true, text.contains("filename=\"a%22b.jpg\""))
    }
}

package com.gamch1k.lifeos.core.share

import com.gamch1k.lifeos.core.capture.CaptureKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ShareMapperTest {
    private fun send(type: String?, text: String? = null, subject: String? = null, streams: List<SharedStream> = emptyList(), multiple: Boolean = false) =
        ShareMapper.map(ShareInput(if (multiple) ShareMapper.ACTION_SEND_MULTIPLE else ShareMapper.ACTION_SEND, type, text, subject, streams))

    private fun image(n: Int, type: String? = "image/jpeg") = SharedStream("content://media/external/images/$n", type)

    @Test
    fun `an Instagram reel goes in as text with its URL`() {
        val plan = send("text/plain", text = "https://www.instagram.com/reel/C9xYz/?igsh=MWQ1ZGUxMzBkMA==") as SharePlan.Capture
        assertEquals(CaptureKind.TEXT, plan.kind)
        assertEquals("https://www.instagram.com/reel/C9xYz/?igsh=MWQ1ZGUxMzBkMA==", plan.text)
        assertEquals(SharedLink.Platform.INSTAGRAM, plan.link!!.platform)
        assertTrue(plan.imageUris.isEmpty())
    }

    @Test
    fun `TikTok and YouTube links are recognised, other links are plain links`() {
        assertEquals(SharedLink.Platform.TIKTOK, (send("text/plain", text = "Look https://vm.tiktok.com/ZMabc/ !") as SharePlan.Capture).link!!.platform)
        assertEquals(SharedLink.Platform.YOUTUBE, (send("text/plain", text = "https://youtu.be/dQw4w9WgXcQ") as SharePlan.Capture).link!!.platform)
        assertEquals(SharedLink.Platform.YOUTUBE, (send("text/plain", text = "https://m.youtube.com/watch?v=x") as SharePlan.Capture).link!!.platform)
        val other = (send("text/plain", text = "Read (https://example.com/a).") as SharePlan.Capture).link!!
        assertEquals(SharedLink.Platform.OTHER, other.platform)
        assertEquals("https://example.com/a", other.url)
        assertNull((send("text/plain", text = "Buy milk") as SharePlan.Capture).link)
    }

    @Test
    fun `a page title becomes the first line, unless the text already has it`() {
        assertEquals("Best ramen\n\nhttps://example.com/ramen", (send("text/plain", text = "https://example.com/ramen", subject = "Best ramen") as SharePlan.Capture).text)
        assertEquals("Best ramen https://example.com/ramen", (send("text/plain", text = "Best ramen https://example.com/ramen", subject = "Best ramen") as SharePlan.Capture).text)
    }

    @Test
    fun `an image from the gallery is a photo capture, its text the caption`() {
        val plan = send("image/jpeg", text = "Receipt", streams = listOf(image(1))) as SharePlan.Capture
        assertEquals(CaptureKind.PHOTO, plan.kind)
        assertEquals(listOf("content://media/external/images/1"), plan.imageUris)
        assertEquals("Receipt", plan.text)
        assertEquals("Lunch\n\nReceipt", plan.content(" Lunch "))
        assertEquals("Receipt", plan.content(""))
    }

    @Test
    fun `several images keep five, and say how many were left out`() {
        val plan = send("image/*", streams = (1..7).map { image(it) }, multiple = true) as SharePlan.Capture
        assertEquals(5, plan.imageUris.size)
        assertEquals(2, plan.droppedImages)
    }

    @Test
    fun `an image stream without its own type takes the intent's`() {
        val plan = send("image/png", streams = listOf(image(1, type = null))) as SharePlan.Capture
        assertEquals(1, plan.imageUris.size)
    }

    @Test
    fun `videos and empty shares are refused with a reason, mixed shares keep the images`() {
        assertTrue(send("video/mp4", streams = listOf(SharedStream("content://v/1", "video/mp4"))) is SharePlan.Unsupported)
        assertTrue(send("text/plain", text = "   ") is SharePlan.Unsupported)
        assertTrue(ShareMapper.map(ShareInput("android.intent.action.VIEW", "text/plain", "x", null)) is SharePlan.Unsupported)
        val mixed = send("*/*", streams = listOf(image(1), SharedStream("content://v/2", "video/mp4")), multiple = true) as SharePlan.Capture
        assertEquals(1, mixed.imageUris.size)
    }
}

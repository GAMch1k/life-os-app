package com.gamch1k.lifeos.core.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The numbers of a transfer on both sides: percent, ETA, the words, and the sliding-window speed. */
class TransferProgressTest {
    @Test
    fun `percent rounds down, so 100 means every byte`() {
        assertEquals(42, TransferProgress(2_300_000, 5_400_000).percent)
        assertEquals(99, TransferProgress(5_399_999, 5_400_000).percent)
        assertEquals(100, TransferProgress(5_400_000, 5_400_000).percent)
        assertEquals("no size known", 0, TransferProgress(10, 0).percent)
        assertEquals(100, TransferProgress(6_000, 5_000).percent)
        assertEquals(0.5f, TransferProgress(50, 100).fraction)
        assertEquals(1f, TransferProgress(150, 100).fraction)
    }

    @Test
    fun `the ETA is what is left at the measured speed, rounded up, and unknown without a speed`() {
        assertEquals(124L, TransferProgress(2_300_000, 5_400_000, 25_000).etaSeconds)
        assertEquals("rounded up", 4L, TransferProgress(90, 100, 3).etaSeconds)
        assertEquals(0L, TransferProgress(100, 100, 3).etaSeconds)
        assertNull(TransferProgress(0, 100).etaSeconds)
        assertNull("a stalled transfer has no ETA", TransferProgress(10, 100, 0).etaSeconds)
    }

    @Test
    fun `the words for sizes, speeds and the time left`() {
        assertEquals("2.3", TransferText.megabytes(2_300_000))
        assertEquals("16.6", TransferText.megabytes(16_600_000))
        assertEquals("0.0", TransferText.megabytes(-5))
        assertEquals("1.5 MB/s", TransferText.speed(1_500_000))
        assertEquals("17 KB/s", TransferText.speed(17_500))
        assertEquals("999 B/s", TransferText.speed(999))
        assertEquals("less than 10 s left", TransferText.eta(9))
        assertEquals("about 10 s left", TransferText.eta(14))
        assertEquals("about 20 s left", TransferText.eta(15))
        assertEquals("about 50 s left", TransferText.eta(54))
        assertEquals("about 1 min left", TransferText.eta(55))
        assertEquals("about 1 min left", TransferText.eta(89))
        assertEquals("about 2 min left", TransferText.eta(90))
        assertEquals("about 16 min left", TransferText.eta(976))
    }

    @Test
    fun `the summary and the detail`() {
        val progress = TransferProgress(2_300_000, 5_400_000, 25_000)
        assertEquals("42% · 2.3 of 5.4 MB · about 2 min left", progress.summary())
        assertEquals("42% · 2.3 of 5.4 MB · 25 KB/s · about 2 min left", progress.detail())
        assertEquals("0% · 0.0 of 5.4 MB", TransferProgress(0, 5_400_000).detail())
        assertEquals("stalled: the speed, no ETA", "10% · 0.5 of 5.4 MB · 0 B/s", TransferProgress(540_000, 5_400_000, 0).detail())
    }

    private class Clock(var now: Long = 0)

    @Test
    fun `the meter says nothing about the speed in the first moments`() {
        val clock = Clock()
        val meter = TransferMeter(1_000_000, clock = { clock.now })
        clock.now = 1_000
        assertNull(meter.update(10_000).bytesPerSecond)
        clock.now = 2_000
        assertEquals(10_000L, meter.update(20_000).bytesPerSecond)
        assertEquals(TransferProgress(20_000, 1_000_000, 10_000), meter.last)
    }

    @Test
    fun `the speed follows the last 10 seconds, not the average since the start`() {
        val clock = Clock()
        val meter = TransferMeter(1_000_000, clock = { clock.now })
        var position = 0L
        // 20 s at 10 KB/s, then 10 s at 1 KB/s.
        repeat(20) {
            clock.now += 1_000
            position += 10_000
            meter.update(position)
        }
        repeat(10) {
            clock.now += 1_000
            position += 1_000
            meter.update(position)
        }
        assertEquals(1_000L, meter.last.bytesPerSecond)
        assertEquals((1_000_000L - 210_000 + 999) / 1_000, meter.last.etaSeconds)
    }

    @Test
    fun `bytes already held when a transfer resumes count for the progress, not the speed`() {
        val clock = Clock()
        val meter = TransferMeter(1_000_000, start = 500_000, clock = { clock.now })
        assertEquals(50, meter.last.percent)
        clock.now = 2_000
        val progress = meter.update(510_000)
        assertEquals(5_000L, progress.bytesPerSecond)
        assertEquals(51, progress.percent)
    }

    @Test
    fun `a transfer that stops reads 0 bytes a second after a whole window, and has no ETA`() {
        val clock = Clock()
        val meter = TransferMeter(1_000_000, start = 100, clock = { clock.now })
        clock.now = 5_000
        assertNull("not yet a whole window", meter.update(100).bytesPerSecond)
        clock.now = 10_000
        val stalled = meter.update(100)
        assertEquals(0L, stalled.bytesPerSecond)
        assertNull(stalled.etaSeconds)
    }
}

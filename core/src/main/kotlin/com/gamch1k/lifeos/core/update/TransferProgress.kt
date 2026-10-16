package com.gamch1k.lifeos.core.update

import java.util.Locale

/**
 * How far a transfer is: [done] of [total] bytes and the speed measured over
 * the last seconds ([TransferMeter]), with what follows from them. Shown by
 * the phone (Settings › Watch, the update's notification) and the watch (its
 * status and notification).
 */
data class TransferProgress(
    val done: Long,
    val total: Long,
    /** Null until enough time has passed to measure; 0 when nothing moved for a whole window. */
    val bytesPerSecond: Long? = null,
) {
    val fraction: Float get() = if (total <= 0) 0f else (done.toDouble() / total).coerceIn(0.0, 1.0).toFloat()

    /** Whole percent, rounded down: 100 only when every byte is there. */
    val percent: Int get() = if (total <= 0) 0 else ((done.coerceIn(0, total) * 100) / total).toInt()

    /** Seconds left at the measured speed, rounded up; null while the speed is unknown or 0. */
    val etaSeconds: Long?
        get() {
            val speed = bytesPerSecond?.takeIf { it > 0 } ?: return null
            val left = (total - done).coerceAtLeast(0)
            return (left + speed - 1) / speed
        }

    /** "43% · 2.3 of 5.4 MB · about 2 min left" (the notification). */
    fun summary(): String = TransferText.summary(this)

    /** "43% · 2.3 of 5.4 MB · 25 KB/s · about 2 min left" (Settings, the watch). */
    fun detail(): String = TransferText.detail(this)
}

/** The words for [TransferProgress]: decimal megabytes (1 MB = 1,000,000 bytes), as the release notes give sizes. */
object TransferText {
    fun megabytes(bytes: Long): String = String.format(Locale.ROOT, "%.1f", bytes.coerceAtLeast(0) / 1_000_000.0)

    fun speed(bytesPerSecond: Long): String = when {
        bytesPerSecond >= 1_000_000 -> String.format(Locale.ROOT, "%.1f MB/s", bytesPerSecond / 1_000_000.0)
        bytesPerSecond >= 1_000 -> "${bytesPerSecond / 1_000} KB/s"
        else -> "$bytesPerSecond B/s"
    }

    /** Rounded like people say it: "less than 10 s", "about 40 s", "about 2 min". */
    fun eta(seconds: Long): String = when {
        seconds < 10 -> "less than 10 s left"
        seconds < 55 -> "about ${((seconds + 5) / 10) * 10} s left"
        else -> "about ${maxOf(1, (seconds + 30) / 60)} min left"
    }

    fun summary(progress: TransferProgress): String = buildString {
        append("${progress.percent}% · ${megabytes(progress.done)} of ${megabytes(progress.total)} MB")
        progress.etaSeconds?.let { append(" · ").append(eta(it)) }
    }

    fun detail(progress: TransferProgress): String = buildString {
        append("${progress.percent}% · ${megabytes(progress.done)} of ${megabytes(progress.total)} MB")
        progress.bytesPerSecond?.let { append(" · ").append(speed(it)) }
        progress.etaSeconds?.let { append(" · ").append(eta(it)) }
    }
}

/**
 * Measures a transfer's speed over a sliding window, so the ETA follows the
 * link's current pace rather than an average since the start. Bytes that were
 * already there when it started (a resumed transfer, [start]) count for the
 * progress but not for the speed. Safe to call from any thread.
 */
class TransferMeter(
    private val total: Long,
    start: Long = 0,
    private val clock: () -> Long = { System.nanoTime() / 1_000_000 },
    private val windowMillis: Long = 10_000,
    /** No speed is shown before this much time has passed; the first second says little. */
    private val minMillis: Long = 1_500,
) {
    private val times = ArrayDeque<Long>()
    private val positions = ArrayDeque<Long>()

    @Volatile
    var last: TransferProgress = TransferProgress(start, total)
        private set

    init {
        times.addLast(clock())
        positions.addLast(start)
    }

    @Synchronized
    fun update(done: Long): TransferProgress {
        val now = clock()
        times.addLast(now)
        positions.addLast(done)
        // Keep one sample at or before the window's start, so the window is always full once it can be.
        while (times.size > 2 && now - times[1] >= windowMillis) {
            times.removeFirst()
            positions.removeFirst()
        }
        val elapsed = now - times.first()
        val moved = done - positions.first()
        val speed = when {
            elapsed < minMillis -> null
            moved <= 0 -> if (elapsed >= windowMillis) 0L else null
            else -> moved * 1000 / elapsed
        }
        return TransferProgress(done, total, speed).also { last = it }
    }
}

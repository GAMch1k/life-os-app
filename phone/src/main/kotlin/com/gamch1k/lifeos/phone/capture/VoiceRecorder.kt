package com.gamch1k.lifeos.phone.capture

import android.content.Context
import android.media.MediaRecorder
import android.os.SystemClock
import java.io.File

/**
 * Records Ogg/Opus, mono, 16 kHz (what Whisper works at), 32 kbit/s: about
 * 14 MB an hour, so the 20-minute cap stays far below Voice's 25 MiB limit.
 */
class VoiceRecorder(private val context: Context) {
    private var recorder: MediaRecorder? = null
    private var startedAt = 0L

    val recording: Boolean get() = recorder != null

    val elapsedMillis: Long get() = if (recorder != null) SystemClock.elapsedRealtime() - startedAt else 0

    /** 0..1, the loudest sample since the last call. */
    fun level(): Float = (runCatching { recorder?.maxAmplitude ?: 0 }.getOrDefault(0) / 32767f).coerceIn(0f, 1f)

    fun start(file: File, onLimit: () -> Unit) {
        val created = MediaRecorder(context)
        try {
            created.setAudioSource(MediaRecorder.AudioSource.MIC)
            created.setOutputFormat(MediaRecorder.OutputFormat.OGG)
            created.setAudioEncoder(MediaRecorder.AudioEncoder.OPUS)
            created.setAudioChannels(1)
            created.setAudioSamplingRate(16_000)
            created.setAudioEncodingBitRate(32_000)
            created.setMaxDuration(MAX_DURATION_MILLIS)
            created.setMaxFileSize(MAX_BYTES)
            created.setOutputFile(file)
            created.setOnInfoListener { _, what, _ ->
                if (what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_DURATION_REACHED || what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_FILESIZE_REACHED) onLimit()
            }
            created.prepare()
            created.start()
        } catch (error: Exception) {
            created.release()
            throw error
        }
        recorder = created
        startedAt = SystemClock.elapsedRealtime()
    }

    /** Stops; returns the duration, or null when nothing usable was recorded. */
    fun stop(file: File): Long? {
        val current = recorder ?: return null
        val duration = elapsedMillis
        recorder = null
        val stopped = runCatching { current.stop() }.isSuccess
        current.release()
        // stop() throws when no audio arrived, or when the recorder already
        // stopped itself at the limit; then a non-empty file is still usable.
        return if (stopped || file.length() > MIN_BYTES) duration else null
    }

    fun cancel() {
        recorder?.let { runCatching { it.stop() }; it.release() }
        recorder = null
    }

    companion object {
        const val MAX_DURATION_MILLIS = 20 * 60 * 1000
        const val MAX_BYTES = 24L * 1024 * 1024
        private const val MIN_BYTES = 1024L
    }
}

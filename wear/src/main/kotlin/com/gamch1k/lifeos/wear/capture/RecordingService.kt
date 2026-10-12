package com.gamch1k.lifeos.wear.capture

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.MediaRecorder
import android.os.IBinder
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import com.gamch1k.lifeos.wear.LifeOsWear
import com.gamch1k.lifeos.wear.R
import com.gamch1k.lifeos.wear.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.time.ZoneId
import java.util.UUID

/** What the recording screen shows. */
sealed interface RecorderState {
    data object Idle : RecorderState

    data class Recording(val captureId: String, val startedAtElapsed: Long) : RecorderState

    /** The recording ended; [outcome] says how. The screen shows it, then calls [Recorder.acknowledge]. */
    data class Finished(val outcome: Outcome, val message: String? = null, val captureId: String? = null) : RecorderState

    enum class Outcome { QUEUED, CANCELLED, TOO_SHORT, FAILED }
}

/** The process-wide recorder state, driven by [RecordingService]. */
object Recorder {
    private val mutableState = MutableStateFlow<RecorderState>(RecorderState.Idle)
    val state: StateFlow<RecorderState> = mutableState.asStateFlow()

    /** Starts recording. Call from a visible activity: Android only lets a foreground app open the microphone. */
    fun start(context: Context) {
        if (state.value is RecorderState.Recording) return
        context.startForegroundService(Intent(context, RecordingService::class.java).setAction(RecordingService.ACTION_START))
    }

    fun send(context: Context) = command(context, RecordingService.ACTION_SEND)

    fun cancel(context: Context) = command(context, RecordingService.ACTION_CANCEL)

    fun acknowledge() {
        if (state.value is RecorderState.Finished) mutableState.value = RecorderState.Idle
    }

    internal fun set(state: RecorderState) {
        mutableState.value = state
    }

    private fun command(context: Context, action: String) {
        if (state.value !is RecorderState.Recording) return
        context.startService(Intent(context, RecordingService::class.java).setAction(action))
    }
}

/**
 * Records one voice capture: Ogg/Opus, mono, 16 kHz, 24 kbit/s (MP4/AAC if
 * the watch can't encode Opus), at most five minutes. A foreground service
 * of type microphone, so the recording goes on when the screen dims or the
 * owner leaves the app. Stop/Send queues it ([CaptureStore]) and wakes the
 * upload queue ([CaptureWorker]); Cancel throws it away.
 */
class RecordingService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var recorder: MediaRecorder? = null
    private var current: Current? = null

    private class Current(val id: String, val file: File, val format: AudioFormat, val startedAtElapsed: Long, val startedAtMillis: Long)

    enum class AudioFormat(val extension: String, val contentType: String) {
        OGG_OPUS("ogg", "audio/ogg"),
        MP4_AAC("m4a", "audio/mp4"),
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> start()
            ACTION_SEND -> finish(send = true)
            ACTION_CANCEL -> finish(send = false)
            else -> if (current == null) stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun start() {
        startForeground(NOTIFICATION_ID, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        if (current != null) return
        val store = LifeOsWear.graph(this).captureStore
        val id = UUID.randomUUID().toString()
        for (format in AudioFormat.entries) {
            val file = store.newAudioFile(id, format.extension)
            val started = try {
                MediaRecorder(this).apply {
                    setAudioSource(MediaRecorder.AudioSource.MIC)
                    when (format) {
                        AudioFormat.OGG_OPUS -> {
                            setOutputFormat(MediaRecorder.OutputFormat.OGG)
                            setAudioEncoder(MediaRecorder.AudioEncoder.OPUS)
                            setAudioEncodingBitRate(24_000)
                        }
                        AudioFormat.MP4_AAC -> {
                            setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                            setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                            setAudioEncodingBitRate(32_000)
                        }
                    }
                    setAudioChannels(1)
                    setAudioSamplingRate(16_000)
                    setMaxDuration(MAX_DURATION_MILLIS)
                    setOutputFile(file)
                    setOnInfoListener { _, what, _ ->
                        if (what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_DURATION_REACHED) finish(send = true)
                    }
                    prepare()
                    start()
                }
            } catch (_: Exception) {
                file.delete()
                null
            }
            if (started != null) {
                recorder = started
                val elapsed = SystemClock.elapsedRealtime()
                current = Current(id, file, format, elapsed, System.currentTimeMillis())
                Recorder.set(RecorderState.Recording(id, elapsed))
                return
            }
        }
        Recorder.set(RecorderState.Finished(RecorderState.Outcome.FAILED, "The microphone could not be opened."))
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun finish(send: Boolean) {
        val recording = current ?: return
        current = null
        val duration = SystemClock.elapsedRealtime() - recording.startedAtElapsed
        val stopped = try {
            recorder?.stop()
            true
        } catch (_: RuntimeException) {
            // stop() right after start() has no audio to finish the file with.
            false
        } finally {
            recorder?.release()
            recorder = null
        }
        if (!send || !stopped || duration < MIN_DURATION_MILLIS) {
            recording.file.delete()
            Recorder.set(
                RecorderState.Finished(if (send) RecorderState.Outcome.TOO_SHORT else RecorderState.Outcome.CANCELLED),
            )
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }
        val graph = LifeOsWear.graph(this)
        val capture = Capture(
            id = recording.id,
            createdAt = recording.startedAtMillis,
            durationMillis = duration,
            fileName = recording.file.name,
            contentType = recording.format.contentType,
            timeZone = ZoneId.systemDefault().id,
        )
        scope.launch {
            graph.captureStore.add(capture)
            CaptureWorker.runNow(this@RecordingService)
            Recorder.set(RecorderState.Finished(RecorderState.Outcome.QUEUED, captureId = capture.id))
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    override fun onDestroy() {
        // Killed while recording: keep nothing half written.
        current?.let { recording ->
            try {
                recorder?.stop()
            } catch (_: RuntimeException) {
            }
            recorder?.release()
            recording.file.delete()
            Recorder.set(RecorderState.Finished(RecorderState.Outcome.CANCELLED))
        }
        super.onDestroy()
    }

    private fun notification() = run {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, getString(R.string.recording_channel), NotificationManager.IMPORTANCE_LOW),
        )
        val open = PendingIntent.getActivity(
            this,
            0,
            MainActivity.recordIntent(this),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_mic)
            .setContentTitle(getString(R.string.recording))
            .setContentIntent(open)
            .setOngoing(true)
            .setLocalOnly(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    companion object {
        const val ACTION_START = "com.gamch1k.lifeos.wear.record.START"
        const val ACTION_SEND = "com.gamch1k.lifeos.wear.record.SEND"
        const val ACTION_CANCEL = "com.gamch1k.lifeos.wear.record.CANCEL"
        const val MAX_DURATION_MILLIS = 5 * 60_000
        const val MIN_DURATION_MILLIS = 800L
        private const val CHANNEL_ID = "recording"
        private const val NOTIFICATION_ID = 1
    }
}

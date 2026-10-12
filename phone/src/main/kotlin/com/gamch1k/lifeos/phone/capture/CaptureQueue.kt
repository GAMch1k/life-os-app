package com.gamch1k.lifeos.phone.capture

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.gamch1k.lifeos.core.capture.Capture
import com.gamch1k.lifeos.core.capture.CaptureKind
import com.gamch1k.lifeos.core.capture.CaptureSender
import com.gamch1k.lifeos.core.capture.CaptureStatus
import com.gamch1k.lifeos.core.capture.CaptureStore
import com.gamch1k.lifeos.core.capture.SendOutcome
import com.gamch1k.lifeos.phone.graph
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * The offline capture queue: every capture is stored first, then sent by a
 * WorkManager job that waits for a network, backs off on failures, and
 * survives reboots. One unique job per capture.
 */
class CaptureQueue(context: Context, private val store: CaptureStore, private val sender: CaptureSender) {
    private val work = WorkManager.getInstance(context)
    private val root = File(context.filesDir, "captures")

    fun newId(): String = UUID.randomUUID().toString()

    /** The capture's own directory for its recording or photos. */
    fun dirFor(id: String): File = File(root, id).apply { mkdirs() }

    suspend fun add(id: String, kind: CaptureKind, text: String = "", files: List<File> = emptyList(), durationMillis: Long? = null) {
        store.add(
            Capture(
                id = id,
                kind = kind,
                createdAt = Instant.now().toString(),
                timeZone = ZoneId.systemDefault().id,
                text = text,
                files = files.map { it.absolutePath },
                durationMillis = durationMillis,
            ),
        )
        enqueue(id)
    }

    fun enqueue(id: String, replace: Boolean = false) {
        val request = OneTimeWorkRequestBuilder<CaptureWorker>()
            .setInputData(workDataOf(KEY_ID to id))
            .setConstraints(Constraints(requiredNetworkType = NetworkType.CONNECTED))
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .addTag(TAG)
            .build()
        work.enqueueUniqueWork(name(id), if (replace) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP, request)
    }

    /** On app start: everything not sent yet gets its job back (KEEP leaves running ones alone). */
    suspend fun resumeAll() {
        for (capture in store.all()) {
            if (capture.status == CaptureStatus.SENDING) store.update(capture.id) { it.copy(status = CaptureStatus.PENDING) }
            if (capture.status == CaptureStatus.PENDING || capture.status == CaptureStatus.SENDING) enqueue(capture.id)
        }
    }

    suspend fun retry(id: String) {
        sender.reset(id)
        enqueue(id, replace = true)
    }

    /** After a new token: what failed on the old one is sent again. */
    suspend fun retryAuthFailures() {
        sender.resetAuthFailures().forEach { enqueue(it, replace = true) }
    }

    suspend fun delete(id: String) {
        work.cancelUniqueWork(name(id))
        store.remove(id)
    }

    suspend fun clear() {
        work.cancelAllWorkByTag(TAG)
        store.clear()
        root.deleteRecursively()
    }

    companion object {
        const val KEY_ID = "capture-id"
        const val TAG = "capture"
        private fun name(id: String) = "capture-$id"
    }
}

class CaptureWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val id = inputData.getString(CaptureQueue.KEY_ID) ?: return Result.failure()
        val graph = applicationContext.graph
        return when (graph.sender.send(id)) {
            SendOutcome.Sent -> {
                graph.statusChecker.refresh(id)
                Result.success()
            }
            is SendOutcome.Retry -> Result.retry()
            is SendOutcome.Failed, SendOutcome.Gone -> Result.failure()
        }
    }
}

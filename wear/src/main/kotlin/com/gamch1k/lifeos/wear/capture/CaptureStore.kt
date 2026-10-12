package com.gamch1k.lifeos.wear.capture

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File

/**
 * The capture queue on disk: `captures/queue.json` (written to a temporary
 * file and renamed, so it is never half written) and the recordings next to
 * it. One instance per process; every change goes through [update] under a
 * lock, and [captures] shows the current list, newest first.
 */
class CaptureStore(val directory: File, private val clock: () -> Long = System::currentTimeMillis) {
    private val queueFile = File(directory, "queue.json")
    private val mutex = Mutex()
    private val state = MutableStateFlow(load())

    val captures: StateFlow<List<Capture>> = state.asStateFlow()

    fun audioFile(capture: Capture) = File(directory, capture.fileName)

    fun newAudioFile(captureId: String, extension: String): File {
        directory.mkdirs()
        return File(directory, "$captureId.$extension")
    }

    suspend fun add(capture: Capture) = mutate { list -> listOf(capture) + list.filter { it.id != capture.id } }

    /** Applies [transform] to one capture; returns the new value (null when the capture is gone). */
    suspend fun update(id: String, transform: (Capture) -> Capture?): Capture? {
        var result: Capture? = null
        mutate { list ->
            list.mapNotNull { capture ->
                if (capture.id == id) transform(capture).also { result = it } else capture
            }
        }
        return result
    }

    suspend fun remove(id: String) {
        mutate { list ->
            list.find { it.id == id }?.let { audioFile(it).delete() }
            list.filter { it.id != id }
        }
    }

    fun get(id: String): Capture? = state.value.find { it.id == id }

    private suspend fun mutate(transform: (List<Capture>) -> List<Capture>) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val next = prune(transform(state.value))
            save(next)
            state.value = next
        }
    }

    /**
     * Keeps the queue small: drops the audio of transcribed recordings, and
     * forgets finished ones after three days and all but the newest 30.
     */
    private fun prune(list: List<Capture>): List<Capture> {
        val now = clock()
        list.filter { !CaptureMachine.keepsAudio(it) }.forEach { audioFile(it).delete() }
        val kept = list.filter { capture ->
            capture.state != CaptureState.IN_INBOX || now - capture.createdAt < KEEP_DONE_MILLIS
        }.sortedByDescending { it.createdAt }
        val dropped = kept.drop(MAX_ENTRIES).filter { it.state == CaptureState.IN_INBOX || it.state == CaptureState.FAILED }
        dropped.forEach { audioFile(it).delete() }
        return kept - dropped.toSet()
    }

    private fun load(): List<Capture> = try {
        if (queueFile.isFile) json.decodeFromString(serializer, queueFile.readText()) else emptyList()
    } catch (_: Exception) {
        emptyList()
    }

    private fun save(list: List<Capture>) {
        directory.mkdirs()
        val temporary = File(directory, "queue.json.tmp")
        temporary.writeText(json.encodeToString(serializer, list))
        if (!temporary.renameTo(queueFile)) {
            queueFile.delete()
            temporary.renameTo(queueFile)
        }
    }

    private companion object {
        const val KEEP_DONE_MILLIS = 3 * 24 * 60 * 60_000L
        const val MAX_ENTRIES = 30
        val json = Json { ignoreUnknownKeys = true }
        val serializer = ListSerializer(Capture.serializer())
    }
}

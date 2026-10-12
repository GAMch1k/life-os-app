package com.gamch1k.lifeos.core.wearlink

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Answers a relayed write's retry from the phone, without running it again.
 *
 * The watch sends every write with an `Idempotency-Key` (a voice upload's
 * job attempt, `<capture id>` or `<capture id>-r<n>`; a task completion's
 * tap) and repeats it unchanged when it retries. If the phone ran the write
 * but the reply to the watch got lost (the watch walked out of Bluetooth
 * range), the retry gets the stored reply, and the audio need not travel to
 * Voice again. A request with the same key that is still running is awaited,
 * not run twice.
 *
 * Only successes (2xx) are kept. A retry after "unreachable", a 5xx, a 429 or
 * a refused token runs again, so a capture Voice never accepted really gets
 * another try; a write that Life OS refused is simply refused again.
 *
 * In memory only: it covers the seconds to minutes of a retry, not a phone
 * restart. Life OS's own `Idempotency-Key` support (Voice since BUG-40, and
 * Tasks) makes the write idempotent beyond that.
 */
class RelayReplayCache(
    private val ttlMillis: Long = 15 * 60_000L,
    private val maxEntries: Int = 64,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private class Entry(val createdAt: Long, val result: CompletableDeferred<RelayHttpResponse>)

    private val mutex = Mutex()
    private val entries = LinkedHashMap<String, Entry>()

    suspend fun getOrExecute(key: String, block: suspend () -> RelayHttpResponse): RelayHttpResponse {
        val (entry, owner) = mutex.withLock {
            prune()
            entries[key]?.let { return@withLock it to false }
            val created = Entry(clock(), CompletableDeferred())
            entries[key] = created
            created to true
        }
        if (!owner) return entry.result.await()
        try {
            val response = block()
            if (HttpOutcome.of(response.status) != HttpOutcome.SUCCESS) {
                // Waiting retries get this answer; later ones run again.
                mutex.withLock { if (entries[key] === entry) entries.remove(key) }
            }
            entry.result.complete(response)
            return response
        } catch (e: Throwable) {
            mutex.withLock { if (entries[key] === entry) entries.remove(key) }
            entry.result.completeExceptionally(if (e is CancellationException) WearRelayException(RelayErrorCode.INTERNAL, "cancelled", e) else e)
            throw e
        }
    }

    suspend fun size(): Int = mutex.withLock { entries.size }

    private fun prune() {
        val now = clock()
        entries.entries.removeAll { (_, entry) -> entry.result.isCompleted && now - entry.createdAt > ttlMillis }
        while (entries.size >= maxEntries) {
            val oldestDone = entries.entries.firstOrNull { it.value.result.isCompleted } ?: break
            entries.remove(oldestDone.key)
        }
    }
}

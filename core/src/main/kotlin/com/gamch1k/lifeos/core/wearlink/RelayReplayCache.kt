package com.gamch1k.lifeos.core.wearlink

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Makes relayed writes idempotent between the watch and the phone.
 *
 * The watch sends every write with an `Idempotency-Key` (a voice capture's
 * id, for example) and repeats it unchanged when it retries. If the phone ran
 * the write but the reply to the watch got lost (the watch walked out of
 * Bluetooth range), the retry gets the stored reply instead of creating a
 * second Voice job. A request with the same key that is still running is
 * awaited, not run twice. Failures are not kept, so a retry after
 * "unreachable" really runs again.
 *
 * In memory only: it covers the seconds to minutes of a retry, not a phone
 * restart. Life OS's own `Idempotency-Key` support (Tasks has it, Voice not
 * yet) covers the rest.
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

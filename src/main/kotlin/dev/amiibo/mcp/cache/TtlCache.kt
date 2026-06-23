package dev.amiibo.mcp.cache

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Instant

class TtlCache<K : Any, V : Any>(
    private val ttlMillis: Long,
    private val clock: () -> Long = { Instant.now().toEpochMilli() },
) {
    private data class Entry<V>(val value: V, val expiresAt: Long)

    private val mutex = Mutex()
    private val values = linkedMapOf<K, Entry<V>>()

    suspend fun getOrPut(key: K, loader: suspend () -> V): V {
        if (ttlMillis <= 0) return loader()
        val now = clock()
        mutex.withLock {
            values[key]?.takeIf { it.expiresAt > now }?.let { return it.value }
        }
        val loaded = loader()
        mutex.withLock {
            values[key] = Entry(loaded, clock() + ttlMillis)
        }
        return loaded
    }

    suspend fun clear() {
        mutex.withLock { values.clear() }
    }
}

package dev.amiibo.mcp.cache

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class TtlCacheTest {
    @Test
    fun `returns cached value before ttl expires`() = runTest {
        var now = 1_000L
        var loads = 0
        val cache = TtlCache<String, String>(ttlMillis = 1_000) { now }

        assertEquals("value-1", cache.getOrPut("key") { "value-${++loads}" })
        now = 1_500L
        assertEquals("value-1", cache.getOrPut("key") { "value-${++loads}" })
        assertEquals(1, loads)
    }

    @Test
    fun `reloads value after ttl expires`() = runTest {
        var now = 1_000L
        var loads = 0
        val cache = TtlCache<String, String>(ttlMillis = 100) { now }

        assertEquals("value-1", cache.getOrPut("key") { "value-${++loads}" })
        now = 1_101L
        assertEquals("value-2", cache.getOrPut("key") { "value-${++loads}" })
    }
}

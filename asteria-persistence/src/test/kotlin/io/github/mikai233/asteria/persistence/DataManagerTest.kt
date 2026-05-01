package io.github.mikai233.asteria.persistence

import io.github.mikai233.asteria.core.EntityKind
import io.github.mikai233.asteria.core.ServiceRegistry
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class DataManagerTest {
    @Test
    fun `loadAll loads modules and flushes auto flush data`() = runBlocking {
        val data = TestData()
        val manager = DataManager(
            scope = DataScope(EntityKind("player"), 1001, ServiceRegistry()),
            modules = listOf(DataModule { data }),
        )

        manager.loadAll()

        assertTrue(data.loaded)
        assertEquals(data, manager.get<TestData>())
        assertTrue(manager.flush())
        assertEquals(1, data.flushes)
    }

    @Test
    fun `loadAll cannot be called twice`() = runBlocking {
        val manager = DataManager(
            scope = DataScope(EntityKind("player"), 1001, ServiceRegistry()),
            modules = listOf(DataModule { TestData() }),
        )

        manager.loadAll()

        assertFailsWith<IllegalStateException> {
            manager.loadAll()
        }
    }
}

private class TestData : AutoFlushMemData {
    var loaded: Boolean = false
    var flushes: Int = 0

    override suspend fun load() {
        loaded = true
    }

    override suspend fun tick() {
        flush()
    }

    override suspend fun flush(): Boolean {
        flushes += 1
        return true
    }
}

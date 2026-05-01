package io.github.mikai233.asteria.persistence

import io.github.mikai233.asteria.core.EntityKind
import io.github.mikai233.asteria.core.ServiceRegistry
import kotlinx.coroutines.runBlocking
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class DataManagerTest {
    @Test
    fun `loadEager only loads eager modules`() = runBlocking {
        val eagerData = TestData()
        val lazyData = NamedData("mail")
        val manager = DataManager(
            scope = DataScope(EntityKind("player"), 1001, ServiceRegistry()),
            modules = listOf(
                dataModule(bucket = DataBucket.eager()) { eagerData },
                dataModule(bucket = DataBucket.lazy()) { lazyData },
            ),
        )

        manager.loadEager()

        assertTrue(eagerData.loaded)
        assertFalse(lazyData.loaded)
        assertEquals(eagerData, manager.requireLoaded<TestData>())
        assertTrue(manager.flush())
        assertEquals(1, eagerData.flushes)
    }

    @Test
    fun `lazy data loads on first access`() = runBlocking {
        val data = NamedData("mail")
        val manager = DataManager(
            scope = DataScope(EntityKind("player"), 1001, ServiceRegistry()),
            modules = listOf(dataModule(bucket = DataBucket.lazy()) { data }),
        )

        manager.loadEager()

        assertFalse(data.loaded)

        val loaded = manager.getOrLoad<NamedData>()

        assertEquals(data, loaded)
        assertTrue(data.loaded)
    }

    @Test
    fun `unloadable data must be accessed with scoped use`() = runBlocking {
        val manager = DataManager(
            scope = DataScope(EntityKind("player"), 1001, ServiceRegistry()),
            modules = listOf(
                dataModule(bucket = DataBucket.unloadableLazy("mail", 30.seconds)) { GuardedData() },
            ),
        )

        assertFailsWith<IllegalStateException> {
            manager.getOrLoad<GuardedData>()
        }
    }

    @Test
    fun `idle unload flushes and invalidates unloadable data`() = runBlocking {
        val clock = MutableClock()
        var leaked: GuardedData? = null
        val manager = DataManager(
            scope = DataScope(EntityKind("player"), 1001, ServiceRegistry()),
            modules = listOf(
                dataModule(bucket = DataBucket.unloadableLazy("mail", 10.seconds)) { GuardedData() },
            ),
            clock = clock,
        )

        manager.use<GuardedData, Unit> { data ->
            data.touch()
            leaked = data
        }

        clock.advanceSeconds(10)
        manager.tick()

        assertEquals(1, leaked?.flushes)
        assertFailsWith<IllegalStateException> {
            leaked?.touch()
        }
    }

    @Test
    fun `failed flush keeps unloadable data alive`() = runBlocking {
        val clock = MutableClock()
        val data = GuardedData(flushResult = false)
        val manager = DataManager(
            scope = DataScope(EntityKind("player"), 1001, ServiceRegistry()),
            modules = listOf(
                dataModule(bucket = DataBucket.unloadableLazy("mail", 10.seconds)) { data },
            ),
            clock = clock,
        )

        manager.use<GuardedData, Unit> { it.touch() }

        clock.advanceSeconds(10)
        manager.tick()

        data.touch()
        assertEquals(1, data.flushes)
    }

    @Test
    fun `loadEager cannot be called twice`() = runBlocking {
        val manager = DataManager(
            scope = DataScope(EntityKind("player"), 1001, ServiceRegistry()),
            modules = listOf(dataModule { TestData() }),
        )

        manager.loadEager()

        assertFailsWith<IllegalStateException> {
            manager.loadEager()
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

private class NamedData(
    val name: String,
) : MemData {
    var loaded: Boolean = false

    override suspend fun load() {
        loaded = true
    }
}

private class GuardedData(
    private val flushResult: Boolean = true,
) : LeaseGuardedMemData(), AutoFlushMemData {
    var loaded: Boolean = false
    var flushes: Int = 0

    override suspend fun load() {
        loaded = true
    }

    fun touch() {
        ensureActive()
    }

    override suspend fun tick() = Unit

    override suspend fun flush(): Boolean {
        flushes += 1
        return flushResult
    }
}

private class MutableClock : Clock() {
    private var instant: Instant = Instant.EPOCH

    override fun instant(): Instant = instant

    override fun withZone(zone: ZoneId): Clock = this

    override fun getZone(): ZoneId = ZoneId.of("UTC")

    fun advanceSeconds(seconds: Long) {
        instant = instant.plusSeconds(seconds)
    }
}

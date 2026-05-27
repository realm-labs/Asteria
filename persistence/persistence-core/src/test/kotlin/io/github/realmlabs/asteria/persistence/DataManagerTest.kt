package io.github.realmlabs.asteria.persistence

import io.github.realmlabs.asteria.core.EntityKind
import io.github.realmlabs.asteria.core.ServiceRegistry
import kotlinx.coroutines.runBlocking
import kotlin.test.*
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

class DataManagerTest {
    @Test
    fun `start only loads eager modules`(): Unit = runBlocking {
        val eagerData = TestData()
        val lazyData = NamedData("mail")
        val manager = DataManager(
            scope = DataScope(EntityKind("player"), 1001, ServiceRegistry()),
            modules = listOf(
                dataModule(bucket = DataBucket.eager()) { eagerData },
                dataModule(bucket = DataBucket.lazy()) { lazyData },
            ),
        )

        manager.start()

        assertTrue(eagerData.loaded)
        assertFalse(lazyData.loaded)
        assertEquals(eagerData, manager.requireLoaded<TestData>())
        assertTrue(manager.flush())
        assertEquals(1, eagerData.flushes)
        assertTrue(manager.drain())
        assertEquals(1, eagerData.drains)
    }

    @Test
    fun `lazy data loads on first access`(): Unit = runBlocking {
        val data = NamedData("mail")
        val manager = DataManager(
            scope = DataScope(EntityKind("player"), 1001, ServiceRegistry()),
            modules = listOf(dataModule(bucket = DataBucket.lazy()) { data }),
        )

        manager.start()

        assertFalse(data.loaded)

        val loaded = manager.getOrLoad<NamedData>()

        assertEquals(data, loaded)
        assertTrue(data.loaded)
    }

    @Test
    fun `data access requires started manager`(): Unit = runBlocking {
        val manager = DataManager(
            scope = DataScope(EntityKind("player"), 1001, ServiceRegistry()),
            modules = listOf(dataModule(bucket = DataBucket.lazy()) { NamedData("mail") }),
        )

        assertFailsWith<IllegalStateException> {
            manager.getOrLoad<NamedData>()
        }
        assertFailsWith<IllegalStateException> {
            manager.use<NamedData, Unit> { }
        }
    }

    @Test
    fun `unloadable data is accessed with scoped use`(): Unit = runBlocking {
        val manager = DataManager(
            scope = DataScope(EntityKind("player"), 1001, ServiceRegistry()),
            modules = listOf(
                unloadableDataModule(bucket = DataBucket.unloadableLazy("mail", 30.seconds)) { GuardedData() },
            ),
        )

        manager.start()
        manager.use<GuardedData, Unit> { data -> data.touch() }
    }

    @Test
    fun `idle unload flushes and invalidates unloadable data`(): Unit = runBlocking {
        val clock = MutableClock()
        var leaked: GuardedData? = null
        val manager = DataManager(
            scope = DataScope(EntityKind("player"), 1001, ServiceRegistry()),
            modules = listOf(
                unloadableDataModule(bucket = DataBucket.unloadableLazy("mail", 10.seconds)) { GuardedData() },
            ),
            clock = clock,
        )

        manager.start()
        manager.use<GuardedData, Unit> { data ->
            data.touch()
            leaked = data
        }

        clock.advanceSeconds(10)
        manager.tick()

        assertEquals(1, leaked?.drains)
        assertFailsWith<IllegalStateException> {
            leaked?.touch()
        }
    }

    @Test
    fun `failed flush keeps unloadable data alive`(): Unit = runBlocking {
        val clock = MutableClock()
        val data = GuardedData(flushResult = false)
        val manager = DataManager(
            scope = DataScope(EntityKind("player"), 1001, ServiceRegistry()),
            modules = listOf(
                unloadableDataModule(bucket = DataBucket.unloadableLazy("mail", 10.seconds)) { data },
            ),
            clock = clock,
        )

        manager.start()
        manager.use<GuardedData, Unit> { it.touch() }

        clock.advanceSeconds(10)
        manager.tick()

        data.touch()
        assertEquals(1, data.drains)
    }

    @Test
    fun `use refreshes unloadable data last access time`(): Unit = runBlocking {
        val clock = MutableClock()
        val data = GuardedData()
        val manager = DataManager(
            scope = DataScope(EntityKind("player"), 1001, ServiceRegistry()),
            modules = listOf(
                unloadableDataModule(bucket = DataBucket.unloadableLazy("mail", 10.seconds)) { data },
            ),
            clock = clock,
        )

        manager.start()
        manager.use<GuardedData, Unit> { it.touch() }
        clock.advanceSeconds(9)
        manager.use<GuardedData, Unit> { it.touch() }
        clock.advanceSeconds(9)
        manager.tick()

        data.touch()
        assertEquals(0, data.drains)

        clock.advanceSeconds(1)
        manager.tick()

        assertEquals(1, data.drains)
        assertFailsWith<IllegalStateException> {
            data.touch()
        }
    }

    @Test
    fun `idle unload only unloads expired modules`(): Unit = runBlocking {
        val clock = MutableClock()
        val expired = GuardedData()
        val active = OtherGuardedData()
        val manager = DataManager(
            scope = DataScope(EntityKind("player"), 1001, ServiceRegistry()),
            modules = listOf(
                unloadableDataModule(bucket = DataBucket.unloadableLazy("mail", 10.seconds)) { expired },
                unloadableDataModule(bucket = DataBucket.unloadableLazy("activity", 10.seconds)) { active },
            ),
            clock = clock,
        )

        manager.start()
        manager.use<GuardedData, Unit> { it.touch() }
        clock.advanceSeconds(5)
        manager.use<OtherGuardedData, Unit> { it.touch() }
        clock.advanceSeconds(5)
        manager.tick()

        assertEquals(1, expired.drains)
        assertEquals(0, active.drains)
        active.touch()
        assertFailsWith<IllegalStateException> {
            expired.touch()
        }
    }

    @Test
    fun `use can load multiple data modules in one scope`(): Unit = runBlocking {
        val clock = MutableClock()
        val first = GuardedData()
        val second = OtherGuardedData()
        val manager = DataManager(
            scope = DataScope(EntityKind("player"), 1001, ServiceRegistry()),
            modules = listOf(
                unloadableDataModule(bucket = DataBucket.unloadableLazy("mail", 10.seconds)) { first },
                unloadableDataModule(bucket = DataBucket.unloadableLazy("activity", 10.seconds)) { second },
            ),
            clock = clock,
        )

        manager.start()
        val result = manager.use { mail: GuardedData, activity: OtherGuardedData ->
            mail.touch()
            activity.touch()
            "used"
        }

        assertEquals("used", result)
        assertTrue(first.loaded)
        assertTrue(second.loaded)

        clock.advanceSeconds(10)
        manager.tick()

        assertEquals(1, first.drains)
        assertEquals(1, second.drains)
        assertFailsWith<IllegalStateException> {
            first.touch()
        }
        assertFailsWith<IllegalStateException> {
            second.touch()
        }
    }

    @Test
    fun `multi use rejects duplicate data types`(): Unit = runBlocking {
        val manager = DataManager(
            scope = DataScope(EntityKind("player"), 1001, ServiceRegistry()),
            modules = listOf(dataModule(bucket = DataBucket.lazy()) { NamedData("mail") }),
        )

        manager.start()
        assertFailsWith<IllegalStateException> {
            manager.use(NamedData::class, NamedData::class) { _, _ -> }
        }
    }

    @Test
    fun `use supports five data modules in one scope`(): Unit = runBlocking {
        val manager = DataManager(
            scope = DataScope(EntityKind("player"), 1001, ServiceRegistry()),
            modules = listOf(
                dataModule(bucket = DataBucket.lazy()) { NamedData("first") },
                dataModule(bucket = DataBucket.lazy()) { SecondData() },
                dataModule(bucket = DataBucket.lazy()) { ThirdData() },
                dataModule(bucket = DataBucket.lazy()) { FourthData() },
                dataModule(bucket = DataBucket.lazy()) { FifthData() },
            ),
        )

        manager.start()
        val result =
            manager.use { first: NamedData, second: SecondData, third: ThirdData, fourth: FourthData, fifth: FifthData ->
                listOf(first.name, second.name, third.name, fourth.name, fifth.name)
            }

        assertEquals(listOf("first", "second", "third", "fourth", "fifth"), result)
    }

    @Test
    fun `unloaded data is loaded again as a fresh instance`(): Unit = runBlocking {
        val clock = MutableClock()
        val created = mutableListOf<GuardedData>()
        val manager = DataManager(
            scope = DataScope(EntityKind("player"), 1001, ServiceRegistry()),
            modules = listOf(
                unloadableDataModule(bucket = DataBucket.unloadableLazy("mail", 10.seconds)) {
                    GuardedData().also { created += it }
                },
            ),
            clock = clock,
        )
        lateinit var first: GuardedData
        lateinit var second: GuardedData

        manager.start()
        manager.use<GuardedData, Unit> { first = it }
        clock.advanceSeconds(10)
        manager.tick()
        manager.use<GuardedData, Unit> { second = it }

        assertEquals(2, created.size)
        assertNotSame(first, second)
        second.touch()
        assertFailsWith<IllegalStateException> {
            first.touch()
        }
    }

    @Test
    fun `start cannot be called twice`(): Unit = runBlocking {
        val manager = DataManager(
            scope = DataScope(EntityKind("player"), 1001, ServiceRegistry()),
            modules = listOf(dataModule { TestData() }),
        )

        manager.start()

        assertFailsWith<IllegalStateException> {
            manager.start()
        }
    }

    @Test
    fun `manager rejects duplicate data module types`() {
        assertFailsWith<IllegalStateException> {
            DataManager(
                scope = DataScope(EntityKind("player"), 1001, ServiceRegistry()),
                modules = listOf(
                    dataModule { TestData() },
                    dataModule { TestData() },
                ),
            )
        }
    }

    @Test
    fun `unregistered data access fails fast`(): Unit = runBlocking {
        val manager = DataManager(
            scope = DataScope(EntityKind("player"), 1001, ServiceRegistry()),
            modules = emptyList(),
        )

        manager.start()
        assertFailsWith<IllegalStateException> {
            manager.getOrLoad<NamedData>()
        }
        assertFailsWith<IllegalStateException> {
            manager.requireLoaded<NamedData>()
        }
    }

    @Test
    fun `failed load is not cached`(): Unit = runBlocking {
        var attempts = 0
        val manager = DataManager(
            scope = DataScope(EntityKind("player"), 1001, ServiceRegistry()),
            modules = listOf(
                dataModule(bucket = DataBucket.lazy()) {
                    attempts += 1
                    FailableLoadData(failLoad = attempts == 1)
                },
            ),
        )

        manager.start()
        assertFailsWith<IllegalStateException> {
            manager.getOrLoad<FailableLoadData>()
        }

        val loaded = manager.getOrLoad<FailableLoadData>()

        assertTrue(loaded.loaded)
        assertEquals(2, attempts)
    }

    @Test
    fun `flush and drain continue after one data returns false`(): Unit = runBlocking {
        val first = TestData(flushResult = false, drainResult = false)
        val second = OtherAutoFlushData()
        val manager = DataManager(
            scope = DataScope(EntityKind("player"), 1001, ServiceRegistry()),
            modules = listOf(
                dataModule { first },
                dataModule { second },
            ),
        )

        manager.start()

        assertFalse(manager.flush())
        assertEquals(1, first.flushes)
        assertEquals(1, second.flushes)

        assertFalse(manager.drain())
        assertEquals(1, first.drains)
        assertEquals(1, second.drains)
    }

    @Test
    fun `idle unload attempts remaining modules after one unload throws`(): Unit = runBlocking {
        val clock = MutableClock()
        val first = ThrowingDrainData()
        val second = OtherGuardedData()
        val manager = DataManager(
            scope = DataScope(EntityKind("player"), 1001, ServiceRegistry()),
            modules = listOf(
                unloadableDataModule(bucket = DataBucket.unloadableLazy("mail", 10.seconds)) { first },
                unloadableDataModule(bucket = DataBucket.unloadableLazy("activity", 10.seconds)) { second },
            ),
            clock = clock,
        )

        manager.start()
        manager.use<ThrowingDrainData, Unit> { it.touch() }
        manager.use<OtherGuardedData, Unit> { it.touch() }
        clock.advanceSeconds(10)

        val error = assertFailsWith<IllegalStateException> {
            manager.tick()
        }

        assertEquals("drain failed", error.message)
        assertEquals(1, first.drains)
        assertEquals(1, second.drains)
        first.touch()
        assertFailsWith<IllegalStateException> {
            second.touch()
        }
    }

    @Test
    fun `multi use refreshes access time when block fails`(): Unit = runBlocking {
        val clock = MutableClock()
        val first = GuardedData()
        val second = OtherGuardedData()
        val manager = DataManager(
            scope = DataScope(EntityKind("player"), 1001, ServiceRegistry()),
            modules = listOf(
                unloadableDataModule(bucket = DataBucket.unloadableLazy("mail", 10.seconds)) { first },
                unloadableDataModule(bucket = DataBucket.unloadableLazy("activity", 10.seconds)) { second },
            ),
            clock = clock,
        )

        manager.start()
        manager.use { mail: GuardedData, activity: OtherGuardedData ->
            mail.touch()
            activity.touch()
        }
        clock.advanceSeconds(9)
        assertFailsWith<IllegalStateException> {
            manager.use { mail: GuardedData, activity: OtherGuardedData ->
                mail.touch()
                activity.touch()
                error("business failure")
            }
        }
        clock.advanceSeconds(9)
        manager.tick()

        first.touch()
        second.touch()
        assertEquals(0, first.drains)
        assertEquals(0, second.drains)

        clock.advanceSeconds(1)
        manager.tick()

        assertEquals(1, first.drains)
        assertEquals(1, second.drains)
    }
}

private class TestData(
    private val flushResult: Boolean = true,
    private val drainResult: Boolean = true,
) : ResidentMemData, AutoFlushMemData {
    var loaded: Boolean = false
    var flushes: Int = 0
    var drains: Int = 0

    override suspend fun load() {
        loaded = true
    }

    override suspend fun tick() {
        flush()
    }

    override suspend fun flush(): Boolean {
        flushes += 1
        return flushResult
    }

    override suspend fun drain(): Boolean {
        drains += 1
        return drainResult
    }
}

private class NamedData(
    val name: String,
) : ResidentMemData {
    var loaded: Boolean = false

    override suspend fun load() {
        loaded = true
    }
}

private class GuardedData(
    private val flushResult: Boolean = true,
) : LeaseGuardedMemData(), AutoFlushMemData {
    var loaded: Boolean = false
    var drains: Int = 0

    override suspend fun load() {
        loaded = true
    }

    fun touch() {
        ensureActive()
    }

    override suspend fun tick() = Unit

    override suspend fun flush(): Boolean {
        return flushResult
    }

    override suspend fun drain(): Boolean {
        drains += 1
        return flushResult
    }
}

private class OtherGuardedData : LeaseGuardedMemData(), AutoFlushMemData {
    var loaded: Boolean = false
    var drains: Int = 0

    override suspend fun load() {
        loaded = true
    }

    fun touch() {
        ensureActive()
    }

    override suspend fun tick() = Unit

    override suspend fun flush(): Boolean {
        return true
    }

    override suspend fun drain(): Boolean {
        drains += 1
        return true
    }
}

private class ThrowingDrainData : LeaseGuardedMemData(), AutoFlushMemData {
    var drains: Int = 0

    override suspend fun load() = Unit

    fun touch() {
        ensureActive()
    }

    override suspend fun tick() = Unit

    override suspend fun flush(): Boolean = true

    override suspend fun drain(): Boolean {
        drains += 1
        error("drain failed")
    }
}

private class OtherAutoFlushData : ResidentMemData, AutoFlushMemData {
    var flushes: Int = 0
    var drains: Int = 0

    override suspend fun load() = Unit

    override suspend fun tick() = Unit

    override suspend fun flush(): Boolean {
        flushes += 1
        return true
    }

    override suspend fun drain(): Boolean {
        drains += 1
        return true
    }
}

private class FailableLoadData(
    private val failLoad: Boolean,
) : ResidentMemData {
    var loaded: Boolean = false

    override suspend fun load() {
        check(!failLoad) { "load failed" }
        loaded = true
    }
}

private class SecondData : ResidentMemData {
    val name: String = "second"

    override suspend fun load() = Unit
}

private class ThirdData : ResidentMemData {
    val name: String = "third"

    override suspend fun load() = Unit
}

private class FourthData : ResidentMemData {
    val name: String = "fourth"

    override suspend fun load() = Unit
}

private class FifthData : ResidentMemData {
    val name: String = "fifth"

    override suspend fun load() = Unit
}

private class MutableClock : Clock {
    private var instant: Instant = Instant.fromEpochMilliseconds(0)

    override fun now(): Instant = instant

    fun advanceSeconds(seconds: Long) {
        instant += seconds.seconds
    }
}

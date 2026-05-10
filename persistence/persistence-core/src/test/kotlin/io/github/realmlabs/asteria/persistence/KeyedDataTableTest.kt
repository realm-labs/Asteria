package io.github.realmlabs.asteria.persistence

import kotlinx.coroutines.runBlocking
import kotlin.test.*
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

class KeyedDataTableTest {
    @Test
    fun `use lazily loads one row`(): Unit = runBlocking {
        val table = TestTable(rows = listOf(TestRow(1, "alice")))

        table.use(1) { row ->
            assertEquals("alice", row.name)
            row.rename("bob")
        }

        assertEquals(setOf(1), table.loadedKeys())
        assertEquals("alice", table.source.getValue(1).name)
        assertTrue(table.flush())
        assertEquals("bob", table.source.getValue(1).name)
    }

    @Test
    fun `useOrNull returns null when row does not exist`(): Unit = runBlocking {
        val table = TestTable(rows = emptyList())

        val result = table.useOrNull(1) { it.name }

        assertNull(result)
        assertEquals(0, table.loadedCount())
    }

    @Test
    fun `idle unload flushes row and invalidates leaked reference`(): Unit = runBlocking {
        val clock = MutableTableClock()
        val table = TestTable(rows = listOf(TestRow(1, "alice")), clock = clock)
        var leaked: TestRow? = null

        table.use(1) { row ->
            row.rename("bob")
            leaked = row
        }

        clock.advanceSeconds(10)
        table.unloadIdle()

        assertEquals(0, table.loadedCount())
        assertEquals(listOf(1), table.flushed)
        assertFailsWith<IllegalStateException> {
            leaked?.rename("carol")
        }
    }

    @Test
    fun `failed row flush keeps row loaded`(): Unit = runBlocking {
        val clock = MutableTableClock()
        val table = TestTable(rows = listOf(TestRow(1, "alice")), clock = clock, flushResult = false)

        table.use(1) { it.rename("bob") }
        clock.advanceSeconds(10)
        table.unloadIdle()

        assertEquals(1, table.loadedCount())
        assertEquals(listOf(1), table.flushed)
        table.use(1) { row ->
            row.rename("carol")
        }
    }

    @Test
    fun `idle unload only unloads expired rows`(): Unit = runBlocking {
        val clock = MutableTableClock()
        val table = TestTable(
            rows = listOf(TestRow(1, "alice"), TestRow(2, "bob")),
            clock = clock,
        )

        table.use(1) { it.rename("alice-1") }
        clock.advanceSeconds(5)
        table.use(2) { it.rename("bob-1") }
        clock.advanceSeconds(5)
        table.unloadIdle()

        assertEquals(setOf(2), table.loadedKeys())
        assertEquals(listOf(1), table.flushed)
        assertEquals("alice-1", table.source.getValue(1).name)
        assertEquals("bob", table.source.getValue(2).name)
    }

    @Test
    fun `use refreshes last access time`(): Unit = runBlocking {
        val clock = MutableTableClock()
        val table = TestTable(rows = listOf(TestRow(1, "alice")), clock = clock)

        table.use(1) { it.rename("bob") }
        clock.advanceSeconds(9)
        table.use(1) { it.rename("carol") }
        clock.advanceSeconds(9)
        table.unloadIdle()

        assertEquals(1, table.loadedCount())
        assertTrue(table.flushed.isEmpty())

        clock.advanceSeconds(1)
        table.unloadIdle()

        assertEquals(0, table.loadedCount())
        assertEquals(listOf(1), table.flushed)
    }

    @Test
    fun `unloaded row is loaded again as a fresh object`(): Unit = runBlocking {
        val clock = MutableTableClock()
        val table = TestTable(rows = listOf(TestRow(1, "alice")), clock = clock)
        var first: TestRow? = null
        var second: TestRow? = null

        table.use(1) { row ->
            row.rename("bob")
            first = row
        }
        clock.advanceSeconds(10)
        table.unloadIdle()
        table.use(1) { row ->
            second = row
        }

        assertNotSame(first, second)
        assertEquals("bob", second?.name)
        assertFailsWith<IllegalStateException> {
            first?.rename("stale")
        }
    }

    @Test
    fun `loadAllIntoMemory loads all rows explicitly`(): Unit = runBlocking {
        val table = TestTable(rows = listOf(TestRow(1, "alice"), TestRow(2, "bob")))

        val loaded = table.loadAllIntoMemory()

        assertEquals(2, loaded)
        assertEquals(setOf(1, 2), table.loadedKeys())
    }

    @Test
    fun `loadAllIntoMemory does not overwrite already loaded rows`(): Unit = runBlocking {
        val table = TestTable(rows = listOf(TestRow(1, "alice"), TestRow(2, "bob")))

        table.use(1) { it.rename("alice-local") }
        val loaded = table.loadAllIntoMemory()
        assertTrue(table.flush())

        assertEquals(1, loaded)
        assertEquals(setOf(1, 2), table.loadedKeys())
        assertEquals("alice-local", table.source.getValue(1).name)
    }

    @Test
    fun `subclass can add created row to loaded cache`(): Unit = runBlocking {
        val table = TestTable(rows = emptyList())

        table.createLoaded(TestRow(1, "alice"))
        table.use(1) { row -> row.rename("bob") }

        assertEquals(setOf(1), table.loadedKeys())
        assertTrue(table.flush())
        assertEquals("bob", table.source.getValue(1).name)
    }

    @Test
    fun `flush writes loaded rows without unloading`(): Unit = runBlocking {
        val table = TestTable(rows = listOf(TestRow(1, "alice"), TestRow(2, "bob")))

        table.use(1) { it.rename("alice-1") }
        table.use(2) { it.rename("bob-1") }

        assertTrue(table.flush())

        assertEquals(setOf(1, 2), table.loadedKeys())
        assertEquals(listOf(1, 2), table.flushed)
        assertEquals("alice-1", table.source.getValue(1).name)
        assertEquals("bob-1", table.source.getValue(2).name)
    }

    @Test
    fun `database queries return candidate keys and caller rechecks current row`(): Unit = runBlocking {
        val table = TestTable(
            rows = listOf(
                TestRow(1, "alice", inactive = true),
                TestRow(2, "bob", inactive = false),
            ),
        )
        val candidates = table.queryKeys { it.inactive }

        table.use(1) { row ->
            row.inactive = false
        }
        candidates.forEach { id ->
            table.use(id) { row ->
                if (row.inactive) {
                    row.selected = true
                }
            }
        }

        assertFalse(table.source.getValue(1).selected)
    }
}

private class TestTable(
    rows: List<TestRow>,
    clock: Clock = Clock.System,
    private val flushResult: Boolean = true,
) : KeyedDataTable<Int, TestRow>(
    cachePolicy = RowCachePolicy(10.seconds),
    clock = clock,
) {
    val source: MutableMap<Int, TestRow> = rows.associateBy { it.id }.toMutableMap()
    val flushed: MutableList<Int> = mutableListOf()
    val loaded: MutableList<Int> = mutableListOf()

    override suspend fun loadRow(key: Int): TestRow? {
        loaded += key
        return source[key]?.copy()
    }

    override suspend fun loadAllRows(): Iterable<TestRow> {
        return source.values.map { it.copy() }
    }

    override fun keyOf(row: TestRow): Int = row.id

    override suspend fun flushRow(row: TestRow): Boolean {
        flushed += row.id
        if (flushResult) {
            source[row.id] = row.copy()
        }
        return flushResult
    }

    fun createLoaded(row: TestRow) {
        addLoaded(row)
    }

    fun queryKeys(predicate: (TestRow) -> Boolean): List<Int> {
        return source.values.filter(predicate).map { it.id }
    }
}

private data class TestRow(
    val id: Int,
    var name: String,
    var inactive: Boolean = false,
    var selected: Boolean = false,
) : DataLeaseAware {
    private var lease: DataLease? = null

    override fun bindLease(lease: DataLease) {
        this.lease = lease
    }

    fun rename(name: String) {
        requireNotNull(lease) { "row lease is not bound" }.ensureActive()
        this.name = name
    }
}

private class MutableTableClock : Clock {
    private var instant: Instant = Instant.fromEpochMilliseconds(0)

    override fun now(): Instant = instant

    fun advanceSeconds(seconds: Long) {
        instant += seconds.seconds
    }
}

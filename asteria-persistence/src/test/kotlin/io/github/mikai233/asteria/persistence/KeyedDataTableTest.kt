package io.github.mikai233.asteria.persistence

import kotlinx.coroutines.runBlocking
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class KeyedDataTableTest {
    @Test
    fun `use lazily loads one row`() = runBlocking {
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
    fun `useOrNull returns null when row does not exist`() = runBlocking {
        val table = TestTable(rows = emptyList())

        val result = table.useOrNull(1) { it.name }

        assertNull(result)
        assertEquals(0, table.loadedCount())
    }

    @Test
    fun `idle unload flushes row and invalidates leaked reference`() = runBlocking {
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
    fun `failed row flush keeps row loaded`() = runBlocking {
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
    fun `loadAllIntoMemory loads all rows explicitly`() = runBlocking {
        val table = TestTable(rows = listOf(TestRow(1, "alice"), TestRow(2, "bob")))

        val loaded = table.loadAllIntoMemory()

        assertEquals(2, loaded)
        assertEquals(setOf(1, 2), table.loadedKeys())
    }

    @Test
    fun `database queries return candidate keys and caller rechecks current row`() = runBlocking {
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
    clock: Clock = Clock.systemUTC(),
    private val flushResult: Boolean = true,
) : KeyedDataTable<Int, TestRow>(
    cachePolicy = RowCachePolicy(10.seconds),
    clock = clock,
) {
    val source: MutableMap<Int, TestRow> = rows.associateBy { it.id }.toMutableMap()
    val flushed: MutableList<Int> = mutableListOf()

    override suspend fun loadRow(key: Int): TestRow? {
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

private class MutableTableClock : Clock() {
    private var instant: Instant = Instant.EPOCH

    override fun instant(): Instant = instant

    override fun withZone(zone: ZoneId): Clock = this

    override fun getZone(): ZoneId = ZoneId.of("UTC")

    fun advanceSeconds(seconds: Long) {
        instant = instant.plusSeconds(seconds)
    }
}

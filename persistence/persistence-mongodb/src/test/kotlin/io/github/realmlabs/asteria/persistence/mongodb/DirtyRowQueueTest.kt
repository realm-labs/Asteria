package io.github.realmlabs.asteria.persistence.mongodb

import io.github.realmlabs.asteria.persistence.mongodb.common.DirtyRowQueue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DirtyRowQueueTest {
    @Test
    fun `same row is only queued once`() {
        val queue = DirtyRowQueue<Int>()

        queue.markDirty(1)
        queue.markDirty(1)
        queue.markDirty(2)

        assertEquals(2, queue.size)
        assertEquals(1, queue.next())
        assertEquals(2, queue.next())
        assertNull(queue.next())
    }

    @Test
    fun `failed row can be requeued behind remaining dirty rows`() {
        val queue = DirtyRowQueue<Int>()

        queue.markDirty(1)
        queue.markDirty(2)
        assertEquals(1, queue.next())
        queue.markDirty(1)

        assertEquals(2, queue.next())
        assertEquals(1, queue.next())
        assertNull(queue.next())
    }

    @Test
    fun `clean row is skipped`() {
        val queue = DirtyRowQueue<Int>()

        queue.markDirty(1)
        queue.markDirty(2)
        queue.markClean(1)

        assertEquals(2, queue.next())
        assertNull(queue.next())
    }

    @Test
    fun `removed row is not returned`() {
        val queue = DirtyRowQueue<Int>()

        queue.markDirty(1)
        queue.markDirty(2)
        queue.remove(1)

        assertEquals(2, queue.next())
        assertNull(queue.next())
    }
}

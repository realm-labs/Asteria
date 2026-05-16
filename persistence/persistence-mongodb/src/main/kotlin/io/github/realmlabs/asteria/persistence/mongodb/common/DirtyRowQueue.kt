package io.github.realmlabs.asteria.persistence.mongodb.common

internal class DirtyRowQueue<ID : Any> {
    private val rows: ArrayDeque<ID> = ArrayDeque()
    private val rowSet: MutableSet<ID> = linkedSetOf()

    val size: Int
        get() = rowSet.size

    fun markDirty(id: ID) {
        if (rowSet.add(id)) {
            rows.addLast(id)
        }
    }

    fun markClean(id: ID) {
        rowSet.remove(id)
    }

    fun remove(id: ID) {
        rowSet.remove(id)
        rows.removeAll { it == id }
    }

    fun next(): ID? {
        while (rows.isNotEmpty()) {
            val id = rows.removeFirst()
            if (id in rowSet) {
                rowSet.remove(id)
                return id
            }
        }
        return null
    }
}

package io.github.realmlabs.asteria.persistence

/**
 * Logical field path inside an entity.
 *
 * The path is database-agnostic. Storage implementations are responsible for encoding path parts into their own update
 * syntax, for example Mongo `$set` paths or SQL column/json paths.
 */
data class FieldPath(
    val parts: List<Any?>,
) {
    init {
        require(parts.isNotEmpty()) { "field path must not be empty" }
    }

    fun child(part: Any?): FieldPath {
        return copy(parts = parts + part)
    }

    fun isDirectChildOf(parent: FieldPath): Boolean {
        return parts.size == parent.parts.size + 1 && parts.take(parent.parts.size) == parent.parts
    }

    companion object {
        fun of(first: Any?, vararg rest: Any?): FieldPath {
            return FieldPath(listOf(first) + rest.toList())
        }
    }
}

/**
 * Logical change detected by an entity scan.
 */
sealed interface FieldChange {
    val path: FieldPath

    data class Set(override val path: FieldPath, val value: Any?) : FieldChange
    data class Unset(override val path: FieldPath) : FieldChange
}

/**
 * Captured field hashes for one entity at one point in time.
 */
data class EntityScanSnapshot(
    val fields: Map<FieldPath, Long>,
) {
    companion object {
        val Empty: EntityScanSnapshot = EntityScanSnapshot(emptyMap())
    }
}

/**
 * Describes how to capture and compare one entity type.
 */
interface EntityScanPlan<E : Any> {
    fun capture(entity: E): EntityScanSnapshot

    fun diff(
        previous: EntityScanSnapshot,
        current: EntityScanSnapshot,
        currentEntity: E,
    ): List<FieldChange>

    fun setAll(snapshot: EntityScanSnapshot, currentEntity: E): List<FieldChange>
}

/**
 * One field tracked by [FieldHashScanPlan].
 *
 * For normal fields, [value] returns the whole stored field value. For map-like fields, [children] can expose stable
 * child values keyed by logical path part, allowing the scan to emit per-key set/unset operations instead of replacing
 * the whole map.
 */
data class ScannedField<E : Any>(
    val path: FieldPath,
    val value: (E) -> Any?,
    val hash: (Any?) -> Long,
    val children: ((Any?) -> Map<Any?, Any?>)? = null,
) {
    fun capture(entity: E): Map<FieldPath, Long> {
        val value = value(entity)
        val children = children ?: return mapOf(path to hash(value))
        return children(value).mapKeys { (key, _) -> path.child(key) }
            .mapValues { (_, childValue) -> hash(childValue) }
    }

    fun valueAt(entity: E, targetPath: FieldPath): Any? {
        if (targetPath == path) {
            return value(entity)
        }
        val children = children ?: return null
        if (!targetPath.isDirectChildOf(path)) {
            return null
        }
        val childKey = targetPath.parts.last()
        return children(value(entity))[childKey]
    }
}

/**
 * Hash-based scan plan for actor-local entities.
 *
 * The plan stores only hashes between scans. When a hash changes, it asks the field extractor for the current value and
 * emits a logical [FieldChange]. It does not know how to serialize or flush those changes.
 */
class FieldHashScanPlan<E : Any>(
    fields: Iterable<ScannedField<E>>,
) : EntityScanPlan<E> {
    private val fields: List<ScannedField<E>> = fields.toList()

    init {
        val duplicate = this.fields.groupBy { it.path }.filterValues { it.size > 1 }.keys.firstOrNull()
        require(duplicate == null) { "duplicate scanned field path $duplicate" }
    }

    override fun capture(entity: E): EntityScanSnapshot {
        return EntityScanSnapshot(fields.flatMap { field -> field.capture(entity).entries }.associate { it.toPair() })
    }

    override fun diff(
        previous: EntityScanSnapshot,
        current: EntityScanSnapshot,
        currentEntity: E,
    ): List<FieldChange> {
        val changes = mutableListOf<FieldChange>()
        previous.fields.keys
            .filter { path -> path !in current.fields }
            .forEach { path -> changes += FieldChange.Unset(path) }
        current.fields.forEach { (path, hash) ->
            if (previous.fields[path] != hash) {
                changes += FieldChange.Set(path, requireCurrentValue(path, currentEntity))
            }
        }
        return changes
    }

    override fun setAll(snapshot: EntityScanSnapshot, currentEntity: E): List<FieldChange> {
        return snapshot.fields.keys.map { path -> FieldChange.Set(path, requireCurrentValue(path, currentEntity)) }
    }

    private fun requireCurrentValue(path: FieldPath, entity: E): Any? {
        fields.forEach { field ->
            val value = field.valueAt(entity, path)
            if (value != null || path == field.path || path.isDirectChildOf(field.path)) {
                return value
            }
        }
        error("no scanned field value provider for $path")
    }
}

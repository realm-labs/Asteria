package io.github.realmlabs.asteria.config

/**
 * One validation problem found while checking a fully loaded config snapshot.
 *
 * [table] and [id] are optional context fields intended for tooling, logs, and GM diagnostics. They do not affect the
 * semantics of validation failure itself.
 */
data class ConfigValidationError(
    val message: String,
    val table: ConfigTableName? = null,
    val id: String? = null,
    val sourcePath: String? = null,
)

/**
 * Thrown when one or more [ConfigValidator] instances reject a snapshot.
 */
class ConfigValidationException(
    val errors: List<ConfigValidationError>,
) : IllegalStateException(errors.joinToString(separator = "\n") { it.format() })

/**
 * Aggregate result returned by a [ConfigValidator].
 */
data class ConfigValidationResult(
    val errors: List<ConfigValidationError> = emptyList(),
) {
    val isValid: Boolean get() = errors.isEmpty()

    /**
     * Throws [ConfigValidationException] when [errors] is not empty.
     */
    fun throwIfFailed() {
        if (errors.isNotEmpty()) {
            throw ConfigValidationException(errors)
        }
    }

    companion object {
        val Valid: ConfigValidationResult = ConfigValidationResult()
    }
}

/**
 * Validates a fully loaded snapshot before it is published.
 *
 * Validators should be deterministic and side-effect free. They run after component builders and before
 * [ConfigService] publishes the new snapshot.
 */
fun interface ConfigValidator {
    suspend fun validate(snapshot: ConfigSnapshot): ConfigValidationResult
}

/**
 * [ConfigValidator] variant for implementations that want to write validation directly in [ConfigValidationScope].
 *
 * This is useful for named validators, especially contributed objects, where [configValidator] would otherwise require
 * an extra field or Kotlin interface delegation.
 */
fun interface DslConfigValidator : ConfigValidator {
    suspend fun ConfigValidationScope.validateConfig(snapshot: ConfigSnapshot)

    override suspend fun validate(snapshot: ConfigSnapshot): ConfigValidationResult {
        return ConfigValidationScope().apply {
            validateConfig(snapshot)
        }.result()
    }
}

/**
 * Mutable helper used by [configValidator] to accumulate validation errors.
 *
 * This scope is intentionally append-only: validators can report multiple problems in one pass instead of failing fast
 * on the first broken row.
 */
class ConfigValidationScope {
    private val errors: MutableList<ConfigValidationError> = mutableListOf()

    /**
     * Records [message] when [condition] is `false`.
     */
    fun check(
        condition: Boolean,
        message: String,
        table: ConfigTableName? = null,
        id: Any? = null,
    ) {
        if (!condition) {
            addError(message, table, id)
        }
    }

    /**
     * Records [message] with generated keyed table reference context when [condition] is `false`.
     */
    fun check(
        condition: Boolean,
        message: String,
        table: ConfigTableRef<*, *>,
        id: Any? = null,
    ) {
        if (!condition) {
            addError(message, table.name, id, table.sourcePath)
        }
    }

    /**
     * Records [message] with generated row table reference context when [condition] is `false`.
     */
    fun check(
        condition: Boolean,
        message: String,
        table: RowConfigTableRef<*>,
        id: Any? = null,
    ) {
        if (!condition) {
            addError(message, table.name, id, table.sourcePath)
        }
    }

    /**
     * Records an unconditional validation failure.
     */
    fun fail(
        message: String,
        table: ConfigTableName? = null,
        id: Any? = null,
    ) {
        addError(message, table, id)
    }

    /**
     * Records an unconditional validation failure with generated keyed table reference context.
     */
    fun fail(
        message: String,
        table: ConfigTableRef<*, *>,
        id: Any? = null,
    ) {
        addError(message, table.name, id, table.sourcePath)
    }

    /**
     * Records an unconditional validation failure with generated row table reference context.
     */
    fun fail(
        message: String,
        table: RowConfigTableRef<*>,
        id: Any? = null,
    ) {
        addError(message, table.name, id, table.sourcePath)
    }

    private fun addError(
        message: String,
        table: ConfigTableName? = null,
        id: Any? = null,
        sourcePath: String? = null,
    ) {
        errors += ConfigValidationError(
            message = message,
            table = table,
            id = id?.toString(),
            sourcePath = sourcePath,
        )
    }

    internal fun result(): ConfigValidationResult {
        return ConfigValidationResult(errors.toList())
    }
}

/**
 * Creates a [ConfigValidator] from an inline DSL.
 *
 * The DSL always returns a full [ConfigValidationResult]; throwing is reserved for unexpected validator bugs rather
 * than business validation failures.
 */
fun configValidator(validate: suspend ConfigValidationScope.(ConfigSnapshot) -> Unit): DslConfigValidator {
    return DslConfigValidator { snapshot ->
        validate(snapshot)
    }
}

private fun ConfigValidationError.format(): String {
    val prefix = buildList {
        if (sourcePath != null) {
            add("source=$sourcePath")
        }
        if (table != null) {
            add("table=$table")
        }
        if (id != null) {
            add("id=$id")
        }
    }.joinToString(prefix = "[", postfix = "]")

    return if (prefix == "[]") message else "$prefix $message"
}

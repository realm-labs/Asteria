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
            errors += ConfigValidationError(
                message = message,
                table = table,
                id = id?.toString(),
            )
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
        errors += ConfigValidationError(
            message = message,
            table = table,
            id = id?.toString(),
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
fun configValidator(validate: suspend ConfigValidationScope.(ConfigSnapshot) -> Unit): ConfigValidator {
    return ConfigValidator { snapshot ->
        ConfigValidationScope().apply {
            validate(snapshot)
        }.result()
    }
}

private fun ConfigValidationError.format(): String {
    val prefix = buildList {
        if (table != null) {
            add("table=$table")
        }
        if (id != null) {
            add("id=$id")
        }
    }.joinToString(prefix = "[", postfix = "]")

    return if (prefix == "[]") message else "$prefix $message"
}

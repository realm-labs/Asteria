package io.github.mikai233.asteria.config

data class ConfigValidationError(
    val message: String,
    val table: ConfigTableName? = null,
    val id: String? = null,
)

class ConfigValidationException(
    val errors: List<ConfigValidationError>,
) : IllegalStateException(errors.joinToString(separator = "\n") { it.format() })

data class ConfigValidationResult(
    val errors: List<ConfigValidationError> = emptyList(),
) {
    val isValid: Boolean get() = errors.isEmpty()

    fun throwIfFailed() {
        if (errors.isNotEmpty()) {
            throw ConfigValidationException(errors)
        }
    }

    companion object {
        val Valid: ConfigValidationResult = ConfigValidationResult()
    }
}

fun interface ConfigValidator {
    suspend fun validate(snapshot: ConfigSnapshot): ConfigValidationResult
}

class ConfigValidationScope {
    private val errors: MutableList<ConfigValidationError> = mutableListOf()

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

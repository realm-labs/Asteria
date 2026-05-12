package io.github.realmlabs.asteria.config.center

/**
 * Normalized absolute path inside a config center namespace.
 *
 * Paths always use slash separators and never end with a trailing slash unless they are the root path `/`.
 */
@JvmInline
value class ConfigPath(val value: String) {
    init {
        require(value.isNotBlank()) { "config path must not be blank" }
        require(value.startsWith("/")) { "config path must start with /" }
        require(!value.contains("//")) { "config path must not contain empty segments: $value" }
        require(value == "/" || !value.endsWith("/")) { "config path must not end with /: $value" }
    }

    /**
     * Last path segment, or an empty string for the root path.
     */
    val name: String
        get() = if (value == "/") "" else value.substringAfterLast("/")

    /**
     * Parent path, or `null` when this path is root.
     */
    val parent: ConfigPath?
        get() {
            if (value == "/") {
                return null
            }
            val parent = value.substringBeforeLast("/", missingDelimiterValue = "/")
            return ConfigPath(parent.ifBlank { "/" })
        }

    /**
     * Appends a relative child segment or nested relative path.
     */
    operator fun div(child: String): ConfigPath {
        require(child.isNotBlank()) { "config path child must not be blank" }
        require(!child.startsWith("/") && !child.endsWith("/") && !child.contains("//")) {
            "config path child must be a relative single or nested path: $child"
        }
        return ConfigPath(if (value == "/") "/$child" else "$value/$child")
    }

    /**
     * Returns `true` when this path is a direct child of [parent].
     */
    fun isChildOf(parent: ConfigPath): Boolean {
        return this.parent == parent
    }

    /**
     * Returns `true` when this path is below [parent] at any depth.
     */
    fun isDescendantOf(parent: ConfigPath): Boolean {
        return value != parent.value &&
                (parent.value == "/" || value.startsWith("${parent.value}/"))
    }

    override fun toString(): String = value

    companion object {
        val Root: ConfigPath = ConfigPath("/")
    }
}

/**
 * Normalizes a raw string into a [ConfigPath].
 */
fun configPath(value: String): ConfigPath {
    val normalized = value.trim().replace(Regex("/+"), "/").removeSuffix("/")
    return ConfigPath(normalized.ifBlank { "/" })
}

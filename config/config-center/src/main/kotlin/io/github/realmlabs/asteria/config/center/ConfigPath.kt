package io.github.realmlabs.asteria.config.center

@JvmInline
value class ConfigPath(val value: String) {
    init {
        require(value.isNotBlank()) { "config path must not be blank" }
        require(value.startsWith("/")) { "config path must start with /" }
        require(!value.contains("//")) { "config path must not contain empty segments: $value" }
        require(value == "/" || !value.endsWith("/")) { "config path must not end with /: $value" }
    }

    val name: String
        get() = if (value == "/") "" else value.substringAfterLast("/")

    val parent: ConfigPath?
        get() {
            if (value == "/") {
                return null
            }
            val parent = value.substringBeforeLast("/", missingDelimiterValue = "/")
            return ConfigPath(if (parent.isBlank()) "/" else parent)
        }

    operator fun div(child: String): ConfigPath {
        require(child.isNotBlank()) { "config path child must not be blank" }
        require(!child.startsWith("/") && !child.endsWith("/") && !child.contains("//")) {
            "config path child must be a relative single or nested path: $child"
        }
        return ConfigPath(if (value == "/") "/$child" else "$value/$child")
    }

    fun isChildOf(parent: ConfigPath): Boolean {
        return this.parent == parent
    }

    fun isDescendantOf(parent: ConfigPath): Boolean {
        return value != parent.value &&
                (parent.value == "/" || value.startsWith("${parent.value}/"))
    }

    override fun toString(): String = value

    companion object {
        val Root: ConfigPath = ConfigPath("/")
    }
}

fun configPath(value: String): ConfigPath {
    val normalized = value.trim().replace(Regex("/+"), "/").removeSuffix("/")
    return ConfigPath(normalized.ifBlank { "/" })
}

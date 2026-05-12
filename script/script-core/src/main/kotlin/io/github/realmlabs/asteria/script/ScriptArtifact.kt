package io.github.realmlabs.asteria.script

/**
 * Script payload handed to a [ScriptEngine].
 *
 * [engine] must match a registered engine name. [body] is the primary source or binary artifact; [extra] is reserved
 * for engine-specific side data such as compiled metadata. [checksum] is carried for policy and audit use and is not
 * verified by this value object.
 */
data class ScriptArtifact(
    val name: String,
    val engine: String,
    val body: ByteArray,
    val extra: ByteArray? = null,
    val checksum: String? = null,
) {
    init {
        require(name.isNotBlank()) { "script name must not be blank" }
        require(engine.isNotBlank()) { "script engine must not be blank" }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as ScriptArtifact

        if (name != other.name) return false
        if (engine != other.engine) return false
        if (!body.contentEquals(other.body)) return false
        if (extra != null) {
            if (other.extra == null) return false
            if (!extra.contentEquals(other.extra)) return false
        } else if (other.extra != null) {
            return false
        }
        if (checksum != other.checksum) return false

        return true
    }

    override fun hashCode(): Int {
        var result = name.hashCode()
        result = 31 * result + engine.hashCode()
        result = 31 * result + body.contentHashCode()
        result = 31 * result + (extra?.contentHashCode() ?: 0)
        result = 31 * result + (checksum?.hashCode() ?: 0)
        return result
    }

    override fun toString(): String {
        return "ScriptArtifact(name='$name', engine='$engine', checksum=$checksum)"
    }
}

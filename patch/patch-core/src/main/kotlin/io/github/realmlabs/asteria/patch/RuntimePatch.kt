package io.github.realmlabs.asteria.patch

import io.github.realmlabs.asteria.core.RoleKey
import java.io.Serializable

@JvmInline
value class PatchId(val value: String) : Serializable {
    init {
        require(value.isNotBlank()) { "patch id must not be blank" }
    }

    override fun toString(): String = value
}

data class RuntimePatch(
    val id: PatchId,
    val name: String,
    val artifact: PatchArtifact,
    val compatibility: PatchCompatibility,
    val target: PatchTarget = PatchTarget.AllNodes,
    val priority: Int = 0,
    val sequence: Long,
    val status: PatchStatus = PatchStatus.Enabled,
) : Serializable {
    init {
        require(name.isNotBlank()) { "patch name must not be blank" }
        require(sequence > 0) { "patch sequence must be positive" }
    }

    val order: PatchOrder = PatchOrder(priority, sequence, id)

    fun canApplyTo(environment: PatchEnvironment): Boolean {
        return status == PatchStatus.Enabled &&
                compatibility.matches(environment) &&
                target.matches(environment)
    }
}

data class PatchArtifact(
    val name: String,
    val checksum: String,
    val version: String? = null,
) : Serializable {
    init {
        require(name.isNotBlank()) { "patch artifact name must not be blank" }
        require(checksum.isNotBlank()) { "patch artifact checksum must not be blank" }
        version?.let { require(it.isNotBlank()) { "patch artifact version must not be blank" } }
    }
}

data class PatchCompatibility(
    val appName: String,
    val versions: Set<String>,
) : Serializable {
    init {
        require(appName.isNotBlank()) { "patch compatibility app name must not be blank" }
        require(versions.isNotEmpty()) { "patch compatibility versions must not be empty" }
        versions.forEach { require(it.isNotBlank()) { "patch compatibility version must not be blank" } }
    }

    fun matches(environment: PatchEnvironment): Boolean {
        return appName == environment.appName && environment.version in versions
    }
}

data class PatchEnvironment(
    val appName: String,
    val version: String,
    val nodeAddress: String? = null,
    val roles: Set<RoleKey> = emptySet(),
) : Serializable {
    init {
        require(appName.isNotBlank()) { "patch environment app name must not be blank" }
        require(version.isNotBlank()) { "patch environment version must not be blank" }
        nodeAddress?.let { require(it.isNotBlank()) { "patch environment node address must not be blank" } }
    }
}

sealed interface PatchTarget : Serializable {
    fun matches(environment: PatchEnvironment): Boolean

    data object AllNodes : PatchTarget {
        override fun matches(environment: PatchEnvironment): Boolean = true
    }

    data class Roles(val roles: Set<RoleKey>) : PatchTarget {
        init {
            require(roles.isNotEmpty()) { "patch target roles must not be empty" }
        }

        override fun matches(environment: PatchEnvironment): Boolean {
            return roles.any { it in environment.roles }
        }
    }

    data class Nodes(val addresses: Set<String>) : PatchTarget {
        init {
            require(addresses.isNotEmpty()) { "patch target node addresses must not be empty" }
            addresses.forEach { require(it.isNotBlank()) { "patch target node address must not be blank" } }
        }

        override fun matches(environment: PatchEnvironment): Boolean {
            return environment.nodeAddress in addresses
        }
    }
}

enum class PatchStatus {
    Draft,
    Enabled,
    Disabled,
    Expired,
    Failed,
}

data class PatchOrder(
    val priority: Int,
    val sequence: Long,
    val id: PatchId,
) : Comparable<PatchOrder>, Serializable {
    init {
        require(sequence > 0) { "patch order sequence must be positive" }
    }

    override fun compareTo(other: PatchOrder): Int {
        return compareBy<PatchOrder> { it.priority }
            .thenBy { it.sequence }
            .thenBy { it.id.value }
            .compare(this, other)
    }
}

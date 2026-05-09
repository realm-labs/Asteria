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

/**
 * Persisted patch metadata used by GM, repositories, and cluster selection.
 *
 * A descriptor decides where a patch may run and how its artifact is resolved. The node-local execution layer receives
 * only [RuntimePatch], which is derived from a descriptor after status, compatibility, and target checks pass.
 */
data class RuntimePatchDescriptor(
    val id: PatchId,
    val artifact: PatchArtifact,
    val compatibility: PatchCompatibility,
    val name: String = id.value,
    val target: PatchTarget = PatchTarget.AllNodes,
    val status: PatchStatus = PatchStatus.Enabled,
    val revision: Long = 0,
) : Serializable {
    init {
        require(name.isNotBlank()) { "patch name must not be blank" }
        require(revision >= 0) { "patch revision must not be negative" }
    }

    fun canApplyTo(environment: PatchEnvironment): Boolean {
        return status == PatchStatus.Enabled &&
                compatibility.matches(environment) &&
                target.matches(environment)
    }

    fun execution(): RuntimePatch {
        require(revision > 0) { "patch revision must be assigned before execution" }
        return RuntimePatch(id, revision)
    }
}

/**
 * Node-local patch execution identity.
 *
 * Compatibility, targeting, artifact resolution, and lifecycle status are handled before a patch reaches
 * [PatchRuntime]. [revision] is the repository-assigned version used to make "newer patch wins" deterministic when
 * several patches replace the same registry slot.
 */
data class RuntimePatch(
    val id: PatchId,
    val revision: Long,
) : Serializable {
    init {
        require(revision > 0) { "patch revision must be positive" }
    }

    val order: PatchOrder = PatchOrder(revision, id)
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

/**
 * Node selection rule for a patch.
 *
 * [PatchTarget.Roles] matches when any configured role is present. [PatchTarget.Nodes] compares exact
 * [PatchEnvironment.nodeAddress] strings.
 */
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

/**
 * Patch lifecycle state.
 *
 * Only [Enabled] patches are applied. [Draft] and [Disabled] are operator-controlled inactive states. [Expired] means a
 * previously enabled patch no longer matches the running app/version. [Failed] records an operator or node-level
 * failure state and is not retried automatically.
 */
enum class PatchStatus {
    Draft,
    Enabled,
    Disabled,
    Expired,
    Failed,
}

/**
 * Stable layer key for patch application.
 *
 * Repositories assign monotonically increasing [revision] values. Registries replay layers by revision, so a newer
 * patch naturally overrides older patches that target the same slot. Removing the newer patch restores the previous
 * layer.
 */
data class PatchOrder(
    val revision: Long,
    val id: PatchId,
) : Comparable<PatchOrder>, Serializable {
    init {
        require(revision > 0) { "patch order revision must be positive" }
    }

    override fun compareTo(other: PatchOrder): Int {
        return compareBy<PatchOrder> { it.revision }
            .thenBy { it.id.value }
            .compare(this, other)
    }
}

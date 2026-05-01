package io.github.mikai233.asteria.gm.script

import io.github.mikai233.asteria.core.AsteriaApplication
import io.github.mikai233.asteria.core.EntityKind
import io.github.mikai233.asteria.core.RoleKey
import io.github.mikai233.asteria.core.SingletonName
import io.github.mikai233.asteria.script.ScriptExecutionCommand
import io.github.mikai233.asteria.script.ScriptTarget

/**
 * Input for validating a GM script target before the command is submitted.
 */
data class GmScriptTargetValidationRequest(
    val command: ScriptExecutionCommand,
    val operatorId: String? = null,
) {
    init {
        operatorId?.let { require(it.isNotBlank()) { "GM script validation operator id must not be blank" } }
    }
}

/**
 * Result of GM script target validation.
 */
sealed interface GmScriptTargetValidationResult {
    data object Allowed : GmScriptTargetValidationResult

    data class Rejected(val reasons: List<String>) : GmScriptTargetValidationResult {
        init {
            require(reasons.isNotEmpty()) { "GM script validation rejection reasons must not be empty" }
            require(reasons.all { it.isNotBlank() }) { "GM script validation rejection reason must not be blank" }
        }
    }
}

/**
 * Validates script targets before execution reaches the runtime.
 *
 * Applications should add validators for business-specific checks such as player existence, zone ownership, account
 * state, or approval requirements. Validators should prefer cached/indexed reads over direct database scans.
 */
fun interface GmScriptTargetValidator {
    suspend fun validate(request: GmScriptTargetValidationRequest): GmScriptTargetValidationResult
}

/**
 * Runs multiple validators and combines all rejection reasons.
 */
class CompositeGmScriptTargetValidator(
    private val validators: List<GmScriptTargetValidator>,
) : GmScriptTargetValidator {
    override suspend fun validate(request: GmScriptTargetValidationRequest): GmScriptTargetValidationResult {
        val reasons = validators.flatMap { validator ->
            when (val result = validator.validate(request)) {
                GmScriptTargetValidationResult.Allowed -> emptyList()
                is GmScriptTargetValidationResult.Rejected -> result.reasons
            }
        }
        return if (reasons.isEmpty()) {
            GmScriptTargetValidationResult.Allowed
        } else {
            GmScriptTargetValidationResult.Rejected(reasons)
        }
    }
}

/**
 * Cached catalog used by validators to answer existence checks.
 *
 * Returning `null` means the catalog does not know that dimension, so the validator should skip that check. Business
 * modules can implement this against Redis, an in-memory index, or another fast lookup source.
 */
interface GmScriptTargetCatalog {
    suspend fun roleExists(role: RoleKey): Boolean? = null

    suspend fun entityKindExists(kind: EntityKind): Boolean? = null

    suspend fun entityIdExists(kind: EntityKind, id: String): Boolean? = null

    suspend fun singletonExists(name: SingletonName): Boolean? = null

    suspend fun nodeAddressExists(address: String): Boolean? = null
}

/**
 * Catalog backed by Asteria application specs.
 */
class ApplicationSpecGmScriptTargetCatalog(
    private val application: AsteriaApplication,
) : GmScriptTargetCatalog {
    override suspend fun roleExists(role: RoleKey): Boolean {
        return role in application.declaredRoles
    }

    override suspend fun entityKindExists(kind: EntityKind): Boolean {
        return application.entities.any { it.kind == kind }
    }

    override suspend fun singletonExists(name: SingletonName): Boolean {
        return application.singletons.any { it.name == name }
    }
}

/**
 * Basic target validator that rejects malformed or risky targets before runtime dispatch.
 */
class BasicGmScriptTargetValidator(
    private val catalog: GmScriptTargetCatalog? = null,
) : GmScriptTargetValidator {
    override suspend fun validate(request: GmScriptTargetValidationRequest): GmScriptTargetValidationResult {
        val reasons = mutableListOf<String>()
        val target = request.command.target
        reasons += target.validateShape()
        if (catalog != null) {
            reasons += target.validateCatalog(catalog)
        }
        return if (reasons.isEmpty()) {
            GmScriptTargetValidationResult.Allowed
        } else {
            GmScriptTargetValidationResult.Rejected(reasons)
        }
    }

    private fun ScriptTarget.validateShape(): List<String> {
        return when (this) {
            ScriptTarget.AllNodes,
            is ScriptTarget.Role,
            is ScriptTarget.Singleton,
            -> emptyList()

            is ScriptTarget.ActorPath -> paths.duplicateReasons("actor paths")
            is ScriptTarget.Entity -> ids.duplicateReasons("entity ids")
            is ScriptTarget.Node -> addresses.duplicateReasons("node addresses")
        }
    }

    private suspend fun ScriptTarget.validateCatalog(catalog: GmScriptTargetCatalog): List<String> {
        val reasons = mutableListOf<String>()
        when (this) {
            ScriptTarget.AllNodes,
            -> Unit

            is ScriptTarget.ActorPath -> Unit
            is ScriptTarget.Entity -> {
                if (catalog.entityKindExists(kind) == false) {
                    reasons += "entity kind ${kind.value} does not exist"
                }
                ids.forEach { id ->
                    if (catalog.entityIdExists(kind, id) == false) {
                        reasons += "entity ${kind.value}:$id does not exist"
                    }
                }
            }

            is ScriptTarget.Node -> addresses.forEach { address ->
                if (catalog.nodeAddressExists(address) == false) {
                    reasons += "node address $address does not exist"
                }
            }

            is ScriptTarget.Role -> {
                if (catalog.roleExists(role) == false) {
                    reasons += "role ${role.value} does not exist"
                }
            }

            is ScriptTarget.Singleton -> {
                if (catalog.singletonExists(name) == false) {
                    reasons += "singleton ${name.value} does not exist"
                }
            }
        }
        return reasons
    }

    private fun List<String>.duplicateReasons(name: String): List<String> {
        val duplicates = groupingBy { it }.eachCount().filterValues { it > 1 }.keys
        return if (duplicates.isEmpty()) {
            emptyList()
        } else {
            listOf("duplicate $name: ${duplicates.joinToString(",")}")
        }
    }
}

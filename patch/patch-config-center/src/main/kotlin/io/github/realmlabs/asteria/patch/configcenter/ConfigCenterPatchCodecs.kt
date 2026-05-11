package io.github.realmlabs.asteria.patch.configcenter

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.annotation.JsonInclude.Value
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.github.realmlabs.asteria.core.RoleKey
import io.github.realmlabs.asteria.patch.*
import java.time.Instant

/**
 * Encodes config-center patch payloads.
 *
 * The wire format uses explicit DTOs so sealed values remain stable and readable regardless of the selected
 * config-center backend.
 */
interface ConfigCenterPatchCodec {
    fun encodePatch(patch: RuntimePatchDescriptor): ByteArray

    fun decodePatch(bytes: ByteArray): RuntimePatchDescriptor

    fun encodePatchIndex(index: ConfigCenterPatchIndex): ByteArray

    fun decodePatchIndex(bytes: ByteArray): ConfigCenterPatchIndex

    fun encodeArtifact(artifact: PatchArtifact): ByteArray

    fun decodeArtifact(bytes: ByteArray): PatchArtifact

    fun encodeNodeResult(result: RuntimePatchNodeResult): ByteArray

    fun decodeNodeResult(bytes: ByteArray): RuntimePatchNodeResult
}

class JacksonConfigCenterPatchCodec(
    private val mapper: ObjectMapper = defaultObjectMapper(),
) : ConfigCenterPatchCodec {
    override fun encodePatch(patch: RuntimePatchDescriptor): ByteArray {
        return mapper.writeValueAsBytes(patch.toDto())
    }

    override fun decodePatch(bytes: ByteArray): RuntimePatchDescriptor {
        return mapper.readValue(bytes, RuntimePatchDto::class.java).toDomain()
    }

    override fun encodePatchIndex(index: ConfigCenterPatchIndex): ByteArray {
        return mapper.writeValueAsBytes(index)
    }

    override fun decodePatchIndex(bytes: ByteArray): ConfigCenterPatchIndex {
        return mapper.readValue(bytes, ConfigCenterPatchIndex::class.java)
    }

    override fun encodeArtifact(artifact: PatchArtifact): ByteArray {
        return mapper.writeValueAsBytes(artifact.toDto())
    }

    override fun decodeArtifact(bytes: ByteArray): PatchArtifact {
        return mapper.readValue(bytes, PatchArtifactDto::class.java).toDomain()
    }

    override fun encodeNodeResult(result: RuntimePatchNodeResult): ByteArray {
        return mapper.writeValueAsBytes(result.toDto())
    }

    override fun decodeNodeResult(bytes: ByteArray): RuntimePatchNodeResult {
        return mapper.readValue(bytes, RuntimePatchNodeResultDto::class.java).toDomain()
    }

    companion object {
        fun defaultObjectMapper(): ObjectMapper {
            return jacksonObjectMapper()
                .registerModule(JavaTimeModule())
                .setDefaultPropertyInclusion(
                    Value.construct(
                        JsonInclude.Include.NON_NULL,
                        JsonInclude.Include.NON_NULL,
                    ),
                )
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
        }
    }
}

/**
 * Index entry mapping one patch id to the app/version metadata entries containing the descriptor.
 */
data class ConfigCenterPatchIndex(
    val appName: String,
    val versions: Set<String>,
)

private data class RuntimePatchDto(
    val id: String,
    val name: String,
    val artifact: PatchArtifactDto,
    val compatibility: PatchCompatibilityDto,
    val requirements: PatchRequirementsDto = PatchRequirementsDto(),
    val target: PatchTargetDto,
    val status: String,
    val revision: Long,
)

private data class PatchArtifactDto(
    val name: String,
    val checksum: String,
    val version: String? = null,
)

private data class PatchCompatibilityDto(
    val appName: String,
    val versions: Set<String>,
)

private data class PatchRequirementsDto(
    val roles: Set<String> = emptySet(),
    val modules: Set<String> = emptySet(),
    val capabilities: Set<String> = emptySet(),
)

private data class PatchTargetDto(
    val type: String,
    val roles: Set<String> = emptySet(),
    val addresses: Set<String> = emptySet(),
)

private data class RuntimePatchNodeResultDto(
    val patchId: String,
    val nodeId: String? = null,
    val address: String,
    val appName: String,
    val version: String,
    val roles: Set<String> = emptySet(),
    val modules: Set<String> = emptySet(),
    val capabilities: Set<String> = emptySet(),
    val status: String,
    val attempt: Int,
    val operationCount: Int? = null,
    val message: String? = null,
    val updatedAt: Long,
)

private fun RuntimePatchDescriptor.toDto(): RuntimePatchDto {
    return RuntimePatchDto(
        id = id.value,
        name = name,
        artifact = artifact.toDto(),
        compatibility = PatchCompatibilityDto(compatibility.appName, compatibility.versions),
        requirements = requirements.toDto(),
        target = target.toDto(),
        status = status.name,
        revision = revision,
    )
}

private fun RuntimePatchDto.toDomain(): RuntimePatchDescriptor {
    return RuntimePatchDescriptor(
        id = PatchId(id),
        artifact = artifact.toDomain(),
        compatibility = PatchCompatibility(compatibility.appName, compatibility.versions),
        requirements = requirements.toDomain(),
        name = name,
        target = target.toDomain(),
        status = PatchStatus.valueOf(status),
        revision = revision,
    )
}

private fun PatchArtifact.toDto(): PatchArtifactDto {
    return PatchArtifactDto(name, checksum, version)
}

private fun PatchArtifactDto.toDomain(): PatchArtifact {
    return PatchArtifact(name, checksum, version)
}

private fun PatchRequirements.toDto(): PatchRequirementsDto {
    return PatchRequirementsDto(
        roles = roles.mapTo(linkedSetOf()) { it.value },
        modules = modules,
        capabilities = capabilities,
    )
}

private fun PatchRequirementsDto.toDomain(): PatchRequirements {
    return PatchRequirements(
        roles = roles.mapTo(linkedSetOf(), ::RoleKey),
        modules = modules,
        capabilities = capabilities,
    )
}

private fun PatchTarget.toDto(): PatchTargetDto {
    return when (this) {
        PatchTarget.AllNodes -> PatchTargetDto(type = "all-nodes")
        is PatchTarget.Roles -> PatchTargetDto(type = "roles", roles = roles.mapTo(linkedSetOf()) { it.value })
        is PatchTarget.Nodes -> PatchTargetDto(type = "nodes", addresses = addresses)
    }
}

private fun PatchTargetDto.toDomain(): PatchTarget {
    return when (type) {
        "all-nodes" -> PatchTarget.AllNodes
        "roles" -> PatchTarget.Roles(roles.mapTo(linkedSetOf(), ::RoleKey))
        "nodes" -> PatchTarget.Nodes(addresses)
        else -> error("unknown config-center patch target type $type")
    }
}

private fun RuntimePatchNodeResult.toDto(): RuntimePatchNodeResultDto {
    return RuntimePatchNodeResultDto(
        patchId = patchId.value,
        nodeId = nodeId,
        address = address,
        appName = appName,
        version = version,
        roles = roles.mapTo(linkedSetOf()) { it.value },
        modules = modules,
        capabilities = capabilities,
        status = status.name,
        attempt = attempt,
        operationCount = operationCount,
        message = message,
        updatedAt = updatedAt.toEpochMilli(),
    )
}

private fun RuntimePatchNodeResultDto.toDomain(): RuntimePatchNodeResult {
    return RuntimePatchNodeResult(
        patchId = PatchId(patchId),
        nodeId = nodeId,
        address = address,
        appName = appName,
        version = version,
        roles = roles.mapTo(linkedSetOf(), ::RoleKey),
        modules = modules,
        capabilities = capabilities,
        status = RuntimePatchNodeStatus.valueOf(status),
        attempt = attempt,
        operationCount = operationCount,
        message = message,
        updatedAt = Instant.ofEpochMilli(updatedAt),
    )
}

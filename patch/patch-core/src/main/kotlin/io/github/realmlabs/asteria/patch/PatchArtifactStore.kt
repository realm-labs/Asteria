package io.github.realmlabs.asteria.patch

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import kotlin.io.path.name

/**
 * Content-addressed store for patch artifacts.
 *
 * Checksums use the `sha256:<hex>` format. [load] must verify the bytes before returning them so corrupted or
 * misaddressed artifacts fail before a plugin resolver tries to execute them.
 */
fun interface PatchArtifactStore {
    suspend fun load(artifact: PatchArtifact): ByteArray
}

/**
 * Artifact store that can persist new bytes and return their normalized [PatchArtifact] descriptor.
 */
interface WritablePatchArtifactStore : PatchArtifactStore {
    suspend fun save(
        name: String,
        bytes: ByteArray,
        version: String? = null,
    ): PatchArtifact
}

class InMemoryPatchArtifactStore(
    artifacts: Map<String, ByteArray> = emptyMap(),
) : WritablePatchArtifactStore {
    private val artifactsByChecksum: MutableMap<String, ByteArray> = artifacts.toMutableMap()

    fun put(artifact: PatchArtifact, bytes: ByteArray) {
        verifyPatchArtifactChecksum(artifact, bytes)
        artifactsByChecksum[artifact.checksum] = bytes.copyOf()
    }

    override suspend fun save(
        name: String,
        bytes: ByteArray,
        version: String?,
    ): PatchArtifact {
        val artifact = PatchArtifact(name = name, checksum = patchArtifactSha256Checksum(bytes), version = version)
        put(artifact, bytes)
        return artifact
    }

    override suspend fun load(artifact: PatchArtifact): ByteArray {
        val bytes = requireNotNull(artifactsByChecksum[artifact.checksum]) {
            "patch artifact ${artifact.name} with checksum ${artifact.checksum} not found"
        }
        verifyPatchArtifactChecksum(artifact, bytes)
        return bytes.copyOf()
    }
}

/**
 * Local filesystem artifact store keyed by checksum.
 *
 * Saved files are named from the normalized checksum plus the safe extension from the artifact name. Original paths are
 * not preserved.
 */
class LocalFilePatchArtifactStore(
    private val directory: Path,
) : WritablePatchArtifactStore {
    init {
        require(directory.toString().isNotBlank()) { "patch artifact directory must not be blank" }
    }

    override suspend fun save(
        name: String,
        bytes: ByteArray,
        version: String?,
    ): PatchArtifact {
        val artifact =
            PatchArtifact(name = name.safeFileName(), checksum = patchArtifactSha256Checksum(bytes), version = version)
        withContext(Dispatchers.IO) {
            Files.createDirectories(directory)
            Files.write(directory.resolve(artifact.storageFileName()), bytes)
        }
        return artifact
    }

    override suspend fun load(artifact: PatchArtifact): ByteArray {
        val bytes = withContext(Dispatchers.IO) {
            Files.readAllBytes(directory.resolve(artifact.storageFileName()))
        }
        verifyPatchArtifactChecksum(artifact, bytes)
        return bytes
    }

    private fun PatchArtifact.storageFileName(): String {
        val checksum = checksum.normalizedSha256()
        val extension = name.safeFileName().substringAfterLast('.', missingDelimiterValue = "")
            .takeIf { it.isNotBlank() }
            ?.let { ".$it" }
            ?: ""
        return "$checksum$extension"
    }
}

fun patchArtifactSha256Checksum(bytes: ByteArray): String {
    return "sha256:${bytes.sha256Hex()}"
}

fun verifyPatchArtifactChecksum(artifact: PatchArtifact, bytes: ByteArray) {
    val expected = artifact.checksum.normalizedSha256()
    val actual = bytes.sha256Hex()
    require(actual == expected) {
        "patch artifact ${artifact.name} checksum mismatch: expected ${artifact.checksum}, got sha256:$actual"
    }
}

private fun String.normalizedSha256(): String {
    val normalized = removePrefix("sha256:").lowercase()
    require(normalized.length == SHA_256_HEX_LENGTH && normalized.all { it in '0'..'9' || it in 'a'..'f' }) {
        "patch artifact checksum must be a sha256 hex value"
    }
    return normalized
}

private fun ByteArray.sha256Hex(): String {
    return MessageDigest.getInstance("SHA-256")
        .digest(this)
        .joinToString("") { "%02x".format(it) }
}

private fun String.safeFileName(): String {
    val raw = substringAfterLast('/').substringAfterLast('\\')
    require(raw.isNotBlank()) { "patch artifact file name must not be blank" }
    require(raw == Path.of(raw).name) { "patch artifact file name must not contain path separators" }
    return raw
}

private const val SHA_256_HEX_LENGTH: Int = 64

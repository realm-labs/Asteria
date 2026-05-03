package io.github.realmlabs.asteria.patch.mongodb

import com.mongodb.client.MongoDatabase
import com.mongodb.client.gridfs.GridFSBucket
import com.mongodb.client.gridfs.GridFSBuckets
import com.mongodb.client.gridfs.model.GridFSUploadOptions
import com.mongodb.client.model.Filters.eq
import io.github.realmlabs.asteria.observability.MetricTags
import io.github.realmlabs.asteria.observability.Metrics
import io.github.realmlabs.asteria.observability.NoopMetrics
import io.github.realmlabs.asteria.patch.PatchArtifact
import io.github.realmlabs.asteria.patch.WritablePatchArtifactStore
import io.github.realmlabs.asteria.patch.patchArtifactSha256Checksum
import io.github.realmlabs.asteria.patch.verifyPatchArtifactChecksum
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.bson.Document
import org.slf4j.LoggerFactory
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

class MongoGridFsPatchArtifactStore(
    database: MongoDatabase,
    bucketName: String = "runtime_patch_artifacts",
    private val metrics: Metrics = NoopMetrics,
) : WritablePatchArtifactStore {
    private val logger = LoggerFactory.getLogger(MongoGridFsPatchArtifactStore::class.java)
    private val bucket: GridFSBucket = GridFSBuckets.create(database, bucketName)

    override suspend fun save(
        name: String,
        bytes: ByteArray,
        version: String?,
    ): PatchArtifact {
        return measured("save", bytes.size.toLong()) {
            val artifact = PatchArtifact(
                name = name.safeArtifactName(),
                checksum = patchArtifactSha256Checksum(bytes),
                version = version,
            )
            val fileName = artifact.storageFileName()
            withContext(Dispatchers.IO) {
                val existing = bucket.find(eq("filename", fileName)).first()
                if (existing == null) {
                    val options = GridFSUploadOptions().metadata(
                        Document("artifactName", artifact.name)
                            .append("checksum", artifact.checksum)
                            .append("version", artifact.version),
                    )
                    bucket.uploadFromStream(fileName, ByteArrayInputStream(bytes), options)
                }
            }
            artifact
        }
    }

    override suspend fun load(artifact: PatchArtifact): ByteArray {
        return measured("load") {
            val bytes = withContext(Dispatchers.IO) {
                val output = ByteArrayOutputStream()
                bucket.downloadToStream(artifact.storageFileName(), output)
                output.toByteArray()
            }
            verifyPatchArtifactChecksum(artifact, bytes)
            metrics.counter("asteria.patch.mongodb.artifact.bytes.total", MetricTags.of("operation" to "load"))
                .increment(bytes.size.toLong())
            bytes
        }
    }

    private suspend fun <T> measured(operation: String, bytes: Long? = null, block: suspend () -> T): T {
        val tags = MetricTags.of("operation" to operation)
        val startedAt = System.nanoTime()
        metrics.counter("asteria.patch.mongodb.artifact.operation.total", tags).increment()
        bytes?.let { metrics.counter("asteria.patch.mongodb.artifact.bytes.total", tags).increment(it) }
        try {
            return block()
        } catch (error: Throwable) {
            metrics.counter("asteria.patch.mongodb.artifact.operation.failed.total", tags).increment()
            logger.error("Mongo GridFS patch artifact operation failed operation={}", operation, error)
            throw error
        } finally {
            metrics.timer("asteria.patch.mongodb.artifact.operation.duration", tags)
                .record((System.nanoTime() - startedAt) / 1_000_000)
        }
    }

    private fun PatchArtifact.storageFileName(): String {
        val checksum = checksum.removePrefix("sha256:").lowercase()
        val extension = name.safeArtifactName().substringAfterLast('.', missingDelimiterValue = "")
            .takeIf { it.isNotBlank() }
            ?.let { ".$it" }
            ?: ""
        return "$checksum$extension"
    }

    private fun String.safeArtifactName(): String {
        val raw = substringAfterLast('/').substringAfterLast('\\')
        require(raw.isNotBlank()) { "patch artifact file name must not be blank" }
        require('/' !in raw && '\\' !in raw) { "patch artifact file name must not contain path separators" }
        return raw
    }
}

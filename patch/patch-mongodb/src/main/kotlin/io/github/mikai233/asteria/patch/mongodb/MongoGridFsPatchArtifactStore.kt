package io.github.mikai233.asteria.patch.mongodb

import com.mongodb.client.MongoDatabase
import com.mongodb.client.gridfs.GridFSBucket
import com.mongodb.client.gridfs.GridFSBuckets
import com.mongodb.client.gridfs.model.GridFSUploadOptions
import com.mongodb.client.model.Filters.eq
import io.github.mikai233.asteria.patch.PatchArtifact
import io.github.mikai233.asteria.patch.WritablePatchArtifactStore
import io.github.mikai233.asteria.patch.patchArtifactSha256Checksum
import io.github.mikai233.asteria.patch.verifyPatchArtifactChecksum
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.bson.Document

class MongoGridFsPatchArtifactStore(
    database: MongoDatabase,
    bucketName: String = "runtime_patch_artifacts",
) : WritablePatchArtifactStore {
    private val bucket: GridFSBucket = GridFSBuckets.create(database, bucketName)

    override suspend fun save(
        name: String,
        bytes: ByteArray,
        version: String?,
    ): PatchArtifact {
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
        return artifact
    }

    override suspend fun load(artifact: PatchArtifact): ByteArray {
        val bytes = withContext(Dispatchers.IO) {
            val output = ByteArrayOutputStream()
            bucket.downloadToStream(artifact.storageFileName(), output)
            output.toByteArray()
        }
        verifyPatchArtifactChecksum(artifact, bytes)
        return bytes
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

package io.github.realmlabs.asteria.config.publisher

import io.github.realmlabs.asteria.config.ConfigRevision
import io.github.realmlabs.asteria.config.center.*
import java.time.Clock
import java.time.Instant

/**
 * Operational API for published config revisions.
 *
 * This is intentionally separated from [ConfigPublisher]. Publisher validates and writes new immutable revisions.
 * Operations read publication history, move the current pointer to an existing revision, and prune old immutable data.
 */
class ConfigPublicationOperations(
    private val store: ConfigStore,
    private val layout: ConfigPublicationLayout = ConfigPublicationLayout(),
    private val codec: ConfigCodec = JacksonConfigCodec(),
    private val clock: Clock = Clock.systemUTC(),
) {
    /**
     * Reads the current pointer, or `null` when no publication has been promoted yet.
     */
    suspend fun current(): CurrentConfigPublication? {
        return repository().get<CurrentConfigPublication>(layout.currentPath)?.value
    }

    /**
     * Lists known publication history records, newest first.
     *
     * The list is based on records under [ConfigPublicationLayout.historyPath]; orphaned revision directories without a
     * history record are intentionally not discovered by this operation.
     */
    suspend fun history(): List<ConfigPublicationRecord> {
        return repository()
            .children<ConfigPublicationRecord>(layout.historyPath)
            .values
            .values
            .map { it.value }
            .sortedWith(compareByDescending<ConfigPublicationRecord> { it.publishedAt }.thenByDescending { it.revision.version })
    }

    /**
     * Loads the immutable manifest for [revision].
     *
     * Missing manifests are reported as [ConfigPublicationNotFoundException] so callers can distinguish absent
     * publication data from validation errors while loading a complete bundle.
     */
    suspend fun manifest(revision: ConfigRevision): ConfigPublicationManifest {
        return repository().get<ConfigPublicationManifest>(layout.manifestPath(revision))?.value
            ?: throw ConfigPublicationNotFoundException(layout.manifestPath(revision))
    }

    /**
     * Moves the current pointer to a previously published revision after validating its manifest and artifacts.
     *
     * This does not rewrite artifacts or the manifest. It writes a new current pointer and creates a missing history
     * record when necessary. Concurrent publishes/promotions are last-writer-wins because the current pointer is not
     * updated with a caller-supplied CAS token.
     */
    suspend fun promote(revision: ConfigRevision): CurrentConfigPublication {
        val bundle = consumer().loadRevision(revision)
        val current = CurrentConfigPublication(
            revision = revision,
            manifestPath = layout.manifestPath(revision).value,
            publishedAt = Instant.now(clock),
        )
        repository().put(layout.currentPath, current)
        ensureHistoryRecord(bundle.manifest, current.publishedAt)
        return current
    }

    /**
     * Deletes old immutable revision data and history records.
     *
     * The current revision is always retained unless [keepCurrent] is false. Artifact paths are read from each
     * manifest, so nested artifact directories do not require tree-list support from the backing config center.
     * Revisions that have no history record are not discovered by this method.
     */
    suspend fun prune(
        retainLatest: Int,
        keepCurrent: Boolean = true,
    ): ConfigPublicationPruneResult {
        require(retainLatest >= 0) { "retainLatest must not be negative" }
        val currentRevision = current()?.revision
        val records = history()
        val retained = records
            .take(retainLatest)
            .mapTo(mutableSetOf()) { it.revision }
        if (keepCurrent && currentRevision != null) {
            retained += currentRevision
        }

        val deleted = mutableListOf<ConfigRevision>()
        for (record in records) {
            if (record.revision in retained) {
                continue
            }
            deleteRevision(record.revision)
            deleted += record.revision
        }
        return ConfigPublicationPruneResult(
            deletedRevisions = deleted,
            retainedRevisions = retained.toList(),
        )
    }

    private suspend fun ensureHistoryRecord(
        manifest: ConfigPublicationManifest,
        publishedAt: Instant,
    ) {
        val path = layout.historyRecordPath(manifest.revision)
        if (repository().get<ConfigPublicationRecord>(path) != null) {
            return
        }
        repository().put(
            path,
            ConfigPublicationRecord(
                revision = manifest.revision,
                manifestPath = layout.manifestPath(manifest.revision).value,
                publishedAt = publishedAt,
                artifactCount = manifest.artifacts.size,
                totalArtifactBytes = manifest.artifacts.sumOf { it.size },
            ),
        )
    }

    private suspend fun deleteRevision(revision: ConfigRevision) {
        val manifestPath = layout.manifestPath(revision)
        val manifest = repository().get<ConfigPublicationManifest>(manifestPath)?.value
        if (manifest != null) {
            for (artifact in manifest.artifacts) {
                deleteIfPresent(layout.artifactPath(revision, artifact.path))
            }
        }
        deleteIfPresent(manifestPath)
        deleteIfPresent(layout.historyRecordPath(revision))
    }

    private suspend fun deleteIfPresent(path: ConfigPath) {
        if (store.get(path) != null) {
            store.delete(path)
        }
    }

    private fun repository(): RuntimeConfigRepository {
        return RuntimeConfigRepository(store, codec)
    }

    private fun consumer(): ConfigPublicationConsumer {
        return ConfigPublicationConsumer(store, layout, codec)
    }
}

/**
 * Result of pruning publication history and immutable revision data.
 *
 * [deletedRevisions] contains only revisions that had history records and were selected for deletion.
 * [retainedRevisions] is the retention set calculated from `retainLatest` plus the optional current revision.
 */
data class ConfigPublicationPruneResult(
    val deletedRevisions: List<ConfigRevision>,
    val retainedRevisions: List<ConfigRevision>,
)

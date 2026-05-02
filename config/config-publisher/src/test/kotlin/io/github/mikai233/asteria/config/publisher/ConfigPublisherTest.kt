package io.github.mikai233.asteria.config.publisher

import io.github.mikai233.asteria.config.ConfigRevision
import io.github.mikai233.asteria.config.ConfigValidationException
import io.github.mikai233.asteria.config.DefaultConfigSnapshot
import io.github.mikai233.asteria.config.configComponentBuilder
import io.github.mikai233.asteria.config.configValidator
import io.github.mikai233.asteria.config.center.InMemoryConfigStore
import io.github.mikai233.asteria.config.center.JacksonConfigCodec
import io.github.mikai233.asteria.config.center.RuntimeConfigRepository
import io.github.mikai233.asteria.config.center.configPath
import io.github.mikai233.asteria.config.mapConfigTable
import java.nio.file.Files
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.runBlocking
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull

class ConfigPublisherTest {
    @Test
    fun `publishes validated artifacts manifest and current pointer`() = runBlocking {
        val dir = Files.createTempDirectory("asteria-config-publisher-test")
        dir.resolve("items.bytes").writeText("items")
        dir.resolve("activity").createDirectories()
        dir.resolve("activity/tasks.bytes").writeText("tasks")

        val store = InMemoryConfigStore()
        val layout = ConfigPublicationLayout(configPath("/game/config"))
        val revision = ConfigRevision("2026.05.02", "checksum-1")
        val publisher = ConfigPublisher(
            loader = {
                DefaultConfigSnapshot(
                    revision = revision,
                    tables = listOf(mapConfigTable("items", mapOf(1 to ItemConfig(1)))),
                )
            },
            artifactSource = DirectoryConfigArtifactSource(dir),
            store = store,
            layout = layout,
            validators = listOf(
                configValidator { snapshot ->
                    check(snapshot.tables().isNotEmpty(), "snapshot must contain at least one table")
                },
            ),
            componentBuilders = listOf(
                configComponentBuilder("item-index") { snapshot ->
                    snapshot.tables().associate { it.name.value to it.size }
                },
            ),
            clock = Clock.fixed(Instant.parse("2026-05-02T00:00:00Z"), ZoneOffset.UTC),
        )

        val result = publisher.publish()
        val repository = RuntimeConfigRepository(store, JacksonConfigCodec())
        val manifest = repository.get<ConfigPublicationManifest>(layout.manifestPath(revision))?.value
        val current = repository.get<CurrentConfigPublication>(layout.currentPath)?.value

        assertEquals(revision, result.snapshot.revision)
        assertNotNull(manifest)
        assertEquals(revision, manifest.revision)
        assertEquals(listOf("items"), manifest.tables)
        assertEquals(listOf("activity/tasks.bytes", "items.bytes"), manifest.artifacts.map { it.path })
        assertEquals(listOf("item-index"), manifest.components.map { it.name })
        assertEquals(revision, current?.revision)
        assertEquals(layout.manifestPath(revision).value, current?.manifestPath)
        assertNotNull(store.get(layout.artifactPath(revision, "items.bytes")))
        assertNotNull(store.get(layout.artifactPath(revision, "activity/tasks.bytes")))
    }

    @Test
    fun `does not publish when validation fails`() = runBlocking {
        val store = InMemoryConfigStore()
        val layout = ConfigPublicationLayout(configPath("/game/config"))
        val publisher = ConfigPublisher(
            loader = {
                DefaultConfigSnapshot(
                    revision = ConfigRevision("bad"),
                    tables = emptyList(),
                )
            },
            artifactSource = ConfigArtifactSource { listOf(ConfigPublicationArtifact("items.bytes", byteArrayOf(1))) },
            store = store,
            layout = layout,
            validators = listOf(
                configValidator { snapshot ->
                    check(snapshot.tables().isNotEmpty(), "snapshot must contain at least one table")
                },
            ),
        )

        assertFailsWith<ConfigValidationException> {
            publisher.publish()
        }
        assertEquals(null, store.get(layout.currentPath))
    }
}

private data class ItemConfig(
    val id: Int,
)

package io.github.mikai233.asteria.script

import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import kotlin.io.path.createTempDirectory
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ScriptResourceTest {
    @Test
    fun localResolverVerifiesChecksum() = runBlocking {
        val file = Files.createTempFile("asteria-script-resource", ".txt")
        file.writeText("hello")
        val resolved = LocalScriptResourceResolver.resolve(
            ScriptResourceRef(
                name = "data",
                uri = file.toString(),
                checksum = "sha256:${sha256(file)}",
            ),
        )

        assertEquals(file, resolved.localPath)
    }

    @Test
    fun cachingResolverDownloadsAndReusesResource() = runBlocking {
        val cache = createTempDirectory("asteria-script-resource-cache")
        val source = Files.createTempFile("asteria-script-resource-source", ".csv")
        source.writeText("id,value\n1,100\n")
        var downloads = 0
        val resolver = CachingScriptResourceResolver(
            cacheDirectory = cache,
            downloader = SchemeScriptResourceDownloader(
                mapOf(
                    "s3" to ScriptResourceDownloader { _, destination ->
                        downloads += 1
                        Files.copy(source, destination)
                    },
                ),
            ),
        )
        val ref = ScriptResourceRef(
            name = "table",
            uri = "s3://bucket/table.csv",
            checksum = "sha256:${sha256(source)}",
            format = "csv",
        )

        val first = resolver.resolve(ref)
        val second = resolver.resolve(ref)

        assertEquals(1, downloads)
        assertEquals(first.localPath, second.localPath)
        assertEquals(source.readText(), first.localPath?.readText())
    }

    @Test
    fun tableReaderStreamsCsvRows() = runBlocking {
        val file = Files.createTempFile("asteria-script-table", ".csv")
        file.writeText("playerId,amount\n1,100\n2,\"20,0\"\n")
        val resources = ScriptResources(
            listOf(ScriptResourceRef(name = "compensation", uri = file.toString())),
            LocalScriptResourceResolver,
        )

        val rows = ScriptResourceTableReader(resources).readCsv("compensation") { sequence ->
            sequence.toList()
        }

        assertEquals(listOf("1", "2"), rows.map { it["playerId"] })
        assertEquals("20,0", rows[1]["amount"])
        assertTrue(rows.all { it.columns == listOf("playerId", "amount") })
    }

    @Test
    fun defaultDownloaderSupportsObjectStoreRefsWithPresignedUrl() = runBlocking {
        val cache = createTempDirectory("asteria-script-resource-cache")
        val source = Files.createTempFile("asteria-script-resource-source", ".jsonl")
        source.writeText("""{"id":1}""" + "\n")
        val resolver = CachingScriptResourceResolver(cache)

        val resolved = resolver.resolve(
            ScriptResourceRef(
                name = "players",
                uri = "s3://bucket/players.jsonl",
                checksum = "sha256:${sha256(source)}",
                format = "jsonl",
                attributes = mapOf("downloadUrl" to source.toUri().toString()),
            ),
        )

        assertEquals(source.readText(), resolved.localPath?.readText())
    }

    private fun sha256(path: Path): String {
        val digest = MessageDigest.getInstance("SHA-256")
        Files.newInputStream(path).use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) {
                    break
                }
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}

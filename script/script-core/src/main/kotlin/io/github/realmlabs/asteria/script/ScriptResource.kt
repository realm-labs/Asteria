package io.github.realmlabs.asteria.script

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.security.MessageDigest
import kotlin.io.path.*

/**
 * External resource referenced by a script execution.
 *
 * A resource reference is intentionally small enough to travel inside actor messages. Large inputs such as compensation
 * tables should live in object storage, shared storage, or a pre-arranged local path and be referenced here by URI.
 */
data class ScriptResourceRef(
    val name: String,
    val uri: String,
    val checksum: String? = null,
    val format: String? = null,
    val sizeBytes: Long? = null,
    val attributes: Map<String, String> = emptyMap(),
) {
    init {
        require(name.isNotBlank()) { "script resource name must not be blank" }
        require(uri.isNotBlank()) { "script resource uri must not be blank" }
        checksum?.let { require(it.isNotBlank()) { "script resource checksum must not be blank" } }
        format?.let { require(it.isNotBlank()) { "script resource format must not be blank" } }
        sizeBytes?.let { require(it >= 0) { "script resource size must not be negative" } }
        attributes.forEach { (key, value) ->
            require(key.isNotBlank()) { "script resource attribute key must not be blank" }
            require(value.isNotBlank()) { "script resource attribute value must not be blank" }
        }
    }
}

/**
 * Resource after node-local resolution.
 *
 * [localPath] is present when the resource can be read from the filesystem. Remote resolvers may still return only a
 * URI when they stream data directly and do not materialize a local copy.
 */
data class ScriptResolvedResource(
    val ref: ScriptResourceRef,
    val localPath: Path? = null,
    val uri: URI = URI.create(ref.uri),
) {
    init {
        localPath?.let { require(it.exists()) { "script resource local path does not exist: $it" } }
    }
}

/**
 * Resolves resource references in the environment of the current node.
 *
 * Implementations should verify checksums before returning local files or downloaded copies.
 */
fun interface ScriptResourceResolver {
    suspend fun resolve(ref: ScriptResourceRef): ScriptResolvedResource
}

/**
 * Downloads one remote script resource into a local file.
 *
 * Cloud object stores should plug in here instead of making [ScriptResourceRef] carry large table payloads. Framework
 * modules can then cache and verify the downloaded file without knowing whether the source is S3, MinIO, OSS, or HTTP.
 */
fun interface ScriptResourceDownloader {
    suspend fun download(ref: ScriptResourceRef, destination: Path)
}

/**
 * Resolver for `file:` and plain local path resource URIs.
 */
object LocalScriptResourceResolver : ScriptResourceResolver {
    override suspend fun resolve(ref: ScriptResourceRef): ScriptResolvedResource {
        val uri = runCatching { URI.create(ref.uri) }.getOrNull()
        val path = when (uri?.scheme) {
            null -> Path(ref.uri)
            "file" -> Paths.get(uri)
            else -> error("unsupported local script resource uri scheme ${uri.scheme}")
        }
        verifyScriptResourceChecksum(ref, path)
        return ScriptResolvedResource(ref = ref, localPath = path, uri = path.toUri())
    }
}

/**
 * Downloader for URL-backed resources.
 */
object UrlScriptResourceDownloader : ScriptResourceDownloader {
    override suspend fun download(ref: ScriptResourceRef, destination: Path) {
        downloadUrl(URI.create(ref.uri), destination)
    }
}

/**
 * Downloader for object-store references that carry a pre-signed URL in `downloadUrl` or `url` attributes.
 */
object PresignedUrlScriptResourceDownloader : ScriptResourceDownloader {
    override suspend fun download(ref: ScriptResourceRef, destination: Path) {
        val url = ref.attributes["downloadUrl"] ?: ref.attributes["url"]
        ?: error("script resource ${ref.name} requires a pre-signed downloadUrl attribute")
        downloadUrl(URI.create(url), destination)
    }
}

private suspend fun downloadUrl(uri: URI, destination: Path) {
    withContext(Dispatchers.IO) {
        Files.createDirectories(destination.parent)
        val tmp = destination.resolveSibling("${destination.name}.tmp")
        uri.toURL().openStream().use { input ->
            tmp.outputStream().use { output -> input.copyTo(output) }
        }
        runCatching {
            Files.move(
                tmp,
                destination,
                java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                java.nio.file.StandardCopyOption.ATOMIC_MOVE,
            )
        }.getOrElse {
            Files.move(tmp, destination, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        }
    }
}

/**
 * Downloader set used by [CachingScriptResourceResolver] when no custom downloader is supplied.
 *
 * HTTP and HTTPS are fetched directly. Object-store schemes require callers to provide a pre-signed URL in resource
 * attributes so the core script module does not depend on a cloud SDK.
 */
object DefaultScriptResourceDownloader : ScriptResourceDownloader {
    private val delegate = SchemeScriptResourceDownloader(
        mapOf(
            "http" to UrlScriptResourceDownloader,
            "https" to UrlScriptResourceDownloader,
            "s3" to PresignedUrlScriptResourceDownloader,
            "minio" to PresignedUrlScriptResourceDownloader,
            "oss" to PresignedUrlScriptResourceDownloader,
        ),
    )

    override suspend fun download(ref: ScriptResourceRef, destination: Path) {
        delegate.download(ref, destination)
    }
}

/**
 * Routes downloads by lower-cased URI scheme.
 *
 * Missing schemes are rejected instead of falling back to local paths, which keeps remote resource declarations
 * explicit.
 */
class SchemeScriptResourceDownloader(
    private val downloaders: Map<String, ScriptResourceDownloader>,
) : ScriptResourceDownloader {
    init {
        require(downloaders.isNotEmpty()) { "script resource downloaders must not be empty" }
        downloaders.keys.forEach { require(it.isNotBlank()) { "script resource downloader scheme must not be blank" } }
    }

    override suspend fun download(ref: ScriptResourceRef, destination: Path) {
        val scheme = requireNotNull(URI.create(ref.uri).scheme) { "script resource ${ref.name} uri has no scheme" }
        val downloader = downloaders[scheme.lowercase()]
            ?: error("script resource downloader for scheme $scheme is not registered")
        downloader.download(ref, destination)
    }
}

/**
 * Resolves remote resources by downloading them to a node-local cache.
 */
class CachingScriptResourceResolver(
    private val cacheDirectory: Path,
    private val downloader: ScriptResourceDownloader = DefaultScriptResourceDownloader,
    private val localResolver: ScriptResourceResolver = LocalScriptResourceResolver,
) : ScriptResourceResolver {
    init {
        require(cacheDirectory.toString().isNotBlank()) { "script resource cache directory must not be blank" }
    }

    override suspend fun resolve(ref: ScriptResourceRef): ScriptResolvedResource {
        val uri = URI.create(ref.uri)
        if (uri.scheme == null || uri.scheme == "file") {
            return localResolver.resolve(ref)
        }
        val path = cacheDirectory.resolve(cacheFileName(ref))
        if (!Files.exists(path) || !isScriptResourceChecksumValid(ref, path)) {
            withContext(Dispatchers.IO) {
                Files.createDirectories(path.parent)
            }
            downloader.download(ref, path)
        }
        verifyScriptResourceChecksum(ref, path)
        return ScriptResolvedResource(ref = ref, localPath = path, uri = path.toUri())
    }

    private fun cacheFileName(ref: ScriptResourceRef): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(ref.uri.toByteArray())
            .joinToString("") { "%02x".format(it) }
        val extension = ref.format?.lowercase()?.takeIf { it.all(Char::isLetterOrDigit) }?.let { ".$it" }.orEmpty()
        return "${ref.name}-$digest$extension"
    }
}

/**
 * Tries multiple resolvers in order.
 */
class CompositeScriptResourceResolver(
    private val resolvers: List<ScriptResourceResolver>,
) : ScriptResourceResolver {
    init {
        require(resolvers.isNotEmpty()) { "script resource resolvers must not be empty" }
    }

    override suspend fun resolve(ref: ScriptResourceRef): ScriptResolvedResource {
        var lastError: Throwable? = null
        resolvers.forEach { resolver ->
            val resolved = runCatching { resolver.resolve(ref) }
            if (resolved.isSuccess) {
                return resolved.getOrThrow()
            }
            lastError = resolved.exceptionOrNull()
        }
        throw IllegalStateException("script resource ${ref.name} cannot be resolved", lastError)
    }
}

/**
 * Resource facade attached to a script execution.
 *
 * Name lookup is strict. If no resolver is registered in the service registry, [resolve] falls back to local file and
 * `file:` URI handling through [LocalScriptResourceResolver].
 */
class ScriptResources(
    private val refs: List<ScriptResourceRef>,
    private val resolver: ScriptResourceResolver?,
) {
    fun all(): List<ScriptResourceRef> = refs

    fun ref(name: String): ScriptResourceRef {
        return refs.firstOrNull { it.name == name } ?: error("script resource $name not found")
    }

    suspend fun resolve(name: String): ScriptResolvedResource {
        val resource = ref(name)
        val resourceResolver = resolver ?: LocalScriptResourceResolver
        return resourceResolver.resolve(resource)
    }
}

/**
 * One row from a tabular script resource.
 */
data class ScriptTableRow(
    val lineNumber: Long,
    val values: List<String>,
    val columns: List<String> = emptyList(),
) {
    operator fun get(column: String): String? {
        val index = columns.indexOf(column)
        return if (index >= 0) values.getOrNull(index) else null
    }
}

/**
 * Streaming helpers for large CSV and JSONL resources.
 */
class ScriptResourceTableReader(
    private val resources: ScriptResources,
) {
    suspend fun <T> readCsv(
        name: String,
        hasHeader: Boolean = true,
        delimiter: Char = ',',
        block: (Sequence<ScriptTableRow>) -> T,
    ): T {
        val path = requireLocalPath(resources.resolve(name))
        return withContext(Dispatchers.IO) {
            Files.newBufferedReader(path).useLines { lines ->
                val iterator = lines.iterator()
                val columns = if (hasHeader && iterator.hasNext()) {
                    parseCsvLine(iterator.next(), delimiter)
                } else {
                    emptyList()
                }
                val startLine = if (hasHeader) 2L else 1L
                val rows = iterator.asSequence().mapIndexed { index, line ->
                    ScriptTableRow(
                        lineNumber = startLine + index,
                        values = parseCsvLine(line, delimiter),
                        columns = columns,
                    )
                }
                block(rows)
            }
        }
    }

    suspend fun <T> readJsonLines(
        name: String,
        block: (Sequence<Pair<Long, String>>) -> T,
    ): T {
        val path = requireLocalPath(resources.resolve(name))
        return withContext(Dispatchers.IO) {
            Files.newBufferedReader(path).useLines { lines ->
                block(
                    lines.mapIndexedNotNull { index, line ->
                        line.takeIf { it.isNotBlank() }?.let { (index + 1L) to it }
                    },
                )
            }
        }
    }

    private fun requireLocalPath(resource: ScriptResolvedResource): Path {
        return requireNotNull(resource.localPath) { "script resource ${resource.ref.name} is not available as a local file" }
    }
}

private fun parseCsvLine(line: String, delimiter: Char): List<String> {
    val values = mutableListOf<String>()
    val current = StringBuilder()
    var quoted = false
    var index = 0
    while (index < line.length) {
        when (val char = line[index]) {
            '"' if quoted && index + 1 < line.length && line[index + 1] == '"' -> {
                current.append('"')
                index += 1
            }

            '"' -> quoted = !quoted
            delimiter if !quoted -> {
                values += current.toString()
                current.clear()
            }
            else -> current.append(char)
        }
        index += 1
    }
    values += current.toString()
    return values
}

private fun verifyScriptResourceChecksum(ref: ScriptResourceRef, path: Path) {
    // TODO remove unused checksum
    val checksum = ref.checksum ?: return
    require(isScriptResourceChecksumValid(ref, path)) {
        "script resource ${ref.name} checksum mismatch"
    }
}

private fun isScriptResourceChecksumValid(ref: ScriptResourceRef, path: Path): Boolean {
    val checksum = ref.checksum ?: return true
    if (!Files.exists(path)) {
        return false
    }
    val (algorithm, expected) = parseChecksum(checksum)
    val actual = digest(path, algorithm)
    return actual.equals(expected, ignoreCase = true)
}

private fun parseChecksum(checksum: String): Pair<String, String> {
    val separator = checksum.indexOfFirst { it == ':' || it == '=' }
    val algorithm = if (separator > 0) checksum.substring(0, separator) else "sha256"
    val value = if (separator > 0) checksum.substring(separator + 1) else checksum
    require(value.isNotBlank()) { "script resource checksum value must not be blank" }
    return when (algorithm.lowercase()) {
        "sha256", "sha-256" -> "SHA-256" to value
        "sha1", "sha-1" -> "SHA-1" to value
        "md5" -> "MD5" to value
        else -> error("unsupported script resource checksum algorithm $algorithm")
    }
}

private fun digest(path: Path, algorithm: String): String {
    val digest = MessageDigest.getInstance(algorithm)
    path.inputStream().use { input ->
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

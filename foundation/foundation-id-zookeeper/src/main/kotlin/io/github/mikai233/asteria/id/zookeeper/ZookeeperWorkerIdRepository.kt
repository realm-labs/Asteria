package io.github.mikai233.asteria.id.zookeeper

import io.github.mikai233.asteria.id.WorkerId
import io.github.mikai233.asteria.id.WorkerIdLease
import io.github.mikai233.asteria.id.WorkerIdOwner
import io.github.mikai233.asteria.id.WorkerIdRange
import io.github.mikai233.asteria.id.WorkerIdRepository
import io.github.mikai233.asteria.id.WorkerIdUnavailableException
import java.nio.charset.StandardCharsets.UTF_8
import java.time.Duration
import java.time.Instant
import java.util.Base64
import java.util.UUID
import kotlinx.coroutines.future.await
import org.apache.curator.x.async.AsyncCuratorFramework
import org.apache.curator.x.async.api.CreateOption
import org.apache.zookeeper.KeeperException
import org.apache.zookeeper.data.Stat

class ZookeeperWorkerIdRepository(
    private val client: AsyncCuratorFramework,
    pathPrefix: String = "/asteria/worker-ids",
) : WorkerIdRepository {
    private val normalizedPrefix = normalizePrefix(pathPrefix)

    override suspend fun acquire(
        owner: WorkerIdOwner,
        range: WorkerIdRange,
        ttl: Duration,
        now: Instant,
    ): WorkerIdLease {
        require(!ttl.isNegative && !ttl.isZero) { "worker id lease ttl must be positive" }
        expire(now)
        leases(now)
            .firstOrNull { lease -> lease.owner == owner && lease.id in range }
            ?.let { current -> renew(current, ttl, now)?.let { return it } }
        for (id in range.ids().map(::WorkerId)) {
            acquireSlot(id, owner, ttl, now)?.let { return it }
        }
        throw WorkerIdUnavailableException(owner, range)
    }

    override suspend fun renew(
        lease: WorkerIdLease,
        ttl: Duration,
        now: Instant,
    ): WorkerIdLease? {
        require(!ttl.isNegative && !ttl.isZero) { "worker id lease ttl must be positive" }
        val path = pathOf(lease.id)
        val current = read(path) ?: return null
        if (current.lease.owner != lease.owner || current.lease.token != lease.token || current.lease.isExpired(now)) {
            if (current.lease.isExpired(now)) {
                delete(path, current.version)
            }
            return null
        }
        val renewed = current.lease.copy(expiresAt = now.plus(ttl))
        return try {
            client.setData()
                .withVersion(current.version)
                .forPath(path, renewed.encode().toByteArray(UTF_8))
                .await()
            renewed
        } catch (_: KeeperException.BadVersionException) {
            null
        } catch (_: KeeperException.NoNodeException) {
            null
        }
    }

    override suspend fun release(lease: WorkerIdLease): Boolean {
        val path = pathOf(lease.id)
        val current = read(path) ?: return false
        if (current.lease.owner != lease.owner || current.lease.token != lease.token) {
            return false
        }
        return delete(path, current.version)
    }

    override suspend fun leases(now: Instant): List<WorkerIdLease> {
        expire(now)
        return children()
            .mapNotNull { id -> read(pathOf(WorkerId(id)))?.lease }
            .filterNot { lease -> lease.isExpired(now) }
            .sortedBy { lease -> lease.id.value }
    }

    private suspend fun acquireSlot(
        id: WorkerId,
        owner: WorkerIdOwner,
        ttl: Duration,
        now: Instant,
    ): WorkerIdLease? {
        val path = pathOf(id)
        val lease = WorkerIdLease(
            id = id,
            owner = owner,
            token = UUID.randomUUID().toString(),
            acquiredAt = now,
            expiresAt = now.plus(ttl),
        )
        val current = read(path)
        return if (current == null) {
            create(path, lease)
        } else {
            if (!current.lease.isExpired(now)) {
                return null
            }
            if (delete(path, current.version)) {
                create(path, lease)
            } else {
                null
            }
        }
    }

    private suspend fun create(path: String, lease: WorkerIdLease): WorkerIdLease? {
        return try {
            client.create()
                .withOptions(setOf(CreateOption.createParentsIfNeeded))
                .forPath(path, lease.encode().toByteArray(UTF_8))
                .await()
            lease
        } catch (_: KeeperException.NodeExistsException) {
            null
        }
    }

    private suspend fun expire(now: Instant) {
        children()
            .mapNotNull { id ->
                val path = pathOf(WorkerId(id))
                val current = read(path) ?: return@mapNotNull null
                if (current.lease.isExpired(now)) ExpiredNode(path, current.version) else null
            }
            .forEach { expired -> delete(expired.path, expired.version) }
    }

    private suspend fun children(): List<Int> {
        return try {
            client.children.forPath(normalizedPrefix)
                .await()
                .mapNotNull { child -> child.toIntOrNull() }
                .sorted()
        } catch (_: KeeperException.NoNodeException) {
            emptyList()
        }
    }

    private suspend fun read(path: String): StoredLease? {
        return try {
            val stat = Stat()
            val bytes = client.data.storingStatIn(stat).forPath(path).await()
            val lease = String(bytes, UTF_8).decodeLeaseOrNull() ?: return null
            StoredLease(lease, stat.version)
        } catch (_: KeeperException.NoNodeException) {
            null
        }
    }

    private suspend fun delete(path: String, version: Int): Boolean {
        return try {
            client.delete()
                .withVersion(version)
                .forPath(path)
                .await()
            true
        } catch (_: KeeperException.BadVersionException) {
            false
        } catch (_: KeeperException.NoNodeException) {
            false
        }
    }

    private fun pathOf(id: WorkerId): String {
        return "$normalizedPrefix/${id.value}"
    }

    private fun WorkerIdLease.encode(): String {
        return listOf(
            id.value.toString(),
            owner.value.encodeToken(),
            token.encodeToken(),
            acquiredAt.toEpochMilli().toString(),
            expiresAt.toEpochMilli().toString(),
        ).joinToString("\n")
    }

    private fun String.decodeLeaseOrNull(): WorkerIdLease? {
        val parts = split("\n")
        if (parts.size != 5) {
            return null
        }
        return WorkerIdLease(
            id = WorkerId(parts[0].toIntOrNull() ?: return null),
            owner = WorkerIdOwner(parts[1].decodeTokenOrNull() ?: return null),
            token = parts[2].decodeTokenOrNull() ?: return null,
            acquiredAt = Instant.ofEpochMilli(parts[3].toLongOrNull() ?: return null),
            expiresAt = Instant.ofEpochMilli(parts[4].toLongOrNull() ?: return null),
        )
    }

    private fun String.encodeToken(): String {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(toByteArray(UTF_8))
    }

    private fun String.decodeTokenOrNull(): String? {
        return runCatching { String(Base64.getUrlDecoder().decode(this), UTF_8) }.getOrNull()
    }

    private fun normalizePrefix(prefix: String): String {
        val trimmed = prefix.trim().trimEnd('/')
        require(trimmed.startsWith("/")) { "zookeeper worker id path prefix must start with /" }
        return trimmed
    }

    private data class StoredLease(
        val lease: WorkerIdLease,
        val version: Int,
    )

    private data class ExpiredNode(
        val path: String,
        val version: Int,
    )
}

package io.github.realmlabs.asteria.id.etcd

import io.etcd.jetcd.ByteSequence
import io.etcd.jetcd.Client
import io.etcd.jetcd.KeyValue
import io.etcd.jetcd.op.Cmp
import io.etcd.jetcd.op.CmpTarget
import io.etcd.jetcd.op.Op
import io.etcd.jetcd.options.DeleteOption
import io.etcd.jetcd.options.GetOption
import io.etcd.jetcd.options.PutOption
import io.github.realmlabs.asteria.id.*
import io.github.realmlabs.asteria.observability.MetricTags
import io.github.realmlabs.asteria.observability.Metrics
import io.github.realmlabs.asteria.observability.NoopMetrics
import kotlinx.coroutines.future.await
import org.slf4j.LoggerFactory
import java.nio.charset.StandardCharsets.UTF_8
import java.util.*
import kotlin.time.Duration
import kotlin.time.Instant

class EtcdWorkerIdRepository(
    private val client: Client,
    keyPrefix: String = "/asteria/worker-ids",
    private val metrics: Metrics = NoopMetrics,
) : WorkerIdRepository {
    private val logger = LoggerFactory.getLogger(EtcdWorkerIdRepository::class.java)
    private val normalizedPrefix = normalizePrefix(keyPrefix)

    override suspend fun acquire(
        owner: WorkerIdOwner,
        range: WorkerIdRange,
        ttl: Duration,
        now: Instant,
    ): WorkerIdLease {
        return measured("acquire") {
            require(ttl > Duration.ZERO) { "worker id lease ttl must be positive" }
            expire(now)
            leases(now)
                .firstOrNull { lease -> lease.owner == owner && lease.id in range }
                ?.let { current -> renew(current, ttl, now)?.let { return@measured it } }
            for (id in range.ids().map(::WorkerId)) {
                acquireSlot(id, owner, ttl, now)?.let { return@measured it }
            }
            throw WorkerIdUnavailableException(owner, range)
        }
    }

    override suspend fun renew(
        lease: WorkerIdLease,
        ttl: Duration,
        now: Instant,
    ): WorkerIdLease? {
        return measured("renew") {
            require(ttl > Duration.ZERO) { "worker id lease ttl must be positive" }
            val key = byteKey(lease.id)
            val current = client.kvClient.get(key).await().kvs.firstOrNull() ?: return@measured null
            val currentLease = current.toLeaseOrNull() ?: return@measured null
            if (currentLease.owner != lease.owner || currentLease.token != lease.token || currentLease.isExpired(now)) {
                if (currentLease.isExpired(now)) {
                    deleteIfRevision(key, current.modRevision)
                }
                return@measured null
            }
            val renewed = currentLease.copy(expiresAt = now + ttl)
            val response = client.kvClient.txn()
                .If(Cmp(key, Cmp.Op.EQUAL, CmpTarget.modRevision(current.modRevision)))
                .Then(Op.put(key, ByteSequence.from(renewed.encode(), UTF_8), PutOption.DEFAULT))
                .commit()
                .await()
            if (response.isSucceeded) renewed else null
        }
    }

    override suspend fun release(lease: WorkerIdLease): Boolean {
        return measured("release") {
            val key = byteKey(lease.id)
            val current = client.kvClient.get(key).await().kvs.firstOrNull() ?: return@measured false
            val currentLease = current.toLeaseOrNull() ?: return@measured false
            if (currentLease.owner != lease.owner || currentLease.token != lease.token) {
                return@measured false
            }
            deleteIfRevision(key, current.modRevision)
        }
    }

    override suspend fun leases(now: Instant): List<WorkerIdLease> {
        return measured("leases") {
            expire(now)
            client.kvClient.get(bytePrefix(), GetOption.builder().isPrefix(true).build())
                .await()
                .kvs
                .mapNotNull { keyValue -> keyValue.toLeaseOrNull() }
                .filterNot { lease -> lease.isExpired(now) }
                .sortedBy { lease -> lease.id.value }
        }
    }

    private suspend fun <T> measured(operation: String, block: suspend () -> T): T {
        val tags = MetricTags.of("backend" to "etcd", "operation" to operation)
        val startedAt = System.nanoTime()
        metrics.counter("asteria.worker_id.repository.operation.total", tags).increment()
        try {
            return block()
        } catch (error: Throwable) {
            metrics.counter("asteria.worker_id.repository.operation.failed.total", tags).increment()
            logger.error("Etcd worker id repository operation failed operation={}", operation, error)
            throw error
        } finally {
            metrics.timer("asteria.worker_id.repository.operation.duration", tags)
                .record((System.nanoTime() - startedAt) / 1_000_000)
        }
    }

    private suspend fun acquireSlot(
        id: WorkerId,
        owner: WorkerIdOwner,
        ttl: Duration,
        now: Instant,
    ): WorkerIdLease? {
        val key = byteKey(id)
        val lease = WorkerIdLease(
            id = id,
            owner = owner,
            token = UUID.randomUUID().toString(),
            acquiredAt = now,
            expiresAt = now + ttl,
        )
        val current = client.kvClient.get(key).await().kvs.firstOrNull()
        val response = if (current == null) {
            client.kvClient.txn()
                .If(Cmp(key, Cmp.Op.EQUAL, CmpTarget.version(0)))
                .Then(Op.put(key, ByteSequence.from(lease.encode(), UTF_8), PutOption.DEFAULT))
                .commit()
                .await()
        } else {
            val currentLease = current.toLeaseOrNull() ?: return null
            if (!currentLease.isExpired(now)) {
                return null
            }
            client.kvClient.txn()
                .If(Cmp(key, Cmp.Op.EQUAL, CmpTarget.modRevision(current.modRevision)))
                .Then(Op.put(key, ByteSequence.from(lease.encode(), UTF_8), PutOption.DEFAULT))
                .commit()
                .await()
        }
        return if (response.isSucceeded) lease else null
    }

    private suspend fun expire(now: Instant) {
        val expired = client.kvClient.get(bytePrefix(), GetOption.builder().isPrefix(true).build())
            .await()
            .kvs
            .mapNotNull { keyValue ->
                val lease = keyValue.toLeaseOrNull() ?: return@mapNotNull null
                if (lease.isExpired(now)) keyValue else null
            }
        expired.forEach { keyValue -> deleteIfRevision(keyValue.key, keyValue.modRevision) }
    }

    private suspend fun deleteIfRevision(key: ByteSequence, revision: Long): Boolean {
        val response = client.kvClient.txn()
            .If(Cmp(key, Cmp.Op.EQUAL, CmpTarget.modRevision(revision)))
            .Then(Op.delete(key, DeleteOption.DEFAULT))
            .commit()
            .await()
        return response.isSucceeded
    }

    private fun byteKey(id: WorkerId): ByteSequence {
        return ByteSequence.from("$normalizedPrefix/${id.value}", UTF_8)
    }

    private fun bytePrefix(): ByteSequence {
        return ByteSequence.from("$normalizedPrefix/", UTF_8)
    }

    private fun KeyValue.toLeaseOrNull(): WorkerIdLease? {
        return String(value.bytes, UTF_8).decodeLeaseOrNull()
    }

    private fun WorkerIdLease.encode(): String {
        return listOf(
            id.value.toString(),
            owner.value.encodeToken(),
            token.encodeToken(),
            acquiredAt.toEpochMilliseconds().toString(),
            expiresAt.toEpochMilliseconds().toString(),
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
            acquiredAt = Instant.fromEpochMilliseconds(parts[3].toLongOrNull() ?: return null),
            expiresAt = Instant.fromEpochMilliseconds(parts[4].toLongOrNull() ?: return null),
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
        require(trimmed.startsWith("/")) { "etcd worker id key prefix must start with /" }
        return trimmed
    }
}

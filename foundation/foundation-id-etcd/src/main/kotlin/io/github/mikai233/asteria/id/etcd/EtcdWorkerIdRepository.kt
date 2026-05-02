package io.github.mikai233.asteria.id.etcd

import io.etcd.jetcd.ByteSequence
import io.etcd.jetcd.Client
import io.etcd.jetcd.KeyValue
import io.etcd.jetcd.op.Cmp
import io.etcd.jetcd.op.CmpTarget
import io.etcd.jetcd.op.Op
import io.etcd.jetcd.options.DeleteOption
import io.etcd.jetcd.options.GetOption
import io.etcd.jetcd.options.PutOption
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

class EtcdWorkerIdRepository(
    private val client: Client,
    keyPrefix: String = "/asteria/worker-ids",
) : WorkerIdRepository {
    private val normalizedPrefix = normalizePrefix(keyPrefix)

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
        val key = byteKey(lease.id)
        val current = client.kvClient.get(key).await().kvs.firstOrNull() ?: return null
        val currentLease = current.toLeaseOrNull() ?: return null
        if (currentLease.owner != lease.owner || currentLease.token != lease.token || currentLease.isExpired(now)) {
            if (currentLease.isExpired(now)) {
                deleteIfRevision(key, current.modRevision)
            }
            return null
        }
        val renewed = currentLease.copy(expiresAt = now.plus(ttl))
        val response = client.kvClient.txn()
            .If(Cmp(key, Cmp.Op.EQUAL, CmpTarget.modRevision(current.modRevision)))
            .Then(Op.put(key, ByteSequence.from(renewed.encode(), UTF_8), PutOption.DEFAULT))
            .commit()
            .await()
        return if (response.isSucceeded) renewed else null
    }

    override suspend fun release(lease: WorkerIdLease): Boolean {
        val key = byteKey(lease.id)
        val current = client.kvClient.get(key).await().kvs.firstOrNull() ?: return false
        val currentLease = current.toLeaseOrNull() ?: return false
        if (currentLease.owner != lease.owner || currentLease.token != lease.token) {
            return false
        }
        return deleteIfRevision(key, current.modRevision)
    }

    override suspend fun leases(now: Instant): List<WorkerIdLease> {
        expire(now)
        return client.kvClient.get(bytePrefix(), GetOption.builder().isPrefix(true).build())
            .await()
            .kvs
            .mapNotNull { keyValue -> keyValue.toLeaseOrNull() }
            .filterNot { lease -> lease.isExpired(now) }
            .sortedBy { lease -> lease.id.value }
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
            expiresAt = now.plus(ttl),
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
        require(trimmed.startsWith("/")) { "etcd worker id key prefix must start with /" }
        return trimmed
    }
}

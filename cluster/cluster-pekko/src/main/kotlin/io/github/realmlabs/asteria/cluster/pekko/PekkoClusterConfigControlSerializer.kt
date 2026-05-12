package io.github.realmlabs.asteria.cluster.pekko

import io.github.realmlabs.asteria.cluster.config.ClusterConfigNodeReloadResult
import io.github.realmlabs.asteria.cluster.config.ClusterConfigNodeReloadStatus
import io.github.realmlabs.asteria.cluster.config.ClusterConfigNodeStatus
import io.github.realmlabs.asteria.config.ConfigRevision
import org.apache.pekko.actor.ExtendedActorSystem
import org.apache.pekko.serialization.SerializerWithStringManifest
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream

/**
 * Wire serializer for config control asks and replies.
 *
 * Config reload/status requests are sent through actor selections to every selected node. The request objects are small,
 * but they still need an explicit binding because production deployments disable Java serialization.
 */
class PekkoClusterConfigControlSerializer(
    @Suppress("unused") private val system: ExtendedActorSystem,
) : SerializerWithStringManifest() {
    override fun identifier(): Int = IDENTIFIER

    override fun manifest(o: Any): String {
        return when (o) {
            PekkoClusterConfigControlMessage.GetStatus -> GET_STATUS_MANIFEST
            PekkoClusterConfigControlMessage.Reload -> RELOAD_MANIFEST
            is ClusterConfigNodeStatus -> NODE_STATUS_MANIFEST
            is ClusterConfigNodeReloadResult -> NODE_RELOAD_RESULT_MANIFEST
            else -> error("unsupported cluster config control message ${o::class.qualifiedName}")
        }
    }

    override fun toBinary(o: Any): ByteArray {
        return encode {
            when (o) {
                PekkoClusterConfigControlMessage.GetStatus -> Unit
                PekkoClusterConfigControlMessage.Reload -> Unit
                is ClusterConfigNodeStatus -> writeNodeStatus(o)
                is ClusterConfigNodeReloadResult -> writeNodeReloadResult(o)
                else -> error("unsupported cluster config control message ${o::class.qualifiedName}")
            }
        }
    }

    override fun fromBinary(
        bytes: ByteArray,
        manifest: String,
    ): Any {
        return decode(bytes) {
            when (manifest) {
                GET_STATUS_MANIFEST -> PekkoClusterConfigControlMessage.GetStatus
                RELOAD_MANIFEST -> PekkoClusterConfigControlMessage.Reload
                NODE_STATUS_MANIFEST -> readNodeStatus()
                NODE_RELOAD_RESULT_MANIFEST -> readNodeReloadResult()
                else -> error("unsupported cluster config control message manifest $manifest")
            }
        }
    }

    private fun DataOutputStream.writeNodeStatus(status: ClusterConfigNodeStatus) {
        writeNullableString(status.nodeId)
        writeString(status.address)
        writeStringSet(status.roles)
        writeConfigRevision(status.revision)
        writeBoolean(status.reachable)
        writeNullableString(status.message)
    }

    private fun DataInputStream.readNodeStatus(): ClusterConfigNodeStatus {
        return ClusterConfigNodeStatus(
            nodeId = readNullableString(),
            address = readString(),
            roles = readStringSet(),
            revision = readConfigRevision(),
            reachable = readBoolean(),
            message = readNullableString(),
        )
    }

    private fun DataOutputStream.writeNodeReloadResult(result: ClusterConfigNodeReloadResult) {
        writeNullableString(result.nodeId)
        writeString(result.address)
        writeStringSet(result.roles)
        writeConfigRevision(result.previousRevision)
        writeConfigRevision(result.currentRevision)
        writeString(result.status.name)
        writeNullableString(result.message)
    }

    private fun DataInputStream.readNodeReloadResult(): ClusterConfigNodeReloadResult {
        return ClusterConfigNodeReloadResult(
            nodeId = readNullableString(),
            address = readString(),
            roles = readStringSet(),
            previousRevision = readConfigRevision(),
            currentRevision = readConfigRevision(),
            status = ClusterConfigNodeReloadStatus.valueOf(readString()),
            message = readNullableString(),
        )
    }

    private fun DataOutputStream.writeConfigRevision(revision: ConfigRevision?) {
        writeBoolean(revision != null)
        if (revision != null) {
            writeString(revision.version)
            writeNullableString(revision.checksum)
        }
    }

    private fun DataInputStream.readConfigRevision(): ConfigRevision? {
        return if (readBoolean()) {
            ConfigRevision(
                version = readString(),
                checksum = readNullableString(),
            )
        } else {
            null
        }
    }

    private fun DataOutputStream.writeNullableString(value: String?) {
        writeBoolean(value != null)
        if (value != null) {
            writeString(value)
        }
    }

    private fun DataInputStream.readNullableString(): String? {
        return if (readBoolean()) readString() else null
    }

    private fun DataOutputStream.writeStringSet(values: Set<String>) {
        writeInt(values.size)
        values.forEach { value -> writeString(value) }
    }

    private fun DataInputStream.readStringSet(): Set<String> {
        return List(readInt()) { readString() }.toSet()
    }

    private fun DataOutputStream.writeString(value: String) {
        val bytes = value.toByteArray(Charsets.UTF_8)
        writeInt(bytes.size)
        write(bytes)
    }

    private fun DataInputStream.readString(): String {
        val bytes = ByteArray(readInt())
        readFully(bytes)
        return bytes.toString(Charsets.UTF_8)
    }

    private fun encode(writeBlock: DataOutputStream.() -> Unit): ByteArray {
        val buffer = ByteArrayOutputStream()
        DataOutputStream(buffer).use { output -> output.writeBlock() }
        return buffer.toByteArray()
    }

    private fun <T> decode(
        bytes: ByteArray,
        readBlock: DataInputStream.() -> T,
    ): T {
        return DataInputStream(ByteArrayInputStream(bytes)).use { input -> input.readBlock() }
    }

    companion object {
        const val IDENTIFIER: Int = 233_120_004
        const val GET_STATUS_MANIFEST: String = "cluster-config-control-get-status"
        const val RELOAD_MANIFEST: String = "cluster-config-control-reload"
        const val NODE_STATUS_MANIFEST: String = "cluster-config-control-node-status"
        const val NODE_RELOAD_RESULT_MANIFEST: String = "cluster-config-control-node-reload-result"
    }
}

package io.github.realmlabs.asteria.patch.pekko

import io.github.realmlabs.asteria.core.RoleKey
import io.github.realmlabs.asteria.patch.PatchApplyResult
import io.github.realmlabs.asteria.patch.PatchId
import io.github.realmlabs.asteria.patch.PatchNode
import io.github.realmlabs.asteria.patch.PatchNodeStatus
import org.apache.pekko.actor.ExtendedActorSystem
import org.apache.pekko.serialization.SerializerWithStringManifest
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream

class PekkoPatchControlSerializer(
    @Suppress("unused") private val system: ExtendedActorSystem,
) : SerializerWithStringManifest() {
    override fun identifier(): Int = IDENTIFIER

    override fun manifest(o: Any): String {
        return when (o) {
            PekkoPatchControlMessage.GetStatus -> GET_STATUS_MANIFEST
            is PekkoPatchControlMessage.Apply -> APPLY_MANIFEST
            is PekkoPatchControlMessage.Disable -> DISABLE_MANIFEST
            is PekkoPatchDisableResult -> DISABLE_RESULT_MANIFEST
            is PatchNode -> PATCH_NODE_MANIFEST
            is PatchApplyResult.Applied -> APPLY_RESULT_APPLIED_MANIFEST
            is PatchApplyResult.Failed -> APPLY_RESULT_FAILED_MANIFEST
            is PatchApplyResult.Ignored -> APPLY_RESULT_IGNORED_MANIFEST
            else -> error("unsupported patch control message ${o::class.qualifiedName}")
        }
    }

    override fun toBinary(o: Any): ByteArray {
        return encode {
            when (o) {
                PekkoPatchControlMessage.GetStatus -> Unit
                is PekkoPatchControlMessage.Apply -> writePatchId(o.patchId)
                is PekkoPatchControlMessage.Disable -> writePatchId(o.patchId)
                is PekkoPatchDisableResult -> {
                    writeBoolean(o.removed)
                    writeNullableString(o.message)
                }

                is PatchNode -> writePatchNode(o)
                is PatchApplyResult.Applied -> {
                    writePatchId(o.patchId)
                    writeInt(o.operationCount)
                }

                is PatchApplyResult.Failed -> {
                    writePatchId(o.patchId)
                    writeString(o.message)
                }

                is PatchApplyResult.Ignored -> {
                    writePatchId(o.patchId)
                    writeString(o.reason)
                }

                else -> error("unsupported patch control message ${o::class.qualifiedName}")
            }
        }
    }

    override fun fromBinary(bytes: ByteArray, manifest: String): Any {
        return decode(bytes) {
            when (manifest) {
                GET_STATUS_MANIFEST -> PekkoPatchControlMessage.GetStatus
                APPLY_MANIFEST -> PekkoPatchControlMessage.Apply(readPatchId())
                DISABLE_MANIFEST -> PekkoPatchControlMessage.Disable(readPatchId())
                DISABLE_RESULT_MANIFEST -> PekkoPatchDisableResult(
                    removed = readBoolean(),
                    message = readNullableString(),
                )

                PATCH_NODE_MANIFEST -> readPatchNode()
                APPLY_RESULT_APPLIED_MANIFEST -> PatchApplyResult.Applied(
                    patchId = readPatchId(),
                    operationCount = readInt(),
                )

                APPLY_RESULT_FAILED_MANIFEST -> PatchApplyResult.Failed(
                    patchId = readPatchId(),
                    message = readString(),
                )

                APPLY_RESULT_IGNORED_MANIFEST -> PatchApplyResult.Ignored(
                    patchId = readPatchId(),
                    reason = readString(),
                )

                else -> error("unsupported patch control message manifest $manifest")
            }
        }
    }

    private fun DataOutputStream.writePatchNode(node: PatchNode) {
        writeNullableString(node.nodeId)
        writeString(node.address)
        writeString(node.appName)
        writeString(node.version)
        writeStringList(node.roles.map { it.value })
        writeStringList(node.modules.toList())
        writeStringList(node.capabilities.toList())
        writeString(node.status.name)
    }

    private fun DataInputStream.readPatchNode(): PatchNode {
        return PatchNode(
            nodeId = readNullableString(),
            address = readString(),
            appName = readString(),
            version = readString(),
            roles = readStringList().mapTo(linkedSetOf(), ::RoleKey),
            modules = readStringList().toSet(),
            capabilities = readStringList().toSet(),
            status = PatchNodeStatus.valueOf(readString()),
        )
    }

    private fun DataOutputStream.writePatchId(id: PatchId) {
        writeString(id.value)
    }

    private fun DataInputStream.readPatchId(): PatchId {
        return PatchId(readString())
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

    private fun DataOutputStream.writeStringList(values: List<String>) {
        writeInt(values.size)
        values.forEach { value -> writeString(value) }
    }

    private fun DataInputStream.readStringList(): List<String> {
        return List(readInt()) { readString() }
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
        const val IDENTIFIER: Int = 233_120_003
        const val GET_STATUS_MANIFEST: String = "patch-control-get-status"
        const val APPLY_MANIFEST: String = "patch-control-apply"
        const val DISABLE_MANIFEST: String = "patch-control-disable"
        const val DISABLE_RESULT_MANIFEST: String = "patch-control-disable-result"
        const val PATCH_NODE_MANIFEST: String = "patch-control-node"
        const val APPLY_RESULT_APPLIED_MANIFEST: String = "patch-apply-applied"
        const val APPLY_RESULT_FAILED_MANIFEST: String = "patch-apply-failed"
        const val APPLY_RESULT_IGNORED_MANIFEST: String = "patch-apply-ignored"
    }
}

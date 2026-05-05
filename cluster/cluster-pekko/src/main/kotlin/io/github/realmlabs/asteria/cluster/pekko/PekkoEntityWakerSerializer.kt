package io.github.realmlabs.asteria.cluster.pekko

import org.apache.pekko.actor.ExtendedActorSystem
import org.apache.pekko.serialization.SerializerWithStringManifest
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.Serializable

/**
 * Cluster wire serializer for entity waker control and status messages.
 *
 * GM/control calls go through the singleton proxy, so these messages can cross node boundaries. Keep [IDENTIFIER],
 * manifests, field order, and target-id type tags stable across rolling upgrades.
 */
class PekkoEntityWakerSerializer(
    @Suppress("unused") private val system: ExtendedActorSystem,
) : SerializerWithStringManifest() {
    override fun identifier(): Int = IDENTIFIER

    override fun manifest(o: Any): String {
        return when (o) {
            is PekkoEntityWakerCommand.Reconcile -> RECONCILE_MANIFEST
            is PekkoEntityWakerCommand.WakeTargets -> WAKE_TARGETS_MANIFEST
            is PekkoEntityWakerCommand.CancelTargets -> CANCEL_TARGETS_MANIFEST
            is PekkoEntityWakerCommand.GetStatus -> GET_STATUS_MANIFEST
            is PekkoEntityWakerStatus -> STATUS_MANIFEST
            else -> error("unsupported entity waker message ${o::class.qualifiedName}")
        }
    }

    override fun toBinary(o: Any): ByteArray {
        return encode {
            when (o) {
                is PekkoEntityWakerCommand.Reconcile -> Unit
                is PekkoEntityWakerCommand.WakeTargets -> writeTargetCommand(o.taskName, o.targetIds)
                is PekkoEntityWakerCommand.CancelTargets -> writeTargetCommand(o.taskName, o.targetIds)
                is PekkoEntityWakerCommand.GetStatus -> writeGetStatus(o)
                is PekkoEntityWakerStatus -> writeStatus(o)
                else -> error("unsupported entity waker message ${o::class.qualifiedName}")
            }
        }
    }

    override fun fromBinary(bytes: ByteArray, manifest: String): Any {
        return decode(bytes) {
            when (manifest) {
                RECONCILE_MANIFEST -> PekkoEntityWakerCommand.Reconcile
                WAKE_TARGETS_MANIFEST -> {
                    val (taskName, targetIds) = readTargetCommand()
                    PekkoEntityWakerCommand.WakeTargets(taskName, targetIds)
                }

                CANCEL_TARGETS_MANIFEST -> {
                    val (taskName, targetIds) = readTargetCommand()
                    PekkoEntityWakerCommand.CancelTargets(taskName, targetIds)
                }

                GET_STATUS_MANIFEST -> readGetStatus()
                STATUS_MANIFEST -> readStatus()
                else -> error("unsupported entity waker message manifest $manifest")
            }
        }
    }

    private fun DataOutputStream.writeTargetCommand(
        taskName: String,
        targetIds: List<Serializable>,
    ) {
        writeString(taskName)
        writeInt(targetIds.size)
        targetIds.forEach { targetId -> writeTargetId(targetId) }
    }

    private fun DataInputStream.readTargetCommand(): Pair<String, List<Serializable>> {
        val taskName = readString()
        val targetIds = List(readInt()) { readTargetId() }
        return taskName to targetIds
    }

    private fun DataOutputStream.writeGetStatus(command: PekkoEntityWakerCommand.GetStatus) {
        writeNullableString(command.taskName)
        writeInt(command.targetLimit)
    }

    private fun DataInputStream.readGetStatus(): PekkoEntityWakerCommand.GetStatus {
        return PekkoEntityWakerCommand.GetStatus(
            taskName = readNullableString(),
            targetLimit = readInt(),
        )
    }

    private fun DataOutputStream.writeStatus(status: PekkoEntityWakerStatus) {
        writeInt(status.tasks.size)
        status.tasks.forEach { task ->
            writeString(task.name)
            writeString(task.entityKind)
            writeInt(task.desired)
            writeInt(task.pending)
            writeInt(task.inFlight)
            writeInt(task.retrying)
            writeInt(task.completed)
            writeInt(task.cancelled)
            writeInt(task.failed)
            writeInt(task.exhausted)
            writeInt(task.currentConcurrency)
            writeTargetSamples(task.targets)
        }
    }

    private fun DataInputStream.readStatus(): PekkoEntityWakerStatus {
        return PekkoEntityWakerStatus(
            tasks = List(readInt()) {
                PekkoEntityWakeTaskStatus(
                    name = readString(),
                    entityKind = readString(),
                    desired = readInt(),
                    pending = readInt(),
                    inFlight = readInt(),
                    retrying = readInt(),
                    completed = readInt(),
                    cancelled = readInt(),
                    failed = readInt(),
                    exhausted = readInt(),
                    currentConcurrency = readInt(),
                    targets = readTargetSamples(),
                )
            },
        )
    }

    private fun DataOutputStream.writeTargetSamples(samples: PekkoEntityWakeTargetStatusSamples) {
        writeStringList(samples.pending)
        writeStringList(samples.inFlight)
        writeStringList(samples.retrying)
        writeStringList(samples.completed)
        writeStringList(samples.cancelled)
        writeFailures(samples.failed)
        writeFailures(samples.exhausted)
    }

    private fun DataInputStream.readTargetSamples(): PekkoEntityWakeTargetStatusSamples {
        return PekkoEntityWakeTargetStatusSamples(
            pending = readStringList(),
            inFlight = readStringList(),
            retrying = readStringList(),
            completed = readStringList(),
            cancelled = readStringList(),
            failed = readFailures(),
            exhausted = readFailures(),
        )
    }

    private fun DataOutputStream.writeFailures(failures: List<PekkoEntityWakeFailureStatus>) {
        writeInt(failures.size)
        failures.forEach { failure ->
            writeString(failure.targetId)
            writeInt(failure.attempts)
            writeNullableString(failure.message)
        }
    }

    private fun DataInputStream.readFailures(): List<PekkoEntityWakeFailureStatus> {
        return List(readInt()) {
            PekkoEntityWakeFailureStatus(
                targetId = readString(),
                attempts = readInt(),
                message = readNullableString(),
            )
        }
    }

    private fun DataOutputStream.writeStringList(values: List<String>) {
        writeInt(values.size)
        values.forEach { value -> writeString(value) }
    }

    private fun DataInputStream.readStringList(): List<String> {
        return List(readInt()) { readString() }
    }

    private fun DataOutputStream.writeTargetId(value: Serializable) {
        when (value) {
            is String -> {
                writeByte(TARGET_STRING)
                writeString(value)
            }

            is Long -> {
                writeByte(TARGET_LONG)
                writeLong(value)
            }

            is Int -> {
                writeByte(TARGET_INT)
                writeInt(value)
            }

            else -> error("unsupported entity wake target id type ${value::class.qualifiedName}; use String, Long, or Int")
        }
    }

    private fun DataInputStream.readTargetId(): Serializable {
        return when (val tag = readByte().toInt()) {
            TARGET_STRING -> readString()
            TARGET_LONG -> readLong()
            TARGET_INT -> readInt()
            else -> error("unsupported entity wake target id type tag $tag")
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
        const val IDENTIFIER: Int = 233_120_002
        const val RECONCILE_MANIFEST: String = "entity-waker-reconcile"
        const val WAKE_TARGETS_MANIFEST: String = "entity-waker-wake-targets"
        const val CANCEL_TARGETS_MANIFEST: String = "entity-waker-cancel-targets"
        const val GET_STATUS_MANIFEST: String = "entity-waker-get-status"
        const val STATUS_MANIFEST: String = "entity-waker-status"
        private const val TARGET_STRING: Int = 1
        private const val TARGET_LONG: Int = 2
        private const val TARGET_INT: Int = 3
    }
}

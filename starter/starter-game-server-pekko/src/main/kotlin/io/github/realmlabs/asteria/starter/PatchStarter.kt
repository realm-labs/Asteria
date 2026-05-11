package io.github.realmlabs.asteria.starter

import io.github.realmlabs.asteria.core.AsteriaApplicationBuilder
import io.github.realmlabs.asteria.patch.*
import io.github.realmlabs.asteria.patch.jar.JarRuntimePatchPluginResolver
import io.github.realmlabs.asteria.patch.pekko.PekkoPatchControlModule
import io.github.realmlabs.asteria.patch.pekko.PekkoPatchEnvironmentProvider
import java.nio.file.Path
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * Installs runtime patch support for a Pekko game node.
 *
 * This wires the local patch runtime, jar plugin resolver, per-node result repository, and Pekko cluster control
 * endpoint used by GM/control nodes. Production deployments should pass a shared [artifactStore], such as GridFS or
 * object storage, so every target node can load the same patch jar.
 */
fun AsteriaApplicationBuilder.runtimePatches(
    environment: PatchEnvironment,
    artifactStore: PatchArtifactStore,
    repository: RuntimePatchRepository = InMemoryRuntimePatchRepository(),
    nodeResults: RuntimePatchNodeResultRepository = InMemoryRuntimePatchNodeResultRepository(),
    cacheDirectory: Path? = null,
    applyOnStart: Boolean = true,
    expireIncompatibleOnStart: Boolean = true,
    reconcileInterval: Duration? = 1.minutes,
    reconcileTrigger: PatchReconcileTrigger? = null,
    controlTimeout: Duration = 10.seconds,
) {
    install(
        PatchModule {
            this.environment = environment
            repository(repository)
            nodeResults(nodeResults)
            resolver(JarRuntimePatchPluginResolver(artifactStore, cacheDirectory = cacheDirectory))
            this.applyOnStart = applyOnStart
            this.expireIncompatibleOnStart = expireIncompatibleOnStart
            this.reconcileInterval = reconcileInterval
            reconcileTrigger?.let(::reconcileTrigger)
        },
    )
    install(PekkoPatchControlModule(controlTimeout))
}

fun AsteriaApplicationBuilder.runtimePatches(
    version: String,
    artifactStore: PatchArtifactStore,
    repository: RuntimePatchRepository = InMemoryRuntimePatchRepository(),
    nodeResults: RuntimePatchNodeResultRepository = InMemoryRuntimePatchNodeResultRepository(),
    cacheDirectory: Path? = null,
    applyOnStart: Boolean = true,
    expireIncompatibleOnStart: Boolean = true,
    reconcileInterval: Duration? = 1.minutes,
    reconcileTrigger: PatchReconcileTrigger? = null,
    controlTimeout: Duration = 10.seconds,
) {
    install(
        PatchModule {
            environment(PekkoPatchEnvironmentProvider(version))
            repository(repository)
            nodeResults(nodeResults)
            resolver(JarRuntimePatchPluginResolver(artifactStore, cacheDirectory = cacheDirectory))
            this.applyOnStart = applyOnStart
            this.expireIncompatibleOnStart = expireIncompatibleOnStart
            this.reconcileInterval = reconcileInterval
            reconcileTrigger?.let(::reconcileTrigger)
        },
    )
    install(PekkoPatchControlModule(controlTimeout))
}

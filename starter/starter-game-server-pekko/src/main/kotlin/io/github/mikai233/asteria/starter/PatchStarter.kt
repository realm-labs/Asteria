package io.github.mikai233.asteria.starter

import io.github.mikai233.asteria.patch.InMemoryRuntimePatchNodeResultRepository
import io.github.mikai233.asteria.patch.InMemoryRuntimePatchRepository
import io.github.mikai233.asteria.patch.PatchArtifactStore
import io.github.mikai233.asteria.patch.PatchEnvironment
import io.github.mikai233.asteria.patch.PatchModule
import io.github.mikai233.asteria.patch.RuntimePatchNodeResultRepository
import io.github.mikai233.asteria.patch.RuntimePatchRepository
import io.github.mikai233.asteria.patch.jar.JarRuntimePatchPluginResolver
import io.github.mikai233.asteria.patch.pekko.PekkoPatchControlModule
import io.github.mikai233.asteria.core.AsteriaApplicationBuilder
import java.nio.file.Path
import kotlin.time.Duration
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
        },
    )
    install(PekkoPatchControlModule(controlTimeout))
}

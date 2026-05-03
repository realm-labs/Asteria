package io.github.realmlabs.asteria.gm.patch.spring

import io.github.realmlabs.asteria.gm.patch.DefaultGmPatchOperations
import io.github.realmlabs.asteria.gm.patch.GmPatchOperations
import io.github.realmlabs.asteria.gm.spring.GmEndpointSupport
import io.github.realmlabs.asteria.gm.spring.GmSpringAutoConfiguration
import io.github.realmlabs.asteria.patch.*
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import java.nio.file.Path

@AutoConfiguration(after = [GmSpringAutoConfiguration::class])
@ConditionalOnProperty(
    prefix = "asteria.gm.patch.web",
    name = ["enabled"],
    havingValue = "true",
    matchIfMissing = true,
)
class GmPatchSpringAutoConfiguration {
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBean(RuntimePatchRepository::class, PatchApplicationService::class)
    fun gmPatchOperations(
        repository: RuntimePatchRepository,
        applications: PatchApplicationService,
        artifacts: ObjectProvider<WritablePatchArtifactStore>,
        clusterApplications: ObjectProvider<PatchClusterApplicationService>,
        nodes: ObjectProvider<PatchNodeProvider>,
    ): GmPatchOperations {
        return DefaultGmPatchOperations(
            repository = repository,
            applications = applications,
            artifacts = artifacts.getIfAvailable(),
            clusterApplications = clusterApplications.getIfAvailable(),
            nodes = nodes.getIfAvailable(),
        )
    }

    @Bean
    @ConditionalOnMissingBean(WritablePatchArtifactStore::class)
    @ConditionalOnProperty(
        prefix = "asteria.gm.patch.artifacts.local",
        name = ["directory"],
    )
    fun gmPatchArtifactStore(
        @Value($$"${asteria.gm.patch.artifacts.local.directory}") directory: String,
    ): WritablePatchArtifactStore {
        return LocalFilePatchArtifactStore(Path.of(directory))
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBean(GmPatchOperations::class)
    fun gmPatchController(
        operations: GmPatchOperations,
        endpoints: GmEndpointSupport,
    ): GmPatchController {
        return GmPatchController(operations, endpoints)
    }
}

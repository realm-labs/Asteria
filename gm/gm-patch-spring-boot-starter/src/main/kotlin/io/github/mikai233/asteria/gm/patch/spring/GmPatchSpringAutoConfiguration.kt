package io.github.mikai233.asteria.gm.patch.spring

import io.github.mikai233.asteria.gm.patch.DefaultGmPatchOperations
import io.github.mikai233.asteria.gm.patch.GmPatchOperations
import io.github.mikai233.asteria.gm.spring.GmEndpointSupport
import io.github.mikai233.asteria.gm.spring.GmSpringAutoConfiguration
import io.github.mikai233.asteria.patch.PatchApplicationService
import io.github.mikai233.asteria.patch.RuntimePatchRepository
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean

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
    ): GmPatchOperations {
        return DefaultGmPatchOperations(repository, applications)
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

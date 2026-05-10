package io.github.realmlabs.asteria.gm.script.spring

import io.github.realmlabs.asteria.cluster.pekko.EntityShardRegistry
import io.github.realmlabs.asteria.cluster.pekko.SingletonActorRegistry
import io.github.realmlabs.asteria.gm.script.*
import io.github.realmlabs.asteria.gm.spring.GmEndpointSupport
import io.github.realmlabs.asteria.gm.spring.GmSpringAutoConfiguration
import io.github.realmlabs.asteria.script.ScriptEngineRegistry
import io.github.realmlabs.asteria.script.job.ScriptJobService
import io.github.realmlabs.asteria.script.job.spring.ScriptJobSpringAutoConfiguration
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean

/**
 * Auto-configuration for script GM HTTP APIs.
 */
@AutoConfiguration(after = [GmSpringAutoConfiguration::class, ScriptJobSpringAutoConfiguration::class])
@ConditionalOnProperty(
    prefix = "asteria.gm.script.web",
    name = ["enabled"],
    havingValue = "true",
    matchIfMissing = true,
)
class GmScriptSpringAutoConfiguration {
    @Bean
    @ConditionalOnMissingBean
    fun gmScriptTargetValidator(): GmScriptTargetValidator {
        return BasicGmScriptTargetValidator()
    }

    @Bean
    @ConditionalOnMissingBean
    fun gmScriptRouteRegistryView(
        entityShards: ObjectProvider<EntityShardRegistry>,
        singletonActors: ObjectProvider<SingletonActorRegistry>,
    ): GmScriptRouteRegistryView {
        return GmScriptRouteRegistryView(
            entityShards = entityShards.ifAvailable,
            singletonActors = singletonActors.ifAvailable,
        )
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBean(ScriptJobService::class)
    fun scriptJobGmScriptOperations(jobs: ScriptJobService): GmScriptOperations {
        return ScriptJobGmScriptOperations(jobs)
    }

    @Bean
    @ConditionalOnMissingBean
    fun gmScriptMetadataProvider(
        engines: ObjectProvider<ScriptEngineRegistry>,
        routeRegistry: GmScriptRouteRegistryView,
        templates: ObjectProvider<GmScriptTemplateCatalog>,
    ): GmScriptMetadataProvider {
        return GmScriptMetadataProvider(
            engineRegistry = engines.ifAvailable,
            routeRegistry = routeRegistry,
            templateCatalog = templates.ifAvailable,
        )
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBean(GmScriptOperations::class)
    fun gmScriptController(
        scripts: GmScriptOperations,
        validator: GmScriptTargetValidator,
        endpointSupport: GmEndpointSupport,
        metadataProvider: GmScriptMetadataProvider,
        routeRegistry: GmScriptRouteRegistryView,
    ): GmScriptController {
        return GmScriptController(scripts, validator, endpointSupport, metadataProvider, routeRegistry)
    }
}

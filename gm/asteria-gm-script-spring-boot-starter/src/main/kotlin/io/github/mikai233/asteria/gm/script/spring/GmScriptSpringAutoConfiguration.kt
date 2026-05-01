package io.github.mikai233.asteria.gm.script.spring

import io.github.mikai233.asteria.gm.script.BasicGmScriptTargetValidator
import io.github.mikai233.asteria.gm.script.GmScriptOperations
import io.github.mikai233.asteria.gm.script.GmScriptMetadataProvider
import io.github.mikai233.asteria.gm.script.GmScriptTemplateCatalog
import io.github.mikai233.asteria.gm.script.GmScriptTargetCatalog
import io.github.mikai233.asteria.gm.script.GmScriptTargetValidator
import io.github.mikai233.asteria.gm.script.ScriptJobGmScriptOperations
import io.github.mikai233.asteria.gm.spring.GmEndpointSupport
import io.github.mikai233.asteria.gm.spring.GmSpringAutoConfiguration
import io.github.mikai233.asteria.script.ScriptEngineRegistry
import io.github.mikai233.asteria.script.job.ScriptJobService
import io.github.mikai233.asteria.script.job.spring.ScriptJobSpringAutoConfiguration
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.beans.factory.ObjectProvider
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
    fun gmScriptTargetValidator(
        catalog: ObjectProvider<GmScriptTargetCatalog>,
    ): GmScriptTargetValidator {
        return BasicGmScriptTargetValidator(catalog = catalog.ifAvailable)
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
        catalog: ObjectProvider<GmScriptTargetCatalog>,
        templates: ObjectProvider<GmScriptTemplateCatalog>,
    ): GmScriptMetadataProvider {
        return GmScriptMetadataProvider(
            engineRegistry = engines.ifAvailable,
            targetCatalog = catalog.ifAvailable,
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
    ): GmScriptController {
        return GmScriptController(scripts, validator, endpointSupport, metadataProvider)
    }
}

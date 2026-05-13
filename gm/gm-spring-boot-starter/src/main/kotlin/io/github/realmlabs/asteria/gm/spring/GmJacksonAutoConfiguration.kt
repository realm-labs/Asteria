package io.github.realmlabs.asteria.gm.spring

import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration
import org.springframework.context.annotation.Bean
import tools.jackson.databind.JacksonModule
import tools.jackson.module.kotlin.KotlinModule

/**
 * Jackson 3 Kotlin support required by GM HTTP DTOs.
 *
 * This configuration is intentionally independent from the GM endpoint enable switch. Applications may reuse GM DTOs
 * or sub-starter controllers even when the feature registry endpoints are disabled.
 */
@AutoConfiguration(before = [JacksonAutoConfiguration::class])
@ConditionalOnClass(JacksonModule::class, KotlinModule::class)
class GmJacksonAutoConfiguration {
    @Bean
    @ConditionalOnMissingBean(KotlinModule::class)
    fun gmJacksonKotlinModule(): KotlinModule {
        return KotlinModule.Builder().build()
    }
}

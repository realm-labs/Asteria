package io.github.realmlabs.asteria.gm.spring

import io.github.realmlabs.asteria.gm.core.GmAction
import io.github.realmlabs.asteria.gm.core.GmActionDescriptor
import io.github.realmlabs.asteria.gm.core.GmFeatureDescriptor
import io.github.realmlabs.asteria.gm.core.GmFeatureId
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.KotlinModule
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

class GmSpringJacksonAutoConfigurationTest {
    private val runner = ApplicationContextRunner()
        .withConfiguration(
            AutoConfigurations.of(
                JacksonAutoConfiguration::class.java,
                GmJacksonAutoConfiguration::class.java,
                GmSpringAutoConfiguration::class.java,
            ),
        )

    @Test
    fun `registers jackson 3 kotlin module for GM dto serialization`(): Unit {
        runner.run { context ->
            val mapper = context.getBean(JsonMapper::class.java)
            val json = mapper.writeValueAsString(
                GmFeatureDescriptor(
                    id = GmFeatureId("script"),
                    name = "Script",
                    actions = listOf(GmActionDescriptor(GmAction("script.submit"), "Submit script")),
                ),
            )
            val tree = mapper.readTree(json)

            assertEquals("script", tree["id"].stringValue())
            assertEquals("Script", tree["name"].stringValue())
            assertEquals("script.submit", tree["actions"][0]["key"].stringValue())
            assertEquals("Submit script", tree["actions"][0]["name"].stringValue())
        }
    }

    @Test
    fun `does not replace application provided kotlin module`(): Unit {
        runner.withUserConfiguration(CustomKotlinModuleConfiguration::class.java).run { context ->
            val modules = context.getBeansOfType(KotlinModule::class.java)

            assertEquals(setOf("customKotlinModule"), modules.keys)
            assertSame(context.getBean("customKotlinModule"), modules.getValue("customKotlinModule"))
        }
    }

    @Test
    fun `registers kotlin module when GM endpoints are disabled`(): Unit {
        runner.withPropertyValues("asteria.gm.enabled=false").run { context ->
            val modules = context.getBeansOfType(KotlinModule::class.java)

            assertEquals(setOf("gmJacksonKotlinModule"), modules.keys)
        }
    }

    @Configuration(proxyBeanMethods = false)
    private class CustomKotlinModuleConfiguration {
        @Bean
        fun customKotlinModule(): KotlinModule {
            return KotlinModule.Builder().build()
        }
    }
}

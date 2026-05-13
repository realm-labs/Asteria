package io.github.realmlabs.asteria.gm.config.spring

import io.github.realmlabs.asteria.cluster.config.ClusterConfigReloadTarget
import io.github.realmlabs.asteria.gm.spring.GmJacksonAutoConfiguration
import io.github.realmlabs.asteria.gm.spring.GmSpringAutoConfiguration
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import tools.jackson.databind.json.JsonMapper
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class GmClusterConfigReloadJacksonTest {
    private val runner = ApplicationContextRunner()
        .withConfiguration(
            AutoConfigurations.of(
                JacksonAutoConfiguration::class.java,
                GmJacksonAutoConfiguration::class.java,
                GmSpringAutoConfiguration::class.java,
            ),
        )

    @Test
    fun `jackson 3 kotlin module preserves explicit reload target fields`(): Unit {
        runner.run { context ->
            val mapper = context.getBean(JsonMapper::class.java)
            val request = mapper.readValue(
                """{"target":"role","role":"Gm"}""",
                GmClusterConfigReloadHttpRequest::class.java,
            )

            val target = assertIs<ClusterConfigReloadTarget.Role>(request.reloadTarget())
            assertEquals("Gm", target.role)
        }
    }
}

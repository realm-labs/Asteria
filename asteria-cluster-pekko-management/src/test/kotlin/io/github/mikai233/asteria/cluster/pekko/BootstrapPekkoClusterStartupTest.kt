package io.github.mikai233.asteria.cluster.pekko

import com.typesafe.config.ConfigFactory
import io.github.mikai233.asteria.core.RoleKey
import kotlin.test.Test
import kotlin.test.assertEquals

class BootstrapPekkoClusterStartupTest {
    @Test
    fun `bootstrap runtime config resolves roles and disables static seed nodes`() {
        val config = bootstrapRuntimeConfig(setOf(RoleKey("player")))
            .withFallback(ConfigFactory.parseString("pekko.remote.artery.canonical.port = 25520"))

        assertEquals(listOf("player"), config.getStringList("pekko.cluster.roles"))
        assertEquals(emptyList(), config.getStringList("pekko.cluster.seed-nodes"))
        assertEquals(25520, config.getInt("pekko.remote.artery.canonical.port"))
    }
}

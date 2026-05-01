package io.github.mikai233.asteria.cluster.pekko

import kotlin.test.Test
import kotlin.test.assertEquals

class KubernetesApiPekkoClusterStartupTest {
    @Test
    fun `kubernetes config uses kubernetes api discovery`() {
        val config = kubernetesApiConfig(
            serviceName = "asteria-gateway",
            namespace = "games",
            podLabelSelector = "app=asteria-gateway",
            requiredContactPointNr = 3,
        )

        assertEquals(
            "kubernetes-api",
            config.getString("pekko.management.cluster.bootstrap.contact-point-discovery.discovery-method"),
        )
        assertEquals(
            "asteria-gateway",
            config.getString("pekko.management.cluster.bootstrap.contact-point-discovery.service-name"),
        )
        assertEquals(
            3,
            config.getInt("pekko.management.cluster.bootstrap.contact-point-discovery.required-contact-point-nr"),
        )
        assertEquals("games", config.getString("pekko.discovery.kubernetes-api.pod-namespace"))
        assertEquals("app=asteria-gateway", config.getString("pekko.discovery.kubernetes-api.pod-label-selector"))
    }
}

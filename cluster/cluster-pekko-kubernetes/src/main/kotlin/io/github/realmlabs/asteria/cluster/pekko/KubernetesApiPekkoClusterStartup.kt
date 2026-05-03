package io.github.realmlabs.asteria.cluster.pekko

import com.typesafe.config.Config
import com.typesafe.config.ConfigFactory
import io.github.realmlabs.asteria.core.RoleKey

/**
 * Convenience startup for Pekko Discovery Kubernetes API.
 *
 * Applications still own deployment-specific values such as canonical host/port, bind address, and
 * service account permissions. Pass them through config or application.conf.
 *
 * [serviceName], [namespace], [podLabelSelector], and [requiredContactPointNr] map directly to
 * Pekko Management / Kubernetes API discovery settings.
 */
class KubernetesApiPekkoClusterStartup(
    roles: Set<RoleKey>,
    serviceName: String? = null,
    namespace: String? = null,
    podLabelSelector: String? = null,
    requiredContactPointNr: Int? = null,
    config: Config = ConfigFactory.empty(),
) : PekkoClusterStartup by BootstrapPekkoClusterStartup(
    roles = roles,
    config = kubernetesApiConfig(
        serviceName = serviceName,
        namespace = namespace,
        podLabelSelector = podLabelSelector,
        requiredContactPointNr = requiredContactPointNr,
    ).withFallback(config),
)

internal fun kubernetesApiConfig(
    serviceName: String?,
    namespace: String?,
    podLabelSelector: String?,
    requiredContactPointNr: Int?,
): Config {
    val values = linkedMapOf<String, Any>(
        "pekko.management.cluster.bootstrap.contact-point-discovery.discovery-method" to "kubernetes-api",
    )
    serviceName?.let {
        values["pekko.management.cluster.bootstrap.contact-point-discovery.service-name"] = it
    }
    namespace?.let {
        values["pekko.discovery.kubernetes-api.pod-namespace"] = it
    }
    podLabelSelector?.let {
        values["pekko.discovery.kubernetes-api.pod-label-selector"] = it
    }
    requiredContactPointNr?.let {
        values["pekko.management.cluster.bootstrap.contact-point-discovery.required-contact-point-nr"] = it
    }
    return ConfigFactory.parseMap(values)
}

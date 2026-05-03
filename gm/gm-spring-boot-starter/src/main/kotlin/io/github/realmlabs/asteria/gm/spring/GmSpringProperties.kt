package io.github.realmlabs.asteria.gm.spring

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "asteria.gm")
class GmSpringProperties {
    var enabled: Boolean = true
}

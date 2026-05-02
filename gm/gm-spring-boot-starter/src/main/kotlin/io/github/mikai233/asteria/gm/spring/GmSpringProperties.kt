package io.github.mikai233.asteria.gm.spring

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "asteria.gm")
class GmSpringProperties {
    var enabled: Boolean = true
}

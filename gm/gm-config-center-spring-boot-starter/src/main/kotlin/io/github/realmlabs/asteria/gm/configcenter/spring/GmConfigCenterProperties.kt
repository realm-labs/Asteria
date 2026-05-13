package io.github.realmlabs.asteria.gm.configcenter.spring

import io.github.realmlabs.asteria.gm.configcenter.ConfigCenterBrowser
import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * Spring configuration for the ConfigCenter GM browser.
 */
@ConfigurationProperties(prefix = "asteria.gm.config-center")
class GmConfigCenterProperties {
    var web: Web = Web()
    var allowedRoots: List<String> = listOf("/")
    var denyPatterns: List<String> = emptyList()
    var previewLimitBytes: Int = ConfigCenterBrowser.DefaultPreviewLimitBytes

    class Web {
        var enabled: Boolean = true
    }
}

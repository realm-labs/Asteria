package io.github.realmlabs.asteria.gm.configcenter

import io.github.realmlabs.asteria.config.center.ConfigPath
import io.github.realmlabs.asteria.config.center.configPath

/**
 * Read boundary for ConfigCenter browsing.
 */
class ConfigCenterBrowserAccessPolicy(
    allowedRoots: Iterable<ConfigPath> = listOf(ConfigPath.Root),
    private val denyPatterns: List<Regex> = emptyList(),
) {
    private val allowedRoots: List<ConfigPath> = allowedRoots.toList()

    init {
        require(this.allowedRoots.isNotEmpty()) { "allowed roots must not be empty" }
    }

    /**
     * Throws when [path] is outside allowed roots or matches a denied pattern.
     */
    fun checkReadable(path: ConfigPath) {
        if (allowedRoots.none { root -> path == root || path.isDescendantOf(root) }) {
            throw ConfigCenterBrowserAccessException("config center path is not allowed")
        }
        if (denyPatterns.any { pattern -> pattern.containsMatchIn(path.value) }) {
            throw ConfigCenterBrowserAccessException("config center path is not allowed")
        }
    }

    companion object {
        fun fromStrings(
            allowedRoots: Iterable<String>,
            denyPatterns: Iterable<String>,
        ): ConfigCenterBrowserAccessPolicy {
            return ConfigCenterBrowserAccessPolicy(
                allowedRoots = allowedRoots.map(::configPath),
                denyPatterns = denyPatterns.map(::Regex).toList(),
            )
        }
    }
}

package io.github.mikai233.asteria.gm.config

import io.github.mikai233.asteria.gm.core.GmFeature
import io.github.mikai233.asteria.gm.core.GmFeatureDescriptor
import io.github.mikai233.asteria.gm.core.GmFeatureId
import io.github.mikai233.asteria.gm.core.GmMenuItem
import io.github.mikai233.asteria.gm.core.GmPermission
import io.github.mikai233.asteria.gm.core.GmPermissionKey
import io.github.mikai233.asteria.gm.core.GmRoute

/**
 * Permission keys contributed by the config GM feature.
 */
object GmConfigPermissions {
    val Read: GmPermissionKey = GmPermissionKey("gm.config.read")
    val Reload: GmPermissionKey = GmPermissionKey("gm.config.reload")
    val Export: GmPermissionKey = GmPermissionKey("gm.config.export")
    val SensitiveRead: GmPermissionKey = GmPermissionKey("gm.config.sensitive-read")
}

/**
 * Built-in GM feature for inspecting the config snapshot used by the running game server.
 */
class GmConfigFeature : GmFeature {
    override val descriptor: GmFeatureDescriptor = GmFeatureDescriptor(
        id = GmFeatureId("asteria.config"),
        name = "Config",
        description = "Browse and diagnose loaded game config tables.",
        permissions = listOf(
            GmPermission(GmConfigPermissions.Read, "Read config tables"),
            GmPermission(GmConfigPermissions.Reload, "Reload config snapshot", highRisk = true),
            GmPermission(GmConfigPermissions.Export, "Export config data"),
            GmPermission(GmConfigPermissions.SensitiveRead, "Read sensitive config fields", highRisk = true),
        ),
        menus = listOf(
            GmMenuItem(
                id = "asteria.config",
                title = "Config",
                route = "/config",
                permission = GmConfigPermissions.Read,
                order = 300,
            ),
        ),
        routes = listOf(
            GmRoute(
                id = "asteria.config.tables",
                path = "/config",
                component = "asteria/config/ConfigTables",
                permission = GmConfigPermissions.Read,
            ),
        ),
    )
}

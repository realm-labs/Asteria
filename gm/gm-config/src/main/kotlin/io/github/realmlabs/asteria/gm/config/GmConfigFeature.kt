package io.github.realmlabs.asteria.gm.config

import io.github.realmlabs.asteria.gm.core.*

/**
 * Action keys contributed by the config GM feature.
 */
object GmConfigActions {
    val Read: GmAction = GmAction("gm.config.read")
    val Reload: GmAction = GmAction("gm.config.reload")
    val Export: GmAction = GmAction("gm.config.export")
    val SensitiveRead: GmAction = GmAction("gm.config.sensitive-read")
}

/**
 * Built-in GM feature for inspecting the config snapshot used by the running game server.
 */
class GmConfigFeature : GmFeature {
    override val descriptor: GmFeatureDescriptor = GmFeatureDescriptor(
        id = GmFeatureId("asteria.config"),
        name = "Config",
        description = "Browse and diagnose loaded game config tables.",
        actions = listOf(
            GmActionDescriptor(GmConfigActions.Read, "Read config tables"),
            GmActionDescriptor(GmConfigActions.Reload, "Reload config snapshot", risk = GmRiskLevel.High),
            GmActionDescriptor(GmConfigActions.Export, "Export config data"),
            GmActionDescriptor(GmConfigActions.SensitiveRead, "Read sensitive config fields", risk = GmRiskLevel.High),
        ),
        menus = listOf(
            GmMenuItem(
                id = "asteria.config",
                title = "Config",
                route = "/config",
                action = GmConfigActions.Read,
                order = 300,
            ),
        ),
        routes = listOf(
            GmRoute(
                id = "asteria.config.tables",
                path = "/config",
                component = "asteria/config/ConfigTables",
                action = GmConfigActions.Read,
            ),
        ),
    )
}

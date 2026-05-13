package io.github.realmlabs.asteria.gm.configcenter

import io.github.realmlabs.asteria.gm.core.GmAction
import io.github.realmlabs.asteria.gm.core.GmActionDescriptor
import io.github.realmlabs.asteria.gm.core.GmFeature
import io.github.realmlabs.asteria.gm.core.GmFeatureDescriptor
import io.github.realmlabs.asteria.gm.core.GmFeatureId
import io.github.realmlabs.asteria.gm.core.GmMenuItem
import io.github.realmlabs.asteria.gm.core.GmRoute

/**
 * Action keys contributed by the ConfigCenter GM feature.
 */
object GmConfigCenterActions {
    val Read: GmAction = GmAction("gm.config-center.read")
}

/**
 * Built-in GM feature for browsing raw entries stored in ConfigCenter.
 */
class GmConfigCenterFeature : GmFeature {
    override val descriptor: GmFeatureDescriptor = GmFeatureDescriptor(
        id = GmFeatureId("asteria.config-center"),
        name = "Config Center",
        description = "Browse raw ConfigStore entries and revisions.",
        actions = listOf(
            GmActionDescriptor(GmConfigCenterActions.Read, "Read ConfigCenter entries"),
        ),
        menus = listOf(
            GmMenuItem(
                id = "asteria.config-center",
                title = "Config Center",
                route = "/config-center",
                action = GmConfigCenterActions.Read,
                order = 310,
            ),
        ),
        routes = listOf(
            GmRoute(
                id = "asteria.config-center.browser",
                path = "/config-center",
                component = "asteria/config-center/ConfigStoreBrowser",
                action = GmConfigCenterActions.Read,
            ),
        ),
    )
}

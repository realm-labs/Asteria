package io.github.realmlabs.asteria.gm.patch

import io.github.realmlabs.asteria.gm.core.*

/**
 * Action keys contributed by the patch GM feature.
 */
object GmPatchActions {
    val Read: GmAction = GmAction("gm.patch.read")
    val Create: GmAction = GmAction("gm.patch.create")
    val Apply: GmAction = GmAction("gm.patch.apply")
    val Expire: GmAction = GmAction("gm.patch.expire")
    val Disable: GmAction = GmAction("gm.patch.disable")
}

/**
 * Built-in GM feature descriptor for runtime patch administration.
 */
class GmPatchFeature : GmFeature {
    override val descriptor: GmFeatureDescriptor = GmFeatureDescriptor(
        id = GmFeatureId("asteria.patch"),
        name = "Patch",
        description = "Manage runtime patches applied to game server nodes.",
        actions = listOf(
            GmActionDescriptor(GmPatchActions.Read, "Read runtime patches"),
            GmActionDescriptor(GmPatchActions.Create, "Create runtime patches", risk = GmRiskLevel.High),
            GmActionDescriptor(GmPatchActions.Apply, "Apply runtime patches", risk = GmRiskLevel.High),
            GmActionDescriptor(GmPatchActions.Expire, "Expire incompatible runtime patches", risk = GmRiskLevel.High),
            GmActionDescriptor(GmPatchActions.Disable, "Disable runtime patches", risk = GmRiskLevel.High),
        ),
        menus = listOf(
            GmMenuItem(
                id = "asteria.patch",
                title = "Patch",
                route = "/patches",
                action = GmPatchActions.Read,
                order = 500,
            ),
        ),
        routes = listOf(
            GmRoute(
                id = "asteria.patch.list",
                path = "/patches",
                component = "asteria/patch/PatchList",
                action = GmPatchActions.Read,
            ),
        ),
    )
}

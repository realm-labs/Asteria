package io.github.mikai233.asteria.gm.patch

import io.github.mikai233.asteria.gm.core.*

object GmPatchPermissions {
    val Read: GmPermissionKey = GmPermissionKey("gm.patch.read")
    val Create: GmPermissionKey = GmPermissionKey("gm.patch.create")
    val Apply: GmPermissionKey = GmPermissionKey("gm.patch.apply")
    val Expire: GmPermissionKey = GmPermissionKey("gm.patch.expire")
    val Disable: GmPermissionKey = GmPermissionKey("gm.patch.disable")
}

class GmPatchFeature : GmFeature {
    override val descriptor: GmFeatureDescriptor = GmFeatureDescriptor(
        id = GmFeatureId("asteria.patch"),
        name = "Patch",
        description = "Manage runtime patches applied to game server nodes.",
        permissions = listOf(
            GmPermission(GmPatchPermissions.Read, "Read runtime patches"),
            GmPermission(GmPatchPermissions.Create, "Create runtime patches", highRisk = true),
            GmPermission(GmPatchPermissions.Apply, "Apply runtime patches", highRisk = true),
            GmPermission(GmPatchPermissions.Expire, "Expire incompatible runtime patches", highRisk = true),
            GmPermission(GmPatchPermissions.Disable, "Disable runtime patches", highRisk = true),
        ),
        menus = listOf(
            GmMenuItem(
                id = "asteria.patch",
                title = "Patch",
                route = "/patches",
                permission = GmPatchPermissions.Read,
                order = 500,
            ),
        ),
        routes = listOf(
            GmRoute(
                id = "asteria.patch.list",
                path = "/patches",
                component = "asteria/patch/PatchList",
                permission = GmPatchPermissions.Read,
            ),
        ),
    )
}

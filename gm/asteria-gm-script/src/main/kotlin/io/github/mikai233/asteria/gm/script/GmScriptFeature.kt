package io.github.mikai233.asteria.gm.script

import io.github.mikai233.asteria.gm.core.GmFeature
import io.github.mikai233.asteria.gm.core.GmFeatureDescriptor
import io.github.mikai233.asteria.gm.core.GmFeatureId
import io.github.mikai233.asteria.gm.core.GmMenuItem
import io.github.mikai233.asteria.gm.core.GmPermission
import io.github.mikai233.asteria.gm.core.GmPermissionKey
import io.github.mikai233.asteria.gm.core.GmRoute

/**
 * Permission keys contributed by the script GM feature.
 */
object GmScriptPermissions {
    val Read: GmPermissionKey = GmPermissionKey("gm.script.read")
    val Execute: GmPermissionKey = GmPermissionKey("gm.script.execute")
    val Cancel: GmPermissionKey = GmPermissionKey("gm.script.cancel")
}

/**
 * Built-in GM feature for script job submission and inspection.
 */
class GmScriptFeature : GmFeature {
    override val descriptor: GmFeatureDescriptor = GmFeatureDescriptor(
        id = GmFeatureId("asteria.script"),
        name = "Script Execution",
        description = "Submit, inspect, and control GM script jobs.",
        permissions = listOf(
            GmPermission(GmScriptPermissions.Read, "Read script jobs"),
            GmPermission(GmScriptPermissions.Execute, "Execute scripts", highRisk = true),
            GmPermission(GmScriptPermissions.Cancel, "Cancel scripts", highRisk = true),
        ),
        menus = listOf(
            GmMenuItem(
                id = "asteria.script",
                title = "Scripts",
                route = "/scripts",
                permission = GmScriptPermissions.Read,
                order = 100,
            ),
        ),
        routes = listOf(
            GmRoute(
                id = "asteria.script.jobs",
                path = "/scripts",
                component = "asteria/script/ScriptJobs",
                permission = GmScriptPermissions.Read,
            ),
        ),
    )
}

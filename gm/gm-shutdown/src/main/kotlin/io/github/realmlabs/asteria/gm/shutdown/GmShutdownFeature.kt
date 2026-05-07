package io.github.realmlabs.asteria.gm.shutdown

import io.github.realmlabs.asteria.gm.core.*

/**
 * Permission keys contributed by the shutdown GM feature.
 */
object GmShutdownPermissions {
    val Read: GmPermissionKey = GmPermissionKey("gm.shutdown.read")
    val Prepare: GmPermissionKey = GmPermissionKey("gm.shutdown.prepare")
    val Start: GmPermissionKey = GmPermissionKey("gm.shutdown.start")
    val Force: GmPermissionKey = GmPermissionKey("gm.shutdown.force")
}

/**
 * Runtime-neutral GM feature for business-side graceful shutdown orchestration.
 */
class GmShutdownFeature : GmFeature {
    override val descriptor: GmFeatureDescriptor = GmFeatureDescriptor(
        id = GmFeatureId("asteria.shutdown"),
        name = "Graceful Shutdown",
        description = "Prepare, run, and inspect business-side graceful shutdown plans.",
        permissions = listOf(
            GmPermission(
                key = GmShutdownPermissions.Read,
                name = "Read shutdown status",
                description = "Allows inspecting the current graceful shutdown state.",
            ),
            GmPermission(
                key = GmShutdownPermissions.Prepare,
                name = "Prepare shutdown",
                description = "Allows putting game services into a shutdown preparation state.",
                highRisk = true,
            ),
            GmPermission(
                key = GmShutdownPermissions.Start,
                name = "Start shutdown",
                description = "Allows running the graceful shutdown plan.",
                highRisk = true,
            ),
            GmPermission(
                key = GmShutdownPermissions.Force,
                name = "Force shutdown",
                description = "Allows bypassing normal graceful shutdown checks.",
                highRisk = true,
            ),
        ),
        menus = listOf(
            GmMenuItem(
                id = "asteria.shutdown",
                title = "Shutdown",
                route = "/shutdown",
                permission = GmShutdownPermissions.Read,
                order = 900,
            ),
        ),
        routes = listOf(
            GmRoute(
                id = "asteria.shutdown.status",
                path = "/shutdown",
                component = "asteria/shutdown/ShutdownStatus",
                permission = GmShutdownPermissions.Read,
            ),
        ),
    )
}

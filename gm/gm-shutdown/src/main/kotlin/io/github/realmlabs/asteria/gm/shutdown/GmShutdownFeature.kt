package io.github.realmlabs.asteria.gm.shutdown

import io.github.realmlabs.asteria.gm.core.*

/**
 * Action keys contributed by the shutdown GM feature.
 */
object GmShutdownActions {
    val Read: GmAction = GmAction("gm.shutdown.read")
    val Prepare: GmAction = GmAction("gm.shutdown.prepare")
    val Start: GmAction = GmAction("gm.shutdown.start")
    val Force: GmAction = GmAction("gm.shutdown.force")
}

/**
 * Runtime-neutral GM feature for business-side graceful shutdown orchestration.
 */
class GmShutdownFeature : GmFeature {
    override val descriptor: GmFeatureDescriptor = GmFeatureDescriptor(
        id = GmFeatureId("asteria.shutdown"),
        name = "Graceful Shutdown",
        description = "Prepare, run, and inspect business-side graceful shutdown plans.",
        actions = listOf(
            GmActionDescriptor(
                key = GmShutdownActions.Read,
                name = "Read shutdown status",
                description = "Allows inspecting the current graceful shutdown state.",
            ),
            GmActionDescriptor(
                key = GmShutdownActions.Prepare,
                name = "Prepare shutdown",
                description = "Allows putting game services into a shutdown preparation state.",
                risk = GmRiskLevel.High,
            ),
            GmActionDescriptor(
                key = GmShutdownActions.Start,
                name = "Start shutdown",
                description = "Allows running the graceful shutdown plan.",
                risk = GmRiskLevel.High,
            ),
            GmActionDescriptor(
                key = GmShutdownActions.Force,
                name = "Force shutdown",
                description = "Allows bypassing normal graceful shutdown checks.",
                risk = GmRiskLevel.High,
            ),
        ),
        menus = listOf(
            GmMenuItem(
                id = "asteria.shutdown",
                title = "Shutdown",
                route = "/shutdown",
                action = GmShutdownActions.Read,
                order = 900,
            ),
        ),
        routes = listOf(
            GmRoute(
                id = "asteria.shutdown.status",
                path = "/shutdown",
                component = "asteria/shutdown/ShutdownStatus",
                action = GmShutdownActions.Read,
            ),
        ),
    )
}

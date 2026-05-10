package io.github.realmlabs.asteria.gm.script

import io.github.realmlabs.asteria.gm.core.*

/**
 * Action keys contributed by the script GM feature.
 */
object GmScriptActions {
    val Read: GmAction = GmAction("gm.script.read")
    val Execute: GmAction = GmAction("gm.script.execute")
    val Cancel: GmAction = GmAction("gm.script.cancel")
}

/**
 * Built-in GM feature for script job submission and inspection.
 */
class GmScriptFeature : GmFeature {
    override val descriptor: GmFeatureDescriptor = GmFeatureDescriptor(
        id = GmFeatureId("asteria.script"),
        name = "Script Execution",
        description = "Submit, inspect, and control GM script jobs.",
        actions = listOf(
            GmActionDescriptor(GmScriptActions.Read, "Read script jobs"),
            GmActionDescriptor(GmScriptActions.Execute, "Execute scripts", risk = GmRiskLevel.High),
            GmActionDescriptor(GmScriptActions.Cancel, "Cancel scripts", risk = GmRiskLevel.High),
        ),
        menus = listOf(
            GmMenuItem(
                id = "asteria.script",
                title = "Scripts",
                route = "/scripts",
                action = GmScriptActions.Read,
                order = 100,
            ),
        ),
        routes = listOf(
            GmRoute(
                id = "asteria.script.jobs",
                path = "/scripts",
                component = "asteria/script/ScriptJobs",
                action = GmScriptActions.Read,
            ),
        ),
    )
}

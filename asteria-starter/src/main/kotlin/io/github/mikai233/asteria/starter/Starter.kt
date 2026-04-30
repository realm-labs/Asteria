package io.github.mikai233.asteria.starter

import io.github.mikai233.asteria.cluster.pekko.PekkoRuntimeModule
import io.github.mikai233.asteria.core.AsteriaApplication
import io.github.mikai233.asteria.core.AsteriaApplicationBuilder
import io.github.mikai233.asteria.core.AsteriaModule
import io.github.mikai233.asteria.core.ModuleContext
import io.github.mikai233.asteria.core.gameApplication
import io.github.mikai233.asteria.message.RouteRegistry
import io.github.mikai233.asteria.message.RouteRegistryBuilder

class RouteModule(
    private val registry: RouteRegistry,
) : AsteriaModule {
    override val name: String = "message-routes"

    override suspend fun install(context: ModuleContext) {
        context.services.register(RouteRegistry::class, registry)
    }
}

fun AsteriaApplicationBuilder.routes(configure: RouteRegistryBuilder.() -> Unit) {
    install(RouteModule(RouteRegistryBuilder().apply(configure).build()))
}

fun localGameApplication(configure: AsteriaApplicationBuilder.() -> Unit): AsteriaApplication {
    return gameApplication {
        install(PekkoRuntimeModule.local())
        configure()
    }
}

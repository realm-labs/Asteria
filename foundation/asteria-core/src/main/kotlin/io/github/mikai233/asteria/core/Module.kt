package io.github.mikai233.asteria.core

interface AsteriaModule {
    val name: String

    suspend fun install(context: ModuleContext) = Unit

    suspend fun start(context: ModuleContext) = Unit

    suspend fun stop(context: ModuleContext) = Unit
}

class ModuleContext internal constructor(
    val application: AsteriaApplication,
    val services: ServiceRegistry,
) {
    val name: String get() = application.name
    val roles: Set<RoleKey> get() = application.roles
    val entities: List<EntitySpec<*>> get() = application.entities
    val singletons: List<SingletonSpec> get() = application.singletons
}

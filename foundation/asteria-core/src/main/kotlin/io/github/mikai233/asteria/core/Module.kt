package io.github.mikai233.asteria.core

/**
 * Extension point for framework and application runtime features.
 *
 * Modules are installed in declaration order, then started in declaration order. They are stopped in
 * reverse order. Put service registration and dependency lookup in [install], and start background
 * work or runtime actors in [start].
 */
interface AsteriaModule {
    /**
     * Stable module name used in diagnostics and tooling.
     */
    val name: String

    /**
     * Registers services and prepares resources before the application is started.
     */
    suspend fun install(context: ModuleContext) = Unit

    /**
     * Starts runtime work after all modules have been installed.
     */
    suspend fun start(context: ModuleContext) = Unit

    /**
     * Releases resources. Called in reverse installation order.
     */
    suspend fun stop(context: ModuleContext) = Unit
}

/**
 * Per-lifecycle view passed to modules.
 *
 * The context exposes application metadata and the shared [ServiceRegistry]. Modules should
 * communicate through registered services instead of depending on concrete module instances.
 */
class ModuleContext internal constructor(
    val application: AsteriaApplication,
    val services: ServiceRegistry,
) {
    val name: String get() = application.name
    val declaredRoles: Set<RoleKey> get() = application.declaredRoles
    val entities: List<EntitySpec<*>> get() = application.entities
    val singletons: List<SingletonSpec> get() = application.singletons
}

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
 * The context is intentionally based on [NodeRuntime] rather than [AsteriaApplication], so applications with their
 * own node implementation can still reuse framework modules. Modules should communicate through registered services
 * instead of depending on concrete module instances.
 */
class ModuleContext(
    val runtime: NodeRuntime,
    val services: ServiceRegistry = runtime.services,
    val topology: RuntimeTopology = RuntimeTopology.Empty,
) {
    val name: String get() = runtime.name
    val roles: Set<RoleKey> get() = runtime.roles
    val declaredRoles: Set<RoleKey> get() = topology.declaredRoles
    val entities: List<EntitySpec<*>> get() = topology.entities
    val singletons: List<SingletonSpec> get() = topology.singletons
}

/**
 * Static runtime topology declared before node startup.
 *
 * This is separate from [NodeRuntime] because custom node implementations often own their concrete roles and state
 * directly, while sharded entities and singleton specs are optional metadata used only by cluster modules.
 */
data class RuntimeTopology(
    val declaredRoles: Set<RoleKey> = emptySet(),
    val entities: List<EntitySpec<*>> = emptyList(),
    val singletons: List<SingletonSpec> = emptyList(),
) {
    companion object {
        val Empty = RuntimeTopology()
    }
}

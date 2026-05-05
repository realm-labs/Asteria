package io.github.realmlabs.asteria.core

/**
 * Extension point for framework and application runtime features.
 *
 * Modules are installed in declaration order, then started in declaration order. They are stopped and uninstalled in
 * reverse order. Put service registration and dependency lookup in [install], start background work or runtime actors
 * in [start], and release resources allocated during [install] in [uninstall].
 *
 * Modules communicate through [ModuleContext.services], not by calling each other directly. This keeps lifecycle order
 * explicit and lets applications replace concrete module implementations without rewriting downstream code.
 */
interface AsteriaModule {
    /**
     * Stable module name used in diagnostics and tooling.
     */
    val name: String

    /**
     * Registers services and prepares resources before the application is started.
     *
     * All modules finish [install] before any module enters [start], so it is safe to look up services registered by
     * earlier modules during this phase. If [install] allocates external resources, [uninstall] must release them.
     */
    suspend fun install(context: ModuleContext) = Unit

    /**
     * Starts runtime work after all modules have been installed.
     *
     * If this method throws, lifecycle launch fails and the exception is propagated to the caller.
     */
    suspend fun start(context: ModuleContext) = Unit

    /**
     * Releases resources. Called in reverse installation order.
     *
     * Stop is only invoked for modules whose [start] completed successfully.
     */
    suspend fun stop(context: ModuleContext) = Unit

    /**
     * Releases resources allocated by [install].
     *
     * This is called in reverse installation order during normal shutdown and during failed startup rollback. It may be
     * called even when [start] was never invoked.
     */
    suspend fun uninstall(context: ModuleContext) = Unit
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
    /**
     * Runtime name, forwarded from [runtime].
     */
    val name: String get() = runtime.name

    /**
     * Roles currently owned by this process.
     */
    val roles: Set<RoleKey> get() = runtime.roles

    /**
     * Union of roles declared by the application topology.
     */
    val declaredRoles: Set<RoleKey> get() = topology.declaredRoles

    /**
     * Declared entity specs available to runtime adapters.
     */
    val entities: List<EntitySpec<*>> get() = topology.entities

    /**
     * Declared singleton specs available to runtime adapters.
     */
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

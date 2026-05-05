package io.github.realmlabs.asteria.core

import kotlin.reflect.KClass

/**
 * Marks Asteria builder DSL receivers to prevent accidental calls on the wrong builder.
 */
@DslMarker
annotation class AsteriaDsl

/**
 * Runtime view shared with modules, actors, and other framework extensions.
 *
 * [roles] is the role set owned by this concrete process. It may differ from
 * [AsteriaApplication.declaredRoles], which is the union of roles requested by application specs.
 */
interface NodeRuntime {
    val name: String

    /**
     * Roles owned by this running node. Runtime modules should set this from the concrete node config.
     */
    val roles: Set<RoleKey>

    /**
     * Current lifecycle state of this runtime view.
     *
     * When the runtime is backed by [AsteriaApplication], this reflects the state managed by its internal
     * [AsteriaModuleLifecycle]. Custom runtimes may expose their own state storage and keep it aligned through
     * [AsteriaApplication.bind].
     */
    val state: NodeState

    /**
     * Shared service container for modules and runtime extensions.
     */
    val services: ServiceRegistry
}

/**
 * Built Asteria application.
 *
 * An application is immutable in terms of declared modules, entities, and singletons. Runtime
 * services and lifecycle state are mutable and owned by [launch] / [stop].
 *
 * The application definition is reusable: you can launch the same instance again after [stop], or bind the definition
 * to an external [NodeRuntime] through [bind]. What stays fixed is the declared topology and module list, not the
 * transient runtime state.
 */
class AsteriaApplication internal constructor(
    override val name: String,
    /**
     * Roles declared by application specs. This is metadata for validating/building runtime config,
     * not necessarily the role set of the current process.
     */
    val declaredRoles: Set<RoleKey>,
    val entities: List<EntitySpec<*>>,
    val singletons: List<SingletonSpec>,
    private val modules: List<AsteriaModule>,
) : NodeRuntime {
    override val services: ServiceRegistry = ServiceRegistry()

    @Volatile
    private var currentRoles: Set<RoleKey> = declaredRoles

    override val roles: Set<RoleKey>
        get() = currentRoles

    val topology: RuntimeTopology = RuntimeTopology(
        declaredRoles = declaredRoles,
        entities = entities,
        singletons = singletons,
    )

    private val lifecycle = AsteriaModuleLifecycle(
        runtime = this,
        topology = topology,
        modules = modules,
    )

    override val state: NodeState
        get() = lifecycle.state

    /**
     * Registers a listener that runs whenever the application enters [state].
     *
     * Listeners are invoked inline with lifecycle transitions. They should stay small and should not assume background
     * dispatch unless the caller provides it explicitly.
     */
    fun onState(state: NodeState, listener: suspend () -> Unit) {
        lifecycle.onState(state, listener)
    }

    /**
     * Updates the roles for this concrete process after the runtime module resolves node config.
     */
    fun setNodeRoles(roles: Set<RoleKey>) {
        currentRoles = roles.toSet()
    }

    /**
     * Installs and starts all modules.
     *
     * A stopped application can be launched again, but concurrent launch/stop calls are serialized.
     * If launch fails part-way through, the exception is propagated after best-effort rollback of already started and
     * installed modules. Startup failure is fatal to the current launch attempt; callers should usually let the process
     * exit instead of continuing with a partially available service.
     */
    suspend fun launch() {
        lifecycle.launch()
    }

    /**
     * Stops and uninstalls all modules in reverse order.
     *
     * Calling [stop] on an unstarted or already stopped application is a no-op.
     */
    suspend fun stop() {
        lifecycle.stop()
    }

    /**
     * Binds this application definition to an external runtime.
     *
     * Use this when the business process owns a strong node type and should still reuse Asteria module lifecycle. If the
     * node stores its own [NodeRuntime.state], pass [stateWriter] to keep that property aligned with lifecycle state.
     *
     * The returned lifecycle is independent from the application's internal lifecycle instance. This lets host
     * runtimes decide when to launch and stop framework modules without forcing the concrete node type to become an
     * [AsteriaApplication].
     */
    fun bind(
        runtime: NodeRuntime,
        stateWriter: ((NodeState) -> Unit)? = null,
    ): AsteriaModuleLifecycle {
        return AsteriaModuleLifecycle(
            runtime = runtime,
            topology = topology,
            modules = modules,
            stateWriter = stateWriter,
        )
    }
}

/**
 * Builder used by [gameApplication].
 *
 * Use this DSL to declare the runtime name, modules, roles, sharded entities, and singletons before
 * the application is launched.
 */
@AsteriaDsl
class AsteriaApplicationBuilder {
    /**
     * Actor system / runtime name. Cluster runtimes commonly use this as the Pekko system name.
     */
    var name: String = "asteria"

    private val declaredRoles: MutableSet<RoleKey> = linkedSetOf()
    private val modules: MutableList<AsteriaModule> = mutableListOf()
    private val entities: MutableList<EntitySpec<*>> = mutableListOf()
    private val singletons: MutableList<SingletonSpec> = mutableListOf()

    /**
     * Adds a module to the application lifecycle.
     *
     * Modules are kept in declaration order. That order becomes install/start order and the reverse of it becomes stop
     * order.
     */
    fun install(module: AsteriaModule) {
        modules.add(module)
    }

    /**
     * Declares a role used by specs or runtime topology validation.
     */
    fun role(value: String): RoleKey {
        return RoleKey(value).also { declaredRoles.add(it) }
    }

    /**
     * Declares a sharded entity type whose id type is inferred from [ID].
     */
    inline fun <reified ID : Any> entity(
        kind: String,
        noinline configure: EntitySpecBuilder<ID>.() -> Unit = {},
    ) {
        entity(kind, ID::class, configure)
    }

    /**
     * Declares a sharded entity type with an explicit id class.
     */
    fun <ID : Any> entity(
        kind: String,
        idType: KClass<ID>,
        configure: EntitySpecBuilder<ID>.() -> Unit = {},
    ) {
        val builder = EntitySpecBuilder(EntityKind(kind), idType).apply(configure)
        builder.role?.let(declaredRoles::add)
        entities.add(builder.build())
    }

    /**
     * Declares a cluster singleton.
     *
     * By default the singleton role is the same as [name]. Override it inside [configure] when the
     * singleton should be hosted by a different role.
     */
    fun singleton(
        name: String,
        configure: SingletonSpecBuilder.() -> Unit,
    ) {
        val builder = SingletonSpecBuilder(SingletonName(name)).apply(configure)
        declaredRoles.add(builder.role)
        singletons.add(builder.build())
    }

    /**
     * Builds an immutable application definition.
     *
     * The returned application reuses the current builder contents as value snapshots; later builder mutations do not
     * affect previously built applications.
     */
    fun build(): AsteriaApplication {
        return AsteriaApplication(
            name = name,
            declaredRoles = declaredRoles.toSet(),
            entities = entities.toList(),
            singletons = singletons.toList(),
            modules = modules.toList(),
        )
    }
}

/**
 * Captures portable entity metadata.
 *
 * Runtime-specific execution details belong in attributes or adapter-specific extension DSLs layered on top.
 */
@AsteriaDsl
class EntitySpecBuilder<ID : Any> internal constructor(
    private val kind: EntityKind,
    private val idType: KClass<ID>,
) {
    /**
     * Role that owns real shard regions for this entity. When absent, any node may host it.
     */
    var role: RoleKey? = null

    /**
     * Number of logical shards used by runtime adapters.
     *
     * This is declarative metadata. Different adapters may interpret it differently, but zero or negative shard counts
     * are always invalid.
     */
    var shardCount: Int = 100

    /**
     * Message sent to entity actors during graceful shard handoff.
     *
     * The framework stores this value opaquely and does not require a specific message base type.
     */
    var handoffMessage: Any? = null
    private val attributes: MutableMap<String, Any> = linkedMapOf()

    /**
     * Sets [role] from a string value.
     */
    fun role(value: String) {
        role = RoleKey(value)
    }

    /**
     * Adds runtime-adapter-specific metadata.
     *
     * Application code should prefer typed extension functions, such as Pekko's `actor { ... }`,
     * instead of writing raw attribute keys.
     */
    fun attribute(key: String, value: Any) {
        require(key.isNotBlank()) { "attribute key must not be blank" }
        attributes[key] = value
    }

    internal fun build(): EntitySpec<ID> {
        return EntitySpec(kind, idType, role, shardCount, handoffMessage, attributes.toMap())
    }
}

/**
 * Captures portable singleton metadata.
 *
 * Runtime-specific meaning belongs to cluster adapters, usually through typed extension DSLs.
 */
@AsteriaDsl
class SingletonSpecBuilder internal constructor(
    private val name: SingletonName,
) {
    /**
     * Role that may host the singleton manager.
     */
    var role: RoleKey = RoleKey(name.value)

    /**
     * Message sent to the singleton actor during graceful handoff.
     */
    var handoffMessage: Any? = null
    private val attributes: MutableMap<String, Any> = linkedMapOf()

    /**
     * Sets [role] from a string value.
     */
    fun role(value: String) {
        role = RoleKey(value)
    }

    /**
     * Adds runtime-adapter-specific metadata.
     */
    fun attribute(key: String, value: Any) {
        require(key.isNotBlank()) { "attribute key must not be blank" }
        attributes[key] = value
    }

    internal fun build(): SingletonSpec {
        return SingletonSpec(name, role, handoffMessage, attributes.toMap())
    }
}

/**
 * Builds an [AsteriaApplication].
 *
 * Example:
 *
 * ```kotlin
 * val app = gameApplication {
 *     name = "demo-game"
 *     role("player")
 * }
 * ```
 */
fun gameApplication(configure: AsteriaApplicationBuilder.() -> Unit): AsteriaApplication {
    return AsteriaApplicationBuilder().apply(configure).build()
}

package io.github.mikai233.asteria.core

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.slf4j.LoggerFactory
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
    val state: NodeState
    val services: ServiceRegistry
}

/**
 * Built Asteria application.
 *
 * An application is immutable in terms of declared modules, entities, and singletons. Runtime
 * services and lifecycle state are mutable and owned by [launch] / [stop].
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
    private val logger = LoggerFactory.getLogger(AsteriaApplication::class.java)

    override val services: ServiceRegistry = ServiceRegistry()

    @Volatile
    private var currentRoles: Set<RoleKey> = declaredRoles

    override val roles: Set<RoleKey>
        get() = currentRoles

    @Volatile
    override var state: NodeState = NodeState.Unstarted
        private set

    private val stateListeners: MutableMap<NodeState, MutableList<suspend () -> Unit>> = linkedMapOf()
    private val lifecycleLock = Mutex()

    /**
     * Registers a listener that runs whenever the application enters [state].
     */
    fun onState(state: NodeState, listener: suspend () -> Unit) {
        stateListeners.getOrPut(state) { mutableListOf() }.add(listener)
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
     */
    suspend fun launch() {
        lifecycleLock.withLock {
            check(state == NodeState.Unstarted || state == NodeState.Stopped) {
                "application $name cannot launch from state $state"
            }
            val startedAt = System.nanoTime()
            logger.info(
                "launching application {} modules={} roles={} entities={} singletons={}",
                name,
                modules.size,
                declaredRoles.size,
                entities.size,
                singletons.size,
            )
            changeState(NodeState.Starting)
            try {
                val context = ModuleContext(this, services)
                modules.forEach { it.install(context) }
                modules.forEach { it.start(context) }
                changeState(NodeState.Started)
                logger.info(
                    "application {} launched in {} ms",
                    name,
                    (System.nanoTime() - startedAt) / 1_000_000,
                )
            } catch (error: Throwable) {
                logger.error("application {} failed to launch", name, error)
                throw error
            }
        }
    }

    /**
     * Stops all modules in reverse order.
     */
    suspend fun stop() {
        lifecycleLock.withLock {
            if (state == NodeState.Stopped || state == NodeState.Unstarted) {
                return
            }
            val startedAt = System.nanoTime()
            logger.info("stopping application {}", name)
            changeState(NodeState.Stopping)
            try {
                val context = ModuleContext(this, services)
                modules.asReversed().forEach { it.stop(context) }
                changeState(NodeState.Stopped)
                logger.info(
                    "application {} stopped in {} ms",
                    name,
                    (System.nanoTime() - startedAt) / 1_000_000,
                )
            } catch (error: Throwable) {
                logger.error("application {} failed to stop", name, error)
                throw error
            }
        }
    }

    private suspend fun changeState(newState: NodeState) {
        state = newState
        stateListeners[newState].orEmpty().forEach { it() }
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
 * Builder for [EntitySpec].
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
     */
    var shardCount: Int = 100
    /**
     * Message sent to entity actors during graceful shard handoff.
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
 * Builder for [SingletonSpec].
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

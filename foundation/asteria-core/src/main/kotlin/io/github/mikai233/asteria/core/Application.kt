package io.github.mikai233.asteria.core

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.reflect.KClass

@DslMarker
annotation class AsteriaDsl

interface NodeRuntime {
    val name: String
    /**
     * Roles owned by this running node. Runtime modules should set this from the concrete node config.
     */
    val roles: Set<RoleKey>
    val state: NodeState
    val services: ServiceRegistry
}

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

    @Volatile
    override var state: NodeState = NodeState.Unstarted
        private set

    private val stateListeners: MutableMap<NodeState, MutableList<suspend () -> Unit>> = linkedMapOf()
    private val lifecycleLock = Mutex()

    fun onState(state: NodeState, listener: suspend () -> Unit) {
        stateListeners.getOrPut(state) { mutableListOf() }.add(listener)
    }

    /**
     * Updates the roles for this concrete process after the runtime module resolves node config.
     */
    fun setNodeRoles(roles: Set<RoleKey>) {
        currentRoles = roles.toSet()
    }

    suspend fun launch() {
        lifecycleLock.withLock {
            check(state == NodeState.Unstarted || state == NodeState.Stopped) {
                "application $name cannot launch from state $state"
            }
            changeState(NodeState.Starting)
            val context = ModuleContext(this, services)
            modules.forEach { it.install(context) }
            modules.forEach { it.start(context) }
            changeState(NodeState.Started)
        }
    }

    suspend fun stop() {
        lifecycleLock.withLock {
            if (state == NodeState.Stopped || state == NodeState.Unstarted) {
                return
            }
            changeState(NodeState.Stopping)
            val context = ModuleContext(this, services)
            modules.asReversed().forEach { it.stop(context) }
            changeState(NodeState.Stopped)
        }
    }

    private suspend fun changeState(newState: NodeState) {
        state = newState
        stateListeners[newState].orEmpty().forEach { it() }
    }
}

@AsteriaDsl
class AsteriaApplicationBuilder {
    var name: String = "asteria"

    private val declaredRoles: MutableSet<RoleKey> = linkedSetOf()
    private val modules: MutableList<AsteriaModule> = mutableListOf()
    private val entities: MutableList<EntitySpec<*>> = mutableListOf()
    private val singletons: MutableList<SingletonSpec> = mutableListOf()

    fun install(module: AsteriaModule) {
        modules.add(module)
    }

    fun role(value: String): RoleKey {
        return RoleKey(value).also { declaredRoles.add(it) }
    }

    inline fun <reified ID : Any> entity(
        kind: String,
        noinline configure: EntitySpecBuilder<ID>.() -> Unit = {},
    ) {
        entity(kind, ID::class, configure)
    }

    fun <ID : Any> entity(
        kind: String,
        idType: KClass<ID>,
        configure: EntitySpecBuilder<ID>.() -> Unit = {},
    ) {
        val builder = EntitySpecBuilder(EntityKind(kind), idType).apply(configure)
        builder.role?.let(declaredRoles::add)
        entities.add(builder.build())
    }

    fun singleton(
        name: String,
        configure: SingletonSpecBuilder.() -> Unit,
    ) {
        val builder = SingletonSpecBuilder(SingletonName(name)).apply(configure)
        declaredRoles.add(builder.role)
        singletons.add(builder.build())
    }

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

@AsteriaDsl
class EntitySpecBuilder<ID : Any> internal constructor(
    private val kind: EntityKind,
    private val idType: KClass<ID>,
) {
    var role: RoleKey? = null
    var shardCount: Int = 100
    var handoffMessage: Any? = null
    private val attributes: MutableMap<String, Any> = linkedMapOf()

    fun role(value: String) {
        role = RoleKey(value)
    }

    fun attribute(key: String, value: Any) {
        require(key.isNotBlank()) { "attribute key must not be blank" }
        attributes[key] = value
    }

    internal fun build(): EntitySpec<ID> {
        return EntitySpec(kind, idType, role, shardCount, handoffMessage, attributes.toMap())
    }
}

@AsteriaDsl
class SingletonSpecBuilder internal constructor(
    private val name: SingletonName,
) {
    var role: RoleKey = RoleKey(name.value)
    var handoffMessage: Any? = null
    private val attributes: MutableMap<String, Any> = linkedMapOf()

    fun role(value: String) {
        role = RoleKey(value)
    }

    fun attribute(key: String, value: Any) {
        require(key.isNotBlank()) { "attribute key must not be blank" }
        attributes[key] = value
    }

    internal fun build(): SingletonSpec {
        return SingletonSpec(name, role, handoffMessage, attributes.toMap())
    }
}

fun gameApplication(configure: AsteriaApplicationBuilder.() -> Unit): AsteriaApplication {
    return AsteriaApplicationBuilder().apply(configure).build()
}

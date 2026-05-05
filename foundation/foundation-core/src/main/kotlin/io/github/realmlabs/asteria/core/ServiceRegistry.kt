package io.github.realmlabs.asteria.core

import kotlin.reflect.KClass

/**
 * Type-keyed service container shared by modules and runtime code.
 *
 * Modules register services during [AsteriaModule.install]. Other modules should retrieve those
 * services from [ModuleContext.services] instead of keeping direct references to module instances.
 *
 * Lookup is by exact [KClass]. The registry does not search assignable supertypes or interfaces automatically. It is
 * also intentionally lightweight and unsynchronized; applications should finish most writes during startup or provide
 * their own higher-level synchronization if they mutate services at runtime.
 */
class ServiceRegistry {
    private val services: MutableMap<KClass<*>, Any> = linkedMapOf()

    /**
     * Registers or replaces a service for [type].
     *
     * Re-registering the same [type] overwrites the previous instance.
     */
    fun <T : Any> register(type: KClass<T>, service: T) {
        services[type] = service
    }

    /**
     * Registers or replaces a service using its reified runtime type.
     */
    inline fun <reified T : Any> register(service: T) {
        register(T::class, service)
    }

    /**
     * Returns the service registered for [type], or throws when absent.
     */
    fun <T : Any> get(type: KClass<T>): T {
        return find(type) ?: error("service ${type.qualifiedName} not found")
    }

    /**
     * Returns the service registered for [T], or throws when absent.
     */
    inline fun <reified T : Any> get(): T = get(T::class)

    /**
     * Returns the service registered for [type], or `null` when absent.
     */
    fun <T : Any> find(type: KClass<T>): T? {
        @Suppress("UNCHECKED_CAST")
        return services[type] as T?
    }

    /**
     * Returns the service registered for [T], or `null` when absent.
     */
    inline fun <reified T : Any> find(): T? = find(T::class)
}

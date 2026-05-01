package io.github.mikai233.asteria.core

import kotlin.reflect.KClass

class ServiceRegistry {
    private val services: MutableMap<KClass<*>, Any> = linkedMapOf()

    fun <T : Any> register(type: KClass<T>, service: T) {
        services[type] = service
    }

    inline fun <reified T : Any> register(service: T) {
        register(T::class, service)
    }

    fun <T : Any> get(type: KClass<T>): T {
        return find(type) ?: error("service ${type.qualifiedName} not found")
    }

    inline fun <reified T : Any> get(): T = get(T::class)

    fun <T : Any> find(type: KClass<T>): T? {
        @Suppress("UNCHECKED_CAST")
        return services[type] as T?
    }

    inline fun <reified T : Any> find(): T? = find(T::class)
}

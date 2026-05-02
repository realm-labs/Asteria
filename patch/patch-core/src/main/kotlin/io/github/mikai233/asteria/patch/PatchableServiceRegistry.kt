package io.github.mikai233.asteria.patch

import kotlin.reflect.KClass

/**
 * Type-keyed service registry whose entries can be replaced by runtime patches.
 *
 * This is the service-oriented form of [PatchableRegistry]. Business code reads services through [require] instead of
 * keeping mutable static references. Patch plugins replace services through [PatchInstallContext.replaceService], so
 * disable/uninstall removes only the target patch layer and falls back to the previous patch implementation when one
 * exists.
 */
class PatchableServiceRegistry(
    services: Map<KClass<*>, Any> = emptyMap(),
) {
    private val registry = PatchableRegistry(services)

    /**
     * Registers a base service. Base services are the original implementations that remain when no patch layer exists.
     */
    fun <T : Any> register(
        type: KClass<T>,
        service: T,
    ) {
        registry.register(type, service)
    }

    /**
     * Registers a base service using its reified service type.
     */
    inline fun <reified T : Any> register(service: T) {
        register(T::class, service)
    }

    /**
     * Returns the currently active service for [type], or `null` when no base service exists.
     */
    fun <T : Any> get(type: KClass<T>): T? {
        @Suppress("UNCHECKED_CAST")
        return registry.get(type) as T?
    }

    /**
     * Returns the currently active service for [T], or `null` when no base service exists.
     */
    inline fun <reified T : Any> get(): T? {
        return get(T::class)
    }

    /**
     * Returns the currently active service for [type].
     */
    fun <T : Any> require(type: KClass<T>): T {
        return get(type) ?: error("patchable service ${type.qualifiedName} not found")
    }

    /**
     * Returns the currently active service for [T].
     */
    inline fun <reified T : Any> require(): T {
        return require(T::class)
    }

    internal fun <T : Any> replace(
        type: KClass<T>,
        service: T,
        order: PatchOrder,
    ) {
        registry.replace(type, service, order)
    }

    internal fun removePatch(id: PatchId) {
        registry.removePatch(id)
    }
}

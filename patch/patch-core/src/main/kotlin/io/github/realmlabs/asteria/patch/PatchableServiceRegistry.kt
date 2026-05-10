package io.github.realmlabs.asteria.patch

import kotlin.reflect.KClass

/**
 * Type-keyed service registry whose entries can be replaced by runtime patches.
 *
 * This is the service-oriented form of [PatchableRegistry]. Business code reads services through [require] instead of
 * keeping mutable static references. Patch plugins replace services through [RuntimePatchInstallContext.services], so
 * disable/uninstall removes only the target patch layer and falls back to the previous patch implementation when one
 * exists.
 */
class PatchableServiceRegistry(
    services: Map<KClass<*>, Any> = emptyMap(),
) : PatchSlotRegistry<KClass<*>, Any> {
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

    /**
     * Returns the currently effective service for [key].
     *
     * Patch runtime uses this to validate and inspect the active service view before installing a replacement layer.
     */
    override fun current(key: KClass<*>): Any? {
        return registry.get(key)
    }

    /**
     * Installs or updates one patch layer for the service identified by [key].
     *
     * This does not mutate the base registration added by [register]. The key must already exist as a base service.
     */
    override fun replace(
        key: KClass<*>,
        value: Any,
        order: PatchOrder,
        scope: PatchRegistryMutationScope,
    ) {
        registry.replace(key, value, order, scope)
    }

    /**
     * Removes every replacement layer owned by [id].
     *
     * Base services remain registered and become visible again when no replacement layer is left for a given type.
     */
    override fun remove(
        id: PatchId,
        scope: PatchRegistryMutationScope,
    ) {
        registry.remove(id, scope)
    }
}

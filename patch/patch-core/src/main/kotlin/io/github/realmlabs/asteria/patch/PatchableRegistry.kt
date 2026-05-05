package io.github.realmlabs.asteria.patch

import java.util.concurrent.atomic.AtomicReference

/**
 * A registry slot that can be replaced by ordered runtime patch layers.
 *
 * This abstraction is intentionally smaller than the public read API of a registry. Patch installation only needs to
 * validate that a key exists, install one ordered layer, and remove that layer during rollback/uninstall. Concrete
 * registries can expose richer business-facing APIs without forcing [PatchInstallContext] to know each registry shape.
 */
interface PatchSlotRegistry<K : Any, V : Any> {
    /**
     * Returns the currently active value for [key], or `null` when the key is not registered.
     *
     * Patch runtime calls this before committing a patch operation so missing handler/service keys fail before partial
     * replacement happens.
     */
    fun current(key: K): V?

    /**
     * Installs [value] as the replacement for [key] in the patch layer represented by [order].
     */
    fun replace(key: K, value: V, order: PatchOrder)

    /**
     * Removes every replacement layer owned by [id].
     */
    fun remove(id: PatchId)
}

/**
 * Copy-on-write registry for runtime patchable dispatch points.
 *
 * Reads are lock-free and always observe a complete immutable snapshot. Patch application stores
 * ordered replacement layers and rebuilds the active snapshot from the original base entries, which
 * makes startup replay and online patching use the same ordering rules.
 */
class PatchableRegistry<K : Any, V : Any>(
    entries: Map<K, V> = emptyMap(),
) : PatchSlotRegistry<K, V> {
    private val state = AtomicReference(RegistryState(entries.toMap()))

    /**
     * Returns the currently active value for [key].
     *
     * This is the effective view after merging the base registry with all active replacement layers.
     */
    fun get(key: K): V? {
        return state.get().active[key]
    }

    /**
     * Returns the currently active value for [key] or throws when absent.
     */
    fun require(key: K): V {
        return get(key) ?: error("patchable registry key $key not found")
    }

    /**
     * Returns the currently effective value for [key].
     *
     * This is equivalent to [get] and may return a patched value rather than the original base value.
     */
    override fun current(key: K): V? {
        return get(key)
    }

    /**
     * Returns an immutable snapshot of the current active registry view.
     *
     * The snapshot already includes every replacement layer that is currently in effect.
     */
    fun snapshot(): Map<K, V> {
        return state.get().active
    }

    /**
     * Returns metadata about installed replacement layers.
     *
     * This is diagnostic information only. It does not include base-only entries that have never been replaced.
     */
    fun replacementInfo(): List<PatchRegistryReplacementInfo<K>> {
        return state.get()
            .layers
            .flatMap { layer -> layer.replacements.keys.map { key -> PatchRegistryReplacementInfo(key, layer.order) } }
    }

    /**
     * Adds one new base entry.
     *
     * Base entries are the long-lived original registry contents. They remain after all patch layers are removed.
     * Registering an existing key fails instead of overwriting it.
     */
    fun register(key: K, value: V) {
        state.updateAndGet { old ->
            check(key !in old.base) { "patchable registry key $key already exists" }
            old.copy(base = old.base + (key to value)).rebuild()
        }
    }

    /**
     * Installs or updates one replacement layer for [key].
     *
     * This never mutates the base entry. Instead it stores [value] inside the patch layer identified by [order], then
     * rebuilds the active view by replaying all layers in order. The key must already exist in the base registry.
     */
    override fun replace(key: K, value: V, order: PatchOrder) {
        state.updateAndGet { old ->
            check(key in old.base) { "patchable registry key $key does not exist" }
            old.withReplacement(key, value, order).rebuild()
        }
    }

    /**
     * Removes every replacement layer owned by [id].
     *
     * Base entries are untouched. After removal, each affected key falls back to the next remaining layer or to its
     * base value when no replacement layer remains.
     */
    override fun remove(id: PatchId) {
        state.updateAndGet { old ->
            old.copy(layers = old.layers.filterNot { it.order.id == id }).rebuild()
        }
    }

    private data class RegistryState<K : Any, V : Any>(
        val base: Map<K, V>,
        val layers: List<ReplacementLayer<K, V>> = emptyList(),
        val active: Map<K, V> = base,
    ) {
        fun withReplacement(key: K, value: V, order: PatchOrder): RegistryState<K, V> {
            val layerIndex = layers.indexOfFirst { it.order == order }
            val nextLayers = if (layerIndex >= 0) {
                layers.toMutableList().also { layers ->
                    val layer = layers[layerIndex]
                    layers[layerIndex] = layer.copy(replacements = layer.replacements + (key to value))
                }
            } else {
                layers + ReplacementLayer(order, mapOf(key to value))
            }
            return copy(layers = nextLayers.sortedBy { it.order })
        }

        fun rebuild(): RegistryState<K, V> {
            val next = LinkedHashMap(base)
            layers.sortedBy { it.order }.forEach { layer ->
                next.putAll(layer.replacements)
            }
            return copy(layers = layers.sortedBy { it.order }, active = next.toMap())
        }
    }

    private data class ReplacementLayer<K : Any, V : Any>(
        val order: PatchOrder,
        val replacements: Map<K, V>,
    )
}

data class PatchRegistryReplacementInfo<K : Any>(
    val key: K,
    val order: PatchOrder,
)

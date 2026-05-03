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

    fun get(key: K): V? {
        return state.get().active[key]
    }

    fun require(key: K): V {
        return get(key) ?: error("patchable registry key $key not found")
    }

    override fun current(key: K): V? {
        return get(key)
    }

    fun snapshot(): Map<K, V> {
        return state.get().active
    }

    fun replacementInfo(): List<PatchRegistryReplacementInfo<K>> {
        return state.get()
            .layers
            .flatMap { layer -> layer.replacements.keys.map { key -> PatchRegistryReplacementInfo(key, layer.order) } }
    }

    fun register(key: K, value: V) {
        state.updateAndGet { old ->
            check(key !in old.base) { "patchable registry key $key already exists" }
            old.copy(base = old.base + (key to value)).rebuild()
        }
    }

    override fun replace(key: K, value: V, order: PatchOrder) {
        state.updateAndGet { old ->
            check(key in old.base) { "patchable registry key $key does not exist" }
            old.withReplacement(key, value, order).rebuild()
        }
    }

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

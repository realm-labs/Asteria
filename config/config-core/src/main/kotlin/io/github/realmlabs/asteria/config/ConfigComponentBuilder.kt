package io.github.realmlabs.asteria.config

import kotlin.reflect.KClass

/**
 * Builds runtime read-model components from raw config tables before a snapshot is published.
 *
 * Components are not part of the config artifact stored in a config center. They are process-local read caches or
 * indexes derived from the loaded tables, such as grouped lookups, timelines, or precomputed weight tables.
 */
interface ConfigComponentBuilder<T : Any> {
    /**
     * Stable name used in diagnostics.
     */
    val name: String

    /**
     * Component type used for typed lookup through [ConfigSnapshot.component].
     */
    val type: KClass<T>

    /**
     * Builds the component from the raw snapshot loaded by [ConfigLoader].
     */
    suspend fun build(snapshot: ConfigSnapshot): T
}

/**
 * Creates a [ConfigComponentBuilder] with an inferred component type.
 *
 * The builder itself is pure metadata plus a function. Any expensive work should happen inside [ConfigComponentBuilder.build]
 * so it participates in the normal reload transaction and failure handling.
 */
inline fun <reified T : Any> configComponentBuilder(
    name: String,
    noinline build: suspend (ConfigSnapshot) -> T,
): ConfigComponentBuilder<T> {
    return FunctionConfigComponentBuilder(name, T::class, build)
}

@PublishedApi
internal class FunctionConfigComponentBuilder<T : Any>(
    override val name: String,
    override val type: KClass<T>,
    private val build: suspend (ConfigSnapshot) -> T,
) : ConfigComponentBuilder<T> {
    init {
        require(name.isNotBlank()) { "config component builder name must not be blank" }
    }

    override suspend fun build(snapshot: ConfigSnapshot): T {
        return build.invoke(snapshot)
    }
}

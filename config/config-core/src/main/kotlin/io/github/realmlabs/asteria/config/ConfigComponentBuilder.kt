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
     * Tables used by this builder.
     *
     * The first implementation rebuilds all components on every reload. Dependencies are still declared now so tooling
     * and later optimizations can explain why a component exists and which table changes affect it.
     */
    val dependencies: Set<ConfigTableName>

    /**
     * Builds the component from the raw snapshot loaded by [ConfigLoader].
     */
    suspend fun build(snapshot: ConfigSnapshot): T
}

/**
 * Creates a [ConfigComponentBuilder] with an inferred component type.
 */
inline fun <reified T : Any> configComponentBuilder(
    name: String,
    dependencies: Set<ConfigTableName> = emptySet(),
    noinline build: suspend (ConfigSnapshot) -> T,
): ConfigComponentBuilder<T> {
    return FunctionConfigComponentBuilder(name, T::class, dependencies, build)
}

@PublishedApi
internal class FunctionConfigComponentBuilder<T : Any>(
    override val name: String,
    override val type: KClass<T>,
    override val dependencies: Set<ConfigTableName>,
    private val build: suspend (ConfigSnapshot) -> T,
) : ConfigComponentBuilder<T> {
    init {
        require(name.isNotBlank()) { "config component builder name must not be blank" }
    }

    override suspend fun build(snapshot: ConfigSnapshot): T {
        return build.invoke(snapshot)
    }
}

package io.github.realmlabs.asteria.gm.core

import java.util.*

/**
 * Stable identifier for one GM feature module.
 *
 * Feature ids are used as registry keys and should stay stable once a module is published. Prefer short,
 * dotted names such as `script` or `cluster.pekko`.
 */
@JvmInline
value class GmFeatureId(val value: String) {
    init {
        require(value.isNotBlank()) { "GM feature id must not be blank" }
    }

    override fun toString(): String = value
}

/**
 * Stable permission key used by backend checks and frontend route/menu guards.
 */
@JvmInline
value class GmPermissionKey(val value: String) {
    init {
        require(value.isNotBlank()) { "GM permission key must not be blank" }
    }

    override fun toString(): String = value
}

/**
 * Permission contributed by a GM feature.
 *
 * `highRisk` marks operations such as script execution, data repair, compensation, or cluster control so that
 * applications can add stronger approval, audit, or MFA policies around them.
 */
data class GmPermission(
    val key: GmPermissionKey,
    val name: String,
    val description: String? = null,
    val highRisk: Boolean = false,
) {
    init {
        require(name.isNotBlank()) { "GM permission name must not be blank" }
        description?.let { require(it.isNotBlank()) { "GM permission description must not be blank" } }
    }
}

/**
 * Menu metadata consumed by a GM frontend shell.
 */
data class GmMenuItem(
    val id: String,
    val title: String,
    val route: String? = null,
    val permission: GmPermissionKey? = null,
    val order: Int = 0,
    val children: List<GmMenuItem> = emptyList(),
) {
    init {
        require(id.isNotBlank()) { "GM menu id must not be blank" }
        require(title.isNotBlank()) { "GM menu title must not be blank" }
        route?.let { require(it.isNotBlank()) { "GM menu route must not be blank" } }
    }
}

/**
 * Frontend route metadata contributed by a feature.
 *
 * The framework only stores the component id. Concrete GM frontends decide how that id maps to local Vue components,
 * remote modules, or application-provided pages.
 */
data class GmRoute(
    val id: String,
    val path: String,
    val component: String,
    val permission: GmPermissionKey? = null,
    val meta: Map<String, String> = emptyMap(),
) {
    init {
        require(id.isNotBlank()) { "GM route id must not be blank" }
        require(path.isNotBlank()) { "GM route path must not be blank" }
        require(component.isNotBlank()) { "GM route component must not be blank" }
        meta.keys.forEach { require(it.isNotBlank()) { "GM route meta key must not be blank" } }
    }
}

/**
 * Public metadata of a GM feature.
 */
data class GmFeatureDescriptor(
    val id: GmFeatureId,
    val name: String,
    val description: String? = null,
    val permissions: List<GmPermission> = emptyList(),
    val menus: List<GmMenuItem> = emptyList(),
    val routes: List<GmRoute> = emptyList(),
) {
    init {
        require(name.isNotBlank()) { "GM feature name must not be blank" }
        description?.let { require(it.isNotBlank()) { "GM feature description must not be blank" } }
    }
}

/**
 * Pluggable GM capability such as script execution, cluster management, recharge tools, or version management.
 *
 * Feature implementations should be cheap to construct. Runtime services and controllers can live in the same module,
 * but the descriptor should describe the feature without requiring network or cluster access.
 */
interface GmFeature {
    val descriptor: GmFeatureDescriptor
}

/**
 * Discovers GM features published through Java's `ServiceLoader`.
 *
 * Optional modules can add a `META-INF/services/io.github.realmlabs.asteria.gm.core.GmFeature` file so applications get
 * their permissions, menus, and routes by only adding a dependency.
 */
fun discoverGmFeatures(
    classLoader: ClassLoader = Thread.currentThread().contextClassLoader ?: GmFeature::class.java.classLoader,
): List<GmFeature> {
    return ServiceLoader.load(GmFeature::class.java, classLoader).toList()
}

/**
 * Immutable catalog of all installed GM features.
 *
 * The registry rejects duplicate feature ids and duplicate permission keys at startup so extension modules cannot
 * silently shadow each other.
 */
class GmFeatureRegistry(
    features: Iterable<GmFeature> = emptyList(),
) {
    private val featuresById: Map<GmFeatureId, GmFeature> = features.associateUniqueBy(
        keyOf = { it.descriptor.id },
        errorOf = { "duplicate GM feature ${it.descriptor.id}" },
    )

    private val permissionsByKey: Map<GmPermissionKey, GmPermission> = features
        .flatMap { it.descriptor.permissions }
        .associateUniqueBy(
            keyOf = { it.key },
            errorOf = { "duplicate GM permission ${it.key}" },
        )

    fun features(): List<GmFeatureDescriptor> {
        return featuresById.values.map { it.descriptor }
    }

    fun feature(id: GmFeatureId): GmFeatureDescriptor? {
        return featuresById[id]?.descriptor
    }

    fun permissions(): List<GmPermission> {
        return permissionsByKey.values.toList()
    }

    fun menus(): List<GmMenuItem> {
        return featuresById.values
            .flatMap { it.descriptor.menus }
            .sortedBy { it.order }
    }

    fun routes(): List<GmRoute> {
        return featuresById.values.flatMap { it.descriptor.routes }
    }
}

private fun <K : Any, V : Any> Iterable<V>.associateUniqueBy(
    keyOf: (V) -> K,
    errorOf: (V) -> String,
): Map<K, V> {
    val result = linkedMapOf<K, V>()
    for (value in this) {
        val key = keyOf(value)
        require(key !in result) { errorOf(value) }
        result[key] = value
    }
    return result
}

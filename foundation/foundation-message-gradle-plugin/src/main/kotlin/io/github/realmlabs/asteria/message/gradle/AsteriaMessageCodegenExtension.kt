package io.github.realmlabs.asteria.message.gradle

import org.gradle.api.Action
import org.gradle.api.model.ObjectFactory
import org.gradle.api.provider.MapProperty
import org.gradle.api.provider.Property
import javax.inject.Inject

abstract class AsteriaMessageCodegenExtension @Inject constructor(objects: ObjectFactory) {
    /**
     * Asteria artifact version used when the target project is not inside the Asteria multi-project build.
     */
    val asteriaVersion: Property<String> = objects.property(String::class.java)
        .convention(AsteriaMessageCodegenPlugin.DEFAULT_ASTERIA_VERSION)

    /**
     * Whether this plugin should add `:foundation:foundation-message` and `:foundation:foundation-message-ksp`.
     */
    val addDependencies: Property<Boolean> = objects.property(Boolean::class.javaObjectType)
        .convention(true)

    /**
     * Dispatcher key to common message super type mapping.
     *
     * Example:
     * `dispatcherSuperType("INTERNAL", "com.example.message.Message")`
     */
    val dispatcherSuperTypes: MapProperty<String, String> = objects.mapProperty(String::class.java, String::class.java)
        .convention(emptyMap())

    fun dispatcherSuperType(dispatcher: String, superType: String) {
        dispatcherSuperTypes.put(dispatcher, superType)
    }

    fun dispatcherSuperTypes(values: Map<String, String>) {
        dispatcherSuperTypes.putAll(values)
    }

    fun dispatcherSuperTypes(action: Action<in MutableMap<String, String>>) {
        val snapshot = dispatcherSuperTypes.orNull?.toMutableMap() ?: mutableMapOf()
        action.execute(snapshot)
        dispatcherSuperTypes.set(snapshot)
    }
}

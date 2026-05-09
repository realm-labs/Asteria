package io.github.realmlabs.asteria.config.gradle

import org.gradle.api.Action
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.model.ObjectFactory
import org.gradle.api.provider.Property
import javax.inject.Inject

/**
 * Gradle configuration for Asteria config table accessor generation.
 */
abstract class AsteriaConfigCodegenExtension @Inject constructor(objects: ObjectFactory) {
    /**
     * Package of the generated Kotlin file.
     */
    val packageName: Property<String> = objects.property(String::class.java)
        .convention("io.github.realmlabs.asteria.generated.config")

    /**
     * Name of the generated object that contains table refs.
     */
    val tablesObjectName: Property<String> = objects.property(String::class.java)
        .convention("GameConfigTables")

    /**
     * Name of the generated dynamic accessor class.
     */
    val accessorClassName: Property<String> = objects.property(String::class.java)
        .convention("GameConfigs")

    /**
     * Asteria artifact version used when the target project is not inside the Asteria multi-project build.
     */
    val asteriaVersion: Property<String> = objects.property(String::class.java)
        .convention(AsteriaConfigCodegenPlugin.DEFAULT_ASTERIA_VERSION)

    /**
     * Whether this plugin should add `:config:config-core`, `:config:config-annotations`, and `:config:config-ksp`.
     */
    val addDependencies: Property<Boolean> = objects.property(Boolean::class.javaObjectType)
        .convention(true)

    /**
     * Optional Luban marker generation bridge.
     *
     * This consumes a small table metadata JSON file and generates `@AsteriaConfigTable` marker
     * objects before KSP runs. It keeps the framework code generator independent from Luban internals
     * while still letting Luban projects avoid handwritten marker files.
     */
    val luban: AsteriaLubanConfigMarkerExtension = objects.newInstance(
        AsteriaLubanConfigMarkerExtension::class.java,
        objects,
    )

    /**
     * Optional config change handler aggregation.
     */
    val configChange: AsteriaConfigChangeCodegenExtension = objects.newInstance(
        AsteriaConfigChangeCodegenExtension::class.java,
        objects,
    )

    /**
     * Optional config validator aggregation.
     */
    val validators: AsteriaConfigValidatorCodegenExtension = objects.newInstance(
        AsteriaConfigValidatorCodegenExtension::class.java,
        objects,
    )

    fun luban(action: Action<in AsteriaLubanConfigMarkerExtension>) {
        action.execute(luban)
    }

    fun configChange(action: Action<in AsteriaConfigChangeCodegenExtension>) {
        action.execute(configChange)
    }

    fun validators(action: Action<in AsteriaConfigValidatorCodegenExtension>) {
        action.execute(validators)
    }
}

abstract class AsteriaLubanConfigMarkerExtension @Inject constructor(objects: ObjectFactory) {
    /**
     * Enables marker generation from [metadataFile].
     */
    val enabled: Property<Boolean> = objects.property(Boolean::class.javaObjectType)
        .convention(false)

    /**
     * JSON metadata exported by the game project or its Luban export step.
     */
    val metadataFile: RegularFileProperty = objects.fileProperty()

    /**
     * Package of the generated marker source file. Blank means using `asteriaConfigCodegen.packageName`.
     */
    val packageName: Property<String> = objects.property(String::class.java)
        .convention("")

    /**
     * Generated marker file name without `.kt`.
     */
    val fileName: Property<String> = objects.property(String::class.java)
        .convention("AsteriaLubanConfigMarkers")
}

abstract class AsteriaConfigChangeCodegenExtension @Inject constructor(objects: ObjectFactory) {
    /**
     * Package of the generated handler list. Blank means using `asteriaConfigCodegen.packageName`.
     */
    val packageName: Property<String> = objects.property(String::class.java)
        .convention("")

    /**
     * Name of the generated object that exposes `ALL`.
     */
    val className: Property<String> = objects.property(String::class.java)
        .convention("GeneratedConfigChangeHandlers")

    /**
     * Fully qualified receiver type accepted by all generated handlers.
     *
     * Leave blank when using `@AsteriaConfigChangeCatalog(receiverType = ...)` in source code.
     */
    val receiverType: Property<String> = objects.property(String::class.java)
        .convention("")
}

abstract class AsteriaConfigValidatorCodegenExtension @Inject constructor(objects: ObjectFactory) {
    /**
     * Package of the generated validator list. Blank means using `asteriaConfigCodegen.packageName`.
     */
    val packageName: Property<String> = objects.property(String::class.java)
        .convention("")

    /**
     * Name of the generated object that exposes `ALL`.
     */
    val className: Property<String> = objects.property(String::class.java)
        .convention("GeneratedConfigValidators")
}

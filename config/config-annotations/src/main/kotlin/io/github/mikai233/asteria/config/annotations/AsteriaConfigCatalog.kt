package io.github.mikai233.asteria.config.annotations

/**
 * Declares the generated config accessor names for a module.
 *
 * This annotation is optional. When it is absent, the KSP processor falls back to Gradle KSP options.
 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.BINARY)
annotation class AsteriaConfigCatalog(
    /**
     * Package of the generated Kotlin file. Blank means using the processor option or default package.
     */
    val packageName: String = "",
    /**
     * Name of the generated object that contains table references.
     */
    val tablesObjectName: String = "GameConfigTables",
    /**
     * Name of the generated dynamic accessor class.
     */
    val accessorClassName: String = "GameConfigs",
)

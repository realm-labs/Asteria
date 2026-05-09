package io.github.realmlabs.asteria.config.annotations

/**
 * Marks a [io.github.realmlabs.asteria.config.ConfigValidator] implementation for generated aggregation.
 *
 * Annotated validators must be public and implement `ConfigValidator`. They can be either `object` declarations or
 * concrete classes with a zero-argument primary constructor.
 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.BINARY)
annotation class AsteriaConfigValidator

/**
 * Declares generated config validator aggregation names for a module.
 *
 * This annotation is optional when the Gradle plugin supplies `asteria.config.validators.*` KSP options.
 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.BINARY)
annotation class AsteriaConfigValidatorCatalog(
    /**
     * Package of the generated Kotlin file. Blank means using the processor option or default package.
     */
    val packageName: String = "",
    /**
     * Name of the generated object that exposes the validator list.
     */
    val className: String = "GeneratedConfigValidators",
)

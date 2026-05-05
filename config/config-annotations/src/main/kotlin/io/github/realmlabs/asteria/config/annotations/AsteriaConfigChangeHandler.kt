package io.github.realmlabs.asteria.config.annotations

import kotlin.reflect.KClass

/**
 * Marks a [io.github.realmlabs.asteria.config.ConfigChangeHandler] implementation for generated aggregation.
 *
 * Annotated classes must be public, concrete, have a zero-argument constructor, and implement
 * `ConfigChangeHandler<Receiver>`, where `Receiver` is configured by `@AsteriaConfigChangeCatalog` or Gradle KSP
 * options.
 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.BINARY)
annotation class AsteriaConfigChangeHandler

/**
 * Declares generated config change handler aggregation names for a module.
 *
 * This annotation is optional when the Gradle plugin supplies `asteria.config.change.*` KSP options.
 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.BINARY)
annotation class AsteriaConfigChangeCatalog(
    /**
     * Package of the generated Kotlin file. Blank means using the processor option or default package.
     */
    val packageName: String = "",
    /**
     * Name of the generated object that exposes the handler list.
     */
    val className: String = "GeneratedConfigChangeHandlers",
    /**
     * Receiver type accepted by all generated handlers.
     */
    val receiverType: KClass<*> = Nothing::class,
)

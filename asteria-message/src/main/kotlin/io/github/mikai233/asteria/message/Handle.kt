package io.github.mikai233.asteria.message

import kotlin.reflect.KClass

@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
annotation class Handle(
    val message: KClass<*> = Any::class,
)

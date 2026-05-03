package io.github.realmlabs.asteria.script

import kotlin.reflect.KClass
import kotlin.reflect.full.createInstance

fun KClass<*>.toCompiledScript(): CompiledScript {
    return when (val instance = createInstance()) {
        is CompiledScript -> instance
        is BlockingScriptFunction -> instance.asCompiledScript()
        else -> error(
            "script class $qualifiedName must implement " +
                    "${CompiledScript::class.qualifiedName}, ${BlockingScriptFunction::class.qualifiedName}, " +
                    "or one of the typed script base classes",
        )
    }
}

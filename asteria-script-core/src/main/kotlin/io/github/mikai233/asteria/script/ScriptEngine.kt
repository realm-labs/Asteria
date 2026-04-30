package io.github.mikai233.asteria.script

interface ScriptEngine {
    val name: String

    fun compile(artifact: ScriptArtifact): CompiledScript
}

fun interface CompiledScript {
    suspend fun execute(context: ScriptContext): ScriptExecutionResult?
}

fun interface BlockingScriptFunction {
    fun execute(context: ScriptContext): ScriptExecutionResult?
}

fun BlockingScriptFunction.asCompiledScript(): CompiledScript {
    return CompiledScript { context -> execute(context) }
}

class ScriptEngineRegistry(
    engines: Iterable<ScriptEngine> = emptyList(),
) {
    private val enginesByName: MutableMap<String, ScriptEngine> = linkedMapOf()

    init {
        engines.forEach(::register)
    }

    fun register(engine: ScriptEngine) {
        check(engine.name.isNotBlank()) { "script engine name must not be blank" }
        check(engine.name !in enginesByName) { "script engine ${engine.name} already registered" }
        enginesByName[engine.name] = engine
    }

    fun engine(name: String): ScriptEngine {
        return requireNotNull(enginesByName[name]) { "script engine $name not found" }
    }

    fun all(): Collection<ScriptEngine> = enginesByName.values
}

class ScriptExecutor(
    private val engines: ScriptEngineRegistry,
) {
    suspend fun execute(context: ScriptContext): ScriptExecutionResult? {
        val compiled = engines.engine(context.artifact.engine).compile(context.artifact)
        return compiled.execute(context)
    }
}

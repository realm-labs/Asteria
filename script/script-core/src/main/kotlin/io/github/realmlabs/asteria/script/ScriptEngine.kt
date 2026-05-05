package io.github.realmlabs.asteria.script

/**
 * Compiles script artifacts for one engine name.
 *
 * Engines should be deterministic for a given artifact. Long-running compiled scripts are expected to cooperate with
 * cancellation by polling [ScriptContext.cancellation] around blocking or repeated work.
 */
interface ScriptEngine {
    val name: String

    fun compile(artifact: ScriptArtifact): CompiledScript
}

/**
 * Executable script produced by a [ScriptEngine].
 *
 * Returning `null` lets the runner create the default success result. Throwing is converted to the runner's failure
 * result and should be reserved for execution failures rather than business-level no-op outcomes.
 */
fun interface CompiledScript {
    suspend fun execute(context: ScriptContext): ScriptExecutionResult?
}

/**
 * Adapter for script functions that do not suspend.
 */
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
    /**
     * Compiles on every call so engine implementations can choose their own artifact cache policy.
     */
    suspend fun execute(context: ScriptContext): ScriptExecutionResult? {
        val compiled = engines.engine(context.artifact.engine).compile(context.artifact)
        return compiled.execute(context)
    }
}

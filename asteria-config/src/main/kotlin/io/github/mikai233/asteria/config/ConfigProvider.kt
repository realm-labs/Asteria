package io.github.mikai233.asteria.config

import kotlin.reflect.KClass

@JvmInline
value class ConfigKey(val value: String) {
    init {
        require(value.isNotBlank()) { "config key must not be blank" }
    }
}

interface ConfigSubscription {
    fun close()
}

interface ConfigProvider {
    suspend fun <T : Any> get(key: ConfigKey, type: KClass<T>): T

    fun <T : Any> watch(
        key: ConfigKey,
        type: KClass<T>,
        listener: suspend (T) -> Unit,
    ): ConfigSubscription
}

suspend inline fun <reified T : Any> ConfigProvider.get(key: ConfigKey): T {
    return get(key, T::class)
}

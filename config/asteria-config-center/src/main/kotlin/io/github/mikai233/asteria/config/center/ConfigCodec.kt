package io.github.mikai233.asteria.config.center

import kotlin.reflect.KClass

interface ConfigCodec {
    fun <T : Any> decode(bytes: ByteArray, type: KClass<T>): T

    fun <T : Any> encode(value: T, type: KClass<T>): ByteArray
}

inline fun <reified T : Any> ConfigCodec.decode(bytes: ByteArray): T {
    return decode(bytes, T::class)
}

inline fun <reified T : Any> ConfigCodec.encode(value: T): ByteArray {
    return encode(value, T::class)
}

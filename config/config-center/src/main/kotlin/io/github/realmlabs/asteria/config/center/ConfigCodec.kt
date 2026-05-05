package io.github.realmlabs.asteria.config.center

import kotlin.reflect.KClass

/**
 * Codec used by [RuntimeConfigRepository] to translate typed values to and from raw config-center payload bytes.
 *
 * The codec contract is whole-value oriented: callers provide the exact target [type] for every decode, and the codec
 * must either return a value of that type or throw. Partial decoding and best-effort coercion are intentionally left to
 * custom codecs rather than the repository layer.
 */
interface ConfigCodec {
    /**
     * Decodes [bytes] into an instance of [type].
     */
    fun <T : Any> decode(bytes: ByteArray, type: KClass<T>): T

    /**
     * Encodes [value] as raw bytes.
     */
    fun <T : Any> encode(value: T, type: KClass<T>): ByteArray
}

/**
 * Reified convenience overload for [ConfigCodec.decode].
 */
inline fun <reified T : Any> ConfigCodec.decode(bytes: ByteArray): T {
    return decode(bytes, T::class)
}

/**
 * Reified convenience overload for [ConfigCodec.encode].
 */
inline fun <reified T : Any> ConfigCodec.encode(value: T): ByteArray {
    return encode(value, T::class)
}

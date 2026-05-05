package io.github.realmlabs.asteria.config.center

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import kotlin.reflect.KClass

/**
 * Default JSON [ConfigCodec] based on Jackson Kotlin support.
 *
 * This codec assumes the stored payload is one JSON document per config value. It is a good default for operational
 * config-center data, while binary game-table payloads are usually loaded through dedicated config loaders instead.
 */
class JacksonConfigCodec(
    private val objectMapper: ObjectMapper = defaultConfigObjectMapper(),
) : ConfigCodec {
    override fun <T : Any> decode(bytes: ByteArray, type: KClass<T>): T {
        return objectMapper.readValue(bytes, type.java)
    }

    override fun <T : Any> encode(value: T, type: KClass<T>): ByteArray {
        return objectMapper.writeValueAsBytes(value)
    }
}

/**
 * Creates the default [ObjectMapper] used by [JacksonConfigCodec].
 */
fun defaultConfigObjectMapper(): ObjectMapper {
    return jacksonObjectMapper()
        .registerModule(JavaTimeModule())
}

package io.github.mikai233.asteria.config.center

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import kotlin.reflect.KClass

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

fun defaultConfigObjectMapper(): ObjectMapper {
    return jacksonObjectMapper()
        .registerModule(JavaTimeModule())
}

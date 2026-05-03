package io.github.realmlabs.asteria.observability.opentelemetry

import io.github.realmlabs.asteria.observability.MetricTags
import io.github.realmlabs.asteria.observability.TraceAttributes
import io.opentelemetry.api.common.Attributes

internal fun TraceAttributes.toOpenTelemetryAttributes(): Attributes {
    return Attributes.builder().apply {
        asMap().forEach { (key, value) -> put(key, value) }
    }.build()
}

internal fun MetricTags.toOpenTelemetryAttributes(): Attributes {
    return Attributes.builder().apply {
        asMap().forEach { (key, value) -> put(key, value) }
    }.build()
}

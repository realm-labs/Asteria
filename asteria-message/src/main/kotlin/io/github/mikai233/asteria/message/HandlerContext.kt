package io.github.mikai233.asteria.message

import io.github.mikai233.asteria.core.EntityKind
import io.github.mikai233.asteria.core.NodeRuntime

interface HandlerContext {
    val runtime: NodeRuntime
    val entityKind: EntityKind?
    val entityId: Any?
    val session: Any?
}

data class DefaultHandlerContext(
    override val runtime: NodeRuntime,
    override val entityKind: EntityKind? = null,
    override val entityId: Any? = null,
    override val session: Any? = null,
) : HandlerContext

package io.github.realmlabs.asteria.starter

import io.github.realmlabs.asteria.core.AsteriaApplicationBuilder
import io.github.realmlabs.asteria.ops.http.ktor.NodeLocalOpsHttpBuilder
import io.github.realmlabs.asteria.ops.http.ktor.NodeLocalOpsHttpModule

/**
 * Installs the node-local ops HTTP endpoint for SSH/curl based operations.
 */
fun AsteriaApplicationBuilder.nodeLocalOpsHttp(
    configure: NodeLocalOpsHttpBuilder.() -> Unit,
) {
    install(NodeLocalOpsHttpModule(configure))
}

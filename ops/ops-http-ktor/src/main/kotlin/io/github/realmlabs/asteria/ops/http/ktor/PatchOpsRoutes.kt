package io.github.realmlabs.asteria.ops.http.ktor

import io.github.realmlabs.asteria.core.ServiceRegistry
import io.github.realmlabs.asteria.patch.*
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.routing.*

fun Route.patchOpsRoutes(
    services: ServiceRegistry,
    options: NodeLocalOpsHttpOptions,
    tokenValidator: NodeLocalOpsTokenValidator,
) {
    route("/ops/patches") {
        get {
            val result = call.executeOpsAction(
                action = "ops.patch.list",
                options = options,
                tokenValidator = tokenValidator,
            ) {
                services.get<RuntimePatchRepository>().list(RuntimePatchQuery())
            }
            call.respond(result)
        }

        get("/node-results") {
            val result = call.executeOpsAction(
                action = "ops.patch.node-results",
                options = options,
                tokenValidator = tokenValidator,
            ) {
                services.find<PatchClusterApplicationService>()?.results(RuntimePatchNodeResultQuery())
                    ?: services.find<RuntimePatchNodeResultRepository>()?.list(RuntimePatchNodeResultQuery())
                    ?: emptyList()
            }
            call.respond(result)
        }

        get("/{patchId}") {
            val patchId = call.pathParameter("patchId")
            val result = call.executeOpsAction(
                action = "ops.patch.find",
                options = options,
                tokenValidator = tokenValidator,
                attributes = mapOf("patchId" to patchId),
            ) {
                services.get<RuntimePatchRepository>().find(PatchId(patchId))
                    ?: throw NodeLocalOpsHttpException(HttpStatusCode.NotFound, "patch $patchId not found")
            }
            call.respond(result)
        }

        post("/{patchId}/apply") {
            val patchId = call.pathParameter("patchId")
            val result = call.executeOpsAction(
                action = "ops.patch.apply",
                options = options,
                tokenValidator = tokenValidator,
                mutation = true,
                attributes = mapOf("patchId" to patchId),
            ) {
                val id = PatchId(patchId)
                services.get<RuntimePatchRepository>().updateStatus(id, PatchStatus.Enabled)
                    ?: throw NodeLocalOpsHttpException(HttpStatusCode.NotFound, "patch $patchId not found")
                services.find<PatchClusterApplicationService>()?.apply(id)
                    ?: services.get<PatchApplicationService>().let { localPatchApplyResult(it, it.apply(id)) }
            }
            call.respond(HttpStatusCode.Accepted, result)
        }

        post("/{patchId}/disable") {
            val patchId = call.pathParameter("patchId")
            val result = call.executeOpsAction(
                action = "ops.patch.disable",
                options = options,
                tokenValidator = tokenValidator,
                mutation = true,
                attributes = mapOf("patchId" to patchId),
            ) {
                val id = PatchId(patchId)
                services.get<RuntimePatchRepository>().updateStatus(id, PatchStatus.Disabled)
                    ?: throw NodeLocalOpsHttpException(HttpStatusCode.NotFound, "patch $patchId not found")
                services.find<PatchClusterApplicationService>()?.disable(id)
                    ?: localPatchDisableResult(services.get<PatchApplicationService>(), id)
            }
            call.respond(HttpStatusCode.Accepted, result)
        }

        post("/reconcile") {
            val result = call.executeOpsAction(
                action = "ops.patch.reconcile",
                options = options,
                tokenValidator = tokenValidator,
                mutation = true,
            ) {
                services.get<PatchApplicationService>().reconcileEnabledPatches()
            }
            call.respond(HttpStatusCode.Accepted, result)
        }
    }
}

private fun localPatchApplyResult(
    service: PatchApplicationService,
    result: PatchApplyResult,
): PatchClusterApplyResult {
    return PatchClusterApplyResult(
        patchId = result.patchId,
        requestedAt = java.time.Instant.now(),
        results = listOf(result.toNodeResult(service.environment)),
    )
}

private suspend fun localPatchDisableResult(
    service: PatchApplicationService,
    id: PatchId,
): PatchClusterApplyResult {
    val removed = service.disable(id)
    val environment = service.environment
    return PatchClusterApplyResult(
        patchId = id,
        requestedAt = java.time.Instant.now(),
        results = listOf(
            RuntimePatchNodeResult(
                patchId = id,
                nodeId = null,
                address = environment.nodeAddress ?: "local",
                appName = environment.appName,
                version = environment.version,
                roles = environment.roles,
                modules = environment.modules,
                capabilities = environment.capabilities,
                status = if (removed) RuntimePatchNodeStatus.Removed else RuntimePatchNodeStatus.Ignored,
                attempt = 1,
            ),
        ),
    )
}

private fun PatchApplyResult.toNodeResult(environment: PatchEnvironment): RuntimePatchNodeResult {
    return when (this) {
        is PatchApplyResult.Applied -> RuntimePatchNodeResult(
            patchId = patchId,
            nodeId = null,
            address = environment.nodeAddress ?: "local",
            appName = environment.appName,
            version = environment.version,
            roles = environment.roles,
            modules = environment.modules,
            capabilities = environment.capabilities,
            status = RuntimePatchNodeStatus.Applied,
            attempt = 1,
            operationCount = operationCount,
        )

        is PatchApplyResult.Ignored -> RuntimePatchNodeResult(
            patchId = patchId,
            nodeId = null,
            address = environment.nodeAddress ?: "local",
            appName = environment.appName,
            version = environment.version,
            roles = environment.roles,
            modules = environment.modules,
            capabilities = environment.capabilities,
            status = RuntimePatchNodeStatus.Ignored,
            attempt = 1,
            message = reason,
        )

        is PatchApplyResult.Failed -> RuntimePatchNodeResult(
            patchId = patchId,
            nodeId = null,
            address = environment.nodeAddress ?: "local",
            appName = environment.appName,
            version = environment.version,
            roles = environment.roles,
            modules = environment.modules,
            capabilities = environment.capabilities,
            status = RuntimePatchNodeStatus.Failed,
            attempt = 1,
            message = message,
        )
    }
}

private fun ApplicationCall.pathParameter(name: String): String {
    return requireNotNull(parameters[name]) { "path parameter $name is required" }
}

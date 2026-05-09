package io.github.realmlabs.asteria.ops.http.ktor

import io.github.realmlabs.asteria.core.ServiceRegistry
import io.github.realmlabs.asteria.script.ScriptRuntime
import io.github.realmlabs.asteria.script.job.*
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.*
import kotlin.time.Duration.Companion.milliseconds

fun Route.scriptOpsRoutes(
    services: ServiceRegistry,
    options: NodeLocalOpsHttpOptions,
    tokenValidator: NodeLocalOpsTokenValidator,
) {
    route("/ops/scripts") {
        post("/execute") {
            val result = call.executeOpsAction(
                action = "ops.script.execute",
                options = options,
                tokenValidator = tokenValidator,
                mutation = true,
            ) { principal ->
                val request = call.receiveOpsScriptCommand(principal, options.maxScriptBytes)
                services.get<ScriptRuntime>().executeAll(
                    command = request.command,
                    timeout = request.timeoutMillis.milliseconds,
                )
            }
            call.respond(result)
        }

        post("/jobs") {
            val result = call.executeOpsAction(
                action = "ops.script.job.submit",
                options = options,
                tokenValidator = tokenValidator,
                mutation = true,
            ) { principal ->
                val request = call.receiveOpsScriptCommand(principal, options.maxScriptBytes)
                services.get<ScriptJobService>().submit(
                    command = request.command,
                    timeout = request.timeoutMillis.milliseconds,
                )
            }
            call.respond(HttpStatusCode.Accepted, result)
        }

        get("/jobs") {
            val result = call.executeOpsAction(
                action = "ops.script.jobs.list",
                options = options,
                tokenValidator = tokenValidator,
                attributes = emptyMap(),
            ) {
                services.get<ScriptJobService>().listJobs(
                    ScriptJobQuery(
                        status = call.request.queryParameters["status"]?.let(ScriptJobStatus::valueOf),
                        requester = call.request.queryParameters["requester"],
                        offset = call.request.queryParameters["offset"]?.toInt() ?: 0,
                        limit = call.request.queryParameters["limit"]?.toInt() ?: 100,
                    ),
                )
            }
            call.respond(result)
        }

        get("/jobs/{jobId}") {
            val jobId = call.pathParameter("jobId")
            val result = call.executeOpsAction(
                action = "ops.script.job.find",
                options = options,
                tokenValidator = tokenValidator,
                attributes = mapOf("jobId" to jobId),
            ) {
                services.get<ScriptJobService>().find(ScriptJobId(jobId))
                    ?: throw NodeLocalOpsHttpException(HttpStatusCode.NotFound, "script job $jobId not found")
            }
            call.respond(result)
        }

        get("/jobs/{jobId}/summary") {
            val jobId = call.pathParameter("jobId")
            val result = call.executeOpsAction(
                action = "ops.script.job.summary",
                options = options,
                tokenValidator = tokenValidator,
                attributes = mapOf("jobId" to jobId),
            ) {
                services.get<ScriptJobService>().summarizeResults(ScriptJobId(jobId))
            }
            call.respond(result)
        }

        get("/jobs/{jobId}/items") {
            val jobId = call.pathParameter("jobId")
            val result = call.executeOpsAction(
                action = "ops.script.job.items.list",
                options = options,
                tokenValidator = tokenValidator,
                attributes = mapOf("jobId" to jobId),
            ) {
                services.get<ScriptJobService>().listItems(
                    ScriptJobId(jobId),
                    ScriptJobItemQuery(
                        status = call.request.queryParameters["status"]?.let(ScriptJobItemStatus::valueOf),
                        offset = call.request.queryParameters["offset"]?.toInt() ?: 0,
                        limit = call.request.queryParameters["limit"]?.toInt() ?: 100,
                    ),
                )
            }
            call.respond(result)
        }

        get("/jobs/{jobId}/items/{itemId}") {
            val jobId = call.pathParameter("jobId")
            val itemId = call.pathParameter("itemId")
            val result = call.executeOpsAction(
                action = "ops.script.job.item.find",
                options = options,
                tokenValidator = tokenValidator,
                attributes = mapOf("jobId" to jobId, "itemId" to itemId),
            ) {
                services.get<ScriptJobService>().findItem(ScriptJobId(jobId), ScriptJobItemId(itemId))
                    ?: throw NodeLocalOpsHttpException(HttpStatusCode.NotFound, "script job item $itemId not found")
            }
            call.respond(result)
        }

        post("/jobs/{jobId}/cancel") {
            val jobId = call.pathParameter("jobId")
            val request = call.receiveOrDefault { OpsScriptCancelRequest() }
            val result = call.executeOpsAction(
                action = "ops.script.job.cancel",
                options = options,
                tokenValidator = tokenValidator,
                mutation = true,
                attributes = mapOf("jobId" to jobId),
            ) { principal ->
                services.get<ScriptJobService>().cancelJob(
                    ScriptJobId(jobId),
                    ScriptJobCancellation(
                        requestedBy = principal.id,
                        reason = request.reason ?: principal.reason,
                    ),
                ) ?: throw NodeLocalOpsHttpException(HttpStatusCode.NotFound, "script job $jobId not found")
            }
            call.respond(HttpStatusCode.Accepted, result)
        }

        post("/jobs/{jobId}/items/{itemId}/cancel") {
            val jobId = call.pathParameter("jobId")
            val itemId = call.pathParameter("itemId")
            val request = call.receiveOrDefault { OpsScriptCancelRequest() }
            val result = call.executeOpsAction(
                action = "ops.script.job.item.cancel",
                options = options,
                tokenValidator = tokenValidator,
                mutation = true,
                attributes = mapOf("jobId" to jobId, "itemId" to itemId),
            ) { principal ->
                services.get<ScriptJobService>().cancelItem(
                    ScriptJobId(jobId),
                    ScriptJobItemId(itemId),
                    ScriptJobCancellation(
                        requestedBy = principal.id,
                        reason = request.reason ?: principal.reason,
                    ),
                ) ?: throw NodeLocalOpsHttpException(HttpStatusCode.NotFound, "script job item $itemId not found")
            }
            call.respond(HttpStatusCode.Accepted, result)
        }

        post("/jobs/{jobId}/items/{itemId}/retry") {
            val jobId = call.pathParameter("jobId")
            val itemId = call.pathParameter("itemId")
            val request = call.receiveOrDefault { OpsScriptRetryItemRequest() }
            val result = call.executeOpsAction(
                action = "ops.script.job.item.retry",
                options = options,
                tokenValidator = tokenValidator,
                mutation = true,
                attributes = mapOf("jobId" to jobId, "itemId" to itemId),
            ) { principal ->
                services.get<ScriptJobService>().retryItem(
                    jobId = ScriptJobId(jobId),
                    itemId = ScriptJobItemId(itemId),
                    timeout = request.timeoutMillis.milliseconds,
                    requestedBy = principal.id,
                )
            }
            call.respond(HttpStatusCode.Accepted, result)
        }

        post("/jobs/{jobId}/failed-items/retry") {
            val jobId = call.pathParameter("jobId")
            val request = call.receiveOrDefault { OpsScriptRetryFailedItemsRequest() }
            val result = call.executeOpsAction(
                action = "ops.script.job.failed-items.retry",
                options = options,
                tokenValidator = tokenValidator,
                mutation = true,
                attributes = buildMap {
                    put("jobId", jobId)
                    put("limit", request.limit.toString())
                    request.error?.let { put("error", it) }
                },
            ) { principal ->
                services.get<ScriptJobService>().retryFailedItems(
                    jobId = ScriptJobId(jobId),
                    request = request.toRetryRequest(),
                    timeout = request.timeoutMillis.milliseconds,
                    requestedBy = principal.id,
                )
            }
            call.respond(HttpStatusCode.Accepted, result)
        }
    }
}

private fun ApplicationCall.pathParameter(name: String): String {
    return requireNotNull(parameters[name]) { "path parameter $name is required" }
}

private suspend inline fun <reified T : Any> ApplicationCall.receiveOrDefault(default: () -> T): T {
    val contentLength = request.headers[HttpHeaders.ContentLength]?.toLongOrNull()
    return if (contentLength == null || contentLength == 0L) default() else receive()
}

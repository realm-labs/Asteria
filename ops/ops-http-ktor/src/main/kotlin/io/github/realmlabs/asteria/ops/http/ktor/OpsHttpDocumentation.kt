package io.github.realmlabs.asteria.ops.http.ktor

/**
 * Self-describing OPS endpoint document returned from `/ops`.
 */
data class OpsHttpDescription(
    val service: String = "asteria-node-local-ops",
    val authentication: OpsHttpAuthenticationDescription,
    val headers: List<OpsHttpHeaderDescription>,
    val limits: OpsHttpLimitDescription,
    val endpoints: List<OpsHttpEndpointDescription>,
    val schemas: Map<String, OpsHttpSchemaDescription>,
    val examples: Map<String, Any>,
)

/**
 * Authentication section of the OPS endpoint document.
 */
data class OpsHttpAuthenticationDescription(
    val scheme: String,
    val required: Boolean,
    val header: String = "Authorization: Bearer <token>",
)

/**
 * Header contract exposed by the OPS endpoint document.
 */
data class OpsHttpHeaderDescription(
    val name: String,
    val requiredForMutations: Boolean,
    val description: String,
)

/**
 * Runtime limits exposed by the OPS endpoint document.
 */
data class OpsHttpLimitDescription(
    val maxScriptBytes: Int,
)

/**
 * One route entry in the OPS endpoint document.
 */
data class OpsHttpEndpointDescription(
    val method: String,
    val path: String,
    val description: String,
    val body: String? = null,
)

/**
 * Schema entry used by the generated OPS endpoint document.
 */
data class OpsHttpSchemaDescription(
    val description: String,
    val fields: List<OpsHttpFieldDescription>,
)

/**
 * Field entry used by schema descriptions in the OPS endpoint document.
 */
data class OpsHttpFieldDescription(
    val name: String,
    val type: String,
    val required: Boolean,
    val description: String,
    val default: String? = null,
    val values: List<String>? = null,
)

/**
 * Builds the node-local OPS endpoint description from runtime options.
 */
fun nodeLocalOpsDescription(options: NodeLocalOpsHttpOptions): OpsHttpDescription {
    return OpsHttpDescription(
        authentication = OpsHttpAuthenticationDescription(
            scheme = "bearer-token",
            required = options.requireToken,
        ),
        headers = listOf(
            OpsHttpHeaderDescription(
                name = OpsHeaders.OPERATOR,
                requiredForMutations = options.requireOperator,
                description = "Operator id recorded in script metadata and audit events.",
            ),
            OpsHttpHeaderDescription(
                name = OpsHeaders.REASON,
                requiredForMutations = options.requireReasonForMutations,
                description = "Human-readable reason for the operation.",
            ),
            OpsHttpHeaderDescription(
                name = OpsHeaders.TICKET,
                requiredForMutations = false,
                description = "Optional ticket, incident, or approval id.",
            ),
            OpsHttpHeaderDescription(
                name = OpsHeaders.SOURCE,
                requiredForMutations = false,
                description = "Optional caller source, defaulting to node-local-http.",
            ),
        ),
        limits = OpsHttpLimitDescription(maxScriptBytes = options.maxScriptBytes),
        endpoints = listOf(
            OpsHttpEndpointDescription("GET", "/ops", "Returns this endpoint description."),
            OpsHttpEndpointDescription("GET", "/ops/health", "Returns basic service health."),
            OpsHttpEndpointDescription(
                "GET",
                "/ops/scripts/targets",
                "Returns the script targets this node's script runtime can route.",
            ),
            OpsHttpEndpointDescription(
                "POST",
                "/ops/scripts/execute",
                "Executes a script and returns a batch result. Accepts JSON or multipart/form-data.",
                "OpsScriptExecutionRequest or multipart target+artifact",
            ),
            OpsHttpEndpointDescription(
                "POST",
                "/ops/scripts/jobs",
                "Submits an asynchronous script job. Accepts JSON or multipart/form-data.",
                "OpsScriptExecutionRequest or multipart target+artifact",
            ),
            OpsHttpEndpointDescription("GET", "/ops/scripts/jobs", "Lists script jobs."),
            OpsHttpEndpointDescription("GET", "/ops/scripts/jobs/{jobId}", "Returns one script job."),
            OpsHttpEndpointDescription("GET", "/ops/scripts/jobs/{jobId}/summary", "Returns a job result summary."),
            OpsHttpEndpointDescription("GET", "/ops/scripts/jobs/{jobId}/items", "Lists script job items."),
            OpsHttpEndpointDescription(
                "GET",
                "/ops/scripts/jobs/{jobId}/items/{itemId}",
                "Returns one script job item."
            ),
            OpsHttpEndpointDescription(
                "POST",
                "/ops/scripts/jobs/{jobId}/cancel",
                "Cancels a script job.",
                "OpsScriptCancelRequest",
            ),
            OpsHttpEndpointDescription(
                "POST",
                "/ops/scripts/jobs/{jobId}/items/{itemId}/cancel",
                "Cancels one script job item.",
                "OpsScriptCancelRequest",
            ),
            OpsHttpEndpointDescription(
                "POST",
                "/ops/scripts/jobs/{jobId}/items/{itemId}/retry",
                "Retries one failed script job item.",
                "OpsScriptRetryItemRequest",
            ),
            OpsHttpEndpointDescription(
                "POST",
                "/ops/scripts/jobs/{jobId}/failed-items/retry",
                "Retries failed script job items selected by error bucket and limit.",
                "OpsScriptRetryFailedItemsRequest",
            ),
            OpsHttpEndpointDescription("GET", "/ops/patches", "Lists runtime patches."),
            OpsHttpEndpointDescription("GET", "/ops/patches/node-results", "Lists patch node results."),
            OpsHttpEndpointDescription("GET", "/ops/patches/{patchId}", "Returns one runtime patch."),
            OpsHttpEndpointDescription("POST", "/ops/patches/{patchId}/apply", "Enables and applies a patch."),
            OpsHttpEndpointDescription("POST", "/ops/patches/{patchId}/disable", "Disables and removes a patch."),
            OpsHttpEndpointDescription("POST", "/ops/patches/reconcile", "Reconciles this node with enabled patches."),
        ),
        schemas = nodeLocalOpsSchemas(),
        examples = mapOf(
            "executeEntityScript" to mapOf(
                "method" to "POST",
                "path" to "/ops/scripts/execute",
                "headers" to mapOf(
                    "Authorization" to "Bearer <token>",
                    OpsHeaders.OPERATOR to "mikai",
                    OpsHeaders.REASON to "repair-player",
                    "Content-Type" to "application/json",
                ),
                "body" to mapOf(
                    "target" to mapOf("type" to "entity", "kind" to "player", "ids" to listOf("1001")),
                    "artifact" to mapOf("engine" to "groovy", "bodyText" to "context.actor.repairPlayer('1001')"),
                ),
            ),
            "listScriptTargets" to mapOf(
                "command" to listOf(
                    "curl http://127.0.0.1:17321/ops/scripts/targets",
                    "-H 'Authorization: Bearer <token>'",
                    "-H 'X-Asteria-Operator: mikai'",
                ),
            ),
            "executeGroovyFile" to mapOf(
                "command" to listOf(
                    "curl -X POST http://127.0.0.1:17321/ops/scripts/execute",
                    "-H 'Authorization: Bearer <token>'",
                    "-H 'X-Asteria-Operator: mikai'",
                    "-H 'X-Asteria-Reason: repair-player'",
                    "-F 'target={\"type\":\"entity\",\"kind\":\"player\",\"ids\":[\"1001\"]}'",
                    "-F 'artifact=@./fix-player.groovy'",
                ),
            ),
            "submitJarFileJob" to mapOf(
                "command" to listOf(
                    "curl -X POST http://127.0.0.1:17321/ops/scripts/jobs",
                    "-H 'Authorization: Bearer <token>'",
                    "-H 'X-Asteria-Operator: mikai'",
                    "-H 'X-Asteria-Reason: repair-players'",
                    "-F 'target={\"type\":\"entity\",\"kind\":\"player\",\"ids\":[\"1001\"]}'",
                    "-F 'artifact=@./repair.jar'",
                ),
            ),
            "applyPatch" to mapOf(
                "method" to "POST",
                "path" to "/ops/patches/{patchId}/apply",
                "headers" to mapOf(
                    "Authorization" to "Bearer <token>",
                    OpsHeaders.OPERATOR to "mikai",
                    OpsHeaders.REASON to "apply-runtime-patch",
                ),
            ),
        ),
    )
}

private fun nodeLocalOpsSchemas(): Map<String, OpsHttpSchemaDescription> {
    return linkedMapOf(
        "OpsScriptExecutionRequest" to OpsHttpSchemaDescription(
            description = "JSON request body used by /ops/scripts/execute and /ops/scripts/jobs.",
            fields = listOf(
                OpsHttpFieldDescription(
                    "executionId",
                    "string",
                    false,
                    "Idempotency and audit id for this execution. Generated when absent.",
                ),
                OpsHttpFieldDescription("target", "ScriptTargetRequest", true, "Script routing target."),
                OpsHttpFieldDescription("artifact", "OpsScriptArtifactRequest", true, "Script artifact payload."),
                OpsHttpFieldDescription(
                    "metadata",
                    "OpsScriptMetadataRequest",
                    false,
                    "Additional metadata and resources."
                ),
                OpsHttpFieldDescription("options", "OpsScriptExecutionOptionsRequest", false, "Execution options."),
                OpsHttpFieldDescription(
                    "timeoutMillis",
                    "long",
                    false,
                    "Per-execution timeout in milliseconds.",
                    "3000"
                ),
            ),
        ),
        "multipart target+artifact" to OpsHttpSchemaDescription(
            description = "Multipart form alternative for /ops/scripts/execute and /ops/scripts/jobs. Prefer this for file uploads.",
            fields = listOf(
                OpsHttpFieldDescription(
                    "target",
                    "json string",
                    true,
                    "ScriptTargetRequest encoded as one form field."
                ),
                OpsHttpFieldDescription(
                    "artifact",
                    "file",
                    true,
                    "Groovy or jar script file. Engine is inferred from .groovy or .jar."
                ),
                OpsHttpFieldDescription(
                    "engine",
                    "string",
                    false,
                    "Required only when artifact filename is not .groovy or .jar."
                ),
                OpsHttpFieldDescription("name", "string", false, "Script name. Defaults to uploaded filename."),
                OpsHttpFieldDescription(
                    "executionId",
                    "string",
                    false,
                    "Idempotency and audit id. Generated when absent."
                ),
                OpsHttpFieldDescription(
                    "metadata",
                    "json string",
                    false,
                    "OpsScriptMetadataRequest encoded as one form field."
                ),
                OpsHttpFieldDescription(
                    "options",
                    "json string",
                    false,
                    "OpsScriptExecutionOptionsRequest encoded as one form field."
                ),
                OpsHttpFieldDescription(
                    "timeoutMillis",
                    "long",
                    false,
                    "Per-execution timeout in milliseconds.",
                    "3000"
                ),
            ),
        ),
        "ScriptTargetRequest" to OpsHttpSchemaDescription(
            description = "Runtime-neutral script target.",
            fields = listOf(
                OpsHttpFieldDescription(
                    "type",
                    "string",
                    true,
                    "Target type.",
                    values = listOf("all-nodes", "role", "nodes", "actor-paths", "entity", "singleton"),
                ),
                OpsHttpFieldDescription("role", "string", false, "Required when type is role."),
                OpsHttpFieldDescription("addresses", "string[]", false, "Required when type is nodes."),
                OpsHttpFieldDescription("paths", "string[]", false, "Required when type is actor-paths."),
                OpsHttpFieldDescription("kind", "string", false, "Required when type is entity."),
                OpsHttpFieldDescription("ids", "string[]", false, "Required when type is entity."),
                OpsHttpFieldDescription("name", "string", false, "Required when type is singleton."),
            ),
        ),
        "OpsScriptTargetCapabilitiesResponse" to OpsHttpSchemaDescription(
            description = "Current node script routing capabilities returned by /ops/scripts/targets.",
            fields = listOf(
                OpsHttpFieldDescription(
                    "supportedTargetTypes",
                    "string[]",
                    true,
                    "Target types supported by the installed script runtime.",
                ),
                OpsHttpFieldDescription(
                    "entityKinds",
                    "string[]",
                    false,
                    "Entity kinds this node can route through a shard region or proxy.",
                    "[]",
                ),
                OpsHttpFieldDescription(
                    "singletons",
                    "string[]",
                    false,
                    "Singleton names this node can route through a singleton actor or proxy.",
                    "[]",
                ),
            ),
        ),
        "OpsScriptArtifactRequest" to OpsHttpSchemaDescription(
            description = "Script bytes encoded for JSON transport.",
            fields = listOf(
                OpsHttpFieldDescription(
                    "name",
                    "string",
                    false,
                    "Script name used for diagnostics and policy.",
                    "ops-script"
                ),
                OpsHttpFieldDescription("engine", "string", true, "Script engine name, for example groovy or jar."),
                OpsHttpFieldDescription(
                    "bodyText",
                    "string",
                    false,
                    "Plain UTF-8 script body. Use this for curl text scripts."
                ),
                OpsHttpFieldDescription(
                    "bodyBase64",
                    "string",
                    false,
                    "Base64-encoded script body. Required when bodyText is absent."
                ),
                OpsHttpFieldDescription("extraBase64", "string", false, "Optional base64-encoded sidecar bytes."),
                OpsHttpFieldDescription("checksum", "string", false, "Optional artifact checksum."),
            ),
        ),
        "OpsScriptMetadataRequest" to OpsHttpSchemaDescription(
            description = "Human and machine metadata for one execution.",
            fields = listOf(
                OpsHttpFieldDescription(
                    "reason",
                    "string",
                    false,
                    "Reason for this script. Header reason is used when absent."
                ),
                OpsHttpFieldDescription(
                    "attributes",
                    "map<string,string>",
                    false,
                    "Additional metadata attributes.",
                    "{}"
                ),
                OpsHttpFieldDescription(
                    "resources",
                    "OpsScriptResourceRequest[]",
                    false,
                    "External resource references.",
                    "[]"
                ),
            ),
        ),
        "OpsScriptResourceRequest" to OpsHttpSchemaDescription(
            description = "External resource reference available to script code.",
            fields = listOf(
                OpsHttpFieldDescription("name", "string", true, "Unique resource name."),
                OpsHttpFieldDescription("uri", "string", true, "Resource URI."),
                OpsHttpFieldDescription("checksum", "string", false, "Optional checksum verified by resolvers."),
                OpsHttpFieldDescription("format", "string", false, "Optional format hint, such as csv or json."),
                OpsHttpFieldDescription("sizeBytes", "long", false, "Optional resource size hint."),
                OpsHttpFieldDescription(
                    "attributes",
                    "map<string,string>",
                    false,
                    "Resolver-specific attributes.",
                    "{}"
                ),
            ),
        ),
        "OpsScriptExecutionOptionsRequest" to OpsHttpSchemaDescription(
            description = "Execution controls for script jobs.",
            fields = listOf(
                OpsHttpFieldDescription(
                    "maxConcurrentItems",
                    "int",
                    false,
                    "Optional per-job item concurrency request stored in metadata.",
                ),
            ),
        ),
        "OpsScriptCancelRequest" to OpsHttpSchemaDescription(
            description = "Optional body for job or item cancellation. Empty body is accepted.",
            fields = listOf(
                OpsHttpFieldDescription(
                    "reason",
                    "string",
                    false,
                    "Cancellation reason. Header reason is used when absent.",
                ),
            ),
        ),
        "OpsScriptRetryItemRequest" to OpsHttpSchemaDescription(
            description = "Optional body for retrying one failed item. Empty body is accepted.",
            fields = listOf(
                OpsHttpFieldDescription(
                    "timeoutMillis",
                    "long",
                    false,
                    "Retry attempt timeout in milliseconds.",
                    "3000"
                ),
            ),
        ),
        "OpsScriptRetryFailedItemsRequest" to OpsHttpSchemaDescription(
            description = "Optional body for retrying failed items. Empty body is accepted.",
            fields = listOf(
                OpsHttpFieldDescription(
                    "error",
                    "string",
                    false,
                    "Only retry failed items whose error summary matches."
                ),
                OpsHttpFieldDescription("limit", "int", false, "Maximum number of failed items to retry.", "100"),
                OpsHttpFieldDescription(
                    "timeoutMillis",
                    "long",
                    false,
                    "Retry attempt timeout in milliseconds.",
                    "3000"
                ),
            ),
        ),
    )
}

package io.github.realmlabs.asteria.script

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

class ScriptPolicyTest {
    @Test
    fun defaultPolicyRejectsForbiddenApiTokens(): Unit = runBlocking {
        val policy = DefaultScriptPolicy(
            allowNodeScripts = true,
            allowedEngines = setOf("groovy"),
        )

        val authorization = policy.authorize(request(body = "Runtime.getRuntime().exec('rm -rf /')"))

        assertIs<ScriptAuthorization.Denied>(authorization)
    }

    @Test
    fun defaultPolicyRequiresApprovalSignatureTemplateAndPermissionsWhenConfigured(): Unit = runBlocking {
        val policy = DefaultScriptPolicy(
            allowActorScripts = true,
            allowedEngines = setOf("groovy"),
            allowedTargetTypes = setOf("entity"),
            approvalRequired = true,
            signatureRequired = true,
            templateRequired = true,
            enginePermissions = mapOf("groovy" to "script.engine.groovy"),
            targetPermissions = mapOf("entity" to "script.target.entity"),
            signatureVerifier = ScriptSignatureVerifier { _, signature -> signature == "valid" },
            templateCatalog = ScriptTemplateCatalog { templateId -> templateId == "tpl-1" },
        )

        val authorization = policy.authorize(
            request(
                scope = ScriptExecutionScope.Actor,
                metadata = ScriptExecutionMetadata(
                    attributes = mapOf(
                        ScriptSecurityAttributes.APPROVED_BY to "lead-gm",
                        ScriptSecurityAttributes.SIGNATURE to "valid",
                        ScriptSecurityAttributes.TEMPLATE_ID to "tpl-1",
                        ScriptSecurityAttributes.PERMISSIONS to "script.engine.groovy,script.target.entity",
                    ),
                ),
            ),
        )

        assertIs<ScriptAuthorization.Allowed>(authorization)
    }

    @Test
    fun compositeAuditSinkNotifiesLaterSinksBeforeRethrowing(): Unit = runBlocking {
        val events = mutableListOf<String>()
        val sink = CompositeScriptAuditSink(
            listOf(
                object : ScriptAuditSink {
                    override suspend fun started(request: ScriptExecutionRequest) {
                        events += "first"
                        error("audit failed")
                    }
                },
                object : ScriptAuditSink {
                    override suspend fun started(request: ScriptExecutionRequest) {
                        events += "second"
                    }
                },
            ),
        )

        val error = assertFailsWith<IllegalStateException> {
            sink.started(request())
        }

        assertEquals("audit failed", error.message)
        assertEquals(listOf("first", "second"), events)
    }

    private fun request(
        body: String = "println('ok')",
        scope: ScriptExecutionScope = ScriptExecutionScope.Node,
        metadata: ScriptExecutionMetadata = ScriptExecutionMetadata(),
    ): ScriptExecutionRequest {
        return ScriptExecutionRequest(
            executionId = "exec-1",
            target = ScriptTarget.Entity(io.github.realmlabs.asteria.core.EntityKind("player"), listOf("1")),
            artifact = ScriptArtifact("test", "groovy", body.encodeToByteArray()),
            scope = scope,
            metadata = metadata,
        )
    }
}

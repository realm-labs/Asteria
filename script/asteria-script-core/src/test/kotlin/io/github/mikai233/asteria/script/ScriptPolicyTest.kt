package io.github.mikai233.asteria.script

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertIs

class ScriptPolicyTest {
    @Test
    fun defaultPolicyRejectsForbiddenApiTokens() = runBlocking {
        val policy = DefaultScriptPolicy(
            allowNodeScripts = true,
            allowedEngines = setOf("groovy"),
        )

        val authorization = policy.authorize(request(body = "Runtime.getRuntime().exec('rm -rf /')"))

        assertIs<ScriptAuthorization.Denied>(authorization)
    }

    @Test
    fun defaultPolicyRequiresApprovalSignatureTemplateAndPermissionsWhenConfigured() = runBlocking {
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
                        ScriptSecurityAttributes.ApprovedBy to "lead-gm",
                        ScriptSecurityAttributes.Signature to "valid",
                        ScriptSecurityAttributes.TemplateId to "tpl-1",
                        ScriptSecurityAttributes.Permissions to "script.engine.groovy,script.target.entity",
                    ),
                ),
            ),
        )

        assertIs<ScriptAuthorization.Allowed>(authorization)
    }

    private fun request(
        body: String = "println('ok')",
        scope: ScriptExecutionScope = ScriptExecutionScope.Node,
        metadata: ScriptExecutionMetadata = ScriptExecutionMetadata(),
    ): ScriptExecutionRequest {
        return ScriptExecutionRequest(
            executionId = "exec-1",
            target = ScriptTarget.Entity(io.github.mikai233.asteria.core.EntityKind("player"), listOf("1")),
            artifact = ScriptArtifact("test", "groovy", body.encodeToByteArray()),
            scope = scope,
            metadata = metadata,
        )
    }
}

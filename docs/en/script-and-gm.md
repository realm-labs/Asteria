# Script and GM

Script modules are intended for operational scripts, GM repair flows, and bulk diagnostics. GM modules provide feature
metadata, permissions, audit context, and Spring HTTP starters. They can be used separately or combined into a full
operations backend.

## Script Runtime

`script-core` defines script resources, contexts, policies, targets, and results. Concrete engines are provided by
`script-engine-groovy` or `script-engine-jar`.

```kotlin
val command = ScriptExecutionCommand(
    executionId = "gm-20260505-001",
    target = ScriptTarget.Entity(kind = "world", ids = listOf("1001")),
    resource = ScriptResource.Inline(
        name = "fix-world",
        language = "groovy",
        content = "world.rebuildIndex()",
    ),
    metadata = ScriptExecutionMetadata(requester = "gm:alice"),
)

val result = scriptRuntime.execute(command)
```

Script policy should be defined by the project according to risk: allowed languages, timeout, target scope, approval
state, and callable functions.

## Pekko Script Targets

`script-pekko` maps script targets to Pekko runtime:

- `AllNodes`: execute on every node.
- `Role`: execute on nodes with a role.
- `Node`: execute on selected nodes.
- `ActorPath`: execute on selected actor paths.
- `Entity`: execute on selected sharding entities.
- `Singleton`: execute on selected cluster singletons.

Business actors must expose a script execution entry point, or inherit/compose the `ScriptableAsteriaActor` support.

`ScriptModule` defaults `allowNodeScripts` and `allowActorScripts` to `false`. Without explicit enablement, the default
policy rejects those targets. Actor path, entity, and singleton targets also require target actors to integrate
`ActorScriptSupport`.

## Async Script Jobs

`script-job` expands multi-target scripts into items, persists status, executes in the background, and exposes retry,
cancel, and export operations:

```kotlin
val job = scriptJobService.submit(command, timeout = 3.seconds)

val page = scriptJobService.listItems(job.id)
val summary = scriptJobService.summarizeResults(job.id)

scriptJobService.retryFailedItems(job.id, requestedBy = "gm:alice")
scriptJobService.cancelJob(job.id, ScriptJobCancellation(requestedBy = "gm:alice"))
```

`ScriptJobService.submit` means orchestration state has been written; it does not mean the script completed. Query APIs
must be used to read results.

`timeout` is the timeout for one item attempt, not a deadline for the whole job. `executionId` and target affect
idempotency and replay semantics, so resubmitting the same command should not be treated as guaranteed re-execution.

## Throttling and Leases

`ScriptJobExecutionLimiter` controls concurrency and external permits. The Mongo permit repository uses leases to
prevent multiple workers from executing the same item. Transient backend failures are retried only while the current
lease remains valid; if ownership cannot be proven, execution fails closed.

Projects can explicitly use a noop limiter, but production systems should usually keep throttling and permits enabled.

## GM Feature

A GM feature is metadata for an operations capability:

```kotlin
class RechargeFeature : GmFeature {
    override val descriptor = GmFeatureDescriptor(
        id = GmFeatureId("recharge"),
        name = "Recharge",
        permissions = listOf(
            GmPermission(GmPermissionKey("recharge.grant"), "Grant Recharge", highRisk = true),
        ),
        menus = listOf(
            GmMenuItem(id = "recharge", title = "Recharge", route = "/recharge"),
        ),
    )
}
```

`GmFeatureRegistry` rejects duplicate feature ids and duplicate permission keys. Optional modules publish features
through Java `ServiceLoader`; Spring starters expose feature lists and concrete HTTP APIs.

`highRisk` is metadata only. It does not automatically add approval, MFA, or ticket workflows. Production systems should
implement those policies in `GmPrincipalResolver`, `GmPolicyEvaluator`, or business controllers.

## Spring Starters

- `gm-spring-boot-starter`: feature metadata API, principal support, exception handling.
- `gm-script-spring-boot-starter`: script submit, query, retry, and cancel HTTP APIs.
- `gm-config-spring-boot-starter`: config snapshot query and cluster config control.
- `gm-cluster-spring-boot-starter`: cluster status and actor query.
- `gm-patch-spring-boot-starter`: patch management.

Security is provided by the business Spring application. The framework provides permission keys and audit models, but
does not decide login, approval, MFA, or ticket workflow.

The default noop principal resolver does not authenticate users, so GM HTTP endpoints will reject requests. Applications
should provide at least a `GmPrincipalResolver`, and usually a `GmAuditSink` plus a policy evaluator that matches the
project permission model.

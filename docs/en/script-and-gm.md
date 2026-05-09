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

Business actors must expose a script execution entry point, usually by composing `ActorScriptSupport` and merging
`ActorScriptSupport.receive()` into the receive states that should accept scripts.

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

## Shutdown Orchestration

`gm-shutdown` provides a runtime-neutral framework for business-side graceful shutdown. A plan runs phases in order, and
each phase runs its steps in order. The framework records status, timeouts, failures, and GM permission metadata. It does
not bind itself to player actors, world actors, gateways, or process-exit semantics.

```kotlin
install(gmShutdownModule {
    phase("gateway-drain") {
        step("stop-accepting") { context ->
            context.services.get<GatewayControl>().stopAccepting()
            GmShutdownStepResult.succeeded()
        }
        step("close-sessions") { context ->
            context.services.get<GatewayControl>().closeAllSessions()
            GmShutdownStepResult.succeeded()
        }
    }

    phase("player-drain") {
        step("flush-players") { context ->
            context.services.get<PlayerDrainService>().drainOnlinePlayers()
            GmShutdownStepResult.succeeded()
        }
    }

    phase("world-drain") {
        step("flush-worlds") { context ->
            context.services.get<WorldDrainService>().drainWorlds()
            GmShutdownStepResult.succeeded()
        }
    }
})
```

GM or operations controllers can retrieve `GmShutdownOperations` from `ServiceRegistry`, submit a `GmShutdownRequest`,
and inspect `status()`. After business drain completes, the final step can call a business-provided node-exit service, or
only mark the node as `ready-to-exit` and let the deployment system scale down or send SIGTERM.

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

## Node-Local Ops HTTP

`ops-http-ktor` provides a GM-independent node-local HTTP endpoint for SSH/curl operations. Operators can SSH to any
game
node and execute scripts or trigger patch controls through loopback HTTP. The endpoint defaults to a local bind address
and is intended as a lightweight operations control plane when no GM node is deployed.

```kotlin
nodeLocalOpsHttp {
    host = "127.0.0.1"
    port = 17321
    tokenFile = Path.of("/var/lib/asteria/ops-token")
}
```

Without the starter, install `NodeLocalOpsHttpModule` directly. The module reads a bearer token on startup;
`requireToken` defaults to `true`, and startup fails unless `token` or `tokenFile` is configured. In production, the
token file should be readable only by trusted operations users or the service account.

Inspect the endpoint description first after logging into a node:

```bash
curl http://127.0.0.1:17321/ops
```

```bash
curl -X POST http://127.0.0.1:17321/ops/scripts/execute \
  -H "Authorization: Bearer $(cat /var/lib/asteria/ops-token)" \
  -H "X-Asteria-Operator: mikai" \
  -H "X-Asteria-Reason: repair-player" \
  -F 'target={"type":"entity","kind":"player","ids":["1001"]}' \
  -F 'artifact=@./fix-player.groovy'
```

Available endpoints include:

- `GET /ops`: returns auth headers, limits, endpoint list, and request examples.
- `POST /ops/scripts/execute`: execute a script and return a batch result.
- `POST /ops/scripts/jobs`: submit an asynchronous script job.
- `GET /ops/scripts/jobs`, `GET /ops/scripts/jobs/{jobId}`, `GET /ops/scripts/jobs/{jobId}/items`: inspect jobs.
- `POST /ops/scripts/jobs/{jobId}/cancel`, `POST /ops/scripts/jobs/{jobId}/items/{itemId}/retry`: cancel or retry work.
- `POST /ops/patches/{patchId}/apply`, `POST /ops/patches/{patchId}/disable`, `POST /ops/patches/reconcile`: apply,
  disable, or reconcile runtime patches.

This endpoint only handles local HTTP authentication and request conversion. The installed `ScriptPolicy` still decides
whether a script is allowed to run. High-risk deployments should require `X-Asteria-Reason`, ticket or approval
metadata,
and a `NodeLocalOpsAuditSink` that records operations to an independent audit stream.

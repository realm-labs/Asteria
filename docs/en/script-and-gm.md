# Script and GM

Script modules are intended for operational scripts, GM repair flows, and bulk diagnostics. GM modules provide feature
metadata, operation authorization entry points, audit context, and Spring HTTP starters. They can be used separately or
combined into a full
operations backend.

## Script Runtime

`script-core` defines script resources, contexts, policies, targets, and results. Concrete engines are provided by
`script-engine-groovy` or `script-engine-jar`.
The core execution chain is:

1. The entry point builds a `ScriptExecutionCommand`. `executionId` is the idempotency and audit key, `target` describes
   routing, `artifact` carries script bytes and the engine name, and `metadata` carries operator, reason, approval,
   permission, ticket, and resource references.
2. `ScriptRuntime` routes the command. `execute` is for one effective result, `executeAll` collects all observable
   results before the timeout, and `dispatch` is fire-and-forget.
3. The target node or actor converts the command into a `ScriptExecutionRequest`, adding `scope`, node address, or actor
   path.
4. `ScriptRunner` calls `ScriptPolicy.authorize` first. Rejections are audited and returned as failed results. Allowed
   requests go through `ScriptExecutionStore` for running/completed checks before `ScriptExecutor` is invoked.
5. `ScriptExecutor` resolves the engine from `ScriptEngineRegistry`, compiles by `artifact.engine`, and executes the
   script. The script sees a `ScriptContext` with runtime, services, metadata, resource access, and an optional
   cancellation token.

```kotlin
val command = ScriptExecutionCommand(
    executionId = "gm-20260505-001",
    target = ScriptTarget.Entity(kind = EntityKind("world"), ids = listOf("1001")),
    artifact = ScriptArtifact(
        name = "fix-world",
        engine = "groovy",
        body = "world.rebuildIndex()".encodeToByteArray(),
    ),
    metadata = ScriptExecutionMetadata(requester = "gm:alice"),
)

val result = scriptRuntime.execute(command)
```

`ScriptPolicy` is the hard pre-execution boundary. The default policy can restrict node/actor scope, allowed engines,
allowed target types, artifact size, approval, signatures, templates, engine/target permissions, and obvious dangerous
API tokens. The default permission check only reads `metadata.attributes["script.permissions"]`; production systems
usually need their own permission authorizer, signature verifier, template catalog, and audit sink.

`ScriptTarget` only describes where a script should go; it does not grant permission. Current targets are `AllNodes`,
`Role`, `Node`, `ActorPath`, `Entity`, and `Singleton`. Routing capability, fan-out behavior, and actor cooperation are
runtime-specific.

## Groovy and jar Artifacts

`ScriptArtifact.engine` must match a registered script engine. The Groovy engine compiles artifact body bytes as UTF-8
Groovy source. The source can be a top-level script or a script class implementing `BlockingScriptFunction`,
`CompiledScript`, `NodeScript`, or `ActorScript`. The jar engine treats body bytes as a JAR; its manifest must contain
`Script-Class`, naming a class that can be adapted by `toCompiledScript`. Both engines cache by checksum when provided,
otherwise by body content.

Top-level Groovy scripts receive these binding variables: `context`, `runtime`, `services`, `request`, `artifact`,
`metadata`, `resources`, `tables`, and `cancellation`. Node contexts also expose `target` and `nodeAddress`; actor
contexts also expose `actor`, `target`, and `actorPath`. Normal completion is reported as success; throwing an error is
reported as failure.

```groovy
// fix-player.groovy
actor.repairPlayer("1001")
```

## Large Resources

Do not put large files into `bodyText`, `bodyBase64`, or multipart artifacts. The script request should carry only
resource references; the actual files should live on a local path, shared storage, HTTP endpoint, or object storage:

```json
{
  "metadata": {
    "resources": [
      {
        "name": "compensation",
        "uri": "s3://ops/compensation.csv",
        "checksum": "sha256:<hex>",
        "format": "csv",
        "attributes": {
          "downloadUrl": "https://..."
        }
      }
    ]
  }
}
```

The default resolver only supports node-local paths and `file:` URIs. Configure `ScriptModule` explicitly when scripts
need to read HTTP or object-store resources:

```kotlin
install(ScriptModule {
    engine(GroovyScriptEngine())
    allowNodeScripts = true
    allowActorScripts = true
    resourceCache(Path.of("/var/lib/asteria/script-resources"))
})
```

`resourceCache` registers a `CachingScriptResourceResolver`: remote resources are downloaded to a node-local cache on
first use and verified by checksum. Use `resourceResolver(customResolver)` when the application needs its own storage,
authorization, or download behavior.

Uploading a Groovy or jar file only converts the file into a `ScriptArtifact` at the entry layer; it does not bypass
policy. File size is constrained by both the entry-point configuration and `ScriptPolicy.maxArtifactBytes`. Checksums,
signatures, template ids, approvers, permissions, and ticket ids should be passed in metadata attributes and validated
by project policy.

Scripts and jar classes run inside the service process and can call business services through `ScriptContext.services`.
Treat submission endpoints, `ScriptPolicy`, review/signature workflows, audit, and service-account permissions as the
security boundary; Groovy/JAR execution is not an isolation sandbox.

## Pekko Script Targets

`script-pekko` maps script targets to Pekko runtime:

- `AllNodes`: execute on every node.
- `Role`: execute on nodes with a role.
- `Node`: execute on selected nodes.
- `ActorPath`: execute on selected actor paths.
- `Entity`: execute on selected sharding entities.
- `Singleton`: execute on selected cluster singletons.

`PekkoScriptRuntime` is backed by the local `ScriptRuntimeActor`. `AllNodes` and `Role` execute locally first, then
publish through distributed pub-sub; `Node` only executes on matching cluster addresses; `ActorPath` sends actor
selection messages one by one; `Entity` routes through `EntityShardRegistry`; `Singleton` sends through
`SingletonActorRegistry`. `executeAll` installs a temporary collector and returns results received before the timeout,
so missing targets or slow replies may produce partial or empty batches.

When `ClusterViewService` is installed, `executeAll` also fills `ScriptExecutionBatchResult.expectedTargets` and
`missingTargets`. `AllNodes` and `Role` are expanded from the cluster view, including configured nodes that are not
currently reachable; `Node`, `ActorPath`, `Entity`, and `Singleton` are expanded from the command target itself. A batch
with missing targets is failed even if all returned results are successful.

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

On submit, `ScriptJobService` creates a `ScriptJob` and expands the target into items: `Node` by address, `ActorPath`
by path, and `Entity` by id. `AllNodes`, `Role`, and `Singleton` remain one item and leave fan-out to the runtime. Each
item attempt derives its execution id as `sourceExecutionId.itemId.attempt` and adds `script.jobId`, `script.itemId`,
`script.attempt`, `script.workerId`, and `script.sourceExecutionId` to metadata.

Item status normally moves `Pending -> Running -> Completed/Failed/Cancelled`. Cancelling a pending item is immediate
in the repository; cancelling a running item depends on cooperative script cancellation or recovery after lease expiry.
Retry is only valid for failed items, creates a new attempt, and schedules it asynchronously. `summarizeResults`
groups failures by error, and `exportResults` emits item-level CSV.

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
        actions = listOf(
            GmActionDescriptor(GmAction("recharge.grant"), "Grant Recharge", risk = GmRiskLevel.High),
        ),
        menus = listOf(
            GmMenuItem(id = "recharge", title = "Recharge", route = "/recharge"),
        ),
    )
}
```

`GmFeatureRegistry` is an immutable feature catalog. It rejects duplicate feature ids and duplicate action keys at
startup so extension modules cannot silently shadow each other. A feature descriptor is metadata only: feature
id/name/description, actions, menus, and routes. Optional modules publish features through Java `ServiceLoader`;
`gm-spring-boot-starter` combines ServiceLoader-discovered features with `GmFeature` Spring beans, then exposes feature,
action, menu, and route lists. Concrete business APIs are still provided by the individual starters or business
controllers.

`risk` is metadata only. It does not automatically add approval, MFA, or ticket workflows. Production systems should
implement those policies in `GmPrincipalResolver` and `GmAuthorizationPolicy`.

## GM Script Target Capabilities

The GM node uses its installed `ScriptRuntime` as the script entry point. To route an `entity` or `singleton` script,
the
GM node must start the corresponding shard region/proxy or singleton/proxy; those refs are registered in
`EntityShardRegistry` and `SingletonActorRegistry`.

`GET /gm/api/scripts/metadata` returns available engines, target types, and the `entityKinds` and `singletons` currently
registered on the GM node. The frontend can use those values directly as selectable actor types. Actor paths are
explicit paths supplied by the caller and are not registered in metadata.

Script submission and single-item retry first check whether the requested entity kind or singleton is routable from the
current GM node. After that check passes, the command is submitted to `ScriptRuntime` for actual cluster routing.
Business rules such as player existence, active world checks, or guild validation should be implemented by application
`GmScriptTargetValidator`s.

## Shutdown Orchestration

`gm-shutdown` provides a runtime-neutral framework for business-side graceful shutdown. A plan runs phases in order, and
each phase runs its steps in order. The framework records status, timeouts, failures, and GM action metadata. It does
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
and inspect `status()`. After business drain completes, the final step can call a business-provided node-exit service,
or
only mark the node as `ready-to-exit` and let the deployment system scale down or send SIGTERM.

## Spring Starters

- `gm-spring-boot-starter`: feature metadata API, principal support, exception handling.
- `gm-script-spring-boot-starter`: script submit, query, retry, and cancel HTTP APIs.
- `gm-config-spring-boot-starter`: config snapshot query and cluster config control.
- `gm-config-center-spring-boot-starter`: read-only raw `ConfigStore` tree browsing and safe entry previews.
- `gm-cluster-spring-boot-starter`: cluster status and actor query.
- `gm-patch-spring-boot-starter`: patch management.

GM HTTP DTOs use Kotlin data classes and value classes. Spring Boot 4 uses Jackson 3, so the HTTP mapper must register
`tools.jackson.module.kotlin.KotlinModule`; otherwise constructor parameters, default values, and value class fields can
be handled incorrectly. `gm-spring-boot-starter` provides this module bean automatically so Boot's HTTP `JsonMapper`
uses it.

Applications that do not use the GM starter but manually register GM controllers or reuse GM DTOs must add and register
the module themselves:

```kotlin
dependencies {
    implementation("tools.jackson.module:jackson-module-kotlin")
}

@Configuration(proxyBeanMethods = false)
class GmJacksonConfiguration {
    @Bean
    @ConditionalOnMissingBean(KotlinModule::class)
    fun kotlinModule(): KotlinModule {
        return KotlinModule.Builder().build()
    }
}
```

Security is provided by the business Spring application. The framework provides action, operation, resource, and audit
models, but
does not decide login, approval, MFA, or ticket workflow.

The default noop principal resolver does not authenticate users, so GM HTTP endpoints will reject requests. Applications
should provide at least a `GmPrincipalResolver`, and usually a `GmAuditSink` plus a `GmAuthorizationPolicy` that matches
the project permission model. The default authorization policy denies every operation.

## Node-Local Ops HTTP

`ops-http-ktor` provides a GM-independent node-local HTTP endpoint for SSH/curl operations. Operators can SSH to any
game node and execute scripts or trigger patch controls through loopback HTTP. The endpoint defaults to a local bind
address and is intended as a lightweight operations control plane when no GM node is deployed.

```kotlin
nodeLocalOpsHttp {
    host = "127.0.0.1"
    port = 17321
    tokenFile = Path.of("/var/lib/asteria/ops-token")
}
```

Without the starter, install `NodeLocalOpsHttpModule` directly. The module reads a bearer token on startup;
`requireToken` defaults to `true`, and startup fails unless `token` or `tokenFile` is configured. `requireOperator`
requires `X-Asteria-Operator` by default, and `requireReasonForMutations` requires `X-Asteria-Reason` for mutating
requests by default. In production, the token file should be readable only by trusted operations users or the service
account, and the endpoint should usually bind to loopback or a controlled management network.

The request path is: Ktor routes validate bearer token, operator, and reason, then convert headers into a
`NodeLocalOpsPrincipal`; JSON body or multipart form is converted into `ScriptExecutionCommand`; the synchronous entry
calls `ScriptRuntime.executeAll`, and the asynchronous entry calls `ScriptJobService.submit`; success and failure are
recorded through `NodeLocalOpsAuditSink`. Local HTTP audit only proves this endpoint received the request. Script-level
allow/deny and execution audit are still owned by `ScriptPolicy`, `ScriptAuditSink`, and `ScriptJobAuditSink`.

OPS HTTP can report the script routing capabilities available from the current node:

```bash
curl http://127.0.0.1:17321/ops/scripts/targets \
  -H "Authorization: Bearer $(cat /var/lib/asteria/ops-token)" \
  -H "X-Asteria-Operator: mikai"
```

The returned `entityKinds` come from the node's `EntityShardRegistry`, and `singletons` come from
`SingletonActorRegistry`. A player node usually reports the player entity kind it can route through a shard region or
proxy. A global node usually reports the singleton/proxy names it can route. Actor paths are explicit paths and are not
registered or restricted by this endpoint.

`POST /ops/scripts/execute` and `POST /ops/scripts/jobs` check the requested target against these capabilities before
submitting the command. If this node cannot route the requested entity kind or singleton, the HTTP request is rejected;
after the check passes, `ScriptRuntime` performs the actual cluster routing.

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

For multipart uploads, `artifact=@./fix-player.groovy` infers engine `groovy`, and `artifact=@./repair.jar` infers
engine `jar`; other filenames must provide an explicit `engine` field. JSON requests can send script content as
`bodyText` or `bodyBase64`. The endpoint only checks maximum bytes and basic request shape. Whether Groovy/JAR is
allowed, and whether signature, approval, or permissions are required, is still decided by the installed
`ScriptPolicy`.

Available endpoints include:

- `GET /ops`: returns auth headers, limits, endpoint list, and request examples.
- `GET /ops/health`: health check for the local endpoint.
- `GET /ops/scripts/targets`: returns entity kinds and singletons routable from this node's script runtime.
- `POST /ops/scripts/execute`: execute a script and return a batch result.
- `POST /ops/scripts/jobs`: submit an asynchronous script job.
- `GET /ops/scripts/jobs`, `GET /ops/scripts/jobs/{jobId}`, `GET /ops/scripts/jobs/{jobId}/summary`,
  `GET /ops/scripts/jobs/{jobId}/items`, `GET /ops/scripts/jobs/{jobId}/items/{itemId}`: inspect jobs, summaries, and
  items.
- `POST /ops/scripts/jobs/{jobId}/cancel`, `POST /ops/scripts/jobs/{jobId}/items/{itemId}/cancel`,
  `POST /ops/scripts/jobs/{jobId}/items/{itemId}/retry`, `POST /ops/scripts/jobs/{jobId}/failed-items/retry`: cancel or
  retry work.
- `GET /ops/patches`, `GET /ops/patches/{patchId}`, `GET /ops/patches/node-results`: inspect patch descriptors and
  node results.
- `POST /ops/patches/{patchId}/apply`, `POST /ops/patches/{patchId}/disable`, `POST /ops/patches/reconcile`: apply,
  disable, or reconcile runtime patches.

This endpoint only handles local HTTP authentication and request conversion. The installed `ScriptPolicy` still decides
whether a script is allowed to run. High-risk deployments should require `X-Asteria-Reason`, ticket or approval
metadata, and a `NodeLocalOpsAuditSink` that records operations to an independent audit stream.

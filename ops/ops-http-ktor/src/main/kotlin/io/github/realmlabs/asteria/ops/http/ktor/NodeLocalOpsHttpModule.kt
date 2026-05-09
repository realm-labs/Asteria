package io.github.realmlabs.asteria.ops.http.ktor

import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import io.github.realmlabs.asteria.core.AsteriaDsl
import io.github.realmlabs.asteria.core.AsteriaModule
import io.github.realmlabs.asteria.core.ModuleContext
import io.ktor.serialization.jackson.jackson
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.plugins.NotFoundException
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import org.slf4j.LoggerFactory
import java.nio.file.Path
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Installs a node-local HTTP endpoint for SSH/curl based operations.
 *
 * The module is intentionally independent from GM. It exposes a small control surface over the services already
 * installed in the node, defaults to loopback binding, and requires a bearer token unless explicitly configured
 * otherwise.
 */
class NodeLocalOpsHttpModule private constructor(
    private val options: NodeLocalOpsHttpOptions,
) : AsteriaModule {
    override val name: String = "node-local-ops-http"

    private var server: EmbeddedServer<*, *>? = null

    override suspend fun install(context: ModuleContext) {
        context.services.register(NodeLocalOpsHttpOptions::class, options)
    }

    override suspend fun start(context: ModuleContext) {
        if (!options.enabled) {
            return
        }
        val tokenValidator = options.tokenValidator()
        val engine = embeddedServer(CIO, host = options.host, port = options.port) {
            installNodeLocalOpsApplication(context, options, tokenValidator)
        }
        engine.start(wait = false)
        server = engine
        logger.info("node-local ops HTTP started at {}:{}", options.host, options.port)
    }

    override suspend fun stop(context: ModuleContext) {
        server?.stop(options.stopGracePeriod.inWholeMilliseconds, options.stopTimeout.inWholeMilliseconds)
        server = null
    }

    companion object {
        private val logger = LoggerFactory.getLogger(NodeLocalOpsHttpModule::class.java)

        operator fun invoke(configure: NodeLocalOpsHttpBuilder.() -> Unit = {}): NodeLocalOpsHttpModule {
            return NodeLocalOpsHttpModule(NodeLocalOpsHttpBuilder().apply(configure).build())
        }
    }
}

data class NodeLocalOpsHttpOptions(
    val enabled: Boolean = true,
    val host: String = "127.0.0.1",
    val port: Int = 17321,
    val requireToken: Boolean = true,
    val token: String? = null,
    val tokenFile: Path? = null,
    val requireOperator: Boolean = true,
    val requireReasonForMutations: Boolean = true,
    val maxScriptBytes: Int = 1024 * 1024,
    val stopGracePeriod: Duration = 1.seconds,
    val stopTimeout: Duration = 5.seconds,
    val auditSink: NodeLocalOpsAuditSink = NoopNodeLocalOpsAuditSink,
) {
    init {
        require(host.isNotBlank()) { "node-local ops HTTP host must not be blank" }
        require(port in 1..65535) { "node-local ops HTTP port must be in 1..65535" }
        require(maxScriptBytes > 0) { "node-local ops max script bytes must be positive" }
        require(stopGracePeriod >= Duration.ZERO) { "node-local ops stop grace period must not be negative" }
        require(stopTimeout > Duration.ZERO) { "node-local ops stop timeout must be positive" }
        token?.let { require(it.isNotBlank()) { "node-local ops token must not be blank" } }
    }
}

@AsteriaDsl
class NodeLocalOpsHttpBuilder {
    var enabled: Boolean = true
    var host: String = "127.0.0.1"
    var port: Int = 17321
    var requireToken: Boolean = true
    var token: String? = null
    var tokenFile: Path? = null
    var requireOperator: Boolean = true
    var requireReasonForMutations: Boolean = true
    var maxScriptBytes: Int = 1024 * 1024
    var stopGracePeriod: Duration = 1.seconds
    var stopTimeout: Duration = 5.seconds
    private var auditSink: NodeLocalOpsAuditSink = NoopNodeLocalOpsAuditSink

    fun auditSink(auditSink: NodeLocalOpsAuditSink) {
        this.auditSink = auditSink
    }

    internal fun build(): NodeLocalOpsHttpOptions {
        return NodeLocalOpsHttpOptions(
            enabled = enabled,
            host = host,
            port = port,
            requireToken = requireToken,
            token = token,
            tokenFile = tokenFile,
            requireOperator = requireOperator,
            requireReasonForMutations = requireReasonForMutations,
            maxScriptBytes = maxScriptBytes,
            stopGracePeriod = stopGracePeriod,
            stopTimeout = stopTimeout,
            auditSink = auditSink,
        )
    }
}

private fun Application.installNodeLocalOpsApplication(
    context: ModuleContext,
    options: NodeLocalOpsHttpOptions,
    tokenValidator: NodeLocalOpsTokenValidator,
) {
    install(ContentNegotiation) {
        jackson {
            registerKotlinModule()
            registerModule(JavaTimeModule())
            disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
        }
    }
    install(StatusPages) {
        exception<NodeLocalOpsHttpException> { call, cause ->
            call.respond(cause.status, OpsErrorResponse(cause.message))
        }
        exception<BadRequestException> { call, cause ->
            call.respond(io.ktor.http.HttpStatusCode.BadRequest, OpsErrorResponse(cause.message ?: "bad request"))
        }
        exception<IllegalArgumentException> { call, cause ->
            call.respond(io.ktor.http.HttpStatusCode.BadRequest, OpsErrorResponse(cause.message ?: "bad request"))
        }
        exception<IllegalStateException> { call, cause ->
            call.respond(io.ktor.http.HttpStatusCode.ServiceUnavailable, OpsErrorResponse(cause.message ?: "unavailable"))
        }
        exception<NotFoundException> { call, cause ->
            call.respond(io.ktor.http.HttpStatusCode.NotFound, OpsErrorResponse(cause.message ?: "not found"))
        }
        exception<Throwable> { call, cause ->
            call.application.environment.log.error("node-local ops request failed", cause)
            call.respond(io.ktor.http.HttpStatusCode.InternalServerError, OpsErrorResponse("internal server error"))
        }
    }
    routing {
        get("/ops") {
            call.respond(nodeLocalOpsDescription(options))
        }
        get("/ops/health") {
            call.respond(mapOf("status" to "ok"))
        }
        scriptOpsRoutes(context.services, options, tokenValidator)
        patchOpsRoutes(context.services, options, tokenValidator)
    }
}

private fun NodeLocalOpsHttpOptions.tokenValidator(): NodeLocalOpsTokenValidator {
    return if (!requireToken) {
        AllowAllNodeLocalOpsTokenValidator
    } else {
        StaticNodeLocalOpsTokenValidator.from(token, tokenFile)
    }
}

suspend fun <T> ApplicationCall.executeOpsAction(
    action: String,
    options: NodeLocalOpsHttpOptions,
    tokenValidator: NodeLocalOpsTokenValidator,
    mutation: Boolean = false,
    attributes: Map<String, String> = emptyMap(),
    block: suspend (NodeLocalOpsPrincipal) -> T,
): T {
    val principal = authenticateNodeLocalOps(options, tokenValidator)
    if (mutation && options.requireReasonForMutations) {
        require(!request.headers[OpsHeaders.REASON].isNullOrBlank()) {
            "node-local ops mutation requires ${OpsHeaders.REASON}"
        }
    }
    val eventBase = NodeLocalOpsAuditEvent(
        action = action,
        principal = principal,
        attributes = attributes,
    )
    return try {
        val result = block(principal)
        options.auditSink.record(eventBase.copy(succeeded = true))
        result
    } catch (error: Throwable) {
        options.auditSink.record(eventBase.copy(succeeded = false, error = error.message ?: error::class.qualifiedName))
        throw error
    }
}

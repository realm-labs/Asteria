package io.github.realmlabs.asteria.gm.cluster.pekko.management

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Duration

/**
 * Minimal client for the Pekko Management Cluster HTTP API.
 */
class PekkoManagementHttpClient(
    private val transport: PekkoManagementHttpTransport = JavaPekkoManagementHttpTransport(),
    private val objectMapper: ObjectMapper = jacksonObjectMapper(),
) {
    suspend fun rawMembers(endpoint: PekkoManagementEndpoint): String {
        return request(
            endpoint = endpoint,
            method = "GET",
            path = "/cluster/members/",
        ).body
    }

    suspend fun members(endpoint: PekkoManagementEndpoint): PekkoManagementMembersResponse {
        val raw = rawMembers(endpoint)
        val json = objectMapper.readTree(raw)
        return PekkoManagementMembersResponse(
            raw = raw,
            selfNode = json.text("selfNode"),
            leader = json.text("leader"),
            oldest = json.text("oldest"),
            members = json["members"].arrayValues().map { it.toMember() },
            unreachable = json["unreachable"].arrayValues().mapNotNull { it.textValue() },
        )
    }

    suspend fun leave(endpoint: PekkoManagementEndpoint, address: String): PekkoManagementOperationResponse {
        return operation(
            endpoint = endpoint,
            method = "DELETE",
            path = "/cluster/members/${encodePath(address)}",
        )
    }

    suspend fun down(endpoint: PekkoManagementEndpoint, address: String): PekkoManagementOperationResponse {
        return operation(
            endpoint = endpoint,
            method = "PUT",
            path = "/cluster/members/${encodePath(address)}",
            form = mapOf("operation" to "Down"),
        )
    }

    suspend fun join(endpoint: PekkoManagementEndpoint, seedAddress: String): PekkoManagementOperationResponse {
        return operation(
            endpoint = endpoint,
            method = "POST",
            path = "/cluster/members/",
            form = mapOf("address" to seedAddress),
        )
    }

    private suspend fun operation(
        endpoint: PekkoManagementEndpoint,
        method: String,
        path: String,
        form: Map<String, String> = emptyMap(),
    ): PekkoManagementOperationResponse {
        val response = request(
            endpoint = endpoint,
            method = method,
            path = path,
            form = form,
        )
        return PekkoManagementOperationResponse(
            statusCode = response.statusCode,
            body = response.body,
            endpoint = endpoint.normalizedBaseUrl,
        )
    }

    private suspend fun request(
        endpoint: PekkoManagementEndpoint,
        method: String,
        path: String,
        form: Map<String, String> = emptyMap(),
    ): PekkoManagementHttpResponse {
        val response = transport.send(
            PekkoManagementHttpRequest(
                method = method,
                url = endpoint.normalizedBaseUrl + path,
                form = form,
            ),
        )
        if (response.statusCode !in 200..299) {
            throw PekkoManagementHttpException(response.statusCode, response.body, endpoint.normalizedBaseUrl)
        }
        return response
    }
}

/**
 * Transport boundary used by tests and alternative HTTP clients.
 */
fun interface PekkoManagementHttpTransport {
    suspend fun send(request: PekkoManagementHttpRequest): PekkoManagementHttpResponse
}

data class PekkoManagementHttpRequest(
    val method: String,
    val url: String,
    val form: Map<String, String> = emptyMap(),
)

data class PekkoManagementHttpResponse(
    val statusCode: Int,
    val body: String,
)

class PekkoManagementHttpException(
    val statusCode: Int,
    val responseBody: String,
    val endpoint: String,
) : RuntimeException("Pekko Management request failed with HTTP $statusCode from $endpoint: $responseBody")

/**
 * Java HTTP client transport used by default so the adapter stays independent from Spring WebClient.
 */
class JavaPekkoManagementHttpTransport(
    private val client: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(3))
        .build(),
    private val timeout: Duration = Duration.ofSeconds(10),
) : PekkoManagementHttpTransport {
    override suspend fun send(request: PekkoManagementHttpRequest): PekkoManagementHttpResponse {
        val body = encodeForm(request.form)
        val builder = HttpRequest.newBuilder()
            .uri(URI.create(request.url))
            .timeout(timeout)
        if (request.form.isEmpty()) {
            builder.method(request.method, HttpRequest.BodyPublishers.noBody())
        } else {
            builder
                .header("Content-Type", "application/x-www-form-urlencoded")
                .method(request.method, HttpRequest.BodyPublishers.ofString(body))
        }
        val httpRequest = builder.build()
        return withContext(Dispatchers.IO) {
            val response = client.send(httpRequest, HttpResponse.BodyHandlers.ofString())
            PekkoManagementHttpResponse(response.statusCode(), response.body())
        }
    }
}

data class PekkoManagementMembersResponse(
    val raw: String,
    val selfNode: String?,
    val leader: String?,
    val oldest: String?,
    val members: List<PekkoManagementMember>,
    val unreachable: List<String>,
)

data class PekkoManagementMember(
    val node: String,
    val nodeUid: String?,
    val status: String,
    val roles: Set<String>,
)

data class PekkoManagementOperationResponse(
    val statusCode: Int,
    val body: String,
    val endpoint: String,
)

private fun JsonNode?.arrayValues(): List<JsonNode> {
    if (this == null || !isArray) return emptyList()
    return this.toList()
}

private fun JsonNode.toMember(): PekkoManagementMember {
    return PekkoManagementMember(
        node = text("node") ?: error("Pekko Management member node is missing"),
        nodeUid = text("nodeUid"),
        status = text("status") ?: "unknown",
        roles = this["roles"].arrayValues().mapNotNullTo(linkedSetOf()) { it.textValue() },
    )
}

private fun JsonNode.text(field: String): String? {
    return this[field]?.takeIf { !it.isNull }?.asText()
}

private fun encodePath(value: String): String {
    return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20")
}

private fun encodeForm(form: Map<String, String>): String {
    return form.entries.joinToString("&") { (key, value) ->
        "${encodeFormPart(key)}=${encodeFormPart(value)}"
    }
}

private fun encodeFormPart(value: String): String {
    return URLEncoder.encode(value, StandardCharsets.UTF_8)
}

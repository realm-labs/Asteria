package io.github.mikai233.asteria.gm.cluster.pekko.management

import io.github.mikai233.asteria.gm.cluster.GmClusterDownRequest
import io.github.mikai233.asteria.gm.cluster.GmClusterJoinRequest
import io.github.mikai233.asteria.gm.cluster.GmClusterLeaveRequest
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class PekkoManagementGmClusterServicesTest {
    @Test
    fun `status service maps management members to gm status`() = runBlocking {
        val transport = RecordingTransport {
            PekkoManagementHttpResponse(
                statusCode = 200,
                body = """
                    {
                      "selfNode": "pekko://game@10.0.0.1:25520",
                      "members": [
                        {
                          "node": "pekko://game@10.0.0.1:25520",
                          "nodeUid": "1",
                          "status": "Up",
                          "roles": ["player"]
                        },
                        {
                          "node": "pekko://game@10.0.0.2:25520",
                          "nodeUid": "2",
                          "status": "Leaving",
                          "roles": ["battle"]
                        }
                      ],
                      "unreachable": ["pekko://game@10.0.0.2:25520"],
                      "leader": "pekko://game@10.0.0.1:25520",
                      "oldest": "pekko://game@10.0.0.1:25520"
                    }
                """.trimIndent(),
            )
        }
        val service = PekkoManagementGmClusterStatusService(
            client = PekkoManagementHttpClient(transport),
            endpoints = PekkoManagementEndpointResolver(
                listOf(PekkoManagementEndpoint("http://127.0.0.1:7626")),
            ),
        )

        val status = service.current()

        assertEquals(2, status.nodes.size)
        assertEquals("1", status.nodes[0].nodeId)
        assertEquals("Up", status.nodes[0].status)
        assertContains(status.nodes[0].roles, "player")
        assertEquals("true", status.nodes[0].attributes["self"])
        assertEquals("true", status.nodes[1].attributes["unreachable"])
        assertEquals("http://127.0.0.1:7626/cluster/members/", transport.requests.single().url)
    }

    @Test
    fun `control service sends leave down and join to management endpoints`() = runBlocking {
        val transport = RecordingTransport {
            PekkoManagementHttpResponse(statusCode = 200, body = "accepted")
        }
        val control = PekkoManagementGmClusterControlService(
            client = PekkoManagementHttpClient(transport),
            endpoints = PekkoManagementEndpointResolver(
                listOf(
                    PekkoManagementEndpoint("http://10.0.0.1:7626", "pekko://game@10.0.0.1:25520"),
                    PekkoManagementEndpoint("http://10.0.0.2:7626", "pekko://game@10.0.0.2:25520"),
                ),
            ),
        )

        control.leave(
            GmClusterLeaveRequest(
                address = "pekko://game@10.0.0.1:25520",
                requestedBy = "ops",
                reason = "maintenance",
            ),
        )
        control.down(
            GmClusterDownRequest(
                address = "pekko://game@10.0.0.1:25520",
                requestedBy = "ops",
                reason = "partition recovery",
                confirmed = true,
            ),
        )
        control.join(
            GmClusterJoinRequest(
                nodeAddress = "pekko://game@10.0.0.2:25520",
                seedAddress = "pekko://game@10.0.0.1:25520",
                requestedBy = "ops",
                reason = "scale up",
            ),
        )

        assertEquals("DELETE", transport.requests[0].method)
        assertTrue(transport.requests[0].url.startsWith("http://10.0.0.1:7626/cluster/members/"))
        assertEquals("PUT", transport.requests[1].method)
        assertEquals(mapOf("operation" to "Down"), transport.requests[1].form)
        assertEquals("POST", transport.requests[2].method)
        assertEquals("http://10.0.0.2:7626/cluster/members/", transport.requests[2].url)
        assertEquals(mapOf("address" to "pekko://game@10.0.0.1:25520"), transport.requests[2].form)
    }

    @Test
    fun `down requires explicit confirmation`() = runBlocking {
        val control = PekkoManagementGmClusterControlService(
            client = PekkoManagementHttpClient(RecordingTransport { PekkoManagementHttpResponse(200, "accepted") }),
            endpoints = PekkoManagementEndpointResolver(
                listOf(PekkoManagementEndpoint("http://10.0.0.1:7626")),
            ),
        )

        assertFailsWith<IllegalArgumentException> {
            control.down(
                GmClusterDownRequest(
                    address = "pekko://game@10.0.0.1:25520",
                    requestedBy = "ops",
                    reason = "manual down",
                    confirmed = false,
                ),
            )
        }
    }
}

private class RecordingTransport(
    private val response: suspend (PekkoManagementHttpRequest) -> PekkoManagementHttpResponse,
) : PekkoManagementHttpTransport {
    val requests: MutableList<PekkoManagementHttpRequest> = mutableListOf()

    override suspend fun send(request: PekkoManagementHttpRequest): PekkoManagementHttpResponse {
        requests += request
        return response(request)
    }
}

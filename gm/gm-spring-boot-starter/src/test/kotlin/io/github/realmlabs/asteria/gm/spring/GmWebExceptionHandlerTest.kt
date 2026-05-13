package io.github.realmlabs.asteria.gm.spring

import org.springframework.http.HttpStatus
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpInputMessage
import org.springframework.http.converter.HttpMessageNotReadableException
import java.io.ByteArrayInputStream
import java.io.InputStream
import kotlin.test.Test
import kotlin.test.assertEquals

class GmWebExceptionHandlerTest {
    @Test
    fun `request body parse errors return bad request response`(): Unit {
        val response = GmWebExceptionHandler().requestBodyNotReadable(
            HttpMessageNotReadableException(
                "JSON parse error",
                IllegalArgumentException("missing required field reason"),
                EmptyHttpInputMessage,
            ),
        )

        assertEquals(HttpStatus.BAD_REQUEST, response.statusCode)
        assertEquals("gm.bad_request", response.body?.code)
        assertEquals("missing required field reason", response.body?.message)
    }

    private object EmptyHttpInputMessage : HttpInputMessage {
        override fun getBody(): InputStream = ByteArrayInputStream(ByteArray(0))

        override fun getHeaders(): HttpHeaders = HttpHeaders()
    }
}

package io.github.realmlabs.asteria.gm.spring

import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice

class GmAuthenticationRequiredException(message: String) : RuntimeException(message)

class GmAccessDeniedException(message: String) : RuntimeException(message)

data class GmErrorResponse(
    val code: String,
    val message: String,
)

/**
 * Converts framework-level GM security errors into stable HTTP responses.
 */
@RestControllerAdvice
class GmWebExceptionHandler {
    @ExceptionHandler(GmAuthenticationRequiredException::class)
    fun authenticationRequired(error: GmAuthenticationRequiredException): ResponseEntity<GmErrorResponse> {
        return ResponseEntity
            .status(HttpStatus.UNAUTHORIZED)
            .body(GmErrorResponse("gm.authentication_required", error.message ?: "GM authentication required"))
    }

    @ExceptionHandler(GmAccessDeniedException::class)
    fun accessDenied(error: GmAccessDeniedException): ResponseEntity<GmErrorResponse> {
        return ResponseEntity
            .status(HttpStatus.FORBIDDEN)
            .body(GmErrorResponse("gm.access_denied", error.message ?: "GM access denied"))
    }

    @ExceptionHandler(HttpMessageNotReadableException::class)
    fun requestBodyNotReadable(error: HttpMessageNotReadableException): ResponseEntity<GmErrorResponse> {
        return ResponseEntity
            .status(HttpStatus.BAD_REQUEST)
            .body(
                GmErrorResponse(
                    code = "gm.bad_request",
                    message = error.mostSpecificCause.message ?: "invalid GM request body",
                ),
            )
    }
}

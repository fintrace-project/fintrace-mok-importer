package com.github.melancholic.fintrace.importer.integration

import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpRequest
import org.springframework.http.HttpStatusCode
import org.springframework.http.client.ClientHttpRequestExecution
import org.springframework.http.client.ClientHttpRequestInterceptor
import org.springframework.http.client.ClientHttpResponse
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream

class CallLoggingInterceptor(private val target: String) : ClientHttpRequestInterceptor {

    override fun intercept(
        request: HttpRequest,
        body: ByteArray,
        execution: ClientHttpRequestExecution
    ): ClientHttpResponse {
        val call = "$target ${request.method} ${request.uri}"
        logger.debug { "$call request headers=${masked(request.headers)} body=${excerpt(body)}" }

        val started = System.nanoTime()
        val response = try {
            execution.execute(request, body)
        } catch (e: IOException) {
            logger.info { "$call failed after ${elapsedMillis(started)} ms: ${e.javaClass.simpleName}" }
            throw e
        }
        logger.info { "$call -> ${response.statusCode.value()} in ${elapsedMillis(started)} ms" }

        if (!logger.isDebugEnabled()) return response
        // Reading the body for the log consumes the stream, so the caller gets a buffered copy
        val buffered = BufferedResponse(response)
        logger.debug { "$call response headers=${masked(response.headers)} body=${excerpt(buffered.bytes)}" }
        return buffered
    }

    private fun masked(headers: HttpHeaders): String = buildString {
        append('{')
        headers.forEach { name, values ->
            if (length > 1) append(", ")
            append(name).append('=').append(if (name.lowercase() in SECRET_HEADERS) "***" else values)
        }
        append('}')
    }

    private fun excerpt(bytes: ByteArray): String = when {
        bytes.isEmpty() -> "<empty>"
        bytes.size <= MAX_LOGGED_BODY -> masked(String(bytes))
        else -> masked(String(bytes, 0, MAX_LOGGED_BODY)) + "… (${bytes.size} bytes)"
    }

    private fun masked(body: String): String = body
        .replace(SECRET_FORM_FIELD, "$1$2=***")
        .replace(SECRET_JSON_FIELD, "$1\"***\"")

    private fun elapsedMillis(started: Long) = (System.nanoTime() - started) / 1_000_000

    private class BufferedResponse(private val delegate: ClientHttpResponse) : ClientHttpResponse {
        val bytes: ByteArray = delegate.body.use { it.readAllBytes() }

        override fun getStatusCode(): HttpStatusCode = delegate.statusCode
        override fun getStatusText(): String = delegate.statusText
        override fun getHeaders(): HttpHeaders = delegate.headers
        override fun getBody(): InputStream = ByteArrayInputStream(bytes)
        override fun close() = delegate.close()
    }

    private companion object {
        private val logger = KotlinLogging.logger {}

        // An import payload runs to megabytes; the head of it is what a debugging session needs
        const val MAX_LOGGED_BODY = 8 * 1024
        val SECRET_HEADERS = setOf("authorization", "proxy-authorization", "cookie", "set-cookie")

        private val SECRET_FIELDS = listOf(
            "subject_token", "actor_token", "access_token", "refresh_token", "id_token", "client_secret",
        ).joinToString("|")
        val SECRET_FORM_FIELD = Regex("""(^|&)($SECRET_FIELDS)=[^&]*""")
        val SECRET_JSON_FIELD = Regex("""("(?:$SECRET_FIELDS)"\s*:\s*)"[^"]*"?""")
    }
}

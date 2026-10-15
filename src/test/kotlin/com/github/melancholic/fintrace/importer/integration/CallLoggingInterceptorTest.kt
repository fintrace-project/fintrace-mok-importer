package com.github.melancholic.fintrace.importer.integration

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.slf4j.LoggerFactory
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.http.client.ClientHttpRequestExecution
import org.springframework.mock.http.client.MockClientHttpRequest
import org.springframework.mock.http.client.MockClientHttpResponse
import java.io.IOException
import java.net.URI
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CallLoggingInterceptorTest {

    private val logger = LoggerFactory.getLogger(CallLoggingInterceptor::class.java) as Logger
    private val appender = ListAppender<ILoggingEvent>()
    private val interceptor = CallLoggingInterceptor("core")

    @BeforeEach
    fun attach() {
        appender.start()
        logger.addAppender(appender)
    }

    @AfterEach
    fun detach() {
        logger.detachAppender(appender)
        logger.level = null
    }

    @Test
    fun `logs the call and its status at INFO`() {
        logger.level = Level.INFO

        interceptor.intercept(request(), ByteArray(0), answering("{}"))

        val line = appender.list.single()
        assertEquals(Level.INFO, line.level)
        assertTrue(line.formattedMessage.startsWith("core GET http://core/api/v1/workspaces/1 -> 200 in "), line.formattedMessage)
    }

    @Test
    fun `at DEBUG logs headers and bodies, and the caller can still read the body`() {
        logger.level = Level.DEBUG

        val response = interceptor.intercept(request(), "{\"a\":1}".toByteArray(), answering("{\"job\":1}"))

        assertEquals("{\"job\":1}", String(response.body.readAllBytes()), "logging must not consume the body")
        val debug = appender.list.filter { it.level == Level.DEBUG }.map { it.formattedMessage }
        assertTrue(debug[0].contains("body={\"a\":1}"), debug[0])
        assertTrue(debug[1].contains("body={\"job\":1}"), debug[1])
    }

    @Test
    fun `never logs a credential`() {
        logger.level = Level.DEBUG

        interceptor.intercept(request(), ByteArray(0), answering("{}"))

        val all = appender.list.joinToString("\n") { it.formattedMessage }
        assertFalse(all.contains("secret-token"), all)
        assertTrue(all.contains("Authorization=***"), all)
    }

    @Test
    fun `masks the tokens in a form body and keeps the other fields`() {
        logger.level = Level.DEBUG
        val form = "subject_token=callers-token&audience=fintrace-core&client_secret=s3cret&actor_token=actors-token"

        interceptor.intercept(request(), form.toByteArray(), answering("{}"))

        val request = appender.list.first { it.level == Level.DEBUG }.formattedMessage
        assertFalse(listOf("callers-token", "s3cret", "actors-token").any(request::contains), request)
        assertTrue(request.contains("body=subject_token=***&audience=fintrace-core&client_secret=***&actor_token=***"), request)
    }

    @Test
    fun `masks the tokens in a JSON body and keeps the other fields`() {
        logger.level = Level.DEBUG
        val json = """{"access_token":"exchanged-token","refresh_token" : "refresh-token","token_type":"Bearer"}"""

        interceptor.intercept(request(), ByteArray(0), answering(json))

        val response = appender.list.filter { it.level == Level.DEBUG }[1].formattedMessage
        assertFalse(response.contains("exchanged-token") || response.contains("refresh-token"), response)
        assertTrue(response.contains("""{"access_token":"***","refresh_token" : "***","token_type":"Bearer"}"""), response)
    }

    @Test
    fun `masks a token the truncation cuts short`() {
        logger.level = Level.DEBUG
        val lead = """{"pad":""""
        val key = """","access_token":""""
        val pad = "x".repeat(8 * 1024 - 50 - lead.length - key.length)
        val json = lead + pad + key + "T".repeat(100) + "\"}"

        interceptor.intercept(request(), ByteArray(0), answering(json))

        val response = appender.list.filter { it.level == Level.DEBUG }[1].formattedMessage
        assertFalse(response.contains("TTT"), response.takeLast(80))
        assertTrue(response.contains(""""access_token":"***"… ("""), response.takeLast(80))
    }

    @Test
    fun `cuts a large body short, saying how large it was`() {
        logger.level = Level.DEBUG

        interceptor.intercept(request(), ByteArray(20_000) { 'x'.code.toByte() }, answering("{}"))

        val request = appender.list.first { it.level == Level.DEBUG }.formattedMessage
        assertTrue(request.endsWith("… (20000 bytes)"), request.takeLast(40))
    }

    @Test
    fun `logs a call that got no answer, and rethrows`() {
        logger.level = Level.INFO
        val failing = ClientHttpRequestExecution { _, _ -> throw IOException("timed out") }

        assertThrows<IOException> { interceptor.intercept(request(), ByteArray(0), failing) }

        val line = appender.list.single()
        assertTrue(line.formattedMessage.contains("failed after"), line.formattedMessage)
    }

    private fun request() = MockClientHttpRequest(HttpMethod.GET, URI("http://core/api/v1/workspaces/1")).apply {
        headers.set(HttpHeaders.AUTHORIZATION, "Bearer secret-token")
        headers.set(HttpHeaders.ACCEPT, "application/json")
    }

    private fun answering(body: String) = ClientHttpRequestExecution { _, _ ->
        MockClientHttpResponse(body.toByteArray(), HttpStatus.OK)
    }
}

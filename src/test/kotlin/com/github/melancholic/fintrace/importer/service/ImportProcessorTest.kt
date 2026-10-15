package com.github.melancholic.fintrace.importer.service

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.github.melancholic.fintrace.importer.ChronicleFixture
import com.github.melancholic.fintrace.importer.integration.CallLoggingInterceptor
import com.github.melancholic.fintrace.importer.contract.ImportJobResponse
import com.github.melancholic.fintrace.importer.contract.ImportProblemDTO
import com.github.melancholic.fintrace.importer.exception.*
import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder
import com.github.tomakehurst.wiremock.client.WireMock.*
import com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig
import com.github.tomakehurst.wiremock.http.Fault
import com.github.tomakehurst.wiremock.stubbing.Scenario
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import com.github.melancholic.fintrace.importer.TestSecurityConfiguration
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.*
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The whole pipeline through the configured client, with Core played by WireMock. */
@SpringBootTest
@Import(TestSecurityConfiguration::class)
class ImportProcessorTest(@Autowired private val processor: ImportProcessor) {

    @TempDir
    lateinit var dir: Path

    @BeforeEach
    fun resetCore() = core.resetAll()

    @Nested
    inner class Results {

        @Test
        fun `reads the workspace, then posts the import and returns the job`() {
            stubWorkspace(okJson(WORKSPACE))
            stubImport(okJson(job("SUCCEEDED")))

            val job = processor.processImport(WORKSPACE_ID, emptyDump())

            assertEquals(ImportJobResponse.Status.SUCCEEDED, job.status)
            core.verify(1, getRequestedFor(urlEqualTo(WORKSPACE_PATH)))
            core.verify(
                1, postRequestedFor(urlEqualTo(IMPORT_PATH))
                    .withHeader("Content-Type", containing("application/json"))
                    .withRequestBody(matchingJsonPath("$.importerName", equalTo("fintrace-mok-importer")))
                    .withRequestBody(matchingJsonPath("$.payload.accounts", equalToJson("[]")))
            )
        }

        @Test
        fun `returns a refused import as its FAILED job rather than throwing`() {
            stubWorkspace(okJson(WORKSPACE))
            stubImport(jsonWith(422, job("FAILED", problems = REFUSED_PROBLEM)))

            val job = processor.processImport(WORKSPACE_ID, emptyDump())

            assertEquals(ImportJobResponse.Status.FAILED, job.status)
            assertEquals(ImportProblemDTO.Code.REFUSED_BY_COMMAND, job.problems.single().code)
        }

        @Test
        fun `an account with no currency takes the workspace's default (D2)`() {
            stubWorkspace(okJson(WORKSPACE.replace("\"EUR\"", "\"RSD\"")))
            stubImport(okJson(job("SUCCEEDED")))
            val dump = dumpOf(20 to """{"uid":1,"name":"Cash","money":0,"currency":0}""")

            processor.processImport(WORKSPACE_ID, dump)

            core.verify(
                postRequestedFor(urlEqualTo(IMPORT_PATH))
                    .withRequestBody(matchingJsonPath("$.payload.accounts[0].currency", equalTo("RSD")))
            )
        }
    }

    @Nested
    inner class InvalidDumps {

        @Test
        fun `refuses a file that is not a dump, and posts nothing`() {
            stubWorkspace(okJson(WORKSPACE))
            val notADump = Files.writeString(dir.resolve("notes.mok"), "not sqlite at all")

            assertThrows<InvalidMokDumpException> { processor.processImport(WORKSPACE_ID, notADump) }
            core.verify(0, postRequestedFor(anyUrl()))
        }

        @Test
        fun `refuses a chronicle that cannot be parsed, keeping the reason`() {
            stubWorkspace(okJson(WORKSPACE))
            val dump = dumpOf(20 to "{not json")

            val e = assertThrows<InvalidChronicleException> { processor.processImport(WORKSPACE_ID, dump) }
            assertTrue(e.message!!.contains("unparseable"), e.message)
            core.verify(0, postRequestedFor(anyUrl()))
        }
    }

    @Nested
    inner class CoreAnswers {

        @Test
        fun `a missing workspace is reported before the dump is read, and not retried`() {
            stubWorkspace(aResponse().withStatus(404))

            assertThrows<WorkspaceNotFoundException> { processor.processImport(WORKSPACE_ID, emptyDump()) }
            core.verify(1, getRequestedFor(urlEqualTo(WORKSPACE_PATH)))
            core.verify(0, postRequestedFor(anyUrl()))
        }

        @Test
        fun `refused credentials are reported as such`() {
            stubWorkspace(aResponse().withStatus(403))

            assertThrows<CoreAccessDeniedException> { processor.processImport(WORKSPACE_ID, emptyDump()) }
        }

        @Test
        fun `a workspace that is not importable is a conflict`() {
            stubWorkspace(okJson(WORKSPACE))
            stubImport(aResponse().withStatus(409))

            assertThrows<WorkspaceNotImportableException> { processor.processImport(WORKSPACE_ID, emptyDump()) }
        }

        @Test
        fun `a malformed request is the importer's defect`() {
            stubWorkspace(okJson(WORKSPACE))
            stubImport(aResponse().withStatus(400))

            assertThrows<ImportRequestRejectedException> { processor.processImport(WORKSPACE_ID, emptyDump()) }
        }

        @Test
        fun `a Core fault on the import means nothing was imported, and is not retried`() {
            stubWorkspace(okJson(WORKSPACE))
            stubImport(aResponse().withStatus(500))

            assertThrows<CoreUnavailableException> { processor.processImport(WORKSPACE_ID, emptyDump()) }
            core.verify(1, postRequestedFor(urlEqualTo(IMPORT_PATH)))
        }
    }

    @Nested
    inner class TokenExchange {

        @AfterEach
        fun signOut() = SecurityContextHolder.clearContext()

        @Test
        fun `exchanges the caller's token once and calls Core with the exchanged one`() {
            signIn(subject = "exchanging-user", token = "callers-token")
            core.stubFor(post(TOKEN_PATH).willReturn(okJson(EXCHANGED)))
            stubWorkspace(okJson(WORKSPACE))
            stubImport(okJson(job("SUCCEEDED")))

            processor.processImport(WORKSPACE_ID, emptyDump())

            core.verify(
                1, postRequestedFor(urlEqualTo(TOKEN_PATH))
                    .withHeader("Authorization", matching("Basic .+"))
                    .withRequestBody(containing("grant_type=urn%3Aietf%3Aparams%3Aoauth%3Agrant-type%3Atoken-exchange"))
                    .withRequestBody(containing("subject_token=callers-token"))
                    .withRequestBody(containing("audience=fintrace-core"))
            )
            core.verify(getRequestedFor(urlEqualTo(WORKSPACE_PATH)).withHeader("Authorization", equalTo("Bearer exchanged-token")))
            core.verify(postRequestedFor(urlEqualTo(IMPORT_PATH)).withHeader("Authorization", equalTo("Bearer exchanged-token")))
        }

        @Test
        fun `the exchange call is logged, and an expired caller token is unauthorized and not retried`() {
            signIn(subject = "expired-user", token = "expired-token")
            core.stubFor(post(TOKEN_PATH).willReturn(jsonWith(400, """{"error":"invalid_grant"}""")))
            val logger = LoggerFactory.getLogger(CallLoggingInterceptor::class.java) as Logger
            val calls = ListAppender<ILoggingEvent>().apply { start() }
            logger.addAppender(calls)

            try {
                assertThrows<UnauthorizedException> { processor.processImport(WORKSPACE_ID, emptyDump()) }
            } finally {
                logger.detachAppender(calls)
            }

            assertTrue(calls.list.any { it.formattedMessage.startsWith("realm POST ${core.baseUrl()}$TOKEN_PATH -> 400") })
            core.verify(1, postRequestedFor(urlEqualTo(TOKEN_PATH)))
            core.verify(0, getRequestedFor(urlEqualTo(WORKSPACE_PATH)))
        }

        @Test
        fun `a realm that cannot be reached is retried, and Core is never called`() {
            signIn(subject = "unlucky-user", token = "callers-token")
            core.stubFor(post(TOKEN_PATH).willReturn(aResponse().withFault(Fault.CONNECTION_RESET_BY_PEER)))

            assertThrows<RealmUnreachableException> { processor.processImport(WORKSPACE_ID, emptyDump()) }

            core.verify(4, postRequestedFor(urlEqualTo(TOKEN_PATH)))
            core.verify(0, getRequestedFor(urlEqualTo(WORKSPACE_PATH)))
        }

        private fun signIn(subject: String, token: String) {
            val jwt = Jwt.withTokenValue(token)
                .header("alg", "none")
                .subject(subject)
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(300))
                .build()
            SecurityContextHolder.getContext().authentication = JwtAuthenticationToken(jwt)
        }
    }

    @Nested
    inner class Retries {

        @Test
        fun `a workspace read that fails once is retried`() {
            core.stubFor(
                get(WORKSPACE_PATH).inScenario("flaky").whenScenarioStateIs(Scenario.STARTED)
                    .willReturn(aResponse().withStatus(503)).willSetStateTo("recovered")
            )
            core.stubFor(
                get(WORKSPACE_PATH).inScenario("flaky").whenScenarioStateIs("recovered")
                    .willReturn(okJson(WORKSPACE))
            )
            stubImport(okJson(job("SUCCEEDED")))

            val job = processor.processImport(WORKSPACE_ID, emptyDump())

            assertEquals(ImportJobResponse.Status.SUCCEEDED, job.status)
            core.verify(2, getRequestedFor(urlEqualTo(WORKSPACE_PATH)))
        }

        @Test
        fun `a workspace read that keeps failing gives up after three retries`() {
            stubWorkspace(aResponse().withStatus(503))

            assertThrows<CoreUnavailableException> { processor.processImport(WORKSPACE_ID, emptyDump()) }
            core.verify(4, getRequestedFor(urlEqualTo(WORKSPACE_PATH)))
        }

        @Test
        fun `an import with no answer is an unknown outcome, sent once`() {
            stubWorkspace(okJson(WORKSPACE))
            stubImport(okJson(job("SUCCEEDED")).withFixedDelay(READ_TIMEOUT_MILLIS * 2))

            assertThrows<ImportOutcomeUnknownException> { processor.processImport(WORKSPACE_ID, emptyDump()) }
            core.verify(1, postRequestedFor(urlEqualTo(IMPORT_PATH)))
        }

        @Test
        fun `an import whose connection drops mid-answer is an unknown outcome, sent once`() {
            stubWorkspace(okJson(WORKSPACE))
            stubImport(aResponse().withFault(Fault.CONNECTION_RESET_BY_PEER))

            assertThrows<ImportOutcomeUnknownException> { processor.processImport(WORKSPACE_ID, emptyDump()) }
            core.verify(1, postRequestedFor(urlEqualTo(IMPORT_PATH)))
        }
    }

    private fun stubWorkspace(response: ResponseDefinitionBuilder) {
        core.stubFor(get(WORKSPACE_PATH).willReturn(response))
    }

    private fun stubImport(response: ResponseDefinitionBuilder) {
        core.stubFor(post(IMPORT_PATH).willReturn(response))
    }

    private fun emptyDump() = ChronicleFixture(dir.resolve("dump.mok")).write()

    private fun dumpOf(vararg rows: Pair<Int, String>) = ChronicleFixture(dir.resolve("dump.mok"))
        .apply { rows.forEach { (type, params) -> row(type, params) } }
        .write()

    companion object {
        // Spring's JDK client times the whole read, body included, so a cold first call needs headroom
        private const val READ_TIMEOUT_MILLIS = 3_000

        private val WORKSPACE_ID = UUID.fromString("01930000-0000-7000-8000-0000000000aa")
        private val WORKSPACE_PATH = "/api/v1/workspaces/$WORKSPACE_ID"
        private val IMPORT_PATH = "$WORKSPACE_PATH/import"
        private const val TOKEN_PATH = "/token"

        private const val EXCHANGED = """
            {"access_token":"exchanged-token","token_type":"Bearer","expires_in":300,
             "issued_token_type":"urn:ietf:params:oauth:token-type:access_token"}
        """

        private val WORKSPACE = """
            {"id":"$WORKSPACE_ID","name":"w","status":"NEW","defaultCurrency":"EUR","version":0,
             "createdAt":"2026-10-14T10:00:00","updatedAt":"2026-10-14T10:00:00"}
        """

        private const val REFUSED_PROBLEM = """
            [{"code":"REFUSED_BY_COMMAND","message":"kind mismatch","aggregateType":"OPERATION",
              "affectedIDs":["01930000-0000-7000-8000-0000000000a1"],"affectedCount":1}]
        """

        private fun job(status: String, problems: String = "[]") = """
            {"id":"01930000-0000-7000-8000-0000000000bb","workspaceId":"$WORKSPACE_ID","status":"$status",
             "startedBy":"01930000-0000-7000-8000-0000000000cc","startedAt":"2026-10-14T10:00:00",
             "importerName":"fintrace-mok-importer","importerVersion":"0.0.1","accounts":0,"categories":0,
             "operations":0,"transfers":0,"anchors":0,"diagnostics":[],"problems":$problems}
        """

        private fun jsonWith(status: Int, json: String) = aResponse()
            .withStatus(status)
            .withHeader("Content-Type", "application/json")
            .withBody(json)

        // One server for the whole class: an extension restarts per @Nested class on a new port, while the
        // cached Spring context keeps the first. Plain HTTP/2 is off because Core's Tomcat does not speak it
        private val core = WireMockServer(wireMockConfig().dynamicPort().http2PlainDisabled(true)).apply { start() }

        @JvmStatic
        @DynamicPropertySource
        fun coreUrl(registry: DynamicPropertyRegistry) {
            registry.add("spring.http.service-client.fintrace-core.base-url") { core.baseUrl() }
            registry.add("spring.http.service-client.fintrace-core.read-timeout") { "${READ_TIMEOUT_MILLIS}ms" }
            registry.add("fintrace.test.token-uri") { "${core.baseUrl()}$TOKEN_PATH" }
        }

        @JvmStatic
        @AfterAll
        fun stopCore() = core.stop()
    }
}

package com.github.melancholic.fintrace.importer.api.v1.controller

import com.github.melancholic.fintrace.importer.ChronicleFixture
import com.github.melancholic.fintrace.importer.TestSecurityConfiguration
import com.github.melancholic.fintrace.importer.contract.ImportJobResponse
import com.github.melancholic.fintrace.importer.exception.*
import com.github.melancholic.fintrace.importer.service.ImportProcessor
import org.hamcrest.Matchers.startsWith
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.doThrow
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.slf4j.MDC
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.mock.web.MockMultipartFile
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import tools.jackson.databind.json.JsonMapper
import java.nio.file.Files
import java.nio.file.Path
import java.util.*
import kotlin.test.assertEquals

/** The upload endpoint over HTTP: the security chain, the facade, and the error handler together. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestSecurityConfiguration::class)
class MokImportRestControllerTest(
    @Autowired private val mvc: MockMvc,
    @Autowired private val json: JsonMapper,
) {

    @MockitoBean
    private lateinit var processor: ImportProcessor

    @TempDir
    lateinit var dir: Path

    @Test
    fun `a signed-in caller uploading a dump gets the job back`() {
        answering(job("SUCCEEDED"))

        mvc.perform(upload(dump()).with(jwt()))
            .andExpect(status().isOk)
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
            .andExpect(jsonPath("$.status").value("SUCCEEDED"))
    }

    @Test
    fun `an upload without a token is 401, and nothing is processed`() {
        mvc.perform(upload(dump()))
            .andExpect(status().isUnauthorized)

        verify(processor, never()).processImport(anyWorkspace(), anyDump())
    }

    @Test
    fun `a FAILED job is 422 carrying the job, not a problem`() {
        answering(job("FAILED"))

        mvc.perform(upload(dump()).with(jwt()))
            .andExpect(status().isUnprocessableContent)
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
            .andExpect(jsonPath("$.status").value("FAILED"))
    }

    @Test
    fun `the processor runs with the workspace and the caller in the MDC`() {
        var seen: Pair<String?, String?>? = null
        doAnswer {
            seen = MDC.get("workspaceId") to MDC.get("userId")
            job("SUCCEEDED")
        }.`when`(processor).processImport(anyWorkspace(), anyDump())

        mvc.perform(upload(dump()).with(jwt().jwt { it.subject("caller-sub") }))
            .andExpect(status().isOk)

        assertEquals(WORKSPACE_ID.toString() to "caller-sub", seen)
    }

    @ParameterizedTest(name = "{0} -> {1}")
    @MethodSource("callerFacing")
    fun `an exception the caller can act on is a problem carrying its message`(e: FintraceMokImporterException, status: Int) {
        doThrow(e).`when`(processor).processImport(anyWorkspace(), anyDump())

        mvc.perform(upload(dump()).with(jwt()))
            .andExpect(status().`is`(status))
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
            .andExpect(jsonPath("$.code").value(e.code.name))
            .andExpect(jsonPath("$.detail").value(e.message!!))
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("faults")
    fun `a fault on the importer's side is a 500 with the generic detail`(e: FintraceMokImporterException) {
        doThrow(e).`when`(processor).processImport(anyWorkspace(), anyDump())

        mvc.perform(upload(dump()).with(jwt()))
            .andExpect(status().isInternalServerError)
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
            .andExpect(jsonPath("$.code").value(e.code.name))
            .andExpect(jsonPath("$.detail").value(startsWith(GENERIC_DETAIL)))
    }

    @Test
    fun `an unexpected exception is INTERNAL and never leaks its message`() {
        doThrow(IllegalStateException("secret internals")).`when`(processor).processImport(anyWorkspace(), anyDump())

        mvc.perform(upload(dump()).with(jwt()))
            .andExpect(status().isInternalServerError)
            .andExpect(jsonPath("$.code").value("INTERNAL"))
            .andExpect(jsonPath("$.detail").value(startsWith(GENERIC_DETAIL)))
    }

    @Test
    fun `a file that is not a dump is 422 INVALID_DUMP, and nothing is processed`() {
        val notADump = MockMultipartFile("file", "dump.mok", "application/octet-stream", "not sqlite".toByteArray())

        mvc.perform(multipart(IMPORT_PATH, WORKSPACE_ID).file(notADump).with(jwt()))
            .andExpect(status().isUnprocessableContent)
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
            .andExpect(jsonPath("$.code").value("INVALID_DUMP"))

        verify(processor, never()).processImport(anyWorkspace(), anyDump())
    }

    @Test
    fun `a request without the file part stays Spring's 400, not the catch-all's 500`() {
        mvc.perform(multipart(IMPORT_PATH, WORKSPACE_ID).with(jwt()))
            .andExpect(status().isBadRequest)
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
    }

    private fun answering(job: ImportJobResponse) =
        doAnswer { job }.`when`(processor).processImport(anyWorkspace(), anyDump())

    // Mockito's matchers return null, which Kotlin rejects for a non-null parameter; the fallback is never used
    private fun anyWorkspace(): UUID = any(UUID::class.java) ?: WORKSPACE_ID
    private fun anyDump(): Path = any(Path::class.java) ?: Path.of("")

    private fun dump(): ByteArray = Files.readAllBytes(ChronicleFixture(dir.resolve("dump.mok")).write())

    private fun upload(bytes: ByteArray) = multipart(IMPORT_PATH, WORKSPACE_ID)
        .file(MockMultipartFile("file", "dump.mok", "application/octet-stream", bytes))

    private fun job(status: String): ImportJobResponse = json.readValue(
        """
        {"id":"01930000-0000-7000-8000-0000000000bb","workspaceId":"$WORKSPACE_ID","status":"$status",
         "startedBy":"01930000-0000-7000-8000-0000000000cc","startedAt":"2026-10-14T10:00:00",
         "importerName":"fintrace-mok-importer","importerVersion":"0.0.1","accounts":0,"categories":0,
         "operations":0,"transfers":0,"anchors":0,"diagnostics":[],"problems":[]}
        """,
        ImportJobResponse::class.java,
    )

    companion object {
        private val WORKSPACE_ID: UUID = UUID.fromString("01930000-0000-7000-8000-0000000000aa")
        private const val IMPORT_PATH = "/api/v1/workspaces/{workspaceId}/import"
        private const val GENERIC_DETAIL = "fintrace-mok-importer failed on its side"

        @JvmStatic
        fun callerFacing() = listOf(
            Arguments.of(InvalidMokDumpException("The dump is broken"), 422),
            Arguments.of(UnauthorizedException(), 401),
            Arguments.of(CoreAccessDeniedException(), 403),
            Arguments.of(WorkspaceNotFoundException(WORKSPACE_ID), 404),
            Arguments.of(WorkspaceNotImportableException(WORKSPACE_ID), 409),
            Arguments.of(RealmUnreachableException(), 503),
            Arguments.of(CoreUnavailableException(), 503),
            Arguments.of(CoreUnreachableException(), 503),
            Arguments.of(ImportOutcomeUnknownException(WORKSPACE_ID), 504),
        )

        @JvmStatic
        fun faults() = listOf(
            Arguments.of(TokenExchangeMisconfiguredException("invalid_client", "Invalid client credentials")),
            Arguments.of(CoreTokenRejectedException()),
            Arguments.of(ImportRequestRejectedException()),
        )
    }
}

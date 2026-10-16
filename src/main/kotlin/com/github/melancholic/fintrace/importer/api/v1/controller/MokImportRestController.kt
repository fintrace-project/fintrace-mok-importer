package com.github.melancholic.fintrace.importer.api.v1.controller

import com.github.melancholic.fintrace.importer.contract.ImportJobResponse
import com.github.melancholic.fintrace.importer.facade.MokImportFacade
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ProblemDetail
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import org.springframework.web.multipart.MultipartFile
import java.util.*

@RestController
@RequestMapping("/api/v1/")
@Tag(name = "Import API")
class MokImportRestController(
    private val facade: MokImportFacade
) {

    @Operation(summary = "Import a MOK dump into a NEW workspace")
    @ApiResponses(
        ApiResponse(
            responseCode = "200",
            description = "Imported; body carries the job with its counts",
            content = [Content(
                mediaType = MediaType.APPLICATION_JSON_VALUE,
                schema = Schema(implementation = ImportJobResponse::class)
            )],
        ),
        ApiResponse(
            responseCode = "422",
            description = "Refused: the FAILED job with its problems, or a problem when the file is not a readable dump",
            content = [
                Content(
                    mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = Schema(implementation = ImportJobResponse::class)
                ),
                Content(
                    mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    schema = Schema(implementation = ProblemDetail::class)
                ),
            ],
        ),
        ApiResponse(
            responseCode = "400",
            description = "No file part, or a malformed workspace id",
            content = [Content(
                mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                schema = Schema(implementation = ProblemDetail::class)
            )]
        ),
        ApiResponse(
            responseCode = "401",
            description = "Not authenticated, or the realm rejected the caller's token",
            content = [Content(
                mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                schema = Schema(implementation = ProblemDetail::class)
            )]
        ),
        ApiResponse(
            responseCode = "403",
            description = "fintrace-core denied the caller",
            content = [Content(
                mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                schema = Schema(implementation = ProblemDetail::class)
            )]
        ),
        ApiResponse(
            responseCode = "404",
            description = "No such workspace for this caller",
            content = [Content(
                mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                schema = Schema(implementation = ProblemDetail::class)
            )]
        ),
        ApiResponse(
            responseCode = "409",
            description = "The workspace is not NEW, not empty, or an import is already running",
            content = [Content(
                mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                schema = Schema(implementation = ProblemDetail::class)
            )]
        ),
        ApiResponse(
            responseCode = "413",
            description = "The file is over the upload limit",
            content = [Content(
                mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                schema = Schema(implementation = ProblemDetail::class)
            )]
        ),
        ApiResponse(
            responseCode = "500",
            description = "A fault on the importer's side",
            content = [Content(
                mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                schema = Schema(implementation = ProblemDetail::class)
            )]
        ),
        ApiResponse(
            responseCode = "503",
            description = "The realm or fintrace-core could not be reached; nothing was imported",
            content = [Content(
                mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                schema = Schema(implementation = ProblemDetail::class)
            )]
        ),
        ApiResponse(
            responseCode = "504",
            description = "No answer from fintrace-core; read the workspace's status to learn whether the import landed",
            content = [Content(
                mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                schema = Schema(implementation = ProblemDetail::class)
            )]
        ),
    )
    @PostMapping("workspaces/{workspaceId}/import", consumes = [MediaType.MULTIPART_FORM_DATA_VALUE])
    fun import(
        @PathVariable workspaceId: UUID,
        @RequestPart("file") file: MultipartFile,
    ): ResponseEntity<ImportJobResponse> {

        val job = facade.importMokDumpFile(workspaceId, file)

        val status = when (job.status) {
            ImportJobResponse.Status.FAILED -> HttpStatus.UNPROCESSABLE_CONTENT
            else -> HttpStatus.OK
        }
        return ResponseEntity.status(status).body(job)
    }
}

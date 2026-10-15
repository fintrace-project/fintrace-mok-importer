package com.github.melancholic.fintrace.importer.api

import com.github.melancholic.fintrace.importer.exception.*
import io.github.oshai.kotlinlogging.KotlinLogging
import io.github.oshai.kotlinlogging.Level
import org.slf4j.MDC
import org.springframework.http.HttpStatus
import org.springframework.http.ProblemDetail
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler


@RestControllerAdvice
class RestControllerErrorHandler : ResponseEntityExceptionHandler() {

    @ExceptionHandler(FintraceMokImporterException::class)
    fun handleBaseException(e: FintraceMokImporterException): ProblemDetail {
        val (status, level) = when (e) {
            is InvalidMokDumpException -> HttpStatus.UNPROCESSABLE_CONTENT to Level.INFO
            is UnauthorizedException -> HttpStatus.UNAUTHORIZED to Level.INFO
            is CoreAccessDeniedException -> HttpStatus.FORBIDDEN to Level.INFO
            is WorkspaceNotFoundException -> HttpStatus.NOT_FOUND to Level.INFO
            is WorkspaceNotImportableException -> HttpStatus.CONFLICT to Level.INFO
            is RealmUnreachableException, is CoreUnavailableException -> HttpStatus.SERVICE_UNAVAILABLE to Level.WARN
            is ImportOutcomeUnknownException -> HttpStatus.GATEWAY_TIMEOUT to Level.WARN
            is TokenExchangeMisconfiguredException, is CoreTokenRejectedException,
            is ContractViolationException -> HttpStatus.INTERNAL_SERVER_ERROR to Level.ERROR
        }
        logAt(level, e)
        return problem(status, e.code, if (level == Level.ERROR) DEF_MESSAGE else e.message)
    }

    @ExceptionHandler(Exception::class)
    fun handleUnexpected(e: Exception): ProblemDetail {
        log.error(e) { "Unhandled ${e.javaClass.simpleName}" }
        return problem(HttpStatus.INTERNAL_SERVER_ERROR, ImporterErrorCode.INTERNAL, DEF_MESSAGE)
    }

    private fun problem(status: HttpStatus, code: ImporterErrorCode, detail: String?) =
        ProblemDetail.forStatusAndDetail(status, detail).apply {
            setProperty("code", code)
            MDC.get("traceId")?.let { setProperty("traceId", it) }
        }

    private fun logAt(level: Level, e: FintraceMokImporterException) = when (level) {
        Level.ERROR -> log.error(e) { "${e.code}: ${e.message}" }
        Level.WARN -> log.warn { "${e.code}: ${e.message}" }
        else -> log.info { "${e.code}: ${e.message}" }
    }

    private companion object {
        private val log = KotlinLogging.logger {}

        const val DEF_MESSAGE = "fintrace-mok-importer failed on its side. Read the workspace's status to see " +
                "whether anything was imported, and quote the traceId when reporting it."
    }
}
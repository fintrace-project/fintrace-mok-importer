package com.github.melancholic.fintrace.importer.exception

import java.util.*

sealed class FintraceMokImporterException(msg: String, cause: Throwable? = null)
    : RuntimeException(msg, cause) {
    abstract val code: ImporterErrorCode
}

sealed class InfrastructureException(msg: String, cause: Throwable? = null)
    : FintraceMokImporterException(msg, cause)

sealed class BusinessException(msg: String, cause: Throwable? = null)
    : FintraceMokImporterException(msg, cause)

sealed class ContractViolationException(msg: String, cause: Throwable? = null)
    : FintraceMokImporterException(msg, cause)

open class InvalidMokDumpException(msg: String, cause: Throwable? = null)
    : BusinessException(msg, cause) {
    override val code = ImporterErrorCode.INVALID_DUMP
}

class UnauthorizedException(cause: Throwable? = null)
    : BusinessException("The realm rejected the caller's token as expired or revoked", cause) {
    override val code = ImporterErrorCode.CALLER_TOKEN_REJECTED
}

class TokenExchangeMisconfiguredException(errorCode: String, description: String?, cause: Throwable? = null)
    : InfrastructureException("The realm refused the token exchange with '$errorCode' (${description ?: "no description"}); check the importer's client and the realm's exchange settings", cause) {
    override val code = ImporterErrorCode.EXCHANGE_MISCONFIGURED
}

class RealmUnreachableException(cause: Throwable? = null)
    : InfrastructureException("The realm could not be reached for the token exchange; nothing was sent to fintrace-core", cause) {
    override val code = ImporterErrorCode.REALM_UNREACHABLE
}

class WorkspaceNotFoundException(workspaceId: UUID, cause: Throwable? = null)
    : BusinessException("Workspace '$workspaceId' does not exist for this caller", cause) {
    override val code = ImporterErrorCode.WORKSPACE_NOT_FOUND
}

class WorkspaceNotImportableException(workspaceId: UUID, cause: Throwable? = null)
    : BusinessException("Workspace '$workspaceId' is not NEW, not empty, or already importing", cause) {
    override val code = ImporterErrorCode.WORKSPACE_NOT_IMPORTABLE
}

class ImportRequestRejectedException(cause: Throwable? = null)
    : ContractViolationException("fintrace-core rejected the import request as malformed", cause) {
    override val code = ImporterErrorCode.CONTRACT_VIOLATION
}

class CoreAccessDeniedException(cause: Throwable? = null)
    : BusinessException("fintrace-core denied the caller access to imports", cause) {
    override val code = ImporterErrorCode.CORE_ACCESS_DENIED
}

class CoreTokenRejectedException(cause: Throwable? = null)
    : InfrastructureException("fintrace-core rejected the exchanged token; check its audience and the realm's mappers", cause) {
    override val code = ImporterErrorCode.CORE_TOKEN_REJECTED
}

class ImportOutcomeUnknownException(workspaceId: UUID, cause: Throwable? = null)
    : InfrastructureException("No answer from fintrace-core for workspace '$workspaceId'; read its status to learn whether the import landed", cause) {
    override val code = ImporterErrorCode.IMPORT_OUTCOME_UNKNOWN
}

open class CoreUnavailableException(msg: String, cause: Throwable? = null) : InfrastructureException(msg, cause) {
    constructor(cause: Throwable? = null) : this("fintrace-core failed or could not be reached; nothing was imported", cause)

    override val code = ImporterErrorCode.CORE_UNAVAILABLE
}

class CoreUnreachableException(cause: Throwable? = null)
    : CoreUnavailableException("fintrace-core refused the connection; nothing was sent", cause)

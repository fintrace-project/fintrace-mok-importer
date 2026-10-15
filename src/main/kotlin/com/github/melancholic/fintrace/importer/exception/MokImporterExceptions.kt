package com.github.melancholic.fintrace.importer.exception

import java.util.*

open class FintraceMokImporterException(msg: String, cause: Throwable? = null)
    : RuntimeException(msg, cause)

open class InvalidMokDumpException(msg: String, cause: Throwable? = null)
    : FintraceMokImporterException(msg, cause)

open class InfrastructureException(msg: String, cause: Throwable? = null)
    : FintraceMokImporterException(msg, cause)

open class BusinessException(msg: String, cause: Throwable? = null)
    : FintraceMokImporterException(msg, cause)

open class ContractViolationException(msg: String, cause: Throwable? = null)
    : FintraceMokImporterException(msg, cause)

class UnauthorizedException(cause: Throwable? = null)
    : BusinessException("The realm rejected the caller's token as expired or revoked", cause)

class TokenExchangeMisconfiguredException(errorCode: String, cause: Throwable? = null)
    : InfrastructureException("The realm refused the token exchange with '$errorCode'; check the importer's client and the realm's exchange settings", cause)

class RealmUnreachableException(cause: Throwable? = null)
    : InfrastructureException("The realm could not be reached for the token exchange; nothing was sent to fintrace-core", cause)

class WorkspaceNotFoundException(workspaceId: UUID, cause: Throwable? = null)
    : BusinessException("Workspace '$workspaceId' does not exist for this caller", cause)

class WorkspaceNotImportableException(workspaceId: UUID, cause: Throwable? = null)
    : BusinessException("Workspace '$workspaceId' is not NEW, not empty, or already importing", cause)

class ImportRequestRejectedException(cause: Throwable? = null)
    : ContractViolationException("fintrace-core rejected the import request as malformed", cause)

class CoreAccessDeniedException(cause: Throwable? = null)
    : BusinessException("fintrace-core refused the caller's credentials", cause)

class ImportOutcomeUnknownException(workspaceId: UUID, cause: Throwable? = null)
    : InfrastructureException("No answer from fintrace-core for workspace '$workspaceId'; read its status to learn whether the import landed", cause)

open class CoreUnavailableException(msg: String, cause: Throwable? = null) : InfrastructureException(msg, cause) {
    constructor(cause: Throwable? = null) : this("fintrace-core failed or could not be reached; nothing was imported", cause)
}

class CoreUnreachableException(cause: Throwable? = null)
    : CoreUnavailableException("fintrace-core refused the connection; nothing was sent", cause)

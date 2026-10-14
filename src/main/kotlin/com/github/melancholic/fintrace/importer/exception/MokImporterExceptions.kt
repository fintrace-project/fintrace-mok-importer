package com.github.melancholic.fintrace.importer.exception

import java.util.*

open class FintraceMokImporterException(msg: String, cause: Throwable? = null)
    : RuntimeException(msg, cause)

open class InvalidMokDumpException(msg: String, cause: Throwable? = null)
    : FintraceMokImporterException(msg, cause)

// CORE ANSWERS THAT ARE NOT AN IMPORT RESULT
open class CoreCallException(msg: String, cause: Throwable? = null)
    : FintraceMokImporterException(msg, cause)

class WorkspaceNotFoundException(workspaceId: UUID, cause: Throwable? = null)
    : CoreCallException("Workspace '$workspaceId' does not exist for this caller", cause)

class WorkspaceNotImportableException(workspaceId: UUID, cause: Throwable? = null)
    : CoreCallException("Workspace '$workspaceId' is not NEW, not empty, or already importing", cause)

class ImportRequestRejectedException(cause: Throwable? = null)
    : CoreCallException("Core rejected the import request as malformed", cause)

class CoreAccessDeniedException(cause: Throwable? = null)
    : CoreCallException("Core refused the caller's credentials", cause)

open class CoreUnavailableException(msg: String, cause: Throwable? = null) : CoreCallException(msg, cause) {
    constructor(cause: Throwable? = null) : this("Core failed or could not be reached; nothing was imported", cause)
}

class CoreUnreachableException(cause: Throwable? = null)
    : CoreUnavailableException("Core refused the connection; nothing was sent", cause)

class ImportOutcomeUnknownException(workspaceId: UUID, cause: Throwable? = null)
    : CoreCallException("No answer from Core for workspace '$workspaceId'; read its status to learn whether the import landed", cause)

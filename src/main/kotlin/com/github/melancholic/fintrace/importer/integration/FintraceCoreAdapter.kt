package com.github.melancholic.fintrace.importer.integration

import com.github.melancholic.fintrace.importer.contract.ImportEnvelopRequest
import com.github.melancholic.fintrace.importer.contract.ImportJobResponse
import com.github.melancholic.fintrace.importer.contract.WorkspaceResponse
import com.github.melancholic.fintrace.importer.exception.*
import org.springframework.resilience.annotation.Retryable
import org.springframework.stereotype.Component
import org.springframework.web.client.ResourceAccessException
import org.springframework.web.client.RestClientResponseException
import java.net.ConnectException
import java.util.*

interface FintraceCoreAdapter {
    fun getWorkspace(workspaceId: UUID): WorkspaceResponse
    fun importWorkspaceData(workspaceId: UUID, request: ImportEnvelopRequest): ImportJobResponse
}

@Component
class FintraceCoreAdapterImpl(
    private val coreRestClient: FintraceCoreRestClient
) : FintraceCoreAdapter {

    @Retryable(includes = [CoreUnavailableException::class])
    override fun getWorkspace(workspaceId: UUID): WorkspaceResponse =
        doCall(workspaceId) {
            coreRestClient.getWorkspace(workspaceId)
        }

    @Retryable(includes = [CoreUnreachableException::class])
    override fun importWorkspaceData(
        workspaceId: UUID,
        request: ImportEnvelopRequest
    ): ImportJobResponse =
        doCall(workspaceId, ifUnanswered = { ImportOutcomeUnknownException(workspaceId, it) }) {
            coreRestClient.importWorkspaceData(workspaceId, request)
        }

    private fun <T> doCall(
        workspaceId: UUID,
        ifUnanswered: (ResourceAccessException) -> CoreCallException = ::CoreUnavailableException,
        call: () -> T,
    ): T = try {
        call()
    } catch (e: RestClientResponseException) {
        throw when (e.statusCode.value()) {
            400 -> ImportRequestRejectedException(e)
            401, 403 -> CoreAccessDeniedException(e)
            404 -> WorkspaceNotFoundException(workspaceId, e)
            409 -> WorkspaceNotImportableException(workspaceId, e)
            else -> CoreUnavailableException(e)
        }
    } catch (e: ResourceAccessException) {
        throw if (e.cause is ConnectException) CoreUnreachableException(e) else ifUnanswered(e)
    }

}
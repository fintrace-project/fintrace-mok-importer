package com.github.melancholic.fintrace.importer.integration

import com.github.melancholic.fintrace.importer.contract.ImportEnvelopRequest
import com.github.melancholic.fintrace.importer.contract.ImportJobResponse
import com.github.melancholic.fintrace.importer.contract.WorkspaceResponse
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.service.annotation.GetExchange
import org.springframework.web.service.annotation.HttpExchange
import org.springframework.web.service.annotation.PostExchange
import java.util.UUID


@HttpExchange("/api/v1")
interface FintraceCoreRestClient {

    @GetExchange("/workspaces/{workspaceId}")
    fun getWorkspace(@PathVariable workspaceId: UUID): WorkspaceResponse

    @PostExchange("/workspaces/{workspaceId}/import")
    fun importWorkspaceData(@PathVariable workspaceId: UUID, @RequestBody request: ImportEnvelopRequest): ImportJobResponse
}
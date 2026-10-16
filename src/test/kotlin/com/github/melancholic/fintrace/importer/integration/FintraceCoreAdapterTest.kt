package com.github.melancholic.fintrace.importer.integration

import com.github.melancholic.fintrace.importer.contract.ImportEnvelopRequest
import com.github.melancholic.fintrace.importer.contract.ImportJobResponse
import com.github.melancholic.fintrace.importer.contract.WorkspaceResponse
import com.github.melancholic.fintrace.importer.exception.RealmUnreachableException
import com.github.melancholic.fintrace.importer.exception.TokenExchangeMisconfiguredException
import com.github.melancholic.fintrace.importer.exception.UnauthorizedException
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.mockito.Mockito.mock
import org.springframework.security.oauth2.client.ClientAuthorizationException
import org.springframework.security.oauth2.core.OAuth2AuthorizationException
import org.springframework.security.oauth2.core.OAuth2Error
import org.springframework.web.client.ResourceAccessException
import java.net.ConnectException
import java.util.*
import kotlin.test.assertTrue

/** Token-exchange failures, thrown in the shape Spring's provider throws them. */
class FintraceCoreAdapterTest {

    @Test
    fun `an expired or revoked caller token is unauthorized`() {
        assertThrows<UnauthorizedException> { adapterFailingWith(exchangeRefused("invalid_grant")).getWorkspace(WORKSPACE_ID) }
    }

    @ParameterizedTest
    @ValueSource(strings = ["invalid_client", "unauthorized_client", "access_denied", "invalid_target", "invalid_request"])
    fun `any other refusal is the exchange's configuration, naming the code`(code: String) {
        val e = assertThrows<TokenExchangeMisconfiguredException> {
            adapterFailingWith(exchangeRefused(code)).getWorkspace(WORKSPACE_ID)
        }
        assertTrue(e.message!!.contains(code), e.message)
    }

    @Test
    fun `a refusal keeps the realm's description for the log`() {
        val error = OAuth2Error("invalid_request", "Parameter 'subject_token_type' is not supported", null)
        val refused = ClientAuthorizationException(error, REGISTRATION, OAuth2AuthorizationException(error))

        val e = assertThrows<TokenExchangeMisconfiguredException> { adapterFailingWith(refused).getWorkspace(WORKSPACE_ID) }

        assertTrue(e.message!!.contains("Parameter 'subject_token_type' is not supported"), e.message)
    }

    @Test
    fun `a realm that cannot be reached is unreachable, on the import too`() {
        val transport = OAuth2AuthorizationException(
            OAuth2Error(INVALID_TOKEN_RESPONSE),
            ResourceAccessException("I/O error on POST", ConnectException("Connection refused")),
        )
        val wrapped = ClientAuthorizationException(transport.error, REGISTRATION, transport)

        assertThrows<RealmUnreachableException> { adapterFailingWith(wrapped).getWorkspace(WORKSPACE_ID) }
        assertThrows<RealmUnreachableException> {
            adapterFailingWith(wrapped).importWorkspaceData(WORKSPACE_ID, mock(ImportEnvelopRequest::class.java))
        }
    }

    @Test
    fun `a realm answering with an empty body is misconfigured, not unreachable`() {
        assertThrows<TokenExchangeMisconfiguredException> {
            adapterFailingWith(exchangeRefused(INVALID_TOKEN_RESPONSE)).getWorkspace(WORKSPACE_ID)
        }
    }

    private fun exchangeRefused(code: String) =
        ClientAuthorizationException(OAuth2Error(code), REGISTRATION, OAuth2AuthorizationException(OAuth2Error(code)))

    private fun adapterFailingWith(e: RuntimeException) = FintraceCoreAdapterImpl(object : FintraceCoreRestClient {
        override fun getWorkspace(workspaceId: UUID): WorkspaceResponse = throw e
        override fun importWorkspaceData(workspaceId: UUID, request: ImportEnvelopRequest): ImportJobResponse = throw e
    })

    private companion object {
        val WORKSPACE_ID: UUID = UUID.fromString("01930000-0000-7000-8000-0000000000aa")
        const val REGISTRATION = "fintrace-core"
        const val INVALID_TOKEN_RESPONSE = "invalid_token_response"
    }
}

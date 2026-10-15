package com.github.melancholic.fintrace.importer.config

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.security.oauth2.client.AuthorizedClientServiceOAuth2AuthorizedClientManager
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientManager
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientService
import org.springframework.security.oauth2.client.TokenExchangeOAuth2AuthorizedClientProvider
import org.springframework.security.oauth2.client.endpoint.RestClientTokenExchangeTokenResponseClient
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository
import org.springframework.util.LinkedMultiValueMap

@Configuration
class TokenExchangeConfiguration {

    // The service-backed manager, not the request-bound default: imports also run outside an HTTP request
    @Bean
    fun authorizedClientManager(
        registrations: ClientRegistrationRepository,
        authorizedClients: OAuth2AuthorizedClientService,
    ): OAuth2AuthorizedClientManager =
        AuthorizedClientServiceOAuth2AuthorizedClientManager(registrations, authorizedClients).apply {
            setAuthorizedClientProvider(TokenExchangeOAuth2AuthorizedClientProvider().apply {
                setAccessTokenResponseClient(RestClientTokenExchangeTokenResponseClient().apply {
                    addParametersConverter { LinkedMultiValueMap<String, String>().apply { add("audience", CORE_AUDIENCE) } }
                })
            })
        }

    companion object {
        const val CORE_REGISTRATION = "fintrace-core"
        const val CORE_AUDIENCE = "fintrace-core"
    }
}

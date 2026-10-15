package com.github.melancholic.fintrace.importer.config

import com.github.melancholic.fintrace.importer.integration.CallLoggingInterceptor
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.converter.FormHttpMessageConverter
import org.springframework.security.oauth2.client.AuthorizedClientServiceOAuth2AuthorizedClientManager
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientManager
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientService
import org.springframework.security.oauth2.client.TokenExchangeOAuth2AuthorizedClientProvider
import org.springframework.security.oauth2.client.endpoint.RestClientTokenExchangeTokenResponseClient
import org.springframework.security.oauth2.client.http.OAuth2ErrorResponseErrorHandler
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository
import org.springframework.security.oauth2.core.http.converter.OAuth2AccessTokenResponseHttpMessageConverter
import org.springframework.util.LinkedMultiValueMap
import org.springframework.web.client.RestClient

@Configuration
class TokenExchangeConfiguration {

    @Bean
    fun authorizedClientManager(
        registrations: ClientRegistrationRepository,
        authorizedClients: OAuth2AuthorizedClientService,
    ): OAuth2AuthorizedClientManager =
        AuthorizedClientServiceOAuth2AuthorizedClientManager(registrations, authorizedClients).apply {
            setAuthorizedClientProvider(TokenExchangeOAuth2AuthorizedClientProvider().apply {
                setAccessTokenResponseClient(RestClientTokenExchangeTokenResponseClient().apply {
                    setRestClient(realmRestClient())
                    addParametersConverter { LinkedMultiValueMap<String, String>().apply { add("audience", CORE_AUDIENCE) } }
                })
            })
        }

    // Spring's default client rebuilt with logging added; without its error handler a realm 400 loses its OAuth2 code
    private fun realmRestClient(): RestClient = RestClient.builder()
        .configureMessageConverters {
            it.addCustomConverter(FormHttpMessageConverter())
            it.addCustomConverter(OAuth2AccessTokenResponseHttpMessageConverter())
        }
        .defaultStatusHandler(OAuth2ErrorResponseErrorHandler())
        .requestInterceptor(CallLoggingInterceptor(REALM_TARGET))
        .build()

    companion object {
        const val CORE_REGISTRATION = "fintrace-core"
        const val CORE_AUDIENCE = "fintrace-core"
        const val REALM_TARGET = "realm"
    }
}

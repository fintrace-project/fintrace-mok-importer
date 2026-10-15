package com.github.melancholic.fintrace.importer

import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.security.oauth2.client.autoconfigure.OAuth2ClientProperties
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.security.oauth2.client.registration.ClientRegistration
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository
import org.springframework.security.oauth2.client.registration.InMemoryClientRegistrationRepository
import org.springframework.security.oauth2.core.AuthorizationGrantType
import org.springframework.security.oauth2.core.ClientAuthenticationMethod
import org.springframework.security.oauth2.jwt.BadJwtException
import org.springframework.security.oauth2.jwt.JwtDecoder

@TestConfiguration(proxyBeanMethods = false)
@EnableConfigurationProperties(OAuth2ClientProperties::class)
class TestSecurityConfiguration {

    // The resource server needs a decoder and only a deployment has an issuer; a dynamic property
    // cannot stand in, since Boot evaluates the issuer condition before it runs
    @Bean
    @ConditionalOnExpression(NO_ISSUER)
    fun jwtDecoder(): JwtDecoder = JwtDecoder { throw BadJwtException("Tests carry no real tokens") }

    // The registration as application.yml declares it, minus the issuer: Boot would discover it at
    // startup, so tests name the token endpoint directly — a stub's, or nothing listening
    @Bean
    @ConditionalOnExpression(NO_ISSUER)
    fun clientRegistrations(
        properties: OAuth2ClientProperties,
        @Value("\${fintrace.test.token-uri:http://localhost:9/token}") tokenUri: String,
    ): ClientRegistrationRepository = InMemoryClientRegistrationRepository(
        properties.registration.map { (id, registration) ->
            ClientRegistration.withRegistrationId(id)
                .clientId(registration.clientId!!)
                .clientSecret(registration.clientSecret)
                .clientAuthenticationMethod(ClientAuthenticationMethod(registration.clientAuthenticationMethod!!))
                .authorizationGrantType(AuthorizationGrantType(registration.authorizationGrantType!!))
                .tokenUri(tokenUri)
                .build()
        }
    )

    private companion object {
        // application.yml maps FINTRACE_OAUTH2_ISSUER_URI with an empty default, so "no issuer" is empty
        const val NO_ISSUER = "'\${spring.security.oauth2.resourceserver.jwt.issuer-uri:}' == ''"
    }
}

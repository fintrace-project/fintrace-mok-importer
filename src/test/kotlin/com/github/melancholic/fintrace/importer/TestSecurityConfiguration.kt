package com.github.melancholic.fintrace.importer

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.security.oauth2.jwt.BadJwtException
import org.springframework.security.oauth2.jwt.JwtDecoder

@TestConfiguration(proxyBeanMethods = false)
class TestSecurityConfiguration {

    // The resource server needs a decoder and only a deployment has an issuer; a dynamic property
    // cannot stand in, since Boot evaluates the issuer condition before it runs
    @Bean
    @ConditionalOnProperty(
        prefix = "spring.security.oauth2.resourceserver.jwt",
        name = ["issuer-uri"],
        havingValue = "no-issuer-configured",
        matchIfMissing = true,
    )
    fun jwtDecoder(): JwtDecoder = JwtDecoder { throw BadJwtException("Tests carry no real tokens") }
}

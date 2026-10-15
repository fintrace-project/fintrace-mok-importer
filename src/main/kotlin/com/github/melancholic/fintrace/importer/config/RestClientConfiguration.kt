package com.github.melancholic.fintrace.importer.config

import com.github.melancholic.fintrace.importer.integration.CallLoggingInterceptor
import com.github.melancholic.fintrace.importer.integration.FintraceCoreRestClient
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.HttpStatus
import org.springframework.resilience.annotation.EnableResilientMethods
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientManager
import org.springframework.security.oauth2.client.web.client.OAuth2ClientHttpRequestInterceptor
import org.springframework.web.client.support.RestClientHttpServiceGroupConfigurer
import org.springframework.web.service.registry.ImportHttpServices


@Configuration
@EnableResilientMethods
@ImportHttpServices(group = RestClientConfiguration.CORE_GROUP, types = [FintraceCoreRestClient::class])
class RestClientConfiguration {

    @Bean
    fun coreRefusalAsResult() = RestClientHttpServiceGroupConfigurer { groups ->
        groups.filterByName(CORE_GROUP).forEachClient { _, builder ->
            builder.defaultStatusHandler({ it.isSameCodeAs(HttpStatus.UNPROCESSABLE_CONTENT) }) { _, _ -> }
        }
    }

    @Bean
    fun coreTokenExchange(authorizedClientManager: OAuth2AuthorizedClientManager) =
        RestClientHttpServiceGroupConfigurer { groups ->
            groups.filterByName(CORE_GROUP).forEachClient { _, builder ->
                builder.requestInterceptor(OAuth2ClientHttpRequestInterceptor(authorizedClientManager).apply {
                    setClientRegistrationIdResolver { TokenExchangeConfiguration.CORE_REGISTRATION }
                })
            }
        }

    @Bean
    fun coreCallLogging() = RestClientHttpServiceGroupConfigurer { groups ->
        groups.filterByName(CORE_GROUP).forEachClient { _, builder ->
            builder.requestInterceptor(CallLoggingInterceptor(CORE_GROUP))
        }
    }

    companion object {
        const val CORE_GROUP = "fintrace-core"
    }
}

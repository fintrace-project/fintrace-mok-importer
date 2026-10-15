package com.github.melancholic.fintrace.importer.config

import com.github.melancholic.fintrace.importer.api.LoggingContextInterceptor
import org.springframework.context.annotation.Configuration
import org.springframework.web.servlet.config.annotation.InterceptorRegistry
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer

@Configuration
class WebConfiguration(
    private val loggingContextInterceptor: LoggingContextInterceptor
) : WebMvcConfigurer {

    override fun addInterceptors(registry: InterceptorRegistry) {
        registry.addInterceptor(loggingContextInterceptor)
    }
}

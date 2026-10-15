package com.github.melancholic.fintrace.importer.api

import com.github.melancholic.fintrace.importer.config.MDCConstants.MDC_USER_ID
import com.github.melancholic.fintrace.importer.config.MDCConstants.MDC_WORKSPACE_ID
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.MDC
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken
import org.springframework.stereotype.Component
import org.springframework.web.servlet.HandlerInterceptor
import org.springframework.web.servlet.HandlerMapping

@Component
class LoggingContextInterceptor: HandlerInterceptor {

    override fun preHandle(
        request: HttpServletRequest,
        response: HttpServletResponse,
        handler: Any
    ): Boolean {

        // TODO: userId here is the IdP's sub, while Core logs its internal t_users id under the same key;
        //  log the internal id once a "current user" API exists (Core's /users/me or a gateway's)
        (SecurityContextHolder.getContext().authentication as? JwtAuthenticationToken)
            ?.token?.subject
            ?.let { MDC.put(MDC_USER_ID, it) }

        @Suppress("UNCHECKED_CAST")
        (request.getAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE) as? Map<String, String>)
            ?.get(MDC_WORKSPACE_ID)
            ?.let { MDC.put(MDC_WORKSPACE_ID, it) }

        return true
    }

    override fun afterCompletion(
        request: HttpServletRequest,
        response: HttpServletResponse,
        handler: Any,
        ex: Exception?
    ) {
        MDC.remove(MDC_USER_ID)
        MDC.remove(MDC_WORKSPACE_ID)
    }
}

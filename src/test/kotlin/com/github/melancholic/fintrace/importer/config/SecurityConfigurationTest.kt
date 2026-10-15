package com.github.melancholic.fintrace.importer.config

import com.github.melancholic.fintrace.importer.TestSecurityConfiguration
import org.hamcrest.Matchers.startsWith
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

/** The chain's own rules: bearer tokens everywhere but the probe and the API docs. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestSecurityConfiguration::class)
class SecurityConfigurationTest(@Autowired private val mvc: MockMvc) {

    @Test
    fun `a request without a token is 401 with a Bearer challenge`() {
        mvc.perform(post("/api/v1/workspaces/01930000-0000-7000-8000-0000000000aa/imports"))
            .andExpect(status().isUnauthorized)
            .andExpect(header().string("WWW-Authenticate", startsWith("Bearer")))
    }

    @Test
    fun `the health probe answers without a token`() {
        mvc.perform(get("/actuator/health"))
            .andExpect(status().isOk)
    }

    @Test
    fun `the API docs answer without a token`() {
        mvc.perform(get("/v3/api-docs"))
            .andExpect(status().isOk)
    }
}

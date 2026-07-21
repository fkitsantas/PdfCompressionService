package com.github.fkitsantas.pdfcompressionservice.web;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Verifies the read-only autostart status endpoint is wired and serializes, using
 * the host's real environment detection. Deliberately does not exercise install
 * or uninstall, which would mutate the machine's autostart configuration.
 */
@SpringBootTest
@AutoConfigureMockMvc
class AutostartApiTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void statusReportsOsScopesAndLaunchCommand() throws Exception {
        mockMvc.perform(get("/service/autostart"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.os").isNotEmpty())
                .andExpect(jsonPath("$.launchCommand").isNotEmpty())
                .andExpect(jsonPath("$.login.id").value("login"))
                .andExpect(jsonPath("$.login.privileged").value(false))
                .andExpect(jsonPath("$.boot.id").value("boot"))
                .andExpect(jsonPath("$.boot.privileged").value(true))
                .andExpect(jsonPath("$.boot.installCommands").isArray());
    }
}

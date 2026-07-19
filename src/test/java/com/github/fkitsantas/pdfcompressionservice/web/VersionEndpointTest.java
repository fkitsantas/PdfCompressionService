package com.github.fkitsantas.pdfcompressionservice.web;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Verifies the build-stamping contract: {@code GET /version} reports the
 * build's identity from the generated {@code build-info.properties}. The
 * {@code build-info} goal runs at generate-resources (before tests), so the
 * {@link org.springframework.boot.info.BuildProperties} bean is present here.
 *
 * <p>Asserts only that the identity fields are <em>present</em>, never their
 * concrete values: the version, build number and git SHA are all overridden
 * per build (CI stamps the run number/commit; a tagged release sets the
 * semantic version via {@code versions:set}), so pinning any specific value
 * here would break the very CI and release builds this endpoint exists to
 * identify.
 */
@SpringBootTest
@AutoConfigureMockMvc
class VersionEndpointTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void versionEndpointReportsBuildIdentity() throws Exception {
        mockMvc.perform(get("/version"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("application/json"))
                .andExpect(jsonPath("$.name").isNotEmpty())
                .andExpect(jsonPath("$.version").isNotEmpty())
                .andExpect(jsonPath("$.buildTime").isNotEmpty())
                .andExpect(jsonPath("$.buildNumber").isNotEmpty());
    }
}

package com.github.fkitsantas.pdfcompressionservice.web;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Verifies the live {@code /logs} view: the HTML page is served, and
 * {@code /logs/stream} is a Server-Sent Events endpoint that replays the
 * captured history (proving the in-process appender -> store -> SSE wiring is
 * live end to end).
 */
@SpringBootTest
@AutoConfigureMockMvc
class LogsControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void logsPageIsServedAsHtmlAndBootstrapsTheLiveStream() throws Exception {
        mockMvc.perform(get("/logs"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_HTML))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Live Logs")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("/logs/stream")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("EventSource")));
    }

    @Test
    void streamIsAServerSentEventsEndpointThatReplaysRecentHistory() throws Exception {
        Logger probe = LoggerFactory.getLogger("LogsStreamProbe");
        String marker = "sse-marker-" + UUID.randomUUID();
        probe.info(marker); // captured by LiveLogAppender into LiveLogStore

        MvcResult result = mockMvc.perform(get("/logs/stream").accept(MediaType.TEXT_EVENT_STREAM))
                .andExpect(request().asyncStarted())
                .andReturn();

        assertThat(result.getResponse().getContentType())
                .as("must be a Server-Sent Events stream")
                .contains(MediaType.TEXT_EVENT_STREAM_VALUE);
        assertThat(result.getResponse().getContentAsString())
                .as("the stream replays retained history, which includes the just-logged marker")
                .contains(marker);
    }
}

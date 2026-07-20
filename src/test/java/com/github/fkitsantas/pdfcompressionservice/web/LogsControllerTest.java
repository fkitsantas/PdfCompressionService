package com.github.fkitsantas.pdfcompressionservice.web;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Verifies the live {@code /logs} view: the HTML page is served, and
 * {@code /logs/stream} is a Server-Sent Events endpoint that replays captured
 * history over a real connection - proving the in-process appender -> store ->
 * bounded-queue -> virtual-thread-sender wiring delivers events end to end.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
class LogsControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Value("${local.server.port}")
    private int port;

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
    void streamReplaysRecentHistoryOverServerSentEvents() throws Exception {
        Logger probe = LoggerFactory.getLogger("LogsStreamProbe");
        String marker = "sse-marker-" + UUID.randomUUID();
        probe.info(marker); // captured by LiveLogAppender into LiveLogStore

        HttpClient client = HttpClient.newHttpClient();
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/logs/stream"))
                .header("Accept", MediaType.TEXT_EVENT_STREAM_VALUE)
                .build();
        HttpResponse<InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());

        assertThat(response.headers().firstValue("content-type").orElse(""))
                .as("must be a Server-Sent Events stream")
                .contains(MediaType.TEXT_EVENT_STREAM_VALUE);

        // Read the (open) stream on a worker so a missing marker can't hang the test.
        InputStream body = response.body();
        ExecutorService reader = Executors.newSingleThreadExecutor();
        Future<Boolean> found = reader.submit(() -> {
            try (BufferedReader in = new BufferedReader(new InputStreamReader(body, StandardCharsets.UTF_8))) {
                String line;
                while ((line = in.readLine()) != null) {
                    if (line.contains(marker)) {
                        return true;
                    }
                }
                return false;
            }
        });
        try {
            assertThat(found.get(10, TimeUnit.SECONDS))
                    .as("the just-logged marker must arrive over the live stream (replayed history)")
                    .isTrue();
        } finally {
            body.close();       // unblocks the reader if it is still waiting on the open stream
            reader.shutdownNow();
        }
    }
}

package com.github.fkitsantas.pdfcompressionservice;

import java.io.IOException;
import java.util.function.Consumer;

import org.springframework.http.MediaType;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import com.github.fkitsantas.pdfcompressionservice.logging.LiveLogStore;
import com.github.fkitsantas.pdfcompressionservice.logging.LogEntry;

/**
 * Serves the live logs view. {@code GET /logs} returns an HTML page that opens
 * a Server-Sent Events connection to {@code GET /logs/stream}; the stream first
 * replays the retained history and then pushes every new log event as it
 * happens, so the page updates live without refreshing.
 *
 * <p>Events come from {@link LiveLogStore} (fed by the in-process
 * {@code LiveLogAppender}); the endpoint reads no files, so there is nothing to
 * confine and it works identically whether launched from a jar, a bundle, or a
 * service manager.
 */
@Controller
public class LogsController {

    /**
     * How long a single SSE connection stays open before the server closes it;
     * the browser's {@code EventSource} then reconnects automatically, resuming
     * from its last-seen event id, so no events are lost across the reconnect.
     */
    private static final long STREAM_TIMEOUT_MS = 30 * 60 * 1000L;

    @GetMapping("/logs")
    public String getLogsPage() {
        return "logs";
    }

    @GetMapping(path = "/logs/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @ResponseBody
    public SseEmitter streamLogs(
            @RequestHeader(name = "Last-Event-ID", required = false) String lastEventId) {
        long afterId = parseLastEventId(lastEventId);
        SseEmitter emitter = new SseEmitter(STREAM_TIMEOUT_MS);
        LiveLogStore store = LiveLogStore.getInstance();

        Consumer<LogEntry> listener = entry -> {
            try {
                emitter.send(SseEmitter.event()
                        .id(Long.toString(entry.id()))
                        .name("log")
                        .data(entry.toJson(), MediaType.APPLICATION_JSON));
            } catch (IOException | IllegalStateException e) {
                // Client gone / response closed: signal LiveLogStore to drop this listener.
                throw new StreamClosedException(e);
            }
        };

        emitter.onCompletion(() -> store.unsubscribe(listener));
        emitter.onTimeout(() -> {
            store.unsubscribe(listener);
            emitter.complete();
        });
        emitter.onError(e -> store.unsubscribe(listener));

        // Atomically replays history then registers for live events (no gap, no duplicate).
        store.subscribe(afterId, listener);
        return emitter;
    }

    private static long parseLastEventId(String lastEventId) {
        if (lastEventId == null || lastEventId.isBlank()) {
            return 0L;
        }
        try {
            return Long.parseLong(lastEventId.trim());
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    /** Unchecked marker so a dead SSE connection unwinds cleanly through {@link LiveLogStore}. */
    private static final class StreamClosedException extends RuntimeException {
        StreamClosedException(Throwable cause) {
            super(cause);
        }
    }
}

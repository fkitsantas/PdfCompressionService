package com.github.fkitsantas.pdfcompressionservice;

import java.io.IOException;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

import org.springframework.http.MediaType;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
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
 *
 * <p><b>Delivery is decoupled from logging.</b> {@link LiveLogStore} only ever
 * hands each event to this connection's bounded queue with a non-blocking
 * offer; a dedicated virtual thread drains that queue and performs the blocking
 * {@link SseEmitter#send} network write. So a slow, paused, or vanished viewer
 * can only fill (and then forfeit) its own queue - it can never block the
 * threads that emit log events, and one viewer never affects another.
 */
@Controller
public class LogsController {

    /**
     * How long a single SSE connection stays open before the server closes it;
     * the browser's {@code EventSource} then reconnects automatically, resuming
     * from its last-seen event id, so no events are lost across the reconnect.
     */
    private static final long STREAM_TIMEOUT_MS = 30 * 60 * 1000L;

    /**
     * Per-connection outbound buffer. Comfortably larger than the store's
     * history capacity so a healthy client receives the full replay plus live
     * burst; a client too slow to keep up overflows it and is dropped rather
     * than allowed to accumulate unbounded memory.
     */
    private static final int STREAM_QUEUE_CAPACITY = 8192;

    @GetMapping("/logs")
    public String getLogsPage() {
        return "logs";
    }

    @GetMapping(path = "/logs/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @ResponseBody
    public SseEmitter streamLogs(
            @RequestHeader(name = "Last-Event-ID", required = false) String lastEventId,
            @RequestParam(name = "after", required = false) String after) {
        // The browser's EventSource sets Last-Event-ID automatically on its own
        // auto-reconnect; the `after` query param is how the page resumes after a
        // deliberate close/reopen (e.g. when the tab was hidden), where no header
        // is sent. Header wins when both are present.
        String resumeFrom = (lastEventId != null && !lastEventId.isBlank()) ? lastEventId : after;
        long afterId = parseLastEventId(resumeFrom);
        SseEmitter emitter = new SseEmitter(STREAM_TIMEOUT_MS);
        LiveLogStore store = LiveLogStore.getInstance();
        BlockingQueue<LogEntry> queue = new LinkedBlockingQueue<>(STREAM_QUEUE_CAPACITY);
        AtomicBoolean open = new AtomicBoolean(true);

        // Runs on the logging/replay thread: never blocks, never does I/O. If the
        // client's queue is full it has fallen too far behind, so drop it (throwing
        // signals LiveLogStore to unregister this listener).
        Consumer<LogEntry> listener = entry -> {
            if (!open.get() || !queue.offer(entry)) {
                open.set(false);
                throw new StreamClosedException(null);
            }
        };

        // One virtual thread per connection performs the blocking sends, so a slow
        // consumer is confined to its own thread and its own queue.
        Thread sender = Thread.ofVirtual().name("logs-sse-sender").unstarted(() -> {
            try {
                while (open.get()) {
                    LogEntry entry = queue.poll(1, TimeUnit.SECONDS);
                    if (entry != null) {
                        emitter.send(SseEmitter.event()
                                .id(Long.toString(entry.id()))
                                .name("log")
                                .data(entry.toJson(), MediaType.APPLICATION_JSON));
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (IOException | RuntimeException e) {
                // Client gone / response closed: fall through and clean up.
            } finally {
                open.set(false);
                store.unsubscribe(listener);
                try {
                    emitter.complete();
                } catch (RuntimeException ignored) {
                    // already completed
                }
            }
        });

        Runnable close = () -> {
            open.set(false);
            store.unsubscribe(listener);
            sender.interrupt();
        };
        emitter.onCompletion(close);
        emitter.onTimeout(close);
        emitter.onError(e -> close.run());

        sender.start();
        // Atomically replays history (non-blocking offers) then registers for live
        // events, so the queue receives history-then-live in id order, no gap, no dup.
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

    /** Unchecked marker so a dead/too-slow SSE connection unwinds cleanly through {@link LiveLogStore}. */
    private static final class StreamClosedException extends RuntimeException {
        StreamClosedException(Throwable cause) {
            super(cause);
        }
    }
}

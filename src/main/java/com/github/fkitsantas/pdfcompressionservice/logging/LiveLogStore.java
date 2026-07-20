package com.github.fkitsantas.pdfcompressionservice.logging;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * Process-wide, in-memory store of recent log events plus a set of live
 * listeners. It is the bridge between {@link LiveLogAppender} (which Logback
 * instantiates outside the Spring context and which pushes events in) and the
 * web layer (which reads recent history and subscribes for live updates to
 * feed the {@code /logs} Server-Sent Events stream).
 *
 * <p>Because the appender is not a Spring bean, this store is a plain
 * eagerly-initialized singleton reached via {@link #getInstance()} rather than
 * dependency injection. It is fully thread-safe: events arrive on arbitrary
 * logging threads and are read/subscribed from request threads.
 *
 * <p>The history is a bounded ring buffer (so memory stays bounded on a
 * long-running service); each event gets a monotonically increasing id so a
 * browser that reconnects can resume from its last-seen id without gaps or
 * duplicates.
 *
 * <p><b>Listener contract:</b> listeners are invoked while the internal lock is
 * held (so replay-then-live ordering is consistent), therefore a listener MUST
 * be non-blocking and MUST NOT perform I/O - it should only hand the event to
 * its own buffer. A listener that throws is dropped. The {@code /logs} endpoint
 * follows this by having its listener do a non-blocking queue offer and doing
 * the actual network send on a separate per-connection thread.
 */
public final class LiveLogStore {

    /** Maximum number of recent events retained for the "history" portion of the view. */
    private static final int DEFAULT_CAPACITY = 5000;

    private static final LiveLogStore INSTANCE = new LiveLogStore(DEFAULT_CAPACITY);

    public static LiveLogStore getInstance() {
        return INSTANCE;
    }

    private final int capacity;
    private final Deque<LogEntry> history;
    private final AtomicLong sequence = new AtomicLong();
    private final List<Consumer<LogEntry>> listeners = new CopyOnWriteArrayList<>();

    LiveLogStore(int capacity) {
        this.capacity = capacity;
        this.history = new ArrayDeque<>(capacity);
    }

    /**
     * Records an event: assigns it the next id, appends it to the bounded
     * history (evicting the oldest if full) and notifies every live listener.
     * The append and the notify happen under the same lock as {@link
     * #subscribe} so a new subscriber can never miss or duplicate an event that
     * arrives while it is replaying history. A listener that throws (e.g. a
     * browser that has disconnected) is dropped so a dead subscriber can never
     * wedge the logging pipeline.
     */
    public void record(long epochMillis, String level, String logger, String thread,
                        String requestId, String message) {
        LogEntry entry = new LogEntry(sequence.incrementAndGet(), epochMillis, level, logger,
                thread, requestId == null ? "" : requestId, message);
        synchronized (history) {
            if (history.size() >= capacity) {
                history.removeFirst();
            }
            history.addLast(entry);
            for (Consumer<LogEntry> listener : listeners) {
                try {
                    listener.accept(entry);
                } catch (RuntimeException ex) {
                    listeners.remove(listener);
                }
            }
        }
    }

    /**
     * Atomically replays the retained history (events with id greater than
     * {@code afterId}) to {@code listener} and then registers it for all future
     * events. Because this runs under the same lock as {@link #record}, there
     * is exactly one consistent ordering: the listener sees every retained
     * event once, in id order, immediately followed by live events, with no gap
     * and no duplicate. {@code afterId} lets a reconnecting browser resume from
     * its last-seen SSE id.
     */
    public void subscribe(long afterId, Consumer<LogEntry> listener) {
        synchronized (history) {
            try {
                for (LogEntry e : history) {
                    if (e.id() > afterId) {
                        listener.accept(e);
                    }
                }
            } catch (RuntimeException alreadyGone) {
                // The client disconnected mid-replay; do not register it for live events.
                return;
            }
            listeners.add(listener);
        }
    }

    /** Snapshot of retained events whose id is greater than {@code afterId} (0 for all). */
    public List<LogEntry> history(long afterId) {
        synchronized (history) {
            List<LogEntry> out = new ArrayList<>(history.size());
            for (LogEntry e : history) {
                if (e.id() > afterId) {
                    out.add(e);
                }
            }
            return out;
        }
    }

    public void unsubscribe(Consumer<LogEntry> listener) {
        listeners.remove(listener);
    }

    /** Test/diagnostic helper: number of currently registered live listeners. */
    public int listenerCount() {
        return listeners.size();
    }
}

package com.github.fkitsantas.pdfcompressionservice.logging;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for the in-memory log store that backs the live {@code /logs}
 * stream: bounded history with monotonic ids, and atomic replay-then-subscribe
 * so a live viewer sees every retained event once, in order, immediately
 * followed by live events, with no gap and no duplicate.
 */
class LiveLogStoreTest {

    private static void record(LiveLogStore store, String message) {
        store.record(System.currentTimeMillis(), "INFO", "Test", "main", "req-1", message);
    }

    @Test
    void historyIsBoundedAndIdsAreMonotonic() {
        LiveLogStore store = new LiveLogStore(3);
        for (int i = 1; i <= 5; i++) {
            record(store, "m" + i);
        }
        List<LogEntry> history = store.history(0);
        assertThat(history).extracting(LogEntry::message).containsExactly("m3", "m4", "m5");
        assertThat(history).extracting(LogEntry::id).containsExactly(3L, 4L, 5L);
    }

    @Test
    void historyAfterIdReturnsOnlyNewerEvents() {
        LiveLogStore store = new LiveLogStore(10);
        for (int i = 1; i <= 4; i++) {
            record(store, "m" + i);
        }
        assertThat(store.history(2)).extracting(LogEntry::message).containsExactly("m3", "m4");
    }

    @Test
    void subscribeReplaysHistoryThenDeliversLiveEventsWithoutGapOrDuplicate() {
        LiveLogStore store = new LiveLogStore(10);
        record(store, "old-1");
        record(store, "old-2");

        List<String> received = new ArrayList<>();
        Consumer<LogEntry> listener = e -> received.add(e.message());
        store.subscribe(0, listener);

        // replay delivered the two retained events, in order
        assertThat(received).containsExactly("old-1", "old-2");

        record(store, "live-1");
        record(store, "live-2");
        assertThat(received).containsExactly("old-1", "old-2", "live-1", "live-2");
        assertThat(store.listenerCount()).isEqualTo(1);
    }

    @Test
    void subscribeWithLastEventIdSkipsAlreadySeenHistory() {
        LiveLogStore store = new LiveLogStore(10);
        record(store, "m1");
        record(store, "m2");
        record(store, "m3");

        List<String> received = new ArrayList<>();
        store.subscribe(2, e -> received.add(e.message()));
        assertThat(received).containsExactly("m3");
    }

    @Test
    void unsubscribedListenerStopsReceivingEvents() {
        LiveLogStore store = new LiveLogStore(10);
        List<String> received = new ArrayList<>();
        Consumer<LogEntry> listener = e -> received.add(e.message());
        store.subscribe(0, listener);
        record(store, "before");
        store.unsubscribe(listener);
        record(store, "after");
        assertThat(received).containsExactly("before");
        assertThat(store.listenerCount()).isZero();
    }

    @Test
    void aListenerThatThrowsIsDroppedAndDoesNotBreakOthers() {
        LiveLogStore store = new LiveLogStore(10);
        List<String> good = new ArrayList<>();
        Consumer<LogEntry> throwing = e -> { throw new RuntimeException("client gone"); };
        store.subscribe(0, throwing);
        store.subscribe(0, e -> good.add(e.message()));

        record(store, "x");
        record(store, "y");

        assertThat(good).containsExactly("x", "y");   // healthy listener keeps working
        assertThat(store.listenerCount()).isEqualTo(1); // the throwing one was dropped
    }

    @Test
    void singletonIsAvailable() {
        assertThat(LiveLogStore.getInstance()).isNotNull();
    }
}

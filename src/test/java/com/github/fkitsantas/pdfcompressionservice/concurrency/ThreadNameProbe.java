package com.github.fkitsantas.pdfcompressionservice.concurrency;

import java.util.HashSet;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Test-only utility: snapshots/diffs live JVM thread names by prefix.
 *
 * <p>Used across the parallelism tests to prove that image-processing work
 * actually ran on {@link com.github.fkitsantas.pdfcompressionservice.compression.PdfCompressionEngine}'s
 * shared bounded executor, without any reliance on wall-clock timing:
 * {@code java.util.concurrent.ThreadPoolExecutor} creates its worker threads
 * lazily, only when a task is actually submitted, so the mere *existence* of
 * a {@code pdf-img-*} named thread after a {@code compress(...)} call is
 * proof that per-image work was dispatched to the executor, regardless of
 * how fast/slow that work ran.
 *
 * <p>Surefire runs this project's test classes sequentially within a single
 * forked JVM (no parallel test execution is configured), so a before/after
 * snapshot diff scoped to one test method is not racing any other test
 * class. Thread names are made globally unique across engine instances via
 * {@code PdfCompressionEngine.IMAGE_THREAD_COUNTER}, so set-membership
 * comparisons are safe even when this JVM has already run other tests that
 * constructed their own engines/executors earlier in the same fork.
 */
final class ThreadNameProbe {

    private ThreadNameProbe() {
    }

    static Set<String> snapshotNames() {
        return Thread.getAllStackTraces().keySet().stream()
                .map(Thread::getName)
                .collect(Collectors.toCollection(HashSet::new));
    }

    /** Names present now but absent from {@code before}, matching {@code prefix}. */
    static Set<String> newNamesWithPrefix(Set<String> before, String prefix) {
        Set<String> result = new HashSet<>();
        for (String name : snapshotNames()) {
            if (name.startsWith(prefix) && !before.contains(name)) {
                result.add(name);
            }
        }
        return result;
    }

    /** All currently-live thread names matching {@code prefix}, no before/after filtering. */
    static Set<String> namesWithPrefix(String prefix) {
        Set<String> result = new HashSet<>();
        for (String name : snapshotNames()) {
            if (name.startsWith(prefix)) {
                result.add(name);
            }
        }
        return result;
    }
}

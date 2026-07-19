package com.github.fkitsantas.pdfcompressionservice.logging;

/**
 * One captured log event, in a form that is cheap to keep in memory and to
 * serialize to the browser for the live {@code /logs} view. Carries only
 * operational metadata (timestamp, level, thread, correlation id, logger and
 * message, plus any stack trace) - never document contents.
 *
 * @param id          monotonically increasing sequence number, used as the SSE
 *                    event id so a reconnecting browser can resume without gaps
 *                    or duplicates
 * @param epochMillis event time (wall clock)
 * @param level       log level (ERROR/WARN/INFO/DEBUG/TRACE)
 * @param logger      short logger name (simple class name)
 * @param thread      the thread that emitted the event
 * @param requestId   the request correlation id from MDC, or empty if none
 * @param message     the formatted message, with any throwable stack trace appended
 */
public record LogEntry(long id, long epochMillis, String level, String logger,
                       String thread, String requestId, String message) {

    /** Serializes to a compact JSON object for the SSE {@code data:} field. */
    public String toJson() {
        StringBuilder sb = new StringBuilder(message.length() + 128);
        sb.append('{')
                .append("\"id\":").append(id)
                .append(",\"epochMillis\":").append(epochMillis)
                .append(",\"level\":\"").append(level).append('"')
                .append(",\"logger\":");
        appendJsonString(sb, logger);
        sb.append(",\"thread\":");
        appendJsonString(sb, thread);
        sb.append(",\"requestId\":");
        appendJsonString(sb, requestId);
        sb.append(",\"message\":");
        appendJsonString(sb, message);
        sb.append('}');
        return sb.toString();
    }

    private static void appendJsonString(StringBuilder sb, String s) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        sb.append('"');
    }
}

package com.github.fkitsantas.pdfcompressionservice.logging;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.classic.spi.ThrowableProxyUtil;
import ch.qos.logback.core.AppenderBase;

/**
 * Logback appender that feeds every log event into {@link LiveLogStore}, from
 * where the {@code /logs} view reads recent history and streams live updates.
 * Referenced by {@code logback-spring.xml}; Logback instantiates it outside the
 * Spring context, which is why it talks to the store through its static
 * singleton rather than via injection.
 *
 * <p>The full stack trace of any logged throwable is appended to the message,
 * so exceptions (the whole point of "detailed logs to debug when something goes
 * wrong") are visible in the browser, not just in the console.
 */
public class LiveLogAppender extends AppenderBase<ILoggingEvent> {

    @Override
    protected void append(ILoggingEvent event) {
        String message = event.getFormattedMessage();
        IThrowableProxy throwable = event.getThrowableProxy();
        if (throwable != null) {
            message = message + System.lineSeparator() + ThrowableProxyUtil.asString(throwable);
        }
        String requestId = event.getMDCPropertyMap().get("requestId");
        LiveLogStore.getInstance().record(
                event.getTimeStamp(),
                event.getLevel().toString(),
                shortLoggerName(event.getLoggerName()),
                event.getThreadName(),
                requestId,
                message);
    }

    /** {@code a.b.c.MyClass} -> {@code MyClass}, keeping the browser view compact. */
    private static String shortLoggerName(String loggerName) {
        if (loggerName == null) {
            return "";
        }
        int dot = loggerName.lastIndexOf('.');
        return dot >= 0 && dot < loggerName.length() - 1 ? loggerName.substring(dot + 1) : loggerName;
    }
}

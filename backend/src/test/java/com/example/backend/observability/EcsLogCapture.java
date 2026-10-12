package com.example.backend.observability;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.OutputStreamAppender;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.slf4j.LoggerFactory;
import org.springframework.boot.logging.logback.StructuredLogEncoder;
import org.springframework.core.env.Environment;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Captures what this service's logging actually emits, encoded by the same
 * {@link StructuredLogEncoder} in the same ECS format the deployed console
 * appender uses, into memory where a test can parse it.
 *
 * <p>Deliberately not stdout capture. Reading the process's output would test the
 * test harness's plumbing — which appender holds which stream, and whether a
 * captured {@code System.out} was installed before logging initialized — as much
 * as the property under test, and a record that never reaches the buffer looks
 * exactly like a record that was correctly redacted. Encoding through the
 * production encoder into a buffer this class owns makes "no forbidden value
 * appears in a log line" a statement about the bytes the service produces.
 */
public final class EcsLogCapture implements AutoCloseable {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    /** The message of the record written once per request as its dispatch unwinds. */
    private static final String REQUEST_RECORD = "HTTP request completed";

    private final LoggerContext loggerContext;
    private final OutputStreamAppender<ILoggingEvent> appender;
    private final ByteArrayOutputStream encoded;

    private EcsLogCapture(
            LoggerContext loggerContext,
            OutputStreamAppender<ILoggingEvent> appender,
            ByteArrayOutputStream encoded) {
        this.loggerContext = loggerContext;
        this.appender = appender;
        this.encoded = encoded;
    }

    /**
     * Attaches to the root logger, so a record from any logger in the service is
     * captured whether or not the test knew to expect it — which is the point when
     * the assertion is that a value appears nowhere.
     *
     * <p>The environment is passed in and placed on the logger context because the
     * encoder reads its ECS service fields from there. Boot's own logging
     * initialization puts it in the same place; doing it here as well makes the
     * capture independent of whether that has already run.
     */
    public static EcsLogCapture attach(Environment environment) {
        LoggerContext loggerContext = (LoggerContext) LoggerFactory.getILoggerFactory();
        loggerContext.putObject(Environment.class.getName(), environment);

        StructuredLogEncoder encoder = new StructuredLogEncoder();
        encoder.setContext(loggerContext);
        encoder.setFormat("ecs");
        encoder.setCharset(StandardCharsets.UTF_8);
        encoder.start();

        ByteArrayOutputStream encoded = new ByteArrayOutputStream();
        OutputStreamAppender<ILoggingEvent> appender = new OutputStreamAppender<>();
        appender.setName("ecs-capture");
        appender.setContext(loggerContext);
        appender.setEncoder(encoder);
        appender.setOutputStream(encoded);
        appender.start();

        rootLogger(loggerContext).addAppender(appender);
        return new EcsLogCapture(loggerContext, appender, encoded);
    }

    /** Everything captured so far, one line per record, exactly as encoded. */
    public String lines() {
        return encoded.toString(StandardCharsets.UTF_8);
    }

    /**
     * Every captured record parsed as JSON. A record that is not valid JSON fails
     * here rather than being skipped: "the output is structured" is one of the
     * things being asserted.
     */
    public List<JsonNode> records() {
        return lines().lines()
                .filter(line -> !line.isBlank())
                .map(EcsLogCapture::parse)
                .toList();
    }

    /**
     * Waits until {@code count} request records have been captured, then lets anything still in
     * flight land. The request record is written as the request dispatch unwinds, which can be
     * after the client has its response; an error dispatch, if any, has finished by the time the
     * response was sent. Gives up after five seconds and leaves the caller's assertions to say
     * what is missing.
     */
    public void awaitRequestRecords(int count) throws InterruptedException {
        long deadline = System.nanoTime() + 5_000_000_000L;
        while (records().stream()
                .filter(record -> REQUEST_RECORD.equals(record.at("/message").asText()))
                .count() < count
                && System.nanoTime() < deadline) {
            Thread.sleep(20);
        }
        Thread.sleep(250);
    }

    /** Discards what was captured, so one test's records cannot bleed into the next. */
    public void reset() {
        encoded.reset();
    }

    @Override
    public void close() {
        rootLogger(loggerContext).detachAppender(appender);
        appender.stop();
    }

    private static JsonNode parse(String line) {
        try {
            return JSON.readTree(line);
        } catch (RuntimeException notJson) {
            throw new IllegalStateException("Log record is not valid JSON: " + line, notJson);
        }
    }

    private static Logger rootLogger(LoggerContext loggerContext) {
        return loggerContext.getLogger(Logger.ROOT_LOGGER_NAME);
    }
}

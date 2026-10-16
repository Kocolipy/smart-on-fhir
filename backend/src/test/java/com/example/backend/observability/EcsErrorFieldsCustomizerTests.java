package com.example.backend.observability;

import static com.example.backend.observability.SqlFailures.PERMISSION_DENIED;
import static com.example.backend.observability.SqlFailures.UNIQUE_CONSTRAINT_VIOLATION;
import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.pattern.ThrowableProxyConverter;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.classic.spi.LoggingEvent;
import ch.qos.logback.classic.spi.ThrowableProxy;
import ch.qos.logback.classic.spi.ThrowableProxyVO;
import java.io.EOFException;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.slf4j.event.KeyValuePair;
import org.springframework.boot.logging.StackTracePrinter;
import org.springframework.boot.logging.logback.StructuredLogEncoder;
import org.springframework.core.env.Environment;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.mock.env.MockEnvironment;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The remap on real encoded output: each record is encoded twice by Boot's own ECS encoder, once
 * with {@link EcsErrorFieldsCustomizer} configured and once without, so the formatter's own
 * rendering is the oracle for everything the customizer must leave as it was.
 */
class EcsErrorFieldsCustomizerTests {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private static final String CUSTOMIZER = "logging.structured.json.customizer";

    /** A failure whose message and stack a record would carry, with a cause, as real ones have. */
    private static final IllegalStateException FAILURE =
            new IllegalStateException("downstream failure", new IllegalArgumentException("root"));

    @Test
    void theClassificationIsWrittenInsideErrorAndNotAtTheTopLevel() {
        JsonNode record = encoded(withCustomizer(), event(null, classification(500, "database", true)));

        assertThat(record.at("/error/code").asInt()).isEqualTo(500);
        assertThat(record.at("/error/code").isIntegralNumber()).isTrue();
        assertThat(record.at("/error/category").asText()).isEqualTo("database");
        assertThat(record.at("/error/follow_up_action").isBoolean()).isTrue();
        assertThat(record.at("/error/follow_up_action").asBoolean()).isTrue();
        assertThat(record.has(LogEvent.ERROR_CODE)).isFalse();
        assertThat(record.has(LogEvent.ERROR_CATEGORY)).isFalse();
        assertThat(record.has(LogEvent.ERROR_FOLLOW_UP_ACTION)).isFalse();
        assertThat(fieldNames(record.get("error")))
                .containsExactly("code", "category", "follow_up_action");
    }

    /** {@code false} is a value, not an absence: a refusal says it needs no follow-up. */
    @Test
    void aFollowUpOfFalseIsWritten() {
        JsonNode record = encoded(withCustomizer(), event(null, classification(400, "data", false)));

        assertThat(record.at("/error/follow_up_action").isBoolean()).isTrue();
        assertThat(record.at("/error/follow_up_action").asBoolean()).isFalse();
    }

    /** Each field on its own still opens the {@code error} object, and only it is written. */
    @Test
    void anyOneFieldAloneIsMoved() {
        for (String key : EcsErrorFieldsCustomizer.FIELDS) {
            JsonNode record = encoded(withCustomizer(), event(null, List.of(new KeyValuePair(key, "v"))));

            assertThat(record.has(key)).as(key).isFalse();
            assertThat(record.get("error").size()).as(key).isEqualTo(1);
            assertThat(record.get("error").valueStream().findFirst().orElseThrow().asText())
                    .as(key).isEqualTo("v");
        }
    }

    /**
     * Beside an attached exception: the formatter's three fields exactly as the formatter writes
     * them, and the classification in the same object — one {@code error}, not two.
     */
    @Test
    void theClassificationJoinsTheFormattersErrorObject() {
        LoggingEvent event = event(FAILURE, classification(500, "application", true));

        JsonNode customized = encoded(withCustomizer(), event);
        JsonNode formatters = encoded(new MockEnvironment(), event).get("error");

        assertThat(fieldNames(customized.get("error"))).containsExactly(
                "type", "message", "stack_trace", "code", "category", "follow_up_action");
        assertThat(customized.at("/error/type")).isEqualTo(formatters.get("type"));
        assertThat(customized.at("/error/message")).isEqualTo(formatters.get("message"));
        assertThat(customized.at("/error/stack_trace")).isEqualTo(formatters.get("stack_trace"));
        assertThat(customized.at("/error/type").asText())
                .isEqualTo(IllegalStateException.class.getName());
        assertThat(customized.at("/error/stack_trace").asText())
                .contains("downstream failure", "Caused by", "root");
        assertThat(rawLine(withCustomizer(), event).split("\"error\":", -1)).hasSize(2);
    }

    /** A record with an exception and no classification is the record the formatter writes. */
    @Test
    void anExceptionWithoutClassificationEncodesAsTheFormatterAlone() {
        LoggingEvent event = event(FAILURE, List.of());

        assertThat(encoded(withCustomizer(), event)).isEqualTo(encoded(new MockEnvironment(), event));
    }

    @Test
    void anExceptionWithANullMessageEncodesAsTheFormatterAlone() {
        LoggingEvent event = event(new IllegalStateException(), List.of());

        JsonNode customized = encoded(withCustomizer(), event);
        assertThat(customized).isEqualTo(encoded(new MockEnvironment(), event));
        assertThat(customized.at("/error").has("message")).isTrue();
        assertThat(customized.at("/error/message").isNull()).isTrue();
    }

    /** No exception and no classification: no {@code error} object, and nothing else changed. */
    @Test
    void aRecordWithNeitherIsUntouched() {
        LoggingEvent event = event(null, List.of(
                new KeyValuePair(LogEvent.OUTCOME, "success"),
                new KeyValuePair(LogEvent.ERROR_CAUSE_OMITTED, "why")));

        JsonNode customized = encoded(withCustomizer(), event);
        assertThat(customized.has("error")).isFalse();
        assertThat(customized).isEqualTo(encoded(new MockEnvironment(), event));
        assertThat(customized.at("/app/error/cause_omitted").asText()).isEqualTo("why");
    }

    /** Only the three top-level keys move; the rest of a record's pairs stay where they were. */
    @Test
    void theOtherPairsStayWhereTheyWere() {
        List<KeyValuePair> pairs = new java.util.ArrayList<>(classification(500, "database", true));
        pairs.add(new KeyValuePair(LogEvent.OUTCOME, "failure"));
        pairs.add(new KeyValuePair(LogEvent.REASON, "SQLServerException"));

        JsonNode record = encoded(withCustomizer(), event(null, pairs));

        assertThat(record.at("/event/outcome").asText()).isEqualTo("failure");
        assertThat(record.at("/event/reason").asText()).isEqualTo("SQLServerException");
        assertThat(record.at("/message").asText()).isEqualTo("record");
        assertThat(record.at("/log/level").asText()).isEqualTo("ERROR");
    }

    /**
     * With a stack trace printer configured, the stack is the printer's, as the formatter's is —
     * not the converter's default rendering.
     */
    @Test
    void aConfiguredStackTracePrinterIsTheOneUsed() {
        LoggingEvent event = event(FAILURE, classification(500, "application", true));

        String customized = encoded(withCustomizer(printing()), event).at("/error/stack_trace").asText();
        String formatters = encoded(printing(), event).at("/error/stack_trace").asText();
        String unconfigured = encoded(withCustomizer(), event).at("/error/stack_trace").asText();

        assertThat(customized).isNotBlank().isEqualTo(formatters).isNotEqualTo(unconfigured);
    }

    /**
     * The printer needs the live {@code Throwable}; a proxy that carries none (a deserialized
     * {@link ThrowableProxyVO}) falls back to the converter even with a printer configured.
     */
    @Test
    void aProxyWithoutItsThrowableFallsBackToTheConverter() {
        LoggingEvent event = new LoggingEvent() {
            @Override
            public IThrowableProxy getThrowableProxy() {
                return ThrowableProxyVO.build(new ThrowableProxy(FAILURE));
            }
        };
        ThrowableProxyConverter converter = new ThrowableProxyConverter() {
            @Override
            public String convert(ILoggingEvent ignored) {
                return "converter";
            }
        };
        StackTracePrinter printer = (failure, out) -> out.append("printer");

        assertThat(new EcsErrorFieldsCustomizer(printer, converter).stackTrace(event))
                .isEqualTo("converter");
    }

    // ---- SQL exception text --------------------------------------------------------------------

    /**
     * What a driver's refusal reads like: the constraint, the schema and the refused value all in
     * the message, as SQL Server's unique-violation text (error 2627) quotes them.
     */
    private static final String DRIVER_TEXT = "Violation of UNIQUE KEY constraint 'uq_probe_name'."
            + " Cannot insert duplicate key in object 'probe_schema.probe'."
            + " The duplicate key value is (refused-alice).";

    /** A translated refusal: Spring's wrapper quotes the driver's text and the statement. */
    private static RuntimeException translatedRefusal() {
        return new DataIntegrityViolationException(
                "StatementCallback; SQL [INSERT INTO probe_db.probe_schema.probe VALUES"
                        + " ('refused-alice')]; " + DRIVER_TEXT,
                UNIQUE_CONSTRAINT_VIOLATION.raised(DRIVER_TEXT));
    }

    /**
     * An SQL exception anywhere in the chain: {@code error.message} is the engine-neutral code
     * and state, never the driver's text, and the record still names the real exception type.
     */
    @Test
    void aChainHoldingAnSqlExceptionHasItsMessageRedactedToTheCodeAndState() {
        JsonNode record = encoded(withCustomizer(),
                event(translatedRefusal(), classification(500, "database", true)));

        assertThat(record.at("/error/message").asText())
                .isEqualTo(UNIQUE_CONSTRAINT_VIOLATION.redacted());
        assertThat(record.at("/error/type").asText())
                .isEqualTo("org.springframework.dao.DataIntegrityViolationException");
    }

    /**
     * The stack trace keeps every class and every frame, and each {@code Caused by:} line
     * carries the redacted text in place of the driver's.
     */
    @Test
    void theStackTraceKeepsClassesAndFramesAndRedactsEachCausedByLine() {
        String stack = encoded(withCustomizer(),
                event(translatedRefusal(), classification(500, "database", true)))
                .at("/error/stack_trace").asText();

        assertThat(stack)
                .startsWith("org.springframework.dao.DataIntegrityViolationException:"
                        + " " + UNIQUE_CONSTRAINT_VIOLATION.redacted())
                .contains("Caused by: java.sql.SQLException: "
                        + UNIQUE_CONSTRAINT_VIOLATION.redacted())
                .contains("at " + EcsErrorFieldsCustomizerTests.class.getName()
                        + ".translatedRefusal(EcsErrorFieldsCustomizerTests.java:");
    }

    /** No part of the driver's text survives anywhere in the record, under either renderer. */
    @Test
    void noPartOfTheDriversTextReachesTheRecordUnderEitherRenderer() {
        LoggingEvent event = event(translatedRefusal(), classification(500, "database", true));

        for (MockEnvironment environment : List.of(withCustomizer(), withCustomizer(printing()))) {
            assertThat(rawLine(environment, event))
                    .doesNotContain("refused-alice")
                    .doesNotContain("probe_schema")
                    .doesNotContain("probe_db")
                    .doesNotContain("uq_probe_name")
                    .doesNotContain("duplicate key");
        }
    }

    /** A suppressed SQL exception is redacted as a cause is, and its frames kept. */
    @Test
    void aSuppressedSqlExceptionIsRedactedToo() {
        IllegalStateException failure = new IllegalStateException("cleanup failed");
        failure.addSuppressed(PERMISSION_DENIED.raised(
                "The SELECT permission was denied on the object 'probe', database 'probe_db',"
                        + " schema 'probe_schema'."));

        String stack = encoded(withCustomizer(), event(failure, List.of()))
                .at("/error/stack_trace").asText();

        assertThat(stack)
                .startsWith("java.lang.IllegalStateException: cleanup failed")
                .contains("Suppressed: java.sql.SQLException: " + PERMISSION_DENIED.redacted())
                .doesNotContain("probe_schema");
    }

    /**
     * A message beneath the SQL exception — the driver's own cause — is the driver's to have
     * written, not a quote of the statement, and is kept; the state, odd or not, is never
     * repeated.
     */
    @Test
    void aCauseBeneathTheSqlExceptionKeepsItsMessageAndTheStateIsNotRepeated() {
        SQLException driver = new SQLException("driver text", "bad\nstate", 4060,
                new EOFException("connection reset"));

        JsonNode record = encoded(withCustomizer(), event(driver, List.of()));

        assertThat(record.at("/error/message").asText()).isEqualTo("SQL Server error 4060");
        assertThat(record.at("/error/stack_trace").asText())
                .contains("Caused by: java.io.EOFException: connection reset")
                .doesNotContain("driver text");
    }

    /**
     * A cause chain that loops back through its SQL exception is redacted and ends: the loop is
     * cut where it would repeat, and the driver's text appears nowhere.
     */
    @Test
    void aCyclicChainThroughAnSqlExceptionIsRedactedAndEnds() {
        SQLException driver = UNIQUE_CONSTRAINT_VIOLATION.raised("quoted-alice");
        IllegalStateException wrapper = new IllegalStateException("wraps quoted-alice", driver);
        driver.initCause(wrapper);

        String line = rawLine(withCustomizer(), event(wrapper, List.of()));

        assertThat(line)
                .contains("Caused by: java.sql.SQLException: "
                        + UNIQUE_CONSTRAINT_VIOLATION.redacted())
                .doesNotContain("quoted-alice");
    }

    /**
     * A cause chain that loops without an SQL exception in it, beside a suppressed one, keeps its
     * own messages and ends: only what quotes an SQL exception is redacted.
     */
    @Test
    void aCyclicChainWithoutAnSqlExceptionKeepsItsMessagesAndEnds() {
        IllegalStateException first = new IllegalStateException("first");
        IllegalArgumentException second = new IllegalArgumentException("second", first);
        first.initCause(second);
        first.addSuppressed(UNIQUE_CONSTRAINT_VIOLATION.raised("quoted-alice"));

        String stack = encoded(withCustomizer(), event(first, List.of()))
                .at("/error/stack_trace").asText();

        assertThat(stack)
                .startsWith("java.lang.IllegalStateException: first")
                .contains("Caused by: java.lang.IllegalArgumentException: second")
                .contains("Suppressed: java.sql.SQLException: "
                        + UNIQUE_CONSTRAINT_VIOLATION.redacted())
                .doesNotContain("quoted-alice");
    }

    /** Two throwables that suppress each other are each written once, and the walk ends. */
    @Test
    void mutuallySuppressedThrowablesAreEachWrittenOnce() {
        IllegalStateException first = new IllegalStateException("first");
        SQLException second = PERMISSION_DENIED.raised("quoted-alice");
        first.addSuppressed(second);
        second.addSuppressed(first);

        String stack = encoded(withCustomizer(), event(first, List.of()))
                .at("/error/stack_trace").asText();

        assertThat(stack.split("Suppressed: ", -1)).hasSize(2);
        assertThat(stack).contains("Suppressed: java.sql.SQLException: " + PERMISSION_DENIED.redacted());
    }

    /**
     * A tree with no SQL exception in it is the one the formatter writes, a cyclic one with a
     * suppressed exception included: logback marks the cycle where it repeats, and nothing is
     * rebuilt to redact.
     */
    @Test
    void aCyclicChainWithNoSqlExceptionEncodesAsTheFormatterAlone() {
        IllegalStateException first = new IllegalStateException("first");
        IllegalArgumentException second = new IllegalArgumentException("second", first);
        first.initCause(second);
        first.addSuppressed(new IllegalStateException("suppressed"));
        LoggingEvent event = event(first, List.of());

        assertThat(encoded(withCustomizer(), event)).isEqualTo(encoded(new MockEnvironment(), event));
    }

    /** An SQL exception that carries no SQLState still says it is one, by number, quoting nothing. */
    @Test
    void anSqlExceptionWithNoStateIsRedactedToItsNumber() {
        JsonNode record = encoded(withCustomizer(),
                event(new SQLException("quoted-alice", (String) null, 0), List.of()));

        assertThat(record.at("/error/message").asText()).isEqualTo("SQL Server error 0");
    }

    /**
     * A throwable that only suppresses an SQL exception does not quote it, so its own message is
     * the record's {@code error.message}.
     */
    @Test
    void aThrowableThatOnlySuppressesAnSqlExceptionKeepsItsOwnMessage() {
        IllegalStateException failure = new IllegalStateException("cleanup failed");
        failure.addSuppressed(PERMISSION_DENIED.raised("quoted-alice"));

        JsonNode record = encoded(withCustomizer(), event(failure, List.of()));

        assertThat(record.at("/error/message").asText()).isEqualTo("cleanup failed");
    }

    /**
     * A deserialized proxy carries no live throwable to redact from, so its message is written
     * as it holds it; the service's own records always carry the live one.
     */
    @Test
    void aProxyWithoutItsThrowableKeepsItsOwnMessage() {
        assertThat(EcsErrorFieldsCustomizer.message(ThrowableProxyVO.build(new ThrowableProxy(FAILURE))))
                .isEqualTo("downstream failure");
    }

    /** A fresh environment each call: {@link MockEnvironment#withProperty} mutates in place. */
    private static MockEnvironment printing() {
        return new MockEnvironment()
                .withProperty("logging.structured.json.stacktrace.root", "first")
                .withProperty("logging.structured.json.stacktrace.max-throwable-depth", "1");
    }

    @Test
    void theReplacedPathsAreExactlyTheTopLevelErrorMembers() {
        assertThat(EcsErrorFieldsCustomizer.replaced(path("error"))).isTrue();
        assertThat(EcsErrorFieldsCustomizer.replaced(path("error_code"))).isTrue();
        assertThat(EcsErrorFieldsCustomizer.replaced(path("error_category"))).isTrue();
        assertThat(EcsErrorFieldsCustomizer.replaced(path("error_follow_up_action"))).isTrue();
        assertThat(EcsErrorFieldsCustomizer.replaced(path("app.error"))).isFalse();
        assertThat(EcsErrorFieldsCustomizer.replaced(path("error.code"))).isFalse();
        assertThat(EcsErrorFieldsCustomizer.replaced(path(EcsErrorFieldsCustomizer.PENDING))).isFalse();
        assertThat(EcsErrorFieldsCustomizer.replaced(path("message"))).isFalse();
    }

    @Test
    void aNullEventHasNoErrorFields() {
        assertThat(EcsErrorFieldsCustomizer.hasErrorFields(null)).isFalse();
    }

    // ---- harness -----------------------------------------------------------------------------

    private static org.springframework.boot.json.JsonWriter.MemberPath path(String value) {
        return org.springframework.boot.json.JsonWriter.MemberPath.of(value);
    }

    private static List<KeyValuePair> classification(int code, String category, boolean followUp) {
        return List.of(
                new KeyValuePair(LogEvent.ERROR_CODE, code),
                new KeyValuePair(LogEvent.ERROR_CATEGORY, category),
                new KeyValuePair(LogEvent.ERROR_FOLLOW_UP_ACTION, followUp));
    }

    private static MockEnvironment withCustomizer() {
        return withCustomizer(new MockEnvironment());
    }

    private static MockEnvironment withCustomizer(MockEnvironment environment) {
        return environment.withProperty(CUSTOMIZER, EcsErrorFieldsCustomizer.class.getName());
    }

    private static LoggingEvent event(Throwable failure, List<KeyValuePair> pairs) {
        // The service's own context, which carries the MDC adapter a record reads its context from.
        LoggerContext context = (LoggerContext) org.slf4j.LoggerFactory.getILoggerFactory();
        LoggingEvent event = new LoggingEvent(EcsErrorFieldsCustomizerTests.class.getName(),
                context.getLogger("test"), Level.ERROR, "record", failure, null);
        pairs.forEach(event::addKeyValuePair);
        event.setTimeStamp(1_790_000_000_000L);
        event.setThreadName("main");
        return event;
    }

    private static JsonNode encoded(Environment environment, LoggingEvent event) {
        return JSON.readTree(rawLine(environment, event));
    }

    private static String rawLine(Environment environment, LoggingEvent event) {
        LoggerContext context = new LoggerContext();
        context.putObject(Environment.class.getName(), environment);
        StructuredLogEncoder encoder = new StructuredLogEncoder();
        encoder.setContext(context);
        encoder.setFormat("ecs");
        encoder.setCharset(StandardCharsets.UTF_8);
        encoder.start();
        try {
            return new String(encoder.encode(event), StandardCharsets.UTF_8);
        } finally {
            encoder.stop();
        }
    }

    private static List<String> fieldNames(JsonNode object) {
        return List.copyOf(object.propertyNames());
    }
}

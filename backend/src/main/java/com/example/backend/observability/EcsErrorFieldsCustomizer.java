package com.example.backend.observability;

import ch.qos.logback.classic.pattern.ThrowableProxyConverter;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.classic.spi.LoggingEvent;
import ch.qos.logback.classic.spi.ThrowableProxy;
import java.util.List;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.slf4j.event.KeyValuePair;
import org.springframework.boot.json.JsonWriter.MemberPath;
import org.springframework.boot.json.JsonWriter.Members;
import org.springframework.boot.logging.StackTracePrinter;
import org.springframework.boot.logging.structured.StructuredLoggingJsonMembersCustomizer;

/**
 * Writes {@code Log_Schema.md} §Error's {@code error.code}, {@code error.category} and
 * {@code error.follow_up_action} where the schema puts them — inside the record's
 * {@code error} object, beside the {@code error.type}, {@code error.message} and
 * {@code error.stack_trace} Boot's ECS formatter writes from an attached throwable.
 *
 * <p>Call sites cannot write those names themselves. The formatter nests a record's dotted
 * keys into objects, so a key {@code error.code} would become an {@code error} object of its
 * own, written beside the formatter's — two {@code error} members in one JSON object, of which
 * a reader keeps one. So the call sites write {@link LogEvent#ERROR_CODE} and its siblings
 * with an underscore, as the standard's recipes do, and this customizer moves them: it drops
 * the formatter's {@code error} member and the three underscore keys, and writes one
 * {@code error} object carrying all six fields. The three throwable fields are written as the
 * formatter writes them — the same accessors, and the stack trace from the same
 * {@link StackTracePrinter} or {@link ThrowableProxyConverter} the encoder holds — so a
 * record with an exception and no classification, and no SQL exception, encodes byte-for-byte
 * as it did before.
 *
 * <p>The replacement is added under a placeholder name ({@link #PENDING}) and renamed to
 * {@code error} when written: a path filter sees a member's name as it was added, so the
 * filter that drops the formatter's {@code error} cannot see — and drop — this one.
 *
 * <p>The throwable's message and stack trace are written with every SQL exception's text
 * redacted ({@link SqlExceptionRedaction}): this is the one place every structured record with
 * an exception passes through, whoever logged it, so it is where that redaction holds for all
 * of them.
 *
 * <p>Applied through {@code logging.structured.json.customizer} in {@code logging.yaml}, with
 * {@link EcsTimestampCustomizer}. See the 2026-10-02 (#94) addendum to
 * {@code docs/adr/0003-ecs-structured-logging-with-redaction.md}.
 */
public final class EcsErrorFieldsCustomizer
        implements StructuredLoggingJsonMembersCustomizer<ILoggingEvent> {

    static final String ERROR = "error";

    /** The name the replacement is added under, until the name processor renames it. */
    static final String PENDING = "app_error_fields_pending";

    /** The underscore keys a call site writes; each is written inside {@code error} instead. */
    static final List<String> FIELDS = List.of(
            LogEvent.ERROR_CODE, LogEvent.ERROR_CATEGORY, LogEvent.ERROR_FOLLOW_UP_ACTION);

    /** The top-level members this customizer takes over. */
    private static final Set<String> REPLACED = Set.of(
            ERROR, LogEvent.ERROR_CODE, LogEvent.ERROR_CATEGORY, LogEvent.ERROR_FOLLOW_UP_ACTION);

    private final @Nullable StackTracePrinter stackTracePrinter;

    private final ThrowableProxyConverter throwableProxyConverter;

    /**
     * Both arguments are the encoder's own, handed over by Boot when it instantiates the
     * customizer: the printer configured under {@code logging.structured.json.stacktrace}, if
     * any, and the converter the formatter falls back to without one.
     */
    public EcsErrorFieldsCustomizer(
            @Nullable StackTracePrinter stackTracePrinter,
            ThrowableProxyConverter throwableProxyConverter) {
        this.stackTracePrinter = stackTracePrinter;
        this.throwableProxyConverter = throwableProxyConverter;
    }

    @Override
    public void customize(Members<ILoggingEvent> members) {
        members.applyingPathFilter(EcsErrorFieldsCustomizer::replaced);
        members.applyingNameProcessor((path, name) ->
                PENDING.equals(path.toUnescapedString()) ? ERROR : name);
        members.add(PENDING).when(EcsErrorFieldsCustomizer::hasErrorFields).usingMembers(error -> {
            error.add("type", ILoggingEvent::getThrowableProxy)
                    .whenNotNull()
                    .as(IThrowableProxy::getClassName);
            error.add("message", ILoggingEvent::getThrowableProxy)
                    .whenNotNull()
                    .as(EcsErrorFieldsCustomizer::message);
            error.add("stack_trace")
                    .when(event -> event.getThrowableProxy() != null)
                    .as(this::stackTrace);
            error.add("code", event -> value(event, LogEvent.ERROR_CODE)).whenNotNull();
            error.add("category", event -> value(event, LogEvent.ERROR_CATEGORY)).whenNotNull();
            error.add("follow_up_action", event -> value(event, LogEvent.ERROR_FOLLOW_UP_ACTION))
                    .whenNotNull();
        });
    }

    /** Whether {@code path} is a top-level member this customizer writes in its own place. */
    static boolean replaced(MemberPath path) {
        return REPLACED.contains(path.toUnescapedString());
    }

    /** Whether the record gets an {@code error} object at all: a throwable or a classification. */
    static boolean hasErrorFields(@Nullable ILoggingEvent event) {
        return event != null && (event.getThrowableProxy() != null
                || FIELDS.stream().anyMatch(key -> value(event, key) != null));
    }

    /** The value a record carries under {@code key}, or {@code null}. */
    static @Nullable Object value(ILoggingEvent event, String key) {
        List<KeyValuePair> pairs = event.getKeyValuePairs();
        if (pairs == null) {
            return null;
        }
        for (KeyValuePair pair : pairs) {
            if (key.equals(pair.key)) {
                return pair.value;
            }
        }
        return null;
    }

    /**
     * The throwable's message as the record may carry it: redacted where it is, or quotes, an
     * SQL exception's ({@link SqlExceptionRedaction}).
     */
    static @Nullable String message(IThrowableProxy proxy) {
        return proxy instanceof ThrowableProxy live
                ? SqlExceptionRedaction.redacted(live.getThrowable()).getMessage()
                : proxy.getMessage();
    }

    /**
     * As Boot's formatter renders it — the configured printer, else the encoder's converter —
     * from the throwable as the record may carry it ({@link SqlExceptionRedaction}): every class
     * and frame, and no SQL exception's text.
     */
    String stackTrace(ILoggingEvent event) {
        if (event.getThrowableProxy() instanceof ThrowableProxy proxy) {
            Throwable shown = SqlExceptionRedaction.redacted(proxy.getThrowable());
            return stackTracePrinter != null
                    ? stackTracePrinter.printStackTraceToString(shown)
                    : throwableProxyConverter.convert(carrying(new ThrowableProxy(shown)));
        }
        return throwableProxyConverter.convert(event);
    }

    /**
     * An event carrying {@code shown} as its throwable, for the converter to print. The
     * converter reads nothing else of an event: Boot configures it with no evaluators, and its
     * output is the throwable's alone.
     */
    static ILoggingEvent carrying(ThrowableProxy shown) {
        LoggingEvent event = new LoggingEvent();
        event.setThrowableProxy(shown);
        return event;
    }
}

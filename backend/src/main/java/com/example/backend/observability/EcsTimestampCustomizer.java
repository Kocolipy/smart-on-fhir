package com.example.backend.observability;

import java.time.Instant;
import java.time.format.DateTimeFormatter;
import org.springframework.boot.json.JsonWriter.Members;
import org.springframework.boot.json.JsonWriter.ValueProcessor;
import org.springframework.boot.logging.structured.StructuredLoggingJsonMembersCustomizer;

/**
 * Renders a record's {@code @timestamp} in {@link ServiceTimeZone#ZONE} —
 * {@code 2026-10-01T16:52:11.726+08:00} — where Boot's ECS formatter writes the
 * {@link Instant} as UTC ({@code ...Z}).
 *
 * <p>The same instant, written with its offset: a collector parses either form to the
 * same point in time, so nothing downstream loses precision or ordering, and an
 * operator reading the raw line reads the zone the standard asks for. Only the
 * top-level {@code @timestamp} member is touched; no other value is.
 *
 * <p>Applied to every structured format through {@code logging.structured.json.customizer}
 * in {@code logging.yaml} — the console and the log file alike. A customizer rather than
 * a formatter of our own, because the ECS formatter is otherwise exactly the one wanted
 * and a copy of it would drift from Boot's. See "Format and envelope" in
 * {@code docs/adr/0003-ecs-structured-logging-with-redaction.md}.
 */
public final class EcsTimestampCustomizer implements StructuredLoggingJsonMembersCustomizer<Object> {

    /** Milliseconds, always three digits, and the offset as {@code +08:00}. */
    static final DateTimeFormatter FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSXXX").withZone(ServiceTimeZone.ZONE);

    static final String TIMESTAMP = "@timestamp";

    @Override
    public void customize(Members<Object> members) {
        members.applyingValueProcessor(
                ValueProcessor.of(EcsTimestampCustomizer::render).whenHasPath(TIMESTAMP));
    }

    /** An {@link Instant} in the service's zone; anything else exactly as it was. */
    static Object render(Object value) {
        return (value instanceof Instant instant) ? FORMAT.format(instant) : value;
    }
}

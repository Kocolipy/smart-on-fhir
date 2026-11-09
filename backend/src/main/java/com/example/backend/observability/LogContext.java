package com.example.backend.observability;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.slf4j.MDC;

/**
 * The only way anything in this service writes to the logging context.
 *
 * <p>Logs carry correlation ids and nothing else. A {@code userName}, a filter
 * expression, a password, a bearer value, a hash and a cookie value never reach a
 * log line — neither in a message nor as a context entry — because a log store is
 * read by more people, retained longer, and shipped further than the database the
 * value came from. That rule cannot be enforced by review of {@code MDC.put}
 * call sites scattered across the service, so there are none: this class exposes
 * only the named setters below, and
 * {@code ArchitectureTest.mdc_is_only_touched_by_the_log_context} holds it to
 * being the single class in production code that touches {@link MDC}.
 *
 * <p>The identity surface's correlation keys are ids rather than values:
 *
 * <ul>
 *   <li>{@link #REQUEST_ID} — minted per inbound request by
 *       {@link RequestIdFilter}, never taken from the caller.
 *   <li>{@link #USER_ID} — the stable SCIM id of the authenticated User the
 *       record concerns, never its userName or email. Typed as a {@link UUID} so
 *       a readable identifier cannot be passed by mistake. Absent wherever the
 *       identity is not resolved — a refused login above all.
 *   <li>{@link #CONNECTOR_ID} — which provisioning connector's token
 *       authenticated the request, once connectors exist.
 *   <li>{@link #RESOURCE_ID} — the stable id of the resource being acted on,
 *       which is why it is an id: the resource's readable identifiers are exactly
 *       what must not be logged.
 * </ul>
 *
 * <p>The {@link #job} setter writes a scheduled-job run's identity as one
 * unit by {@link ScheduledJobMetrics#instrumentLocked} for the run's lifetime:
 * {@link #JOB_NAME} (a constant from code), {@link #JOB_RUN_ID} (a UUID minted per run)
 * and {@link #TRIGGER_TYPE} (always {@value #TRIGGER_SCHEDULED}). None is a
 * value anything outside the service supplies.
 *
 * <p>{@link #TRACE_ID} and {@link #SPAN_ID} exist beside these, and this class does not write them: the tracer does, for each span's scope. They are tracer-minted
 * hex ids rather than values anything else supplies, which is why they can come from a
 * library without weakening the rule above.
 *
 * <p>Every value is passed through {@link #sanitize} first. A value that reaches
 * here can still be client-influenced further out — a connector id read from a
 * token, a resource id parsed from a path — and a newline or a carriage return
 * inside one would let a caller close the current log record and forge the next
 * (CWE-117). Structured output makes that harder, not impossible: the sanitizing
 * is what makes it impossible.
 */
public final class LogContext {

    /** Correlation id for one inbound HTTP request. ECS {@code http.request.id}. */
    public static final String REQUEST_ID = "http.request.id";

    /** Stable SCIM id of the User the record concerns. ECS {@code user.id}. */
    public static final String USER_ID = "user.id";

    /** The provisioning connector whose token authenticated the request. */
    public static final String CONNECTOR_ID = "scim.connector.id";

    /** Stable id of the resource being read or written. Never its userName. */
    public static final String RESOURCE_ID = "scim.resource.id";

    /** The scheduled job a record belongs to. {@code Log_Schema.md} §Batch {@code batch.job.name}. */
    public static final String JOB_NAME = "batch.job.name";

    /** One run of that job, a fresh UUID per run. {@code Log_Schema.md} §Batch {@code batch.job.run.id}. */
    public static final String JOB_RUN_ID = "batch.job.run.id";

    /**
     * What started the run. {@code Log_Schema.md} §Trigger {@code trigger.type}, which the
     * schema types as an array; the logging context holds strings only, so it is the one
     * member, {@value #TRIGGER_SCHEDULED}.
     */
    public static final String TRIGGER_TYPE = "trigger.type";

    /** The {@link #TRIGGER_TYPE} of every run here: each is started by its cron. */
    public static final String TRIGGER_SCHEDULED = "scheduled";

    /**
     * The current trace's id. ECS {@code trace.id}. Not one of this class's keys: the
     * tracer writes it, through the listener {@code TraceLogCorrelationConfig} registers,
     * for the lifetime of each span's scope. Its value is a hex id the tracer minted —
     * never a caller's, because no propagator is installed (see {@code telemetry.yaml})
     * — so it carries nothing a caller chose and needs no sanitizing.
     */
    public static final String TRACE_ID = "trace.id";

    /** The current span's id. ECS {@code span.id}. Written by the tracer, as {@link #TRACE_ID}. */
    public static final String SPAN_ID = "span.id";

    /**
     * Every key this class will write, so {@link #clear()} can remove exactly
     * what was added and nothing a library put there.
     */
    private static final Set<String> KEYS =
            Set.of(REQUEST_ID, USER_ID, CONNECTOR_ID, RESOURCE_ID, JOB_NAME, JOB_RUN_ID, TRIGGER_TYPE);

    /**
     * Longest value written. An id is far shorter than this; the cap exists so a
     * caller-supplied value cannot turn one log record into a megabyte.
     */
    private static final int MAX_VALUE_LENGTH = 128;

    private LogContext() {
    }

    /** Puts the request correlation id in scope for the current thread. */
    public static Scope requestId(String requestId) {
        return put(REQUEST_ID, requestId);
    }

    /**
     * Puts the User's stable id in scope for the current thread. {@code null}
     * removes the key for the scope's lifetime — how a record about an unresolved
     * identity is kept from inheriting the request's — and restores it on close.
     */
    public static Scope userId(UUID userId) {
        return put(USER_ID, userId == null ? null : userId.toString());
    }

    /** Puts the authenticated connector's id in scope for the current thread. */
    public static Scope connectorId(String connectorId) {
        return put(CONNECTOR_ID, connectorId);
    }

    /** Puts the acted-on resource's stable id in scope for the current thread. */
    public static Scope resourceId(String resourceId) {
        return put(RESOURCE_ID, resourceId);
    }

    /**
     * Puts one scheduled-job run's identity in scope for the current thread: the job's
     * name, the run's id and the {@value #TRIGGER_SCHEDULED} trigger. Closing the scope
     * restores all three, so nothing of the run outlives it on the scheduler's thread.
     */
    public static Scope job(String jobName, UUID runId) {
        return new Scope()
                .put(JOB_NAME, jobName)
                .put(JOB_RUN_ID, runId.toString())
                .put(TRIGGER_TYPE, TRIGGER_SCHEDULED);
    }

    /**
     * Removes every key this class owns, leaving anything else in the context
     * alone. The filter does this at the end of a request as a backstop; a
     * {@link Scope} closed normally has already undone its own entry.
     */
    public static void clear() {
        KEYS.forEach(MDC::remove);
    }

    /**
     * The value as it is safe to write: control characters — line breaks among
     * them — replaced by {@code _}, and the result truncated. {@code null} and
     * blank both become {@code null}, which means "do not record this key" rather
     * than recording an empty one, so a reader can tell an absent id from a
     * present empty string.
     */
    static String sanitize(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String truncated = value.length() > MAX_VALUE_LENGTH
                ? value.substring(0, MAX_VALUE_LENGTH)
                : value;
        StringBuilder safe = new StringBuilder(truncated.length());
        truncated.codePoints().forEach(codePoint ->
                safe.appendCodePoint(Character.isISOControl(codePoint) ? '_' : codePoint));
        return safe.toString();
    }

    private static Scope put(String key, String value) {
        return new Scope().put(key, value);
    }

    /**
     * The lifetime of one or more context entries. Closing it restores whatever each
     * key held before, so a nested scope — a resource id set inside a request — cannot
     * erase the outer one, and a scope that never nests simply removes its keys.
     *
     * <p>Intended for try-with-resources. {@link AutoCloseable} rather than
     * {@code Closeable} because nothing here can fail with an
     * {@code IOException}, and a checked exception on close would spread through
     * every call site for no reason.
     */
    public static final class Scope implements AutoCloseable {

        private final List<String> keys = new ArrayList<>();
        private final List<String> previous = new ArrayList<>();

        private Scope() {
        }

        private Scope put(String key, String value) {
            keys.add(key);
            previous.add(MDC.get(key));
            String sanitized = sanitize(value);
            if (sanitized == null) {
                MDC.remove(key);
            } else {
                MDC.put(key, sanitized);
            }
            return this;
        }

        @Override
        public void close() {
            for (int entry = 0; entry < keys.size(); entry++) {
                String key = keys.get(entry);
                String outer = previous.get(entry);
                if (outer == null) {
                    MDC.remove(key);
                } else {
                    MDC.put(key, outer);
                }
            }
        }
    }
}

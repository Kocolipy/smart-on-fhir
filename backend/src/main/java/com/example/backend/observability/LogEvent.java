package com.example.backend.observability;

import java.util.Arrays;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.spi.LoggingEventBuilder;

/**
 * The ECS field names a log record uses to say what happened, and the closed
 * vocabulary their values come from.
 *
 * <p>These are structured fields rather than sentences because that is what makes
 * the redaction rule enforceable: a record's variable part is always a key and a
 * value, and its message is always a constant. Nothing is concatenated into a
 * message, so no value a caller influenced can alter the shape of a record, and a
 * static rule can check the property by looking for concatenation alone — see
 * {@code semgrep/rules/service-security.yml}.
 *
 * <h2>The event vocabulary</h2>
 *
 * <p>{@code event.kind}, {@code event.category}, {@code event.type} and
 * {@code event.action} take their values from the closed enums in the logging
 * standard's {@code Log_Schema.md} §Event, and only the members this service uses
 * are declared here. A record is classified in one call, {@link #classify}, which
 * takes an {@link Operation}: the operations this service logs, each mapped onto
 * the standard's action once, here, rather than at every call site. Nothing else
 * writes {@link #ACTION} or {@link #LOCAL_ACTION} —
 * {@code be-log-event-action-outside-the-vocabulary} holds that — so a free-text
 * action cannot creep back in, and the mapping table in
 * {@code /docs/adr/0003-ecs-structured-logging-with-redaction.md} is the whole of it.
 *
 * <p>Where the standard has no action that fits an operation, or one action covers
 * several of them, the operation's own name is kept under {@link #LOCAL_ACTION}
 * instead of a member being invented for the standard's enum.
 *
 * <h2>The record shapes</h2>
 *
 * <p>A record is built here whole, not assembled at its call site: {@link #success},
 * {@link #refused}, {@link #error}, the scheduled-run records ({@link #jobScheduled},
 * {@link #jobStart}, {@link #jobEnd}, {@link #jobFailed}), the request record
 * ({@link #requestEnd}), the outbound-call records ({@link #outboundStart},
 * {@link #outboundEnd}, {@link #outboundFailed}) and the retry warnings
 * ({@link #jwksRefetchWarning}, {@link #deadlockRetried}) each open the record at its level, classify it, set
 * {@link #OUTCOME} and {@link #DURATION_MS} where the shape has them, and set the operation's
 * fixed message for that shape. A caller names the operation, adds only the ids and counts it
 * alone can supply, and calls {@code log()}. {@code be-log-record-outside-log-event} holds
 * production code to that: no other class opens a record, writes the outcome or the duration,
 * or chooses a message. See ADR 0003, "Records are built in shapes".
 */
public final class LogEvent {

    /** The nature of the record: always {@link Kind#EVENT} here. */
    public static final String KIND = "event.kind";

    /** High-level classification, an array of {@link Category} values. */
    public static final String CATEGORY = "event.category";

    /** Lifecycle within the category, an array of {@link Type} values. */
    public static final String TYPE = "event.type";

    /** What was attempted, as an {@link Action} value. Written by {@link #classify} only. */
    public static final String ACTION = "event.action";

    /**
     * The operation's own name where {@link #ACTION} alone does not identify it — no
     * standard action fits, or one is shared by several operations. Namespaced under
     * {@code app.}, the service's own configuration namespace, so it can never be
     * mistaken for a standard field. Written by {@link #classify} only.
     */
    public static final String LOCAL_ACTION = "app.event.action";

    /** Whether it worked: {@link #SUCCESS} or {@link #FAILURE}. */
    public static final String OUTCOME = "event.outcome";

    /**
     * Why a failure was refused, as a type name from this service's own code. Never
     * a message built from submitted input.
     */
    public static final String REASON = "event.reason";

    /** Operational severity, for routing an alert independently of the level. */
    public static final String SEVERITY = "event.severity";

    /** How long the operation the record ends took, in milliseconds. */
    public static final String DURATION_MS = "event.duration_ms";

    /**
     * The stable SCIM id of the User a record's operation was performed ON, when that
     * is a different role from the actor. The actor is {@code user.id}, which
     * {@link LogContext} carries for the whole request; ECS names the acted-on
     * identity {@code user.target.*}. Never a userName.
     */
    public static final String USER_TARGET_ID = "user.target.id";

    /**
     * The stable SCIM id of the Group a record is about — the Group whose membership change
     * granted or revoked Permissions. ECS {@code group.id}. Never a {@code displayName}.
     */
    public static final String GROUP_ID = "group.id";

    public static final String SUCCESS = "success";

    public static final String FAILURE = "failure";

    /**
     * The cron expression a scheduled job runs on, on its startup record.
     * {@code Log_Schema.md} §Trigger.
     */
    public static final String TRIGGER_CRON_EXPRESSION = "trigger.cron.expression";

    /** The IANA zone that cron is evaluated in. {@code Log_Schema.md} §Trigger. */
    public static final String TRIGGER_CRON_TIMEZONE = "trigger.cron.timezone";

    /**
     * What a scheduled job does, in a sentence an operator reads on its startup record. The
     * schema has no field for it, so it is namespaced under {@code app.}, as
     * {@link #LOCAL_ACTION} is.
     */
    public static final String JOB_DESCRIPTION = "app.job.description";

    /**
     * {@code Log_Schema.md} §Error {@code error.code}, spelled with an underscore at the call
     * site: Boot's ECS formatter owns the {@code error} object (it writes {@code error.type},
     * {@code error.message} and {@code error.stack_trace} from the attached throwable), so a
     * dotted key of ours would write a second {@code error} object beside it. The standard's
     * recipes use this spelling for the same reason, and {@link EcsErrorFieldsCustomizer}
     * moves it into the {@code error} object on the way out, so the emitted JSON carries
     * {@code error.code}. Written through {@link #withError} only.
     */
    public static final String ERROR_CODE = "error_code";

    /**
     * {@code Log_Schema.md} §Error {@code error.category}, an {@link ErrorCategory}; see
     * {@link #ERROR_CODE}. Written through {@link #withError}, and alone by
     * {@link #deadlockRetried}, whose record has answered no status to give a code.
     */
    public static final String ERROR_CATEGORY = "error_category";

    /**
     * {@code Log_Schema.md} §Error {@code error.follow_up_action}: whether the error needs a
     * person to act on it. See {@link #ERROR_CODE} for the spelling.
     */
    public static final String ERROR_FOLLOW_UP_ACTION = "error_follow_up_action";

    /**
     * Why a record that would carry its exception carries none. Namespaced under {@code app.},
     * as {@link #LOCAL_ACTION} is: the schema has no field for it.
     */
    public static final String ERROR_CAUSE_OMITTED = "app.error.cause_omitted";

    /**
     * The {@link #REASON} of a scheduled run that found another run of the same job holding
     * the job's lock, and so did nothing.
     */
    public static final String REASON_LOCK_HELD = "lock-held";

    /**
     * The {@link #REASON} of a write retried because the database chose its transaction as a
     * deadlock's victim ({@link #deadlockRetried}).
     */
    public static final String REASON_DEADLOCK = "deadlock";

    /**
     * How a scheduled run ended, on its {@code job-end}: {@value #JOB_COMPLETED}, {@value
     * #JOB_SKIPPED} or {@value #JOB_FAILED}. {@code Log_Schema.md} §Batch.
     */
    public static final String BATCH_JOB_STATUS = "batch.job.status";

    /** {@link #BATCH_JOB_STATUS} of a run that did the work. */
    public static final String JOB_COMPLETED = "completed";

    /** {@link #BATCH_JOB_STATUS} of a run that found another run holding the job's lock. */
    public static final String JOB_SKIPPED = "skipped";

    /** {@link #BATCH_JOB_STATUS} of a run that threw. */
    public static final String JOB_FAILED = "failed";

    /**
     * What a scheduled run did to each kind of record, on its {@code job-end}: an array with one
     * object per kind ({@link JobRecord}), {@code [{type: audit_event, deleted: 12, batches: 1}]}.
     * {@code Log_Schema.md} §Batch.
     */
    public static final String RECORD = "record";

    /** An inbound request's method, from a closed set. ECS {@code http.request.method}. */
    public static final String HTTP_METHOD = "http.request.method";

    /**
     * The route TEMPLATE an inbound request matched ({@code /scim/v2/Users/{id}}), never the
     * path it arrived on, so no id or filter text a caller put in the URL reaches a record.
     * Neither ECS nor {@code Log_Schema.md} names a template field; this is OpenTelemetry's
     * {@code http.route}.
     */
    public static final String HTTP_ROUTE = "http.route";

    /** The status an inbound request was answered with. ECS {@code http.response.status_code}. */
    public static final String HTTP_STATUS_CODE = "http.response.status_code";

    /**
     * Where an outbound call went, as scheme, host, port and path only: never a query, a fragment
     * or user info, any of which may carry a value no record may hold. ECS {@code url.full},
     * narrowed so.
     */
    public static final String URL_FULL = "url.full";

    /**
     * Which Epic call an outbound record or an Epic Login failure is about — {@code discovery},
     * {@code jwks} or {@code token} (ADR 0012, D25) — the same name the
     * {@code epic.outbound} meters are tagged with. Under this service's own {@code app}
     * namespace: ECS names no such field.
     */
    public static final String EPIC_CALL = "app.epic.call";

    /**
     * Which retry of an operation a record reports, from 1. Under this service's own {@code app}
     * namespace: ECS names no such field.
     */
    public static final String RETRY_ATTEMPT = "app.retry.attempt";

    /** How many sessions one revocation ended. */
    public static final String SESSIONS_ENDED = "session.ended_count";

    /**
     * The idle bound a session started with, in seconds, as the session itself reports it.
     * The logging standard's AuthN recipe names it on the {@code session-start} record. Never
     * paired with the session's id, which is its bearer credential.
     */
    public static final String SESSION_MAX_INACTIVE_INTERVAL = "session.max_inactive_interval";

    /**
     * How a Login proved who signed in, {@code password} or {@code sso} (Epic Login, D15), on the
     * accepted {@code user-authentication} and the {@code session-start} records — the same
     * spelling the audit trail's login events carry.
     * ECS names no such field, so it is under this service's own {@code app} namespace.
     */
    public static final String LOGIN_METHOD = "app.login.method";

    /**
     * The session a record concerns, as {@link SessionHash} names it — never by its id. On the
     * records an Epic Login ends in, so a refusal, which names no user, still correlates with the
     * browser session it happened in. Log_Schema {@code session.hash}.
     */
    public static final String SESSION_HASH = "session.hash";

    /** The machine's host name, on the startup record. ECS {@code host.name}. */
    public static final String HOST_NAME = "host.name";

    /** The machine's own address — never a client's — on the startup record. ECS {@code host.ip}. */
    public static final String HOST_IP = "host.ip";

    /** {@code error.code} of a failed scheduled run: a job failing is the service's own fault, and
     * the schema aligns the code with HTTP statuses, so it is the {@code 500} of a fault off any
     * request. */
    static final int FAILED_RUN_ERROR_CODE = 500;

    /**
     * {@code error.code} of an outbound call that got no answer, or none this service could use:
     * a gateway's {@code 502}, as the schema aligns codes with HTTP statuses.
     */
    public static final int BAD_GATEWAY_ERROR_CODE = 502;

    /** {@link #deadlockRetried}'s message: fixed text, naming no row, table or value. */
    private static final String DEADLOCK_RETRIED_MESSAGE =
            "Database deadlock: the write is retried once";

    /** {@link #jwksRefetchWarning}'s message: fixed text, naming the key's role and no value. */
    private static final String JWKS_REFETCH_MESSAGE =
            "Epic JWKS refetched: it lacked the id_token's key";

    /* The messages several operations share, so each is spelled once. */
    private static final String ADMINISTRATIVE_CHANGE_APPLIED =
            "Administrative identity change applied";
    private static final String ADMINISTRATIVE_CHANGE_REFUSED =
            "Administrative identity change refused";
    private static final String CONNECTOR_CHANGE_APPLIED =
            "SCIM connector lifecycle change applied";
    private static final String CONNECTOR_ROTATION_REFUSED =
            "SCIM connector token rotation refused: the token can no longer be rotated";
    private static final String SCIM_REQUEST_REFUSED = "SCIM request refused";

    private LogEvent() {
    }

    /**
     * A record of an operation that went through: {@code INFO}, {@code event.outcome}
     * {@code success}, and the operation's success message. The caller adds only the ids and
     * counts it alone can supply, then calls {@code log()}.
     *
     * @return the record, for the rest of the fluent chain
     */
    public static LoggingEventBuilder success(
            Logger log, Operation operation, Category category, Type... types) {
        return succeeded(log.atInfo(), operation, category, types);
    }

    /**
     * {@link #success(Logger, Operation, Category, Type...)} at {@code WARN}: an operation that
     * went through but is worth an operator's attention — a User locked for dormancy.
     */
    public static LoggingEventBuilder successAtWarn(
            Logger log, Operation operation, Category category, Type... types) {
        return succeeded(log.atWarn(), operation, category, types);
    }

    /**
     * {@link #success(Logger, Operation, Category, Type...)} for a record that ends something
     * it measured, carrying how long it took as {@code event.duration_ms}.
     */
    public static LoggingEventBuilder success(
            Logger log, Operation operation, long durationMs, Category category, Type... types) {
        return success(log, operation, category, types).addKeyValue(DURATION_MS, durationMs);
    }

    /**
     * A record of an operation refused as the caller's or the subject's fault: {@code WARN},
     * {@code event.outcome} {@code failure}, and the operation's refusal message. The caller
     * adds {@code event.reason} where the refusal has one.
     */
    public static LoggingEventBuilder refused(
            Logger log, Operation operation, Category category, Type... types) {
        return classify(log.atWarn(), operation, category, types)
                .addKeyValue(OUTCOME, FAILURE)
                .setMessage(message(operation, MessageSlot.REFUSED));
    }

    /**
     * A record of an operation the service failed: {@code ERROR}, carrying the
     * {@code Log_Schema.md} §Error classification with {@code error.follow_up_action}
     * {@code true}, {@code event.outcome} {@code failure}, and the operation's error message.
     * The only way this service opens an {@code ERROR} record outside a scheduled run or a
     * request's own record — {@code be-log-error-without-error-fields} holds that.
     *
     * @param code the error's code: the HTTP status a request fault was answered with, or
     *             {@code 500} for a fault off any request
     */
    public static LoggingEventBuilder error(
            Logger log, Operation operation, int code, ErrorCategory errorCategory,
            Category category, Type... types) {
        return error(log, operation, code, errorCategory, true, category, types);
    }

    /**
     * {@link #error(Logger, Operation, int, ErrorCategory, Category, Type...)} for a failure that
     * is not this service's to fix, and so may need no person: ADR 0012's error-category table says
     * whether each of its categories does — a timeout or an Epic {@code 5xx} passes on its own,
     * a refused credential does not.
     *
     * @param followUp {@code error.follow_up_action}: whether a person must act on the error
     */
    public static LoggingEventBuilder error(
            Logger log, Operation operation, int code, ErrorCategory errorCategory,
            boolean followUp, Category category, Type... types) {
        return classify(withError(log.atError(), code, errorCategory, followUp),
                        operation, category, types)
                .addKeyValue(OUTCOME, FAILURE)
                .setMessage(message(operation, MessageSlot.ERROR));
    }

    /**
     * The record of an Epic call about to be sent: {@code INFO}, which call, its method and
     * where it goes ({@link #URL_FULL}, never a query).
     */
    public static LoggingEventBuilder outboundStart(
            Logger log, String call, String method, String url) {
        return outbound(log.atInfo(), call, method, url, Type.START)
                .setMessage("Epic outbound call started");
    }

    /**
     * The record of an Epic call that was answered, whatever the status: {@code INFO},
     * {@code http.response.status_code}, {@code event.duration_ms}, and {@code event.outcome}
     * {@code success} below {@code 400}. What the status means for the operation is its own
     * record's to say.
     */
    public static LoggingEventBuilder outboundEnd(Logger log, String call, String method,
            String url, int status, long durationMs) {
        return outbound(log.atInfo(), call, method, url, Type.END)
                .addKeyValue(HTTP_STATUS_CODE, status)
                .addKeyValue(DURATION_MS, durationMs)
                .addKeyValue(OUTCOME, status < 400 ? SUCCESS : FAILURE)
                .setMessage("Epic outbound call completed");
    }

    /**
     * The record of an Epic call that got no answer — a timeout, or no connection: {@code
     * ERROR} with {@code error.code} {@code 502}, {@code error.category} {@code network} and no
     * follow-up (the other service being down needs none of ours), {@code event.outcome}
     * {@code failure} and {@code event.duration_ms}. The caller attaches a
     * {@link RedactedFaultException} in place of the failure.
     */
    public static LoggingEventBuilder outboundFailed(Logger log, String call, String method,
            String url, long durationMs) {
        return outbound(withError(log.atError(), BAD_GATEWAY_ERROR_CODE, ErrorCategory.NETWORK,
                        false), call, method, url, Type.ERROR)
                .addKeyValue(DURATION_MS, durationMs)
                .addKeyValue(OUTCOME, FAILURE)
                .setMessage("Epic outbound call failed");
    }

    /**
     * The record of a JWKS refetch about to be made (D26): {@code WARN},
     * {@link Operation#EPIC_JWKS_REFETCH}, {@link #RETRY_ATTEMPT}, and no outcome — the refetch
     * has none yet. Its failure, should every refetch fail, is that operation's {@link #error}
     * record.
     */
    public static LoggingEventBuilder jwksRefetchWarning(Logger log, int attempt) {
        return classify(log.atWarn(), Operation.EPIC_JWKS_REFETCH, Category.NETWORK,
                        Type.CONNECTION, Type.START)
                .addKeyValue(RETRY_ATTEMPT, attempt)
                .setMessage(JWKS_REFETCH_MESSAGE);
    }

    /**
     * The record of a write about to be retried because the database chose its transaction as a
     * deadlock's victim (the deadlock retry, {@code DeadlockRetryAdapter}): {@code WARN}, classified as the write's own
     * operation, {@code error.category} {@code database} with no {@code error.code} — no status
     * has been answered — and no follow-up, {@link #RETRY_ATTEMPT} {@code 1}, {@link #REASON}
     * {@value #REASON_DEADLOCK}, and no outcome, which the retried write's own records state. A
     * second deadlock is the write's failure, written by the request's fault handler as its
     * {@code ERROR}.
     */
    public static LoggingEventBuilder deadlockRetried(Logger log, Operation operation) {
        return classify(log.atWarn(), operation, Category.DATABASE, Type.ERROR)
                .addKeyValue(ERROR_CATEGORY, ErrorCategory.DATABASE.value())
                .addKeyValue(RETRY_ATTEMPT, 1)
                .addKeyValue(REASON, REASON_DEADLOCK)
                .setMessage(DEADLOCK_RETRIED_MESSAGE);
    }

    /** The outbound records' common shape: an {@link Operation#EPIC_OUTBOUND} connection. */
    private static LoggingEventBuilder outbound(LoggingEventBuilder opened, String call,
            String method, String url, Type type) {
        return classify(opened, Operation.EPIC_OUTBOUND, Category.NETWORK, Type.CONNECTION, type)
                .addKeyValue(EPIC_CALL, call)
                .addKeyValue(HTTP_METHOD, method)
                .addKeyValue(URL_FULL, url);
    }

    /**
     * The startup record of a job's schedule, classified as the job's operation, with the
     * job's name, its cron and the zone that cron is evaluated in, and what the job does. The
     * caller adds what is particular to the job — its window, say.
     */
    public static LoggingEventBuilder jobScheduled(
            Logger log, Operation operation, String job, String cron, String description) {
        return classify(log.atInfo(), operation, Category.CONFIGURATION, Type.INFO)
                .addKeyValue(LogContext.JOB_NAME, job)
                .addKeyValue(JOB_DESCRIPTION, description)
                .addKeyValue(TRIGGER_CRON_EXPRESSION, cron)
                .addKeyValue(TRIGGER_CRON_TIMEZONE, ServiceTimeZone.ZONE.getId())
                .setMessage(message(operation, MessageSlot.SCHEDULED));
    }

    /** The {@code job-start} record of a scheduled run. */
    public static LoggingEventBuilder jobStart(Logger log, Operation operation) {
        return classify(log.atInfo(), operation, Category.BATCH, Type.JOB_START)
                .setMessage("Scheduled job started");
    }

    /**
     * The {@code job-end} record of a scheduled run that did not fail: {@code INFO},
     * {@code event.outcome} {@code success}, {@code event.duration_ms} and
     * {@code batch.job.status} {@value #JOB_COMPLETED} — or {@value #JOB_SKIPPED}, with
     * {@code event.reason} {@value #REASON_LOCK_HELD}, when the run found another holding the
     * job's lock and so did nothing. The caller adds what the run counted.
     */
    public static LoggingEventBuilder jobEnd(
            Logger log, Operation operation, long durationMs, boolean skipped) {
        LoggingEventBuilder end = classify(log.atInfo(), operation, Category.BATCH, Type.JOB_END)
                .addKeyValue(OUTCOME, SUCCESS)
                .addKeyValue(DURATION_MS, durationMs);
        return skipped
                ? end.addKeyValue(BATCH_JOB_STATUS, JOB_SKIPPED)
                        .addKeyValue(REASON, REASON_LOCK_HELD)
                        .setMessage("Scheduled job skipped: another run holds its lock")
                : end.addKeyValue(BATCH_JOB_STATUS, JOB_COMPLETED)
                        .setMessage("Scheduled job completed");
    }

    /**
     * The {@code job-end} record of a scheduled run that threw: {@code ERROR} with
     * {@code error.code} {@code 500}, {@code event.outcome} {@code failure},
     * {@code batch.job.status} {@value #JOB_FAILED}, {@code event.severity} {@code high} and
     * {@code event.duration_ms}. The caller attaches the exception, and adds what the run had
     * committed.
     */
    public static LoggingEventBuilder jobFailed(
            Logger log, Operation operation, long durationMs, ErrorCategory errorCategory) {
        return classify(atError(log, FAILED_RUN_ERROR_CODE, errorCategory),
                        operation, Category.BATCH, Type.JOB_END)
                .addKeyValue(OUTCOME, FAILURE)
                .addKeyValue(BATCH_JOB_STATUS, JOB_FAILED)
                .addKeyValue(SEVERITY, Severity.HIGH.value())
                .addKeyValue(DURATION_MS, durationMs)
                .setMessage("Scheduled job failed");
    }

    /**
     * The one record of an inbound request, at its end: {@code http.response.status_code},
     * {@code event.duration_ms}, and {@code event.outcome} {@code success} below {@code 400}.
     * {@code INFO} below {@code 400}, {@code WARN} for a {@code 4xx}, and {@code ERROR} — as an
     * {@code application} error needing follow-up — for a {@code 5xx}, unless a handler already
     * wrote the fault's {@code ERROR} record, when it is {@code WARN} so the one failure is not
     * reported twice. The caller adds the method and route.
     */
    public static LoggingEventBuilder requestEnd(
            Logger log, int status, boolean faultRecorded, long durationMs) {
        LoggingEventBuilder opened = switch (requestLevel(status, faultRecorded)) {
            case ERROR -> atError(log, status, ErrorCategory.APPLICATION);
            case WARN -> log.atWarn();
            default -> log.atInfo();
        };
        return classify(opened, Operation.HTTP_REQUEST, Category.NETWORK, Type.ACCESS, Type.END)
                .addKeyValue(HTTP_STATUS_CODE, status)
                .addKeyValue(DURATION_MS, durationMs)
                .addKeyValue(OUTCOME, status < 400 ? SUCCESS : FAILURE)
                .setMessage("HTTP request completed");
    }

    /** The level {@link #requestEnd} writes a request's record at. */
    static org.slf4j.event.Level requestLevel(int status, boolean faultRecorded) {
        if (status >= 500 && !faultRecorded) {
            return org.slf4j.event.Level.ERROR;
        }
        return status >= 400 ? org.slf4j.event.Level.WARN : org.slf4j.event.Level.INFO;
    }

    private static LoggingEventBuilder succeeded(
            LoggingEventBuilder opened, Operation operation, Category category, Type... types) {
        return classify(opened, operation, category, types)
                .addKeyValue(OUTCOME, SUCCESS)
                .setMessage(successMessage(operation));
    }

    /** The success message of an operation's records, whatever their types. */
    static String successMessage(Operation operation) {
        return message(operation, MessageSlot.SUCCESS);
    }

    /**
     * The operation's fixed message for one record shape: the one it declares, or the shape's
     * generic message where it declares none. The fallback is kept rather than a failure, because
     * a record is never worth an exception on the path that writes it; {@code
     * OperationMessagesTests} is what holds every operation to the messages its call sites need.
     */
    static String message(Operation operation, MessageSlot slot) {
        String declared = operation.messages.get(slot);
        return declared != null ? declared : slot.generic;
    }

    /**
     * Classifies a record: {@code event.kind}, {@code event.category},
     * {@code event.type}, and the operation's {@code event.action} and local name
     * where it has them. Every record shape above starts here.
     *
     * @return {@code record}, for the rest of the fluent chain
     */
    static LoggingEventBuilder classify(
            LoggingEventBuilder record, Operation operation, Category category, Type... types) {
        LoggingEventBuilder classified = record
                .addKeyValue(KIND, Kind.EVENT.value())
                .addKeyValue(CATEGORY, List.of(category.value()))
                .addKeyValue(TYPE, Arrays.stream(types).map(Type::value).toList());
        if (operation.action() != null) {
            classified = classified.addKeyValue(ACTION, operation.action().value());
        }
        if (operation.local() != null) {
            classified = classified.addKeyValue(LOCAL_ACTION, operation.local());
        }
        return classified;
    }

    /**
     * An {@code ERROR} record, already carrying its {@code Log_Schema.md} §Error
     * classification, with {@code error.follow_up_action} {@code true}: an {@code ERROR} needs a
     * person unless its shape says otherwise. The shapes that may need none —
     * {@link #outboundFailed}, and {@link #error} with {@code followUp} {@code false} — open
     * through {@link #withError} instead, and {@code be-log-error-without-error-fields} keeps
     * any other opener out of production code, so no {@code ERROR} record can reach the stream
     * without {@code error.code}, {@code error.category} and {@code error.follow_up_action}.
     */
    private static LoggingEventBuilder atError(Logger log, int code, ErrorCategory category) {
        return withError(log.atError(), code, category, true);
    }

    /**
     * Adds the {@code Log_Schema.md} §Error classification to a record below {@code ERROR} —
     * a refusal that is the caller's error, with {@code followUp} {@code false}.
     *
     * @return {@code record}, for the rest of the fluent chain
     */
    public static LoggingEventBuilder withError(
            LoggingEventBuilder record, int code, ErrorCategory category, boolean followUp) {
        return record
                .addKeyValue(ERROR_CODE, code)
                .addKeyValue(ERROR_CATEGORY, category.value())
                .addKeyValue(ERROR_FOLLOW_UP_ACTION, followUp);
    }

    /**
     * What this service logs, each mapped onto the standard's {@link Action} — or onto
     * none, where none fits — and carrying its own name wherever that action alone
     * would not say which operation it was.
     *
     * <p>An operation with no action is an exception to the schema's required
     * {@code event.action}, and every one is recorded, with the reason no allowed value
     * fits, in ADR 0003's "Operations with no event.action", beside "The full mapping",
     * which carries this whole table. {@code LogEventTests} holds the two together: an operation
     * added with no action and no ADR entry fails it.
     *
     * <p>Each operation also declares, on its own line, the fixed message of every record shape
     * it is written in: {@link #onSuccess}, {@link #onChange}, {@link #onRefusal},
     * {@link #onError} and {@link #onSchedule}, one per {@link MessageSlot}. A scheduled run's
     * summary is its {@code job-end}, so no shape writes a summary beside it.
     * {@code OperationMessagesTests} reads the production call sites that hand an operation to a
     * shape and fails for a message the operation does not declare. The shapes whose message is
     * the same whatever the operation — {@link LogEvent#jobStart}, {@link LogEvent#jobEnd},
     * {@link LogEvent#jobFailed}, {@link LogEvent#requestEnd}, the outbound records,
     * {@link LogEvent#jwksRefetchWarning} and {@link LogEvent#deadlockRetried} — need none.
     */
    public enum Operation {
        LOGIN(Action.USER_AUTHENTICATION, null,
                onSuccess("Login accepted"), onRefusal("Login refused")),
        EPIC_LOGIN(Action.USER_AUTHENTICATION, "epic.login",
                onRefusal("Epic sign-in refused"), onError("Epic sign-in failed")),
        EPIC_OUTBOUND(Action.USER_AUTHENTICATION, "epic.outbound"),
        EPIC_JWKS_REFETCH(Action.USER_AUTHENTICATION, "epic.jwks_refetch",
                onError("Epic JWKS still lacks the id_token's key after its refetches")),
        UNLOCK(Action.ACCESS_CONTROL, "identity.unlock",
                onSuccess(ADMINISTRATIVE_CHANGE_APPLIED), onRefusal(ADMINISTRATIVE_CHANGE_REFUSED)),
        FORCE_PASSWORD_CHANGE(Action.PASSWORD_CHANGE_ENFORCEMENT, null,
                onSuccess(ADMINISTRATIVE_CHANGE_APPLIED), onRefusal(ADMINISTRATIVE_CHANGE_REFUSED)),
        PASSWORD_CHANGE(Action.USER_ADMINISTRATION, "identity.password_change",
                onSuccess("Self-service change completed"),
                onRefusal("Self-service change refused")),
        DORMANCY(Action.USER_ADMINISTRATION, "identity.dormancy",
                onSuccess("Dormancy job run at startup for the development fixtures"),
                onSchedule("Dormancy job scheduled")),
        DORMANCY_LOCKOUT(Action.USER_ADMINISTRATION, "identity.dormancy_lockout",
                onSuccess("User locked for dormancy")),
        DORMANCY_MEMBERSHIP_REVOCATION(Action.USER_ADMINISTRATION,
                "identity.dormancy_membership_revocation",
                onSuccess("Group memberships revoked for dormancy")),
        CONNECTOR_CREATE(Action.USER_PROVISIONING, "scim.connector.create",
                onSuccess(CONNECTOR_CHANGE_APPLIED)),
        CONNECTOR_DELETE(Action.USER_PROVISIONING, "scim.connector.delete",
                onSuccess(CONNECTOR_CHANGE_APPLIED)),
        CONNECTOR_TOKEN_ISSUE(Action.USER_ADMINISTRATION, "scim.connector.token.issue",
                onSuccess(CONNECTOR_CHANGE_APPLIED)),
        CONNECTOR_TOKEN_ROTATE(Action.USER_ADMINISTRATION, "scim.connector.token.rotate",
                onSuccess(CONNECTOR_CHANGE_APPLIED), onRefusal(CONNECTOR_ROTATION_REFUSED)),
        CONNECTOR_TOKEN_REVOKE(Action.USER_PROVISIONING, "scim.connector.token.revoke",
                onSuccess(CONNECTOR_CHANGE_APPLIED)),
        SCIM_WRITE(Action.USER_PROVISIONING, "scim.write",
                onError("SCIM write refused by an unmapped integrity violation")),
        SCIM_REFUSAL(Action.USER_PROVISIONING, "scim.refusal",
                onRefusal(SCIM_REQUEST_REFUSED), onError(SCIM_REQUEST_REFUSED)),
        ACCESS_DENIED(Action.ACCESS_CONTROL, "access.denied",
                onRefusal("Request refused: access denied")),
        UNAUTHENTICATED(Action.ACCESS_CONTROL, "access.unauthenticated",
                onRefusal("Request refused: authentication required")),
        LOGOUT(Action.USER_LOGOUT, null,
                onSuccess("Logout completed")),
        PERMISSION_GRANT(Action.USER_ADMINISTRATION, "identity.permission_grant",
                onSuccess("Permissions granted by a Group's membership")),
        PERMISSION_REVOKE(Action.USER_ADMINISTRATION, "identity.permission_revoke",
                onSuccess("Permissions revoked by a Group's membership")),
        GROUP_PERMISSIONS_CHANGE(Action.USER_ADMINISTRATION,
                "authorization.group_permissions_change",
                onSuccess("Group Permissions set"),
                onRefusal("Group Permissions change refused")),
        PATIENT_SEED(Action.APPLICATION_STARTUP, "patient.seed",
                onSuccess("Development patient fixtures seeded")),
        SESSION_START(Action.SESSION_START, null,
                onSuccess("Session started")),
        SESSION_END(Action.SESSION_END, null,
                onSuccess("Session ended"),
                onError("Sessions could not be ended; they may still be live")),
        AUDIT_RETENTION(null, "audit.retention",
                onSchedule("Audit retention job scheduled")),
        AUDIT_APPEND(null, "audit.append",
                onError("Audit event could not be appended; the request was not altered")),
        HTTP_REQUEST(null, "http.request"),
        HTTP_REQUEST_REFUSAL(null, "http.request.refusal",
                onRefusal("Request refused")),
        HTTP_REQUEST_FAULT(null, "http.request.fault",
                onError("Request failed with an unexpected exception")),
        APPLICATION_STARTUP(Action.APPLICATION_STARTUP, null,
                onSuccess("Application started")),
        APPLICATION_SHUTDOWN(Action.APPLICATION_SHUTDOWN, null,
                onSuccess("Application shutting down"));

        private final Action action;
        private final String local;
        private final Map<MessageSlot, String> messages;

        Operation(Action action, String local, Message... declared) {
            this.action = action;
            this.local = local;
            this.messages = Message.bySlot(declared);
        }

        /** The standard's action, or {@code null} where none fits. */
        public Action action() {
            return action;
        }

        /** The operation's own name, or {@code null} where {@link #action()} identifies it. */
        public String local() {
            return local;
        }

        /** Whether this operation declares its own message for {@code slot}. */
        boolean declares(MessageSlot slot) {
            return messages.containsKey(slot);
        }

        /** The message of its {@link LogEvent#success} and {@code successAtWarn} records. */
        private static Message onSuccess(String text) {
            return new Message(MessageSlot.SUCCESS, text);
        }

        /** The message of its {@link LogEvent#refused} records. */
        private static Message onRefusal(String text) {
            return new Message(MessageSlot.REFUSED, text);
        }

        /** The message of its {@link LogEvent#error} records. */
        private static Message onError(String text) {
            return new Message(MessageSlot.ERROR, text);
        }

        /** The message of its job's {@link LogEvent#jobScheduled} startup record. */
        private static Message onSchedule(String text) {
            return new Message(MessageSlot.SCHEDULED, text);
        }

    }

    /**
     * The record shapes whose message is the operation's own, each with the generic message an
     * operation that declares none gets. {@link #success} and {@link #successAtWarn} share
     * {@link #SUCCESS}.
     */
    enum MessageSlot {
        SUCCESS("Operation completed"),
        REFUSED("Operation refused"),
        ERROR("Operation failed"),
        SCHEDULED("Scheduled job registered");

        private final String generic;

        MessageSlot(String generic) {
            this.generic = generic;
        }
    }

    /** One message an {@link Operation} declares: fixed text, never a value. */
    record Message(MessageSlot slot, String text) {
        Message {
            if (text == null || text.isBlank()) {
                throw new IllegalArgumentException("a declared message has text");
            }
        }

        /**
         * An operation's declared messages by slot. Two for one slot is a mistake in the
         * operation's line, refused rather than one silently winning.
         */
        static Map<MessageSlot, String> bySlot(Message... declared) {
            Map<MessageSlot, String> bySlot = new EnumMap<>(MessageSlot.class);
            for (Message message : declared) {
                if (bySlot.putIfAbsent(message.slot(), message.text()) != null) {
                    throw new IllegalArgumentException(
                            "two " + message.slot() + " messages for one operation");
                }
            }
            return Collections.unmodifiableMap(bySlot);
        }
    }

    /** {@code event.action} values from {@code Log_Schema.md} §Event that this service uses. */
    public enum Action {
        USER_AUTHENTICATION("user-authentication"),
        USER_LOGOUT("user-logout"),
        SESSION_START("session-start"),
        SESSION_END("session-end"),
        USER_ADMINISTRATION("user-administration"),
        USER_PROVISIONING("user-provisioning"),
        PASSWORD_CHANGE_ENFORCEMENT("password-change-enforcement"),
        ACCESS_CONTROL("access-control"),
        APPLICATION_STARTUP("application-startup"),
        APPLICATION_SHUTDOWN("application-shutdown");

        private final String value;

        Action(String value) {
            this.value = value;
        }

        public String value() {
            return value;
        }
    }

    /** {@code event.kind} values from {@code Log_Schema.md} §Event that this service uses. */
    public enum Kind {
        EVENT("event");

        private final String value;

        Kind(String value) {
            this.value = value;
        }

        public String value() {
            return value;
        }
    }

    /** {@code event.category} values from {@code Log_Schema.md} §Event that this service uses. */
    public enum Category {
        CONFIGURATION("configuration"),
        DATABASE("database"),
        BATCH("batch"),
        NETWORK("network"),
        PROCESS("process");

        private final String value;

        Category(String value) {
            this.value = value;
        }

        public String value() {
            return value;
        }
    }

    /** {@code event.type} values from {@code Log_Schema.md} §Event that this service uses. */
    public enum Type {
        ACCESS("access"),
        ADMIN("admin"),
        ALLOWED("allowed"),
        CHANGE("change"),
        CONNECTION("connection"),
        CREATION("creation"),
        DELETION("deletion"),
        DENIED("denied"),
        END("end"),
        ERROR("error"),
        GROUP("group"),
        INFO("info"),
        JOB_END("job-end"),
        JOB_START("job-start"),
        START("start"),
        USER("user");

        private final String value;

        Type(String value) {
            this.value = value;
        }

        public String value() {
            return value;
        }
    }

    /** {@code event.severity} values from {@code Log_Schema.md} §Event that this service uses. */
    public enum Severity {
        LOW("low"),
        HIGH("high");

        private final String value;

        Severity(String value) {
            this.value = value;
        }

        public String value() {
            return value;
        }
    }

    /** {@code error.category} values from {@code Log_Schema.md} §Error that this service uses. */
    public enum ErrorCategory {
        APPLICATION("application"),
        DATABASE("database"),
        /**
         * Input the service refused: the caller's error, not the service's — or an answer from a
         * service this one called that it could not use (ADR 0012's malformed Epic
         * response).
         */
        DATA("data"),
        /** A call to another service timed out or could not connect (ADR 0012, "Log"). */
        NETWORK("network"),
        /** Another service answered {@code 5xx} (ADR 0012, "Log"). */
        SERVER("server"),
        /**
         * Another service refused this one's own credential — Epic's {@code invalid_client}, or
         * Epic rejecting our client assertion (ADR 0012, "Log"): likely a key or a registration
         * problem.
         */
        CERT_AUTH("cert/auth");

        private final String value;

        ErrorCategory(String value) {
            this.value = value;
        }

        public String value() {
            return value;
        }
    }
}

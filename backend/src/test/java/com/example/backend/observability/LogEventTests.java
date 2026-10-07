package com.example.backend.observability;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import com.example.backend.audit.CapturedLog;
import com.example.backend.observability.LogEvent.Action;
import com.example.backend.observability.LogEvent.Category;
import com.example.backend.observability.LogEvent.ErrorCategory;
import com.example.backend.observability.LogEvent.Kind;
import com.example.backend.observability.LogEvent.Operation;
import com.example.backend.observability.LogEvent.Severity;
import com.example.backend.observability.LogEvent.Type;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.spi.LoggingEventBuilder;

/**
 * The event vocabulary: every value it can write is a member of the logging
 * standard's closed enum ({@code Log_Schema.md} §Event), and {@link LogEvent#classify}
 * writes exactly the fields an operation's mapping says it should. Then each record shape,
 * once, through its own public method: its level, its outcome and duration, and its fixed
 * message per operation.
 */
class LogEventTests {

    private static final Logger log = LoggerFactory.getLogger(LogEventTests.class);

    /** {@code Log_Schema.md} §Event, the allowed values this service may draw from. */
    private static final Set<String> STANDARD_ACTIONS = Set.of(
            "user-authentication", "user-logout", "user-provisioning", "user-administration",
            "password-reset", "password-change-enforcement", "session-start", "session-end",
            "access-control", "application-startup", "application-shutdown");
    private static final Set<String> STANDARD_KINDS = Set.of("event", "state");
    private static final Set<String> STANDARD_CATEGORIES = Set.of(
            "configuration", "network", "database", "batch", "interface", "process");
    private static final Set<String> STANDARD_TYPES = Set.of(
            "access", "admin", "allowed", "change", "connection", "creation", "deletion",
            "denied", "end", "error", "group", "indicator", "info", "installation",
            "interface-end", "interface-start", "job-end", "job-start", "protocol", "start",
            "step-end", "step-start", "user");
    private static final Set<String> STANDARD_SEVERITIES =
            Set.of("low", "medium", "high", "critical");
    /** {@code Log_Schema.md} §Error {@code error.category}. */
    private static final Set<String> STANDARD_ERROR_CATEGORIES = Set.of(
            "server", "network", "cert/auth", "database", "application", "data", "others");

    /**
     * Where ADR 0003 records each operation's exception to the schema's required
     * {@code event.action}, with the reason no allowed value fits.
     */
    private static final String NO_ACTION_SECTION =
            "docs/adr/0003 § Addendum (2026-10-02): user.id on the request record, and every"
                    + " record's event.action — Operations with no event.action";

    /**
     * The operations that carry no {@code event.action}, each naming the ADR section that
     * says why. An operation added with no action and no entry here fails
     * {@link #everyOperationHasAnActionOrADocumentedException}; so does an entry left
     * behind after its operation gains one.
     */
    private static final Map<Operation, String> NO_ACTION_EXCEPTIONS = Map.of(
            Operation.HTTP_REQUEST, NO_ACTION_SECTION,
            Operation.HTTP_REQUEST_REFUSAL, NO_ACTION_SECTION,
            Operation.HTTP_REQUEST_FAULT, NO_ACTION_SECTION,
            Operation.AUDIT_RETENTION, NO_ACTION_SECTION,
            Operation.AUDIT_APPEND, NO_ACTION_SECTION);

    /**
     * Every operation either maps onto a standard action or is a documented exception —
     * never both, and never neither. An exception still names its operation, by
     * {@code app.event.action}.
     */
    @ParameterizedTest
    @EnumSource(Operation.class)
    void everyOperationHasAnActionOrADocumentedException(Operation operation) {
        if (NO_ACTION_EXCEPTIONS.containsKey(operation)) {
            assertThat(operation.action())
                    .as("%s is listed as an exception but has an action", operation)
                    .isNull();
            assertThat(NO_ACTION_EXCEPTIONS.get(operation)).startsWith("docs/adr/0003 § ");
            assertThat(operation.local()).isNotBlank();
        } else {
            assertThat(operation.action())
                    .as("%s has no event.action and no documented exception", operation)
                    .isNotNull();
            assertThat(STANDARD_ACTIONS).contains(operation.action().value());
        }
    }

    /**
     * The mapping #95 changed: the dormancy role revocation is administration of a User's
     * standing, and a connector and its tokens are the provisioning channel's lifecycle —
     * except issuing and rotating a token, which grant Permissions and so are user
     * administration (#117, ADR 0003). {@code access-control} is left to
     * the access decisions themselves.
     */
    @Test
    void eachOperationCarriesItsNearestStandardAction() {
        assertThat(Operation.DORMANCY_ROLE_REVOCATION.action())
                .isEqualTo(Action.USER_ADMINISTRATION);
        assertThat(List.of(Operation.CONNECTOR_CREATE, Operation.CONNECTOR_DELETE,
                        Operation.CONNECTOR_TOKEN_REVOKE))
                .extracting(Operation::action)
                .containsOnly(Action.USER_PROVISIONING);
        assertThat(List.of(Operation.CONNECTOR_TOKEN_ISSUE, Operation.CONNECTOR_TOKEN_ROTATE))
                .extracting(Operation::action)
                .containsOnly(Action.USER_ADMINISTRATION);
        assertThat(Arrays.stream(Operation.values())
                        .filter(operation -> operation.action() == Action.ACCESS_CONTROL))
                .containsExactlyInAnyOrder(
                        Operation.UNLOCK, Operation.ACCESS_DENIED, Operation.UNAUTHENTICATED);
    }

    @Test
    void everyDeclaredValueIsAMemberOfTheStandardsEnum() {
        assertThat(values(Action.values(), Action::value)).isSubsetOf(STANDARD_ACTIONS);
        assertThat(values(Kind.values(), Kind::value)).isSubsetOf(STANDARD_KINDS);
        assertThat(values(Category.values(), Category::value)).isSubsetOf(STANDARD_CATEGORIES);
        assertThat(values(Type.values(), Type::value)).isSubsetOf(STANDARD_TYPES);
        assertThat(values(Severity.values(), Severity::value)).isSubsetOf(STANDARD_SEVERITIES);
        assertThat(values(ErrorCategory.values(), ErrorCategory::value))
                .isSubsetOf(STANDARD_ERROR_CATEGORIES);
    }

    /** Pins each spelling, so a typo in one constant is a failure rather than a subset. */
    @Test
    void theDeclaredSpellingsAreTheStandards() {
        assertThat(Action.USER_AUTHENTICATION.value()).isEqualTo("user-authentication");
        assertThat(Action.USER_LOGOUT.value()).isEqualTo("user-logout");
        assertThat(Action.SESSION_START.value()).isEqualTo("session-start");
        assertThat(Action.SESSION_END.value()).isEqualTo("session-end");
        assertThat(Action.USER_ADMINISTRATION.value()).isEqualTo("user-administration");
        assertThat(Action.USER_PROVISIONING.value()).isEqualTo("user-provisioning");
        assertThat(Action.PASSWORD_CHANGE_ENFORCEMENT.value())
                .isEqualTo("password-change-enforcement");
        assertThat(Action.ACCESS_CONTROL.value()).isEqualTo("access-control");
        assertThat(Action.APPLICATION_STARTUP.value()).isEqualTo("application-startup");
        assertThat(Action.APPLICATION_SHUTDOWN.value()).isEqualTo("application-shutdown");
        assertThat(Kind.EVENT.value()).isEqualTo("event");
        assertThat(Category.CONFIGURATION.value()).isEqualTo("configuration");
        assertThat(Category.DATABASE.value()).isEqualTo("database");
        assertThat(Category.BATCH.value()).isEqualTo("batch");
        assertThat(Category.NETWORK.value()).isEqualTo("network");
        assertThat(Category.PROCESS.value()).isEqualTo("process");
        assertThat(Type.ACCESS.value()).isEqualTo("access");
        assertThat(Type.START.value()).isEqualTo("start");
        assertThat(Type.END.value()).isEqualTo("end");
        assertThat(Type.JOB_END.value()).isEqualTo("job-end");
        assertThat(Type.JOB_START.value()).isEqualTo("job-start");
        assertThat(Severity.LOW.value()).isEqualTo("low");
        assertThat(Severity.HIGH.value()).isEqualTo("high");
        assertThat(ErrorCategory.APPLICATION.value()).isEqualTo("application");
        assertThat(ErrorCategory.DATABASE.value()).isEqualTo("database");
    }

    /**
     * An operation is identifiable from its record: by its local name where it has
     * one, or else by an action no other operation WITHOUT a local name shares — a
     * record carrying no {@code app.event.action} is then the one operation with that
     * action and no local name, however many named operations share the action.
     */
    @Test
    void everyOperationIsIdentifiableFromItsRecord() {
        Map<Action, Long> sharing = Arrays.stream(Operation.values())
                .filter(operation -> operation.action() != null && operation.local() == null)
                .collect(Collectors.groupingBy(Operation::action, Collectors.counting()));

        assertThat(Operation.values()).allSatisfy(operation -> {
            if (operation.local() == null) {
                assertThat(operation.action()).isNotNull();
                assertThat(sharing.get(operation.action())).isEqualTo(1L);
            }
        });
        assertThat(Arrays.stream(Operation.values()).map(Operation::local).filter(l -> l != null))
                .doesNotHaveDuplicates();
    }

    @Test
    void classifyWritesTheStandardFieldsAndTheLocalName() {
        Map<String, Object> fields = classified(
                Operation.UNLOCK, Category.PROCESS, Type.ADMIN, Type.USER, Type.CHANGE);

        assertThat(fields)
                .containsEntry(LogEvent.KIND, "event")
                .containsEntry(LogEvent.CATEGORY, List.of("process"))
                .containsEntry(LogEvent.TYPE, List.of("admin", "user", "change"))
                .containsEntry(LogEvent.ACTION, "access-control")
                .containsEntry(LogEvent.LOCAL_ACTION, "identity.unlock");
    }

    @Test
    void anOperationWithAnExactActionWritesNoLocalName() {
        Map<String, Object> fields =
                classified(Operation.LOGIN, Category.PROCESS, Type.USER, Type.ALLOWED);

        assertThat(fields)
                .containsEntry(LogEvent.ACTION, "user-authentication")
                .doesNotContainKey(LogEvent.LOCAL_ACTION);
    }

    @Test
    void anOperationNoActionFitsWritesItsLocalNameAlone() {
        Map<String, Object> fields =
                classified(Operation.AUDIT_RETENTION, Category.BATCH, Type.JOB_END);

        assertThat(fields)
                .containsEntry(LogEvent.LOCAL_ACTION, "audit.retention")
                .containsEntry(LogEvent.TYPE, List.of("job-end"))
                .doesNotContainKey(LogEvent.ACTION);
    }

    @ParameterizedTest
    @EnumSource(Operation.class)
    void everyOperationClassifiesToItsMapping(Operation operation) {
        Map<String, Object> fields = classified(operation, Category.PROCESS, Type.INFO);

        assertThat(fields.get(LogEvent.ACTION))
                .isEqualTo(operation.action() == null ? null : operation.action().value());
        assertThat(fields.get(LogEvent.LOCAL_ACTION)).isEqualTo(operation.local());
    }

    // ---- the record shapes -------------------------------------------------------------------

    /** {@code success}: {@code INFO}, {@code success}, classified, the operation's message. */
    @Test
    void successIsAnInfoRecordWithTheSuccessOutcomeAndTheOperationsMessage() {
        ILoggingEvent record = only(() -> LogEvent.success(
                log, Operation.LOGIN, Category.PROCESS, Type.USER, Type.ALLOWED));

        assertThat(record.getLevel()).isEqualTo(Level.INFO);
        assertThat(record.getMessage()).isEqualTo("Login accepted");
        assertThat(CapturedLog.fields(record))
                .containsEntry(LogEvent.OUTCOME, "success")
                .containsEntry(LogEvent.ACTION, "user-authentication")
                .containsEntry(LogEvent.TYPE, List.of("user", "allowed"))
                .doesNotContainKeys(LogEvent.DURATION_MS, LogEvent.REASON, LogEvent.ERROR_CODE);
    }

    /** {@code successAtWarn}: the same record, at {@code WARN}. */
    @Test
    void successAtWarnIsTheSuccessRecordAtWarn() {
        ILoggingEvent record = only(() -> LogEvent.successAtWarn(
                log, Operation.DORMANCY_LOCKOUT, Category.PROCESS, Type.CHANGE));

        assertThat(record.getLevel()).isEqualTo(Level.WARN);
        assertThat(record.getMessage()).isEqualTo("User locked for dormancy");
        assertThat(CapturedLog.fields(record))
                .containsEntry(LogEvent.OUTCOME, "success")
                .containsEntry(LogEvent.LOCAL_ACTION, "identity.dormancy_lockout")
                .doesNotContainKeys(LogEvent.DURATION_MS, LogEvent.ERROR_CODE);
    }

    /** {@code success} with a duration: the success record, carrying {@code event.duration_ms}. */
    @Test
    void successWithADurationCarriesIt() {
        ILoggingEvent record = only(() -> LogEvent.success(
                log, Operation.APPLICATION_SHUTDOWN, 1234L, Category.PROCESS, Type.END));

        assertThat(record.getLevel()).isEqualTo(Level.INFO);
        assertThat(record.getMessage()).isEqualTo("Application shutting down");
        assertThat(CapturedLog.fields(record))
                .containsEntry(LogEvent.OUTCOME, "success")
                .containsEntry(LogEvent.DURATION_MS, 1234L)
                .containsEntry(LogEvent.ACTION, "application-shutdown");
    }

    /** {@code refused}: {@code WARN}, {@code failure}, no error classification of its own. */
    @Test
    void refusedIsAWarnRecordWithTheFailureOutcomeAndTheOperationsMessage() {
        ILoggingEvent record = only(() -> LogEvent.refused(
                log, Operation.PASSWORD_CHANGE, Category.PROCESS, Type.USER, Type.DENIED));

        assertThat(record.getLevel()).isEqualTo(Level.WARN);
        assertThat(record.getMessage()).isEqualTo("Self-service change refused");
        assertThat(CapturedLog.fields(record))
                .containsEntry(LogEvent.OUTCOME, "failure")
                .containsEntry(LogEvent.LOCAL_ACTION, "identity.password_change")
                .containsEntry(LogEvent.TYPE, List.of("user", "denied"))
                .doesNotContainKeys(LogEvent.DURATION_MS, LogEvent.ERROR_CODE);
    }

    /**
     * {@code error}: {@code ERROR}, already carrying all three error fields with follow-up
     * {@code true}, {@code failure}, and the operation's message.
     */
    @Test
    void errorIsAnErrorRecordCarryingTheErrorClassificationAndTheFailureOutcome() {
        ILoggingEvent record = only(() -> LogEvent.error(log, Operation.AUDIT_APPEND, 503,
                ErrorCategory.DATABASE, Category.DATABASE, Type.ERROR));

        assertThat(record.getLevel()).isEqualTo(Level.ERROR);
        assertThat(record.getMessage())
                .isEqualTo("Audit event could not be appended; the request was not altered");
        assertThat(CapturedLog.fields(record))
                .containsEntry(LogEvent.ERROR_CODE, 503)
                .containsEntry(LogEvent.ERROR_CATEGORY, "database")
                .containsEntry(LogEvent.ERROR_FOLLOW_UP_ACTION, true)
                .containsEntry(LogEvent.OUTCOME, "failure")
                .containsEntry(LogEvent.LOCAL_ACTION, "audit.append")
                .doesNotContainKey(LogEvent.DURATION_MS);
    }

    /**
     * {@code withError} adds the error classification to a record below {@code ERROR} and
     * leaves its level, outcome and message alone.
     */
    @Test
    void withErrorClassifiesARecordAtItsOwnLevel() {
        ILoggingEvent record = only(() -> LogEvent.withError(LogEvent.refused(log,
                        Operation.HTTP_REQUEST_REFUSAL, Category.PROCESS, Type.DENIED),
                400, ErrorCategory.DATA, false));

        assertThat(record.getLevel()).isEqualTo(Level.WARN);
        assertThat(record.getMessage()).isEqualTo("Request refused");
        assertThat(CapturedLog.fields(record))
                .containsEntry(LogEvent.ERROR_CODE, 400)
                .containsEntry(LogEvent.ERROR_CATEGORY, "data")
                .containsEntry(LogEvent.ERROR_FOLLOW_UP_ACTION, false)
                .containsEntry(LogEvent.OUTCOME, "failure")
                .containsEntry(LogEvent.LOCAL_ACTION, "http.request.refusal");
    }

    /**
     * A job's startup record names the job, says what it does, and states its cron with the
     * zone the cron is evaluated in, classified as the job's operation. It reports a schedule,
     * not an outcome.
     */
    @Test
    void jobScheduledNamesTheJobItsDescriptionCronAndZone() {
        ILoggingEvent record = only(() -> LogEvent.jobScheduled(log, Operation.DORMANCY,
                "probe", "0 0 4 * * *", "Does the probe's work"));

        assertThat(record.getLevel()).isEqualTo(Level.INFO);
        assertThat(record.getMessage()).isEqualTo("Dormancy job scheduled");
        assertThat(CapturedLog.fields(record))
                .containsEntry(LogContext.JOB_NAME, "probe")
                .containsEntry(LogEvent.JOB_DESCRIPTION, "Does the probe's work")
                .containsEntry(LogEvent.TRIGGER_CRON_EXPRESSION, "0 0 4 * * *")
                .containsEntry(LogEvent.TRIGGER_CRON_TIMEZONE, "Asia/Singapore")
                .containsEntry(LogEvent.CATEGORY, List.of("configuration"))
                .containsEntry(LogEvent.TYPE, List.of("info"))
                .containsEntry(LogEvent.ACTION, "user-administration")
                .containsEntry(LogEvent.LOCAL_ACTION, "identity.dormancy")
                .doesNotContainKeys(LogEvent.OUTCOME, LogEvent.DURATION_MS);
    }

    /** {@code jobStart}: {@code INFO} {@code batch}/{@code job-start}, with no outcome yet. */
    @Test
    void jobStartIsTheRunsStartRecord() {
        ILoggingEvent record = only(() -> LogEvent.jobStart(log, Operation.AUDIT_RETENTION));

        assertThat(record.getLevel()).isEqualTo(Level.INFO);
        assertThat(record.getMessage()).isEqualTo("Scheduled job started");
        assertThat(CapturedLog.fields(record))
                .containsEntry(LogEvent.CATEGORY, List.of("batch"))
                .containsEntry(LogEvent.TYPE, List.of("job-start"))
                .containsEntry(LogEvent.LOCAL_ACTION, "audit.retention")
                .doesNotContainKeys(LogEvent.OUTCOME, LogEvent.DURATION_MS);
    }

    /**
     * {@code jobEnd}: {@code INFO} {@code job-end}, {@code success} and the duration; a skipped
     * run says why, by {@code lock-held}, and under its own message.
     */
    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "false | Scheduled job completed",
            "true | Scheduled job skipped: another run holds its lock"})
    void jobEndIsTheRunsSuccessfulEnd(boolean skipped, String message) {
        ILoggingEvent record =
                only(() -> LogEvent.jobEnd(log, Operation.DORMANCY, 42L, skipped));

        assertThat(record.getLevel()).isEqualTo(Level.INFO);
        assertThat(record.getMessage()).isEqualTo(message);
        Map<String, Object> fields = CapturedLog.fields(record);
        assertThat(fields)
                .containsEntry(LogEvent.OUTCOME, "success")
                .containsEntry(LogEvent.DURATION_MS, 42L)
                .containsEntry(LogEvent.CATEGORY, List.of("batch"))
                .containsEntry(LogEvent.TYPE, List.of("job-end"))
                .containsEntry(LogEvent.LOCAL_ACTION, "identity.dormancy")
                .doesNotContainKey(LogEvent.ERROR_CODE);
        assertThat(fields.get(LogEvent.REASON)).isEqualTo(skipped ? "lock-held" : null);
    }

    /**
     * {@code jobFailed}: {@code ERROR} {@code job-end} with {@code error.code} {@code 500}, the
     * given error category, {@code failure}, {@code high} severity and the duration.
     */
    @Test
    void jobFailedIsTheRunsFailedEnd() {
        ILoggingEvent record = only(() -> LogEvent.jobFailed(
                log, Operation.AUDIT_RETENTION, 7L, ErrorCategory.DATABASE));

        assertThat(record.getLevel()).isEqualTo(Level.ERROR);
        assertThat(record.getMessage()).isEqualTo("Scheduled job failed");
        assertThat(CapturedLog.fields(record))
                .containsEntry(LogEvent.ERROR_CODE, 500)
                .containsEntry(LogEvent.ERROR_CATEGORY, "database")
                .containsEntry(LogEvent.ERROR_FOLLOW_UP_ACTION, true)
                .containsEntry(LogEvent.OUTCOME, "failure")
                .containsEntry(LogEvent.SEVERITY, "high")
                .containsEntry(LogEvent.DURATION_MS, 7L)
                .containsEntry(LogEvent.TYPE, List.of("job-end"))
                .containsEntry(LogEvent.LOCAL_ACTION, "audit.retention");
    }

    /** {@code jobSummary}: {@code INFO} {@code batch}/{@code info}, leaving outcome to job-end. */
    @Test
    void jobSummaryReportsWhatARunDidWithNoOutcome() {
        ILoggingEvent record = only(() -> LogEvent.jobSummary(log, Operation.AUDIT_RETENTION));

        assertThat(record.getLevel()).isEqualTo(Level.INFO);
        assertThat(record.getMessage()).isEqualTo("Audit retention run complete");
        assertThat(CapturedLog.fields(record))
                .containsEntry(LogEvent.CATEGORY, List.of("batch"))
                .containsEntry(LogEvent.TYPE, List.of("info"))
                .containsEntry(LogEvent.LOCAL_ACTION, "audit.retention")
                .doesNotContainKeys(LogEvent.OUTCOME, LogEvent.DURATION_MS);
    }

    /**
     * {@code requestEnd}: the status, the duration and the outcome ({@code success} below
     * {@code 400}), at {@code INFO} below {@code 400}, {@code WARN} for a {@code 4xx}, and
     * {@code ERROR} — classified — for a {@code 5xx} no handler recorded; a {@code 5xx} a
     * handler recorded is {@code WARN} and unclassified, so the one failure is one ERROR.
     */
    @ParameterizedTest
    @CsvSource({
            "200, false, INFO, success",
            "399, false, INFO, success",
            "200, true, INFO, success",
            "400, false, WARN, failure",
            "499, true, WARN, failure",
            "499, false, WARN, failure",
            "500, false, ERROR, failure",
            "503, true, WARN, failure"})
    void requestEndIsTheRequestsRecordAtTheLevelItsStatusGives(
            int status, boolean faultRecorded, String level, String outcome) {
        ILoggingEvent record = only(() -> LogEvent.requestEnd(log, status, faultRecorded, 9L));

        assertThat(record.getLevel()).isEqualTo(Level.toLevel(level));
        assertThat(record.getMessage()).isEqualTo("HTTP request completed");
        Map<String, Object> fields = CapturedLog.fields(record);
        assertThat(fields)
                .containsEntry(LogEvent.HTTP_STATUS_CODE, status)
                .containsEntry(LogEvent.DURATION_MS, 9L)
                .containsEntry(LogEvent.OUTCOME, outcome)
                .containsEntry(LogEvent.CATEGORY, List.of("network"))
                .containsEntry(LogEvent.TYPE, List.of("access", "end"))
                .containsEntry(LogEvent.LOCAL_ACTION, "http.request");
        if (level.equals("ERROR")) {
            assertThat(fields)
                    .containsEntry(LogEvent.ERROR_CODE, status)
                    .containsEntry(LogEvent.ERROR_CATEGORY, "application")
                    .containsEntry(LogEvent.ERROR_FOLLOW_UP_ACTION, true);
        } else {
            assertThat(fields).doesNotContainKeys(LogEvent.ERROR_CODE, LogEvent.ERROR_CATEGORY,
                    LogEvent.ERROR_FOLLOW_UP_ACTION);
        }
    }

    // ---- the fixed messages ------------------------------------------------------------------

    /**
     * Every operation that writes a success record, with its message; an operation that writes
     * none gets the shape's generic message rather than a failure on the path that logs.
     */
    @ParameterizedTest
    @CsvSource(delimiter = '|', quoteCharacter = '"', value = {
            "LOGIN | Login accepted",
            "UNLOCK | Administrative identity change applied",
            "FORCE_PASSWORD_CHANGE | Administrative identity change applied",
            "PASSWORD_CHANGE | Self-service change completed",
            "DORMANCY | Dormancy job run at startup for the development fixtures",
            "DORMANCY_LOCKOUT | User locked for dormancy",
            "DORMANCY_ROLE_REVOCATION | Roles revoked for dormancy",
            "CONNECTOR_CREATE | SCIM connector lifecycle change applied",
            "CONNECTOR_DELETE | SCIM connector lifecycle change applied",
            "CONNECTOR_TOKEN_ISSUE | SCIM connector lifecycle change applied",
            "CONNECTOR_TOKEN_ROTATE | SCIM connector lifecycle change applied",
            "CONNECTOR_TOKEN_REVOKE | SCIM connector lifecycle change applied",
            "LOGOUT | Logout completed",
            "ROLE_GRANT | Role granted by a mapped Group's membership",
            "ROLE_REVOKE | Role revoked by a mapped Group's membership",
            "ROLE_MAPPING_STARTUP | Role mapping validated",
            "SESSION_START | Session started",
            "SESSION_END | Session ended",
            "APPLICATION_STARTUP | Application started",
            "APPLICATION_SHUTDOWN | Application shutting down",
            "SCIM_WRITE | Operation completed"})
    void eachOperationsSuccessMessage(Operation operation, String message) {
        assertThat(LogEvent.successMessage(operation)).isEqualTo(message);
    }

    /**
     * The one success message keyed by type as well as operation: the role-mapping pass's
     * {@code change} record names the sessions it ended, while its {@code info} record — and
     * a {@code change} record of any other operation — keeps the operation's own message.
     */
    @Test
    void theRoleMappingChangeRecordNamesTheSessionsItEnded() {
        assertThat(LogEvent.successMessage(Operation.ROLE_MAPPING_STARTUP, Type.CHANGE))
                .isEqualTo("Sessions issued under another role mapping ended");
        assertThat(LogEvent.successMessage(Operation.ROLE_MAPPING_STARTUP, Type.INFO))
                .isEqualTo("Role mapping validated");
        assertThat(LogEvent.successMessage(Operation.ROLE_MAPPING_STARTUP))
                .isEqualTo("Role mapping validated");
        assertThat(LogEvent.successMessage(Operation.ROLE_GRANT, Type.CHANGE))
                .isEqualTo("Role granted by a mapped Group's membership");
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', quoteCharacter = '"', value = {
            "LOGIN | Login refused",
            "UNLOCK | Administrative identity change refused",
            "FORCE_PASSWORD_CHANGE | Administrative identity change refused",
            "PASSWORD_CHANGE | Self-service change refused",
            "CONNECTOR_TOKEN_ISSUE | SCIM connector issue refused: it would exceed the"
                    + " requester's Permissions",
            "CONNECTOR_TOKEN_ROTATE | SCIM connector issue refused: it would exceed the"
                    + " requester's Permissions",
            "SCIM_REFUSAL | SCIM request refused",
            "ACCESS_DENIED | Request refused: access denied",
            "UNAUTHENTICATED | Request refused: authentication required",
            "HTTP_REQUEST_REFUSAL | Request refused",
            "LOGOUT | Operation refused"})
    void eachOperationsRefusalMessage(Operation operation, String message) {
        assertThat(LogEvent.refusedMessage(operation)).isEqualTo(message);
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', quoteCharacter = '"', value = {
            "SCIM_WRITE | SCIM write refused by an unmapped integrity violation",
            "SCIM_REFUSAL | SCIM request refused",
            "AUDIT_APPEND | Audit event could not be appended; the request was not altered",
            "HTTP_REQUEST_FAULT | Request failed with an unexpected exception",
            "EPIC_LOGIN | Epic sign-in failed",
            "EPIC_JWKS_REFETCH | Epic JWKS still lacks the id_token's key after its refetches",
            "LOGIN | Operation failed"})
    void eachOperationsErrorMessage(Operation operation, String message) {
        assertThat(LogEvent.errorMessage(operation)).isEqualTo(message);
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "DORMANCY | Dormancy job scheduled",
            "AUDIT_RETENTION | Audit retention job scheduled",
            "LOGIN | Scheduled job registered"})
    void eachJobsScheduleMessage(Operation operation, String message) {
        assertThat(LogEvent.scheduledMessage(operation)).isEqualTo(message);
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "AUDIT_RETENTION | Audit retention run complete",
            "DORMANCY | Scheduled job run summary"})
    void eachJobsSummaryMessage(Operation operation, String message) {
        assertThat(LogEvent.summaryMessage(operation)).isEqualTo(message);
    }

    /** The call-site spellings the encoder's customizer moves into {@code error}. */
    @Test
    void theErrorKeysAreTheUnderscoreSpellings() {
        assertThat(LogEvent.ERROR_CODE).isEqualTo("error_code");
        assertThat(LogEvent.ERROR_CATEGORY).isEqualTo("error_category");
        assertThat(LogEvent.ERROR_FOLLOW_UP_ACTION).isEqualTo("error_follow_up_action");
        assertThat(LogEvent.ERROR_CAUSE_OMITTED).isEqualTo("app.error.cause_omitted");
        assertThat(ErrorCategory.DATA.value()).isEqualTo("data");
    }

    /** {@code Log_Schema.md} has no session field; the AuthN recipe's spelling is the one used. */
    @Test
    void theSessionStartFieldIsTheRecipesSpelling() {
        assertThat(LogEvent.SESSION_MAX_INACTIVE_INTERVAL)
                .isEqualTo("session.max_inactive_interval");
    }

    // ---- Epic Login's outbound records (spec section 5) ---------------------------------------

    /** {@code error} with its follow-up chosen: Epic being down needs no person of ours. */
    @Test
    void errorWithoutFollowUpSaysSo() {
        ILoggingEvent record = only(() -> LogEvent.error(log, Operation.EPIC_LOGIN, 503,
                ErrorCategory.SERVER, false, Category.NETWORK, Type.ERROR));

        assertThat(record.getLevel()).isEqualTo(Level.ERROR);
        assertThat(record.getMessage()).isEqualTo("Epic sign-in failed");
        assertThat(CapturedLog.fields(record))
                .containsEntry(LogEvent.ERROR_CODE, 503)
                .containsEntry(LogEvent.ERROR_CATEGORY, "server")
                .containsEntry(LogEvent.ERROR_FOLLOW_UP_ACTION, false)
                .containsEntry(LogEvent.OUTCOME, "failure")
                .containsEntry(LogEvent.ACTION, "user-authentication");
    }

    @Test
    void outboundStartNamesTheCallItsMethodAndWhereItGoes() {
        ILoggingEvent record = only(() -> LogEvent.outboundStart(log,
                "token", "POST", "https://epic.example.org/oauth2/token"));

        assertThat(record.getLevel()).isEqualTo(Level.INFO);
        assertThat(record.getMessage()).isEqualTo("Epic outbound call started");
        assertThat(CapturedLog.fields(record))
                .containsEntry(LogEvent.EPIC_CALL, "token")
                .containsEntry(LogEvent.HTTP_METHOD, "POST")
                .containsEntry(LogEvent.URL_FULL, "https://epic.example.org/oauth2/token")
                .containsEntry(LogEvent.LOCAL_ACTION, "epic.outbound")
                .containsEntry(LogEvent.CATEGORY, List.of("network"))
                .containsEntry(LogEvent.TYPE, List.of("connection", "start"))
                .doesNotContainKeys(LogEvent.OUTCOME, LogEvent.DURATION_MS);
    }

    @Test
    void outboundEndCarriesTheStatusTheDurationAndTheOutcome() {
        ILoggingEvent record = only(() -> LogEvent.outboundEnd(log,
                "jwks", "GET", "https://epic.example.org/oauth2/jwks", 200, 42));

        assertThat(record.getLevel()).isEqualTo(Level.INFO);
        assertThat(record.getMessage()).isEqualTo("Epic outbound call completed");
        assertThat(CapturedLog.fields(record))
                .containsEntry(LogEvent.HTTP_STATUS_CODE, 200)
                .containsEntry(LogEvent.DURATION_MS, 42L)
                .containsEntry(LogEvent.OUTCOME, "success")
                .containsEntry(LogEvent.TYPE, List.of("connection", "end"));
    }

    /** A {@code 5xx} is an answer, so still {@code INFO}; the failure is the operation's to log. */
    @ParameterizedTest
    @CsvSource({"399, success", "400, failure", "503, failure"})
    void outboundEndsOutcomeFollowsTheStatus(int status, String outcome) {
        ILoggingEvent record = only(() -> LogEvent.outboundEnd(log,
                "token", "POST", "https://epic.example.org/oauth2/token", status, 1));

        assertThat(record.getLevel()).isEqualTo(Level.INFO);
        assertThat(CapturedLog.fields(record)).containsEntry(LogEvent.OUTCOME, outcome);
    }

    @Test
    void outboundFailedIsAnErrorUnderTheNetworkCategoryWithNoFollowUp() {
        ILoggingEvent record = only(() -> LogEvent.outboundFailed(log,
                "discovery", "GET", "https://epic.example.org/oauth2/.well-known/x", 5001));

        assertThat(record.getLevel()).isEqualTo(Level.ERROR);
        assertThat(record.getMessage()).isEqualTo("Epic outbound call failed");
        assertThat(CapturedLog.fields(record))
                .containsEntry(LogEvent.ERROR_CODE, 502)
                .containsEntry(LogEvent.ERROR_CATEGORY, "network")
                .containsEntry(LogEvent.ERROR_FOLLOW_UP_ACTION, false)
                .containsEntry(LogEvent.OUTCOME, "failure")
                .containsEntry(LogEvent.DURATION_MS, 5001L)
                .containsEntry(LogEvent.EPIC_CALL, "discovery")
                .containsEntry(LogEvent.TYPE, List.of("connection", "error"));
    }

    @Test
    void jwksRefetchWarningNamesTheAttemptAndHasNoOutcomeYet() {
        ILoggingEvent record = only(() -> LogEvent.jwksRefetchWarning(log, 2));

        assertThat(record.getLevel()).isEqualTo(Level.WARN);
        assertThat(record.getMessage())
                .isEqualTo("Epic JWKS refetched: it lacked the id_token's key");
        assertThat(CapturedLog.fields(record))
                .containsEntry(LogEvent.RETRY_ATTEMPT, 2)
                .containsEntry(LogEvent.LOCAL_ACTION, "epic.jwks_refetch")
                .doesNotContainKey(LogEvent.OUTCOME);
    }

    @Test
    void theOutboundKeysAreTheirSpellings() {
        assertThat(List.of(LogEvent.URL_FULL, LogEvent.EPIC_CALL, LogEvent.RETRY_ATTEMPT))
                .containsExactly("url.full", "app.epic.call", "app.retry.attempt");
    }

    private static Map<String, Object> classified(
            Operation operation, Category category, Type... types) {
        return CapturedLog.fields(only(() -> LogEvent.classify(
                log.atWarn(), operation, category, types).setMessage("classified")));
    }

    /** Logs the record {@code shape} builds and returns it, the one record captured. */
    private static ILoggingEvent only(java.util.function.Supplier<LoggingEventBuilder> shape) {
        try (CapturedLog captured = CapturedLog.attach()) {
            shape.get().log();
            List<ILoggingEvent> records = captured.withAction(Level.TRACE, LogEvent.KIND, "event");
            assertThat(records).hasSize(1);
            return records.getFirst();
        }
    }

    private static <E> Set<String> values(E[] members, Function<E, String> value) {
        return Arrays.stream(members).map(value).collect(Collectors.toSet());
    }
}

package com.example.backend.auth.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import com.example.backend.audit.CapturedLog;
import com.example.backend.audit.RecordingAuditTrail;
import com.example.backend.audit.RecordingAuditTrail.Recorded;
import com.example.backend.audit.RecordingOperationalAlerts;
import com.example.backend.audit.domain.AuditOperation;
import com.example.backend.auth.InMemoryAccountSessions;
import com.example.backend.auth.PendingCommit;
import com.example.backend.auth.domain.AccountSessions;
import com.example.backend.auth.domain.SessionRevocationCause;
import com.example.backend.observability.LogEvent;
import com.example.backend.scim.domain.ScimUserSessions;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Session revocation as one module: whatever triggered it, the sessions end after the commit
 * (ADR 0002) and never on a rollback, and a revocation that ended anything — or failed — is
 * audited and logged under its cause. A store failure is always recorded and alerted, and reaches
 * the caller unless the request was already being refused (ADR 0004).
 */
class SessionRevocationServiceTests {

    private static final UUID CONNECTOR = UUID.fromString("00000000-0000-0000-0000-00000000c0c0");

    private static final UUID ADMIN = UUID.fromString("00000000-0000-0000-0000-00000000ad00");

    private static final String RETAINED = "s-login";

    private final UUID user = UUID.randomUUID();

    private final InMemoryAccountSessions sessions = new InMemoryAccountSessions();

    private final PendingCommit commit = new PendingCommit();

    private final RecordingAuditTrail audit = new RecordingAuditTrail();

    private final RecordingOperationalAlerts alerts = new RecordingOperationalAlerts();

    private final SessionRevocationService revocation =
            new SessionRevocationService(sessions, commit, audit, alerts);

    // ---- after the commit, and never on a rollback (ADR 0002) ---------------------------------

    @Test
    void nothing_is_revoked_or_recorded_until_the_transaction_commits() {
        sessions.open(user, "s-1");

        revocation.revokeAllAfterCommit(user, SessionRevocationCause.FORCED_PASSWORD_CHANGE, ADMIN);

        assertThat(sessions.sessionsOf(user)).containsExactly("s-1");
        assertThat(audit.recorded()).isEmpty();

        commit.commit();

        assertThat(sessions.sessionsOf(user)).isEmpty();
    }

    @Test
    void a_rolled_back_change_revokes_nothing_and_records_nothing() {
        sessions.open(user, "s-1");

        revocation.revokeAllAfterCommit(user, SessionRevocationCause.FAILURE_RUN_LOCKOUT, null);
        revocation.revokeOtherSessionsAfterCommit(user, RETAINED);
        commit.rollback();

        assertThat(sessions.sessionsOf(user)).containsExactly("s-1");
        assertThat(audit.recorded()).isEmpty();
    }

    // ---- audited under its cause ---------------------------------------------------------------

    /**
     * Every cause, through the module, is one {@code USER_SESSIONS_REVOKE} naming it as the
     * reason — with the attribute whose change it is as the changed path, when it is one.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("everyCause")
    void a_revocation_is_audited_under_its_cause(SessionRevocationCause cause, String detail) {
        sessions.open(user, "s-1");

        revoke(cause);
        commit.commit();

        assertThat(audit.of(AuditOperation.USER_SESSIONS_REVOKE))
                .containsExactly(new Recorded(AuditOperation.USER_SESSIONS_REVOKE, null, user, detail));
    }

    static Stream<Arguments> everyCause() {
        return Stream.of(
                Arguments.of(SessionRevocationCause.DEACTIVATED, "SUCCESS:ACTIVE:DEACTIVATED"),
                Arguments.of(SessionRevocationCause.PASSWORD_CHANGED,
                        "SUCCESS:PASSWORD:PASSWORD_CHANGED"),
                Arguments.of(SessionRevocationCause.USER_NAME_CHANGED,
                        "SUCCESS:USER_NAME:USER_NAME_CHANGED"),
                Arguments.of(SessionRevocationCause.DELETED, "SUCCESS::DELETED"),
                Arguments.of(SessionRevocationCause.ROLE_REVOKED, "SUCCESS:GROUPS:ROLE_REVOKED"),
                Arguments.of(SessionRevocationCause.DORMANCY_LOCKOUT, "SUCCESS::DORMANCY_LOCKOUT"),
                Arguments.of(SessionRevocationCause.FAILURE_RUN_LOCKOUT,
                        "SUCCESS::FAILURE_RUN_LOCKOUT"),
                Arguments.of(SessionRevocationCause.FORCED_PASSWORD_CHANGE,
                        "SUCCESS::FORCED_PASSWORD_CHANGE"),
                Arguments.of(SessionRevocationCause.REPLACED_BY_LOGIN,
                        "SUCCESS::REPLACED_BY_LOGIN"));
    }

    @Test
    void every_cause_is_covered_by_the_audited_under_its_cause_test() {
        assertThat(everyCause().map(arguments -> arguments.get()[0]))
                .containsExactlyInAnyOrder((Object[]) SessionRevocationCause.values());
    }

    @Test
    void the_administrator_who_forced_a_password_change_is_recorded_as_the_actor() {
        sessions.open(user, "s-1");

        revocation.revokeAllAfterCommit(user, SessionRevocationCause.FORCED_PASSWORD_CHANGE, ADMIN);
        commit.commit();

        assertThat(audit.of(AuditOperation.USER_SESSIONS_REVOKE))
                .extracting(Recorded::actorId)
                .containsExactly(ADMIN);
    }

    /** Nothing ended and nothing failed: there is nothing to account for, so no event. */
    @Test
    void a_revocation_that_ended_no_session_is_not_audited() {
        revocation.revokeAllAfterCommit(user, SessionRevocationCause.FAILURE_RUN_LOCKOUT, null);
        revocation.revokeOtherSessionsAfterCommit(user, RETAINED);
        commit.commit();

        assertThat(audit.recorded()).isEmpty();
    }

    // ---- one session per User ------------------------------------------------------------------

    @Test
    void a_login_ends_every_other_session_and_keeps_its_own() {
        sessions.open(user, "s-earlier");
        sessions.open(user, RETAINED);

        revocation.revokeOtherSessionsAfterCommit(user, RETAINED);
        commit.commit();

        assertThat(sessions.sessionsOf(user)).containsExactly(RETAINED);
    }

    /** A Login keeping its only session ended nothing, so it is not audited. */
    @Test
    void a_login_holding_the_only_session_is_not_audited() {
        sessions.open(user, RETAINED);

        revocation.revokeOtherSessionsAfterCommit(user, RETAINED);
        commit.commit();

        assertThat(audit.recorded()).isEmpty();
    }

    /** One session per User is a Login's own sweep; no other trigger keeps a session. */
    @Test
    void replaced_by_login_is_not_a_cause_a_caller_names_directly() {
        assertThatThrownBy(() -> revocation.revokeAllAfterCommit(
                        user, SessionRevocationCause.REPLACED_BY_LOGIN, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(commit.pending()).isZero();
    }

    // ---- the directory's port ------------------------------------------------------------------

    /** The connector is the actor, and each of the write's causes is named under its own name. */
    @Test
    void a_scim_write_is_audited_with_the_connector_and_every_cause_it_named() {
        sessions.open(user, "s-1");

        revocation.revokeAfterCommit(CONNECTOR, user, EnumSet.of(
                ScimUserSessions.Cause.DEACTIVATED, ScimUserSessions.Cause.USER_NAME_CHANGED));
        commit.commit();

        assertThat(audit.of(AuditOperation.USER_SESSIONS_REVOKE)).containsExactly(new Recorded(
                AuditOperation.USER_SESSIONS_REVOKE, CONNECTOR, user,
                "SUCCESS:ACTIVE,USER_NAME:DEACTIVATED,USER_NAME_CHANGED"));
    }

    /** Each of the directory's causes is the login surface's cause of the same name. */
    @ParameterizedTest(name = "{0}")
    @MethodSource("everyScimCause")
    void a_scim_cause_is_audited_under_the_cause_of_its_name(
            ScimUserSessions.Cause cause, String detail) {
        sessions.open(user, "s-1");

        revocation.revokeAfterCommit(CONNECTOR, user, Set.of(cause));
        commit.commit();

        assertThat(audit.of(AuditOperation.USER_SESSIONS_REVOKE))
                .extracting(Recorded::detail)
                .containsExactly(detail);
    }

    static Stream<Arguments> everyScimCause() {
        return Stream.of(
                Arguments.of(ScimUserSessions.Cause.DEACTIVATED, "SUCCESS:ACTIVE:DEACTIVATED"),
                Arguments.of(ScimUserSessions.Cause.PASSWORD_CHANGED,
                        "SUCCESS:PASSWORD:PASSWORD_CHANGED"),
                Arguments.of(ScimUserSessions.Cause.USER_NAME_CHANGED,
                        "SUCCESS:USER_NAME:USER_NAME_CHANGED"),
                Arguments.of(ScimUserSessions.Cause.DELETED, "SUCCESS::DELETED"),
                Arguments.of(ScimUserSessions.Cause.ROLE_REVOKED, "SUCCESS:GROUPS:ROLE_REVOKED"));
    }

    @Test
    void every_scim_cause_is_covered() {
        assertThat(everyScimCause().map(arguments -> arguments.get()[0]))
                .containsExactlyInAnyOrder((Object[]) ScimUserSessions.Cause.values());
    }

    /** A connector's write on a User signed in nowhere ended nothing, so it is not on record. */
    @Test
    void a_scim_write_that_ended_no_session_is_not_audited() {
        revocation.revokeAfterCommit(CONNECTOR, user, Set.of(ScimUserSessions.Cause.DELETED));
        commit.commit();

        assertThat(audit.recorded()).isEmpty();
    }

    @Test
    void a_scim_revocation_with_no_cause_is_a_programming_error_and_schedules_nothing() {
        assertThatThrownBy(() -> revocation.revokeAfterCommit(CONNECTOR, user, Set.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(commit.pending()).isZero();
    }

    // ---- the session-end log -------------------------------------------------------------------

    /**
     * A revocation that ended sessions is one INFO {@code session-end} record: its cause, the
     * User by stable id as the target, and how many ended — never a session id, which is the
     * session's bearer credential.
     */
    @Test
    void a_revocation_is_one_session_end_record_naming_its_cause_and_the_user() {
        sessions.open(user, "s-1");
        sessions.open(user, "s-2");

        ILoggingEvent record = onlyRecord(() -> {
            revocation.revokeAllAfterCommit(user, SessionRevocationCause.DORMANCY_LOCKOUT, null);
            commit.commit();
        });

        assertThat(record.getLevel()).isEqualTo(Level.INFO);
        assertThat(record.getFormattedMessage()).isEqualTo("Session ended");
        assertThat(CapturedLog.fields(record))
                .containsEntry(LogEvent.ACTION, "session-end")
                .containsEntry(LogEvent.TYPE, List.of("end"))
                .containsEntry(LogEvent.OUTCOME, "success")
                .containsEntry(LogEvent.REASON, "DORMANCY_LOCKOUT")
                .containsEntry(LogEvent.USER_TARGET_ID, user.toString())
                .containsEntry(LogEvent.SESSIONS_ENDED, 2);
        assertThat(CapturedLog.fields(record).toString() + record.getMDCPropertyMap())
                .doesNotContain("s-1").doesNotContain("s-2");
    }

    @Test
    void a_login_ending_the_other_sessions_logs_it_under_its_cause() {
        sessions.open(user, "s-earlier");
        sessions.open(user, RETAINED);

        ILoggingEvent record = onlyRecord(() -> {
            revocation.revokeOtherSessionsAfterCommit(user, RETAINED);
            commit.commit();
        });

        assertThat(CapturedLog.fields(record))
                .containsEntry(LogEvent.REASON, "REPLACED_BY_LOGIN")
                .containsEntry(LogEvent.SESSIONS_ENDED, 1);
    }

    @Test
    void a_scim_write_logs_every_cause_it_named_in_order() {
        sessions.open(user, "s-1");

        ILoggingEvent record = onlyRecord(() -> {
            revocation.revokeAfterCommit(CONNECTOR, user, EnumSet.of(
                    ScimUserSessions.Cause.USER_NAME_CHANGED, ScimUserSessions.Cause.DEACTIVATED));
            commit.commit();
        });

        assertThat(CapturedLog.fields(record))
                .containsEntry(LogEvent.REASON, "DEACTIVATED,USER_NAME_CHANGED");
    }

    @Test
    void a_revocation_that_ended_nothing_writes_no_record() {
        sessions.open(user, RETAINED);

        try (CapturedLog captured = CapturedLog.attach()) {
            revocation.revokeAllAfterCommit(UUID.randomUUID(), SessionRevocationCause.DELETED, null);
            revocation.revokeOtherSessionsAfterCommit(user, RETAINED);
            commit.commit();

            assertThat(captured.withAction(Level.TRACE, LogEvent.KIND, "event")).isEmpty();
        }
    }

    // ---- a session store that fails (ADR 0004) --------------------------------------------------

    private final IllegalStateException storeDown =
            new IllegalStateException("session store unavailable");

    private final SessionRevocationService failing =
            new SessionRevocationService(new BrokenSessions(storeDown), commit, audit, alerts);

    /**
     * On a request that would otherwise succeed the change is already durable, so the failure is
     * recorded and alerted and then surfaces — the caller is not told a change fully succeeded
     * while the sessions it should have ended survive.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("everyCauseButTheRefusals")
    void a_store_failure_after_a_change_that_succeeds_propagates(SessionRevocationCause cause) {
        revoke(failing, cause);

        assertThatThrownBy(commit::commit).isSameAs(storeDown);
    }

    static Stream<SessionRevocationCause> everyCauseButTheRefusals() {
        return Stream.of(SessionRevocationCause.values())
                .filter(cause -> cause != SessionRevocationCause.FAILURE_RUN_LOCKOUT);
    }

    /**
     * A failure-run lockout is imposed by a refusal: the caller is already being answered with a
     * bare refusal, and a failure of the session store must not change that answer.
     */
    @Test
    void a_store_failure_after_a_refusal_is_swallowed() {
        failing.revokeAllAfterCommit(user, SessionRevocationCause.FAILURE_RUN_LOCKOUT, null);

        assertThatCode(commit::commit).doesNotThrowAnyException();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("everyCauseAsAFailure")
    void a_store_failure_is_audited_as_a_failure_under_its_cause(
            SessionRevocationCause cause, String detail) {
        revoke(failing, cause);
        commitIgnoringFailure();

        assertThat(audit.of(AuditOperation.USER_SESSIONS_REVOKE))
                .extracting(Recorded::detail)
                .containsExactly(detail);
    }

    static Stream<Arguments> everyCauseAsAFailure() {
        return everyCause().map(arguments -> Arguments.of(arguments.get()[0],
                ((String) arguments.get()[1]).replaceFirst("^SUCCESS", "FAILURE")));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("everyCauseButTheRefusals")
    void a_store_failure_that_propagates_is_alerted(SessionRevocationCause cause) {
        revoke(failing, cause);
        commitIgnoringFailure();

        assertThat(alerts.revocationFailures()).containsExactly(IllegalStateException.class);
    }

    @Test
    void a_store_failure_that_is_swallowed_is_alerted() {
        failing.revokeAllAfterCommit(user, SessionRevocationCause.FAILURE_RUN_LOCKOUT, null);
        commit.commit();

        assertThat(alerts.revocationFailures()).containsExactly(IllegalStateException.class);
    }

    @Test
    void a_failed_scim_revocation_is_recorded_as_a_failure_and_propagates() {
        failing.revokeAfterCommit(CONNECTOR, user, Set.of(ScimUserSessions.Cause.USER_NAME_CHANGED));

        assertThatThrownBy(commit::commit).isSameAs(storeDown);
        assertThat(audit.of(AuditOperation.USER_SESSIONS_REVOKE)).containsExactly(new Recorded(
                AuditOperation.USER_SESSIONS_REVOKE, CONNECTOR, user,
                "FAILURE:USER_NAME:USER_NAME_CHANGED"));
    }

    @Test
    void a_successful_revocation_raises_no_alert() {
        sessions.open(user, "s-1");

        revocation.revokeAllAfterCommit(user, SessionRevocationCause.DORMANCY_LOCKOUT, null);
        commit.commit();

        assertThat(alerts.revocationFailures()).isEmpty();
    }

    // ---- helpers -------------------------------------------------------------------------------

    private void revoke(SessionRevocationCause cause) {
        revoke(revocation, cause);
    }

    /** One session per User is reached through its own operation; every other cause directly. */
    private void revoke(SessionRevocationService through, SessionRevocationCause cause) {
        if (cause == SessionRevocationCause.REPLACED_BY_LOGIN) {
            through.revokeOtherSessionsAfterCommit(user, RETAINED);
        } else {
            through.revokeAllAfterCommit(user, cause, null);
        }
    }

    private void commitIgnoringFailure() {
        try {
            commit.commit();
        } catch (IllegalStateException expected) {
            // asserted elsewhere: this test is about what was recorded
        }
    }

    private static ILoggingEvent onlyRecord(Runnable action) {
        try (CapturedLog captured = CapturedLog.attach()) {
            action.run();
            List<ILoggingEvent> records = captured.withAction(Level.TRACE, LogEvent.KIND, "event");
            assertThat(records).hasSize(1);
            return records.getFirst();
        }
    }

    /** A session store that is down. */
    private record BrokenSessions(RuntimeException failure) implements AccountSessions {

        @Override
        public int revokeAll(UUID accountId) {
            throw failure;
        }

        @Override
        public int revokeAllExcept(UUID accountId, String retainedSessionId) {
            throw failure;
        }
    }
}

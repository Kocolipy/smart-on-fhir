package com.example.backend.auth.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import com.example.backend.audit.CapturedLog;
import com.example.backend.audit.RecordingAuditTrail;
import com.example.backend.audit.domain.AuditLoginMethod;
import com.example.backend.audit.domain.AuditMfaFactor;
import com.example.backend.audit.domain.AuditOperation;
import com.example.backend.auth.InMemoryAccountSessions;
import com.example.backend.auth.MutableClock;
import com.example.backend.auth.PendingCommit;
import com.example.backend.auth.RecordingEpicLoginCounts;
import com.example.backend.auth.application.EpicLoginOutcome.FailedCall;
import com.example.backend.auth.application.EpicLoginOutcome.Refused;
import com.example.backend.auth.application.EpicLoginOutcome.SignedIn;
import com.example.backend.auth.application.EpicLoginOutcome.Unavailable;
import com.example.backend.auth.domain.EpicInputField;
import com.example.backend.auth.domain.EpicInputRule;
import com.example.backend.auth.domain.EpicLoginFailureReason;
import com.example.backend.observability.LogContext;
import com.example.backend.observability.LogEvent;
import com.example.backend.observability.LogEvent.ErrorCategory;
import com.example.backend.observability.SessionHash;
import com.example.backend.scim.InMemoryScimUserRepository;
import com.example.backend.scim.domain.LockoutPolicy;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * How an Epic Login's ending is recorded ({@link EpicLoginOutcomeService}): for each outcome, the
 * audit record, the log records and the counts it moves — the whole table at one interface.
 */
class EpicLoginOutcomeServiceTests {

    private static final String SESSION = "the-launch-session";

    // Built per test, not once per class: a call built in a static initializer is built before
    // any mutant of FailedCall is in place, so no assertion on it could catch one.
    private final FailedCall tokenTimedOut = new FailedCall("token", ErrorCategory.NETWORK, 502);

    private final FailedCall token5xx = new FailedCall("token", ErrorCategory.SERVER, 503);

    private final FailedCall credentialRefused =
            new FailedCall("token", ErrorCategory.CERT_AUTH, 401);

    private final RecordingAuditTrail audit = new RecordingAuditTrail();

    private final RecordingEpicLoginCounts counts = new RecordingEpicLoginCounts();

    private final EpicLoginOutcomeService outcomes = new EpicLoginOutcomeService(
            new LoginAttemptService(new InMemoryScimUserRepository(), new InMemoryAccountSessions(),
                    new PendingCommit(), new LockoutPolicy(5), audit,
                    new MutableClock(Instant.parse("2026-10-09T00:00:00Z"))),
            counts);

    // ---- signed in ----------------------------------------------------------------------------

    @Test
    void aSignedInLoginIsOneInfoNamingTheUserMethodFactorAndSession() {
        UUID user = UUID.randomUUID();

        List<ILoggingEvent> records = recording(
                new SignedIn(user, AuditMfaFactor.HWK), SESSION);

        assertThat(records).singleElement().satisfies(record -> {
            assertThat(record.getLevel()).isEqualTo(Level.INFO);
            assertThat(record.getMDCPropertyMap()).containsEntry(LogContext.USER_ID, user.toString());
            assertThat(CapturedLog.fields(record))
                    .containsEntry(LogEvent.LOGIN_METHOD, "sso")
                    .containsEntry(LogEvent.MFA_FACTOR, "hwk")
                    .containsEntry(LogEvent.SESSION_HASH, SessionHash.of(SESSION));
        });
        assertThat(counts.moved()).containsExactly("success");
    }

    /** The {@code LOGIN_SUCCESS} is fail-closed, so the login decision's transaction writes it. */
    @Test
    void aSignedInLoginWritesNoAuditRecordOfItsOwn() {
        outcomes.record(new SignedIn(UUID.randomUUID(), AuditMfaFactor.IDP_ATTESTED), SESSION);

        assertThat(audit.recorded()).isEmpty();
    }

    // ---- refused ------------------------------------------------------------------------------

    @Test
    void aRefusalIsAuditedWithItsReasonAndSubjectUnderSso() {
        UUID refused = UUID.randomUUID();

        outcomes.record(Refused.account(refused, EpicLoginFailureReason.ACCOUNT_LOCKED), SESSION);

        assertThat(audit.recorded()).containsExactly(new RecordingAuditTrail.Recorded(
                AuditOperation.LOGIN_FAILURE, null, refused, "ACCOUNT_LOCKED"));
        assertThat(audit.loginMethods()).containsExactly(AuditLoginMethod.SSO);
    }

    /**
     * ADR 0013, "Audit": an Epic {@code LOGIN_FAILURE} carries its reason spelled as the reason's
     * own name — every reason on the list, so one added to it is held to the same.
     */
    @ParameterizedTest(name = "{0} is audited as {0}")
    @EnumSource(value = EpicLoginFailureReason.class, mode = EnumSource.Mode.EXCLUDE,
            names = "EPIC_UNAVAILABLE")
    void everyRefusalIsAuditedUnderItsOwnName(EpicLoginFailureReason reason) {
        outcomes.record(Refused.because(reason), SESSION);

        assertThat(audit.recorded()).extracting(RecordingAuditTrail.Recorded::detail)
                .containsExactly(reason.name());
    }

    @Test
    void aRefusalIsOneGenericWarnWithNoReasonNoUserAndTheSessionsHash() {
        List<ILoggingEvent> records;
        try (LogContext.Scope colleague = LogContext.userId(UUID.randomUUID())) {
            records = recording(
                    Refused.account(UUID.randomUUID(), EpicLoginFailureReason.ACCOUNT_DISABLED),
                    SESSION);
        }

        assertThat(records).singleElement().satisfies(record -> {
            assertThat(record.getLevel()).isEqualTo(Level.WARN);
            assertThat(record.getFormattedMessage()).isEqualTo("Epic sign-in refused");
            assertThat(record.getMDCPropertyMap()).doesNotContainKey(LogContext.USER_ID);
            assertThat(CapturedLog.fields(record))
                    .containsEntry(LogEvent.LOGIN_METHOD, "sso")
                    .containsEntry(LogEvent.SESSION_HASH, SessionHash.of(SESSION))
                    .doesNotContainKey(LogEvent.REASON);
        });
        assertThat(counts.moved()).containsExactly("refused:ACCOUNT_DISABLED");
    }

    @Test
    void aRefusedInputNamesItsFieldAndRule() {
        List<ILoggingEvent> records = recording(Refused.input(EpicLoginFailureReason.INVALID_LAUNCH,
                EpicInputField.LAUNCH, EpicInputRule.LENGTH), SESSION);

        assertThat(CapturedLog.fields(records.getFirst()))
                .containsEntry(LogEvent.EPIC_INPUT_FIELD, "launch")
                .containsEntry(LogEvent.EPIC_INPUT_RULE, "length");
    }

    /** A refusal of nothing in particular names no input. */
    @Test
    void aRefusalOfNoInputNamesNone() {
        List<ILoggingEvent> records =
                recording(Refused.because(EpicLoginFailureReason.INVALID_STATE), SESSION);

        assertThat(CapturedLog.fields(records.getFirst()))
                .doesNotContainKeys(LogEvent.EPIC_INPUT_FIELD, LogEvent.EPIC_INPUT_RULE);
    }

    /** A browser with no session is named by nothing, not by a hash of nothing. */
    @Test
    void aRefusalWithNoSessionCarriesNoSessionHash() {
        List<ILoggingEvent> records =
                recording(Refused.because(EpicLoginFailureReason.INVALID_STATE), null);

        assertThat(CapturedLog.fields(records.getFirst())).doesNotContainKey(LogEvent.SESSION_HASH);
    }

    /** {@code invalid_client}: likely a key or a registration, so it needs a person. */
    @Test
    void aRefusedCredentialIsAlsoTheCallsFollowedUpError() {
        List<ILoggingEvent> records = recording(
                Refused.call(EpicLoginFailureReason.TOKEN_EXCHANGE_FAILED, credentialRefused),
                SESSION);

        assertThat(records).extracting(ILoggingEvent::getLevel)
                .containsExactly(Level.ERROR, Level.WARN);
        assertThat(CapturedLog.fields(records.getFirst()))
                .containsEntry(LogEvent.ERROR_CATEGORY, "cert/auth")
                .containsEntry(LogEvent.ERROR_CODE, 401)
                .containsEntry(LogEvent.ERROR_FOLLOW_UP_ACTION, true)
                .containsEntry(LogEvent.EPIC_CALL, "token")
                .containsEntry(LogEvent.SESSION_HASH, SessionHash.of(SESSION));
        assertThat(counts.moved()).containsExactly(
                "failed_call:token:cert/auth", "refused:TOKEN_EXCHANGE_FAILED");
    }

    /** The browser's session is not whom the launch was for, on the call's record too. */
    @Test
    void aFailedCallsErrorNamesNoUserEvenWhenTheBrowserCarriedOne() {
        List<ILoggingEvent> records;
        try (LogContext.Scope colleague = LogContext.userId(UUID.randomUUID())) {
            records = recording(
                    Refused.call(EpicLoginFailureReason.TOKEN_EXCHANGE_FAILED, credentialRefused),
                    SESSION);
        }

        assertThat(records).allSatisfy(record ->
                assertThat(record.getMDCPropertyMap()).doesNotContainKey(LogContext.USER_ID));
    }

    // ---- unavailable --------------------------------------------------------------------------

    @Test
    void anUnavailableLoginIsAuditedAsEpicUnavailableNamingNobody() {
        outcomes.record(new Unavailable(token5xx), SESSION);

        assertThat(audit.recorded()).containsExactly(new RecordingAuditTrail.Recorded(
                AuditOperation.LOGIN_FAILURE, null, null, "EPIC_UNAVAILABLE"));
    }

    @Test
    void anEpic5xxIsOneErrorNeedingNoFollowUp() {
        List<ILoggingEvent> records = recording(new Unavailable(token5xx), SESSION);

        assertThat(records).singleElement().satisfies(record -> {
            assertThat(record.getLevel()).isEqualTo(Level.ERROR);
            assertThat(CapturedLog.fields(record))
                    .containsEntry(LogEvent.ERROR_CATEGORY, "server")
                    .containsEntry(LogEvent.ERROR_CODE, 503)
                    .containsEntry(LogEvent.ERROR_FOLLOW_UP_ACTION, false)
                    .containsEntry(LogEvent.EPIC_CALL, "token");
        });
        assertThat(counts.moved()).containsExactly("failed_call:token:server", "unavailable");
    }

    /**
     * Logging §3.3: a call that got no answer was logged at {@code ERROR} by the outbound
     * interceptor that saw it fail, so the ending logs it no second time.
     */
    @Test
    void aCallThatGotNoAnswerIsNotLoggedASecondTime() {
        assertThat(recording(new Unavailable(tokenTimedOut), SESSION)).isEmpty();
        assertThat(counts.moved()).containsExactly("failed_call:token:network", "unavailable");
    }

    // ---- what an outcome can be ---------------------------------------------------------------

    @Test
    void epicBeingUnavailableIsNoRefusal() {
        assertThatThrownBy(() -> Refused.because(EpicLoginFailureReason.EPIC_UNAVAILABLE))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Refused.call(
                        EpicLoginFailureReason.TOKEN_EXCHANGE_FAILED, token5xx))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /** No outcome is no ending: nothing to record, and a caller's bug rather than a refusal. */
    @Test
    void noOutcomeIsRefusedAsANullPointer() {
        assertThatThrownBy(() -> outcomes.record(null, SESSION))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void anOutcomeLacksNothingItRecords() {
        assertThatThrownBy(() -> new SignedIn(null, AuditMfaFactor.MFA))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new SignedIn(UUID.randomUUID(), null))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> Refused.because(null))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new FailedCall(null, ErrorCategory.DATA, 502))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new FailedCall("token", null, 502))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void aCallEpicAnsweredIsNotEpicUnavailable() {
        assertThatThrownBy(() -> new Unavailable(credentialRefused))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /** The records written under Epic Login's two operations while {@code outcome} is recorded. */
    private List<ILoggingEvent> recording(EpicLoginOutcome outcome, String sessionId) {
        try (CapturedLog captured = CapturedLog.attach()) {
            outcomes.record(outcome, sessionId);
            return captured.withAction(Level.INFO, LogEvent.ACTION, "user-authentication");
        }
    }
}

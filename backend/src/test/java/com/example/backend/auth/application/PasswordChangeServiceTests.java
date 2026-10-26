package com.example.backend.auth.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import com.example.backend.audit.CapturedLog;
import com.example.backend.audit.RecordingAuditTrail;
import com.example.backend.audit.RecordingOperationalAlerts;
import com.example.backend.audit.domain.AuditOperation;
import com.example.backend.auth.InMemoryAccountSessions;
import com.example.backend.auth.LoginStateModules;
import com.example.backend.auth.MutableClock;
import com.example.backend.auth.PendingCommit;
import com.example.backend.auth.config.SecurityConfig;
import com.example.backend.observability.LogEvent;
import com.example.backend.scim.InMemoryScimPasswordHistoryRepository;
import com.example.backend.scim.InMemoryScimUserRepository;
import com.example.backend.scim.InMemoryScimWriteLocks;
import com.example.backend.scim.ScimIdentities;
import com.example.backend.scim.config.ScimPasswordAcceptanceConfig;
import com.example.backend.scim.domain.LockoutPolicy;
import com.example.backend.scim.domain.PasswordAcceptance;
import com.example.backend.scim.domain.PasswordPolicy;
import com.example.backend.scim.domain.ScimLoginState;
import com.example.backend.scim.domain.ScimUser;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * The self-service change against the in-memory directory: what an accepted change writes, and
 * what each refusal leaves behind. The same flow over HTTP, against SQL Server and Redis, is
 * {@code PasswordChangeLifecycleIntegrationTests}.
 */
class PasswordChangeServiceTests {

    private static final String CURRENT = "the-current-password";
    private static final String NEXT = "a-brand-new-passphrase";
    private static final int MAX_ATTEMPTS = 3;

    private final PasswordEncoder encoder = new SecurityConfig().passwordEncoder();
    private final InMemoryScimUserRepository users = new InMemoryScimUserRepository();
    private final InMemoryScimPasswordHistoryRepository history =
            new InMemoryScimPasswordHistoryRepository();
    private final InMemoryAccountSessions accountSessions = new InMemoryAccountSessions();
    private final PendingCommit transaction = new PendingCommit();
    private final RecordingAuditTrail audit = new RecordingAuditTrail();
    private final MutableClock clock = new MutableClock(ScimIdentities.NOW);

    private final SessionRevocationService revocation = new SessionRevocationService(
            accountSessions,
            transaction,
            audit,
            new RecordingOperationalAlerts());
    private final LoginStateService loginState = LoginStateModules.over(
            users, revocation, new LockoutPolicy(MAX_ATTEMPTS), audit, clock);
    private final LoginAttemptService attempts =
            new LoginAttemptService(users, loginState, revocation, audit);

    private final InMemoryScimWriteLocks writeLocks = new InMemoryScimWriteLocks();

    private PasswordChangeService service;
    private ScimUser ada;

    @BeforeEach
    void setUp() {
        service = new PasswordChangeService(
                users,
                writeLocks,
                new PasswordAcceptance(history, ScimPasswordAcceptanceConfig.hasher(encoder)),
                encoder,
                attempts,
                loginState,
                revocation,
                audit);
        ada = users.given(ScimIdentities.userWithLoginState("ada", new ScimLoginState(
                encoder.encode(CURRENT), 1, null, null, ScimIdentities.NOW)));
        accountSessions.open(ada.id(), "submitting-session");
        accountSessions.open(ada.id(), "other-session");
        clock.advanceBy(Duration.ofDays(1));
    }

    /** The change advances the User's version alone, so the User is the one lock it asks for. */
    @Test
    void aChangeLocksTheUserAloneThroughTheLockModule() {
        service.changePassword(ada.id(), CURRENT, NEXT);

        assertThat(writeLocks.requests()).containsExactly(Set.of(ada.id()));
    }

    @Test
    void anAcceptedChangeHashesClearsTheFlagAdvancesTheVersionAndRevokesEverySessionAfterCommit() {
        service.changePassword(ada.id(), CURRENT, NEXT);

        ScimUser after = users.require("ada");
        assertThat(encoder.matches(NEXT, after.login().passwordHash())).isTrue();
        assertThat(after.login().passwordHash()).doesNotContain(NEXT);
        assertThat(after.login().isPasswordChangeRequired()).isFalse();
        assertThat(after.login().failedLoginAttempts())
                .as("a verified current password ends the failure run, as a login does")
                .isZero();
        assertThat(after.version()).isEqualTo(ada.version() + 1);
        assertThat(history.findRecentHashes(ada.id())).containsExactly(after.login().passwordHash());
        assertThat(audit.of(AuditOperation.PASSWORD_CHANGE))
                .singleElement()
                .satisfies(event -> {
                    assertThat(event.actorId()).isEqualTo(ada.id());
                    assertThat(event.subjectId()).isEqualTo(ada.id());
                    assertThat(event.detail()).as("an accepted change carries no reason").isNull();
                });

        assertThat(accountSessions.sessionsOf(ada.id()))
                .as("revocation waits for the commit")
                .hasSize(2);
        transaction.commit();
        assertThat(accountSessions.sessionsOf(ada.id()))
                .as("every session ends, the submitter's included")
                .isEmpty();
        assertThat(audit.of(AuditOperation.USER_SESSIONS_REVOKE))
                .singleElement()
                .extracting(RecordingAuditTrail.Recorded::detail)
                .isEqualTo("SUCCESS:PASSWORD:PASSWORD_CHANGED");
    }

    /**
     * The completed change is use of the account — for a User that owed it, the first, since its
     * confined logins did not move the dormancy basis — so it records the authentication as of the
     * change. A refused change records none: see the refusal tests below.
     */
    @Test
    void anAcceptedChangeMovesTheDormancyBasis() {
        assertThat(ada.login().lastAuthenticatedAt()).isNull();

        service.changePassword(ada.id(), CURRENT, NEXT);

        assertThat(users.require("ada").login().lastAuthenticatedAt()).isEqualTo(clock.instant());
    }

    @Test
    void aRefusedChangeLeavesTheDormancyBasisAlone() {
        assertThatThrownBy(() -> service.changePassword(ada.id(), "not-the-password", NEXT))
                .isInstanceOf(CurrentPasswordRejectedException.class);
        assertThatThrownBy(() -> service.changePassword(ada.id(), CURRENT, "short"))
                .isInstanceOf(PasswordPolicyViolationException.class);

        assertThat(users.require("ada").login().lastAuthenticatedAt()).isNull();
    }

    @Test
    void aWrongCurrentPasswordIsRefusedAndCountedTowardTheLoginLockout() {
        assertThatThrownBy(() -> service.changePassword(ada.id(), "not-the-password", NEXT))
                .isInstanceOf(CurrentPasswordRejectedException.class);

        ScimUser after = users.require("ada");
        assertThat(after.login().failedLoginAttempts()).isEqualTo(2);
        assertThat(after.login().passwordHash()).isEqualTo(ada.login().passwordHash());
        assertThat(after.login().isPasswordChangeRequired()).isTrue();
        assertThat(audit.of(AuditOperation.PASSWORD_CHANGE))
                .extracting(RecordingAuditTrail.Recorded::detail)
                .containsExactly("BAD_CURRENT_PASSWORD");
    }

    /** The history remembers the new password as of the instant the change was completed at. */
    @Test
    void anAcceptedChangeIsRememberedAsOfTheInstantItWasCompleted() {
        service.changePassword(ada.id(), CURRENT, NEXT);

        assertThat(history.findRecentSetAts(ada.id())).containsExactly(clock.instant());
    }

    // ---- the paths each row names --------------------------------------------------------------

    /** A completed change names the credential it replaced and the flag it cleared. */
    @Test
    void anAcceptedChangeOfAUserOwingOneNamesTheCredentialAndTheFlagItCleared() {
        service.changePassword(ada.id(), CURRENT, NEXT);

        assertThat(audit.changedPaths(AuditOperation.PASSWORD_CHANGE))
                .containsExactly(List.of("password", "passwordChangeRequiredSince"));
    }

    /** A User owing no change has no flag for the completion to clear, so none is named. */
    @Test
    void anAcceptedChangeOfAUserOwingNoneNamesTheCredentialAlone() {
        ScimUser bob = users.given(ScimIdentities.userWithLoginState("bob", new ScimLoginState(
                encoder.encode(CURRENT), 0, null, null, ScimIdentities.NOW, null)));

        service.changePassword(bob.id(), CURRENT, NEXT);

        assertThat(audit.changedPaths(AuditOperation.PASSWORD_CHANGE))
                .containsExactly(List.of("password"));
    }

    /**
     * A wrong current password lengthens the failure run, so its refusal names the run; a refusal
     * decided before any comparison — here the policy's — moves nothing and names nothing.
     */
    @Test
    void aWrongCurrentPasswordsRefusalNamesTheRunItLengthenedAndAPolicyRefusalNothing() {
        assertThatThrownBy(() -> service.changePassword(ada.id(), "not-the-password", NEXT))
                .isInstanceOf(CurrentPasswordRejectedException.class);
        assertThatThrownBy(() -> service.changePassword(ada.id(), CURRENT, "short"))
                .isInstanceOf(PasswordPolicyViolationException.class);

        assertThat(audit.changedPaths(AuditOperation.PASSWORD_CHANGE))
                .containsExactly(List.of("failedLoginAttempts"), List.of());
    }

    /**
     * The change endpoint's own half of the Lockout: its wrong current passwords count into the
     * login run, and once that locks the User the endpoint refuses even the correct password as
     * {@code ACCOUNT_LOCKED}, without counting it. What imposing the Lockout implies — its audit
     * and the revocation of every Session — is {@code LoginStateServiceTests}'.
     */
    @Test
    void atTheThresholdTheUserLocksAndEvenTheCorrectPasswordIsRefused() {
        assertThatThrownBy(() -> service.changePassword(ada.id(), "wrong-once", NEXT))
                .isInstanceOf(CurrentPasswordRejectedException.class);
        assertThatThrownBy(() -> service.changePassword(ada.id(), "wrong-twice", NEXT))
                .isInstanceOf(CurrentPasswordRejectedException.class);

        assertThat(users.require("ada").login().isLocked()).isTrue();

        audit.reset();
        assertThatThrownBy(() -> service.changePassword(ada.id(), CURRENT, NEXT))
                .as("nothing lifts the lock but an Admin's Unlock")
                .isInstanceOf(CurrentPasswordRejectedException.class);
        clock.advanceBy(Duration.ofDays(365));
        assertThatThrownBy(() -> service.changePassword(ada.id(), CURRENT, NEXT))
                .as("and no passage of time does")
                .isInstanceOf(CurrentPasswordRejectedException.class);

        ScimUser after = users.require("ada");
        assertThat(encoder.matches(CURRENT, after.login().passwordHash())).isTrue();
        assertThat(after.login().failedLoginAttempts())
                .as("attempts against a lock are not counted")
                .isEqualTo(MAX_ATTEMPTS);
        assertThat(audit.of(AuditOperation.PASSWORD_CHANGE))
                .extracting(RecordingAuditTrail.Recorded::detail)
                .containsExactly("ACCOUNT_LOCKED", "ACCOUNT_LOCKED");
    }

    @Test
    void anInactiveUserIsRefusedWithoutAComparison() {
        ScimUser bob = users.given(ScimIdentities.inactiveUser("bob"));

        assertThatThrownBy(() -> service.changePassword(bob.id(), "anything-at-all", NEXT))
                .isInstanceOf(CurrentPasswordRejectedException.class);

        assertThat(users.require("bob").login().failedLoginAttempts()).isZero();
        assertThat(audit.of(AuditOperation.PASSWORD_CHANGE))
                .extracting(RecordingAuditTrail.Recorded::detail)
                .containsExactly("ACCOUNT_DISABLED");
    }

    /** Locked and deactivated both: locked first, as a Login refuses the same User. */
    @Test
    void aUserBothLockedAndInactiveIsRefusedAsLocked() {
        ScimUser bob = users.given(ScimIdentities.withActive(ScimIdentities.user("bob"), false,
                new ScimLoginState("hash", MAX_ATTEMPTS, clock.instant())));

        assertThatThrownBy(() -> service.changePassword(bob.id(), "anything-at-all", NEXT))
                .isInstanceOf(CurrentPasswordRejectedException.class);

        assertThat(audit.of(AuditOperation.PASSWORD_CHANGE))
                .extracting(RecordingAuditTrail.Recorded::detail)
                .containsExactly("ACCOUNT_LOCKED");
    }

    @Test
    void anUnknownUserIsRefused() {
        assertThatThrownBy(() -> service.changePassword(java.util.UUID.randomUUID(), CURRENT, NEXT))
                .isInstanceOf(CurrentPasswordRejectedException.class);
        assertThat(audit.recorded()).isEmpty();
    }

    @Test
    void anAcceptedChangeIsStampedWithTheClockAndWritesTheCredentialOnceWithoutAFailureRun() {
        ScimUser bob = users.given(ScimIdentities.userWithLoginState("bob", new ScimLoginState(
                encoder.encode(CURRENT), 0, null, null, ScimIdentities.NOW)));
        int writesBefore = users.writes();

        service.changePassword(bob.id(), CURRENT, NEXT);

        assertThat(users.require("bob").lastModifiedAt()).isEqualTo(clock.instant());
        assertThat(users.writes())
                .as("no failure run to clear, so only the credential write")
                .isEqualTo(writesBefore + 1);
    }

    @Test
    void aUserWithNoCredentialIsRefusedAsABadCurrentPassword() {
        ScimUser carol = users.given(ScimIdentities.credentiallessUser("carol"));

        assertThatThrownBy(() -> service.changePassword(carol.id(), "anything-at-all", NEXT))
                .isInstanceOf(CurrentPasswordRejectedException.class);

        assertThat(users.require("carol").login().hasPassword()).isFalse();
        assertThat(users.require("carol").login().failedLoginAttempts())
                .as("there is no password to guess, so the run does not grow")
                .isZero();
        assertThat(audit.of(AuditOperation.PASSWORD_CHANGE))
                .extracting(RecordingAuditTrail.Recorded::detail)
                .containsExactly("BAD_CURRENT_PASSWORD");
    }

    /**
     * As on the Login path: a User who signs in only through Epic cannot be locked out by
     * submitting the change, however often.
     */
    @Test
    void aUserWithNoCredentialIsNeverLockedOutByRepeatedChanges() {
        ScimUser carol = users.given(ScimIdentities.credentiallessUser("carol"));
        accountSessions.open(carol.id(), "epic-session");

        for (int attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
            assertThatThrownBy(() -> service.changePassword(carol.id(), "anything-at-all", NEXT))
                    .isInstanceOf(CurrentPasswordRejectedException.class);
        }
        transaction.commit();

        assertThat(users.require("carol").login().isLocked()).isFalse();
        assertThat(accountSessions.sessionsOf(carol.id())).containsExactly("epic-session");
    }

    /**
     * A credentialless User is refused by its missing credential, not by the encoder's handling of
     * a null hash: even an encoder that would verify anything against it never gets the chance to
     * accept the change.
     */
    @Test
    void aUserWithNoCredentialIsRefusedEvenByAnEncoderThatWouldVerifyAnything() {
        PasswordEncoder verifiesAnything = new PasswordEncoder() {
            @Override
            public String encode(CharSequence rawPassword) {
                return encoder.encode(rawPassword);
            }

            @Override
            public boolean matches(CharSequence rawPassword, String encodedPassword) {
                return true;
            }
        };
        PasswordChangeService permissive = new PasswordChangeService(
                users,
                new InMemoryScimWriteLocks(),
                new PasswordAcceptance(history, ScimPasswordAcceptanceConfig.hasher(encoder)),
                verifiesAnything,
                attempts,
                loginState,
                revocation,
                audit);
        ScimUser carol = users.given(ScimIdentities.credentiallessUser("carol"));

        assertThatThrownBy(() -> permissive.changePassword(carol.id(), "anything-at-all", NEXT))
                .isInstanceOf(CurrentPasswordRejectedException.class);

        assertThat(users.require("carol").login().hasPassword()).isFalse();
        assertThat(history.findRecentHashes(carol.id())).isEmpty();
        assertThat(audit.of(AuditOperation.PASSWORD_CHANGE))
                .extracting(RecordingAuditTrail.Recorded::detail)
                .containsExactly("BAD_CURRENT_PASSWORD");
    }

    @Test
    void aStandingRefusalIsAttributedToTheUser() {
        ScimUser bob = users.given(ScimIdentities.inactiveUser("bob"));
        ScimUser dan = users.given(ScimIdentities.userWithLoginState("dan", new ScimLoginState(
                encoder.encode(CURRENT), MAX_ATTEMPTS, ScimIdentities.NOW, null, null)));

        assertThatThrownBy(() -> service.changePassword(bob.id(), CURRENT, NEXT))
                .isInstanceOf(CurrentPasswordRejectedException.class);
        assertThatThrownBy(() -> service.changePassword(dan.id(), CURRENT, NEXT))
                .isInstanceOf(CurrentPasswordRejectedException.class);

        assertThat(audit.of(AuditOperation.PASSWORD_CHANGE))
                .extracting(RecordingAuditTrail.Recorded::subjectId, RecordingAuditTrail.Recorded::detail)
                .containsExactly(
                        tuple(bob.id(), "ACCOUNT_DISABLED"),
                        tuple(dan.id(), "ACCOUNT_LOCKED"));
    }

    /**
     * Every refusal, and the accepted change, is one record in the standard's vocabulary,
     * the refusals by their closed-set reason.
     */
    @Test
    void eachOutcomeIsOneClassifiedRecordNamingTheRefusalReason() {
        ScimUser bob = users.given(ScimIdentities.inactiveUser("bob"));
        ScimUser dan = users.given(ScimIdentities.userWithLoginState("dan", new ScimLoginState(
                encoder.encode(CURRENT), MAX_ATTEMPTS, ScimIdentities.NOW, null, null)));

        try (CapturedLog captured = CapturedLog.attach()) {
            assertThatThrownBy(() -> service.changePassword(bob.id(), CURRENT, NEXT))
                    .isInstanceOf(CurrentPasswordRejectedException.class);
            assertThatThrownBy(() -> service.changePassword(dan.id(), CURRENT, NEXT))
                    .isInstanceOf(CurrentPasswordRejectedException.class);
            assertThatThrownBy(() -> service.changePassword(ada.id(), "not-the-password", NEXT))
                    .isInstanceOf(CurrentPasswordRejectedException.class);
            service.changePassword(ada.id(), CURRENT, NEXT);

            List<ILoggingEvent> records = captured.withAction(
                    Level.INFO, LogEvent.LOCAL_ACTION, "identity.password_change");
            assertThat(records)
                    .extracting(ILoggingEvent::getLevel,
                            record -> CapturedLog.fields(record).get(LogEvent.REASON),
                            record -> CapturedLog.fields(record).get(LogEvent.TYPE))
                    .containsExactly(
                            tuple(Level.WARN, "ACCOUNT_DISABLED", List.of("user", "denied")),
                            tuple(Level.WARN, "ACCOUNT_LOCKED", List.of("user", "denied")),
                            tuple(Level.WARN, "BAD_CURRENT_PASSWORD", List.of("user", "denied")),
                            tuple(Level.INFO, null, List.of("user", "change")));
            assertThat(records).allSatisfy(record -> assertThat(CapturedLog.fields(record))
                    .containsEntry(LogEvent.ACTION, "user-administration")
                    .containsEntry(LogEvent.CATEGORY, List.of("process")));
        }
    }

    @Test
    void aTooShortPasswordIsRefusedNamingTheRuleWithoutEitherValue() {
        assertPolicyRefusal("short-pass1", PasswordPolicy.Rule.TOO_SHORT, "TOO_SHORT");
    }

    @Test
    void aPasswordContainingTheUserNameIsRefusedNamingTheRule() {
        assertPolicyRefusal("my-name-is-ADA-okay", PasswordPolicy.Rule.CONTAINS_USER_NAME,
                "CONTAINS_USER_NAME");
    }

    @Test
    void aRetainedPreviousPasswordIsRefusedAsReused() {
        String previous = "an-older-passphrase";
        history.record(ada.id(), encoder.encode(previous), ScimIdentities.NOW);

        assertPolicyRefusal(previous, PasswordPolicy.Rule.REUSED, "REUSED");
    }

    private void assertPolicyRefusal(String candidate, PasswordPolicy.Rule rule, String reason) {
        List<String> historyBefore = history.findRecentHashes(ada.id());
        try (CapturedLog captured = CapturedLog.attach()) {
            assertThatThrownBy(() -> service.changePassword(ada.id(), CURRENT, candidate))
                    .isInstanceOfSatisfying(PasswordPolicyViolationException.class, refused -> {
                        assertThat(refused.ruleName()).isEqualTo(rule.name());
                        assertThat(refused.getMessage())
                                .isEqualTo(rule.message())
                                .doesNotContain(candidate)
                                .doesNotContain(CURRENT);
                    });
            assertThat(captured.withAction(
                            Level.INFO, LogEvent.LOCAL_ACTION, "identity.password_change"))
                    .as("the refusal is logged by its rule")
                    .singleElement()
                    .satisfies(record -> {
                        assertThat(record.getLevel()).isEqualTo(Level.WARN);
                        assertThat(CapturedLog.fields(record)).containsEntry(LogEvent.REASON, reason);
                    });
        }

        ScimUser after = users.require("ada");
        assertThat(after.login().passwordHash()).isEqualTo(ada.login().passwordHash());
        assertThat(after.login().isPasswordChangeRequired()).isTrue();
        assertThat(after.version()).isEqualTo(ada.version());
        assertThat(after.login().failedLoginAttempts())
                .as("a policy violation is not a credential failure")
                .isEqualTo(1);
        assertThat(audit.of(AuditOperation.PASSWORD_CHANGE))
                .singleElement()
                .satisfies(event -> {
                    assertThat(event.detail()).isEqualTo(reason);
                    assertThat(event.subjectId()).isEqualTo(ada.id());
                });
        assertThat(history.findRecentHashes(ada.id()))
                .as("a refused candidate advances no history")
                .isEqualTo(historyBefore);
        transaction.commit();
        assertThat(accountSessions.sessionsOf(ada.id())).hasSize(2);
    }

    /**
     * The accepted hash is remembered only after the credential write lands: a write that finds no
     * User refuses the change, and the history, the audit trail and the sessions are untouched.
     */
    @Test
    void anAcceptedPasswordWhoseCredentialWriteFindsNoUserIsNeitherRememberedNorAudited() {
        List<String> historyBefore = history.findRecentHashes(ada.id());
        users.vanishBeforeNextPasswordChange(ada.id());

        assertThatThrownBy(() -> service.changePassword(ada.id(), CURRENT, NEXT))
                .isInstanceOf(CurrentPasswordRejectedException.class);

        assertThat(history.findRecentHashes(ada.id())).isEqualTo(historyBefore);
        assertThat(audit.of(AuditOperation.PASSWORD_CHANGE)).isEmpty();
        transaction.commit();
        assertThat(accountSessions.sessionsOf(ada.id())).hasSize(2);
    }

    /**
     * A wrong current password is a credential failure and nothing else: it never reaches the new
     * password's acceptance, so even a new value that would break a rule is not judged, named or
     * hashed, and the history does not move.
     */
    @Test
    void aWrongCurrentPasswordNeverReachesNewPasswordAcceptance() {
        history.record(ada.id(), encoder.encode(CURRENT), ScimIdentities.NOW);
        List<String> historyBefore = history.findRecentHashes(ada.id());

        assertThatThrownBy(() -> service.changePassword(ada.id(), "not-the-password", "short"))
                .isInstanceOf(CurrentPasswordRejectedException.class);

        assertThat(users.require("ada").login().failedLoginAttempts()).isEqualTo(2);
        assertThat(history.findRecentHashes(ada.id())).isEqualTo(historyBefore);
        assertThat(audit.of(AuditOperation.PASSWORD_CHANGE))
                .extracting(RecordingAuditTrail.Recorded::detail)
                .containsExactly("BAD_CURRENT_PASSWORD");
    }
}

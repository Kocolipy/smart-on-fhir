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
import com.example.backend.auth.MutableClock;
import com.example.backend.auth.PendingCommit;
import com.example.backend.auth.config.SecurityConfig;
import com.example.backend.observability.LogEvent;
import com.example.backend.scim.InMemoryScimPasswordHistoryRepository;
import com.example.backend.scim.InMemoryScimUserRepository;
import com.example.backend.scim.ScimIdentities;
import com.example.backend.scim.config.ScimPasswordAcceptanceConfig;
import com.example.backend.scim.domain.LockoutPolicy;
import com.example.backend.scim.domain.PasswordAcceptance;
import com.example.backend.scim.domain.PasswordHistoryPolicy;
import com.example.backend.scim.domain.PasswordPolicy;
import com.example.backend.scim.domain.ScimLoginState;
import com.example.backend.scim.domain.ScimUser;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * The self-service change against the in-memory directory: what an accepted change writes, and
 * what each refusal leaves behind. The same flow over HTTP, against Postgres and Redis, is
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

    private PasswordChangeService service;
    private ScimUser ada;

    @BeforeEach
    void setUp() {
        LoginAttemptService attempts = new LoginAttemptService(
                users,
                new SessionRevocationService(
                        accountSessions,
                        transaction,
                        audit,
                        new RecordingOperationalAlerts()),
                new LockoutPolicy(MAX_ATTEMPTS),
                audit,
                clock);
        service = new PasswordChangeService(
                users,
                new PasswordAcceptance(history, ScimPasswordAcceptanceConfig.hasher(encoder)),
                encoder,
                attempts,
                new SessionRevocationService(
                        accountSessions,
                        transaction,
                        audit,
                        new RecordingOperationalAlerts()),
                audit,
                clock);
        ada = users.given(ScimIdentities.userWithLoginState("ada", new ScimLoginState(
                encoder.encode(CURRENT), 1, null, null, ScimIdentities.NOW)));
        accountSessions.open(ada.id(), "submitting-session");
        accountSessions.open(ada.id(), "other-session");
        clock.advanceBy(Duration.ofDays(1));
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

    /**
     * The change endpoint's own half of the Lockout: its wrong current passwords count into the
     * login run, and once that locks the User the endpoint refuses even the correct password as
     * {@code ACCOUNT_LOCKED}, without counting it. What imposing the Lockout implies — its audit
     * and the revocation of every Session — is {@code FailureCounterTests}'.
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
        assertThat(audit.of(AuditOperation.PASSWORD_CHANGE))
                .extracting(RecordingAuditTrail.Recorded::detail)
                .containsExactly("BAD_CURRENT_PASSWORD");
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
                new PasswordAcceptance(history, ScimPasswordAcceptanceConfig.hasher(encoder)),
                verifiesAnything,
                new LoginAttemptService(
                        users,
                        new SessionRevocationService(
                                accountSessions,
                                transaction,
                                audit,
                                new RecordingOperationalAlerts()),
                        new LockoutPolicy(MAX_ATTEMPTS),
                        audit,
                        clock),
                new SessionRevocationService(
                        accountSessions,
                        transaction,
                        audit,
                        new RecordingOperationalAlerts()),
                audit,
                clock);
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
    void aTooLongPasswordIsRefusedNamingTheRule() {
        assertPolicyRefusal("x".repeat(PasswordPolicy.MAX_LENGTH + 1),
                PasswordPolicy.Rule.TOO_LONG, "TOO_LONG");
    }

    @Test
    void aPasswordContainingTheUserNameIsRefusedNamingTheRule() {
        assertPolicyRefusal("my-name-is-ADA-okay", PasswordPolicy.Rule.CONTAINS_USER_NAME,
                "CONTAINS_USER_NAME");
    }

    /**
     * The intrinsic rules come before reuse — the order SCIM writes apply too. A retained value
     * that is also too short is refused for its length.
     */
    @Test
    void aValueBothSubPolicyAndReusedIsRefusedByThePolicyFirst() {
        history.record(ada.id(), encoder.encode("old-short"), ScimIdentities.NOW);

        assertPolicyRefusal("old-short", PasswordPolicy.Rule.TOO_SHORT, "TOO_SHORT");
    }

    @Test
    void theCurrentPasswordIsRefusedAsReused() {
        assertPolicyRefusal(CURRENT, PasswordPolicy.Rule.REUSED, "REUSED");
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

    /**
     * The accepted hash is the stored credential and is remembered exactly once, as the newest of
     * the retained three: the oldest ages out, and the replaced current password stays refused.
     */
    @Test
    void anAcceptedChangeIsRememberedOnceAsTheNewestOfTheRetainedThree() {
        String older = "an-older-passphrase";
        String oldest = "the-oldest-passphrase";
        history.record(ada.id(), encoder.encode(oldest), ScimIdentities.NOW);
        history.record(ada.id(), encoder.encode(older), ScimIdentities.NOW);
        history.record(ada.id(), ada.login().passwordHash(), ScimIdentities.NOW);

        service.changePassword(ada.id(), CURRENT, NEXT);

        String stored = users.require("ada").login().passwordHash();
        assertThat(history.findRecentHashes(ada.id()))
                .hasSize(PasswordHistoryPolicy.RETAINED)
                .startsWith(stored, ada.login().passwordHash())
                .doesNotHaveDuplicates();
        assertThatThrownBy(() -> service.changePassword(ada.id(), NEXT, CURRENT))
                .isInstanceOfSatisfying(PasswordPolicyViolationException.class, refused ->
                        assertThat(refused.ruleName()).isEqualTo("REUSED"));
        service.changePassword(ada.id(), NEXT, oldest);
        assertThat(encoder.matches(oldest, users.require("ada").login().passwordHash())).isTrue();
    }

    /** "é" precomposed and decomposed are one password, so the second spelling is a reuse. */
    @Test
    void aCanonicallyEquivalentSpellingOfTheCurrentPasswordIsRefusedAsReused() {
        String composed = "caf\u00e9-au-lait-2026";
        service.changePassword(ada.id(), CURRENT, composed);

        assertThatThrownBy(() -> service.changePassword(ada.id(), composed, "cafe\u0301-au-lait-2026"))
                .isInstanceOfSatisfying(PasswordPolicyViolationException.class, refused ->
                        assertThat(refused.ruleName()).isEqualTo("REUSED"));
    }
}

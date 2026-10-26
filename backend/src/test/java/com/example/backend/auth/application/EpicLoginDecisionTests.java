package com.example.backend.auth.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import com.example.backend.audit.CapturedLog;
import com.example.backend.audit.RecordingAuditTrail;
import com.example.backend.audit.RecordingOperationalAlerts;
import com.example.backend.audit.domain.AuditLoginMethod;
import com.example.backend.audit.domain.AuditMfaFactor;
import com.example.backend.audit.domain.AuditOperation;
import com.example.backend.auth.InMemoryAccountSessions;
import com.example.backend.auth.MutableClock;
import com.example.backend.auth.PendingCommit;
import com.example.backend.auth.RecordingLoginCounts;
import com.example.backend.auth.config.SecurityConfig;
import com.example.backend.auth.application.LoginService.LoginDecision;
import com.example.backend.auth.domain.EpicLoginFailureReason;
import com.example.backend.authorization.TestGroupPermissions;
import com.example.backend.observability.LogEvent;
import com.example.backend.observability.SessionHash;
import com.example.backend.scim.InMemoryScimGroupRepository;
import com.example.backend.scim.InMemoryScimUserRepository;
import com.example.backend.scim.InMemoryScimWriteLocks;
import com.example.backend.scim.ScimIdentities;
import com.example.backend.scim.domain.LockCause;
import com.example.backend.scim.domain.LockoutPolicy;
import com.example.backend.scim.domain.ReservedResourceName;
import com.example.backend.scim.domain.ScimLoginState;
import com.example.backend.scim.domain.ScimUser;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * The login decision for an Epic Login ({@link LoginService#logInFromEpic}), over the real
 * login path wired by hand as {@code LoginLockoutTests} wires it.
 *
 * <p>Epic proves who the clinician is, but Lockout and deactivation are this service's own, and
 * apply to an Epic Login exactly as to a password one (D12): Epic's word does not unlock or
 * reactivate anybody.
 */
class EpicLoginDecisionTests {

    private static final Instant NOW = Instant.parse("2026-09-24T07:00:00Z");

    private final InMemoryScimUserRepository users = new InMemoryScimUserRepository();
    private final InMemoryScimGroupRepository groups = new InMemoryScimGroupRepository(users);
    private final RecordingAuditTrail audit = new RecordingAuditTrail();
    private final RecordingLoginCounts counts = new RecordingLoginCounts();
    private final InMemoryScimWriteLocks locks = new InMemoryScimWriteLocks();

    private LoginService login;

    @BeforeEach
    void setUp() {
        SecurityConfig config = new SecurityConfig();
        PasswordEncoder passwordEncoder = config.passwordEncoder();
        LoginIdentityService identities = new LoginIdentityService(
                users, groups, TestGroupPermissions.superuserOnly());
        SessionRevocationService revocation = new SessionRevocationService(
                new InMemoryAccountSessions(),
                new PendingCommit(),
                audit,
                new RecordingOperationalAlerts());
        LoginAttemptService attempts = new LoginAttemptService(
                users,
                new LoginStateService(users, locks, revocation, new LockoutPolicy(5), audit,
                        new MutableClock(NOW)),
                revocation,
                audit);
        login = new LoginService(
                config.authenticationManager(identities, passwordEncoder),
                attempts,
                identities,
                new LoginOutcomeService(attempts, audit, counts, counts));
    }

    @Test
    void aPractitionerIdLinkedToNoUserIsRefusedAsAnUnknownAccount() {
        assertThat(refusalOf("eNOBODY")).isEqualTo(EpicLoginFailureReason.UNKNOWN_ACCOUNT);
    }

    @Test
    void anAcceptedEpicLoginCarriesTheAuthoritiesOfAUserInNoMappedGroup() {
        users.given(ScimIdentities.user("eACTIVE"));

        assertThat(attested("eACTIVE").authentication().getAuthorities())
                .extracting(GrantedAuthority::getAuthority)
                .containsExactlyInAnyOrder("ROLE_USER", "patient:read");
    }

    /** As for a password Login, the session's authentication never carries the stored hash. */
    @Test
    void anAcceptedEpicLoginCarriesNoPasswordHash() {
        users.given(ScimIdentities.user("eACTIVE"));

        Object principal = attested("eACTIVE").authentication().getPrincipal();

        assertThat(((UserDetails) principal).getPassword()).isNull();
    }

    /** D17: the factor the Login was made with is on its {@code LOGIN_SUCCESS}. */
    @Test
    void anAcceptedEpicLoginRecordsItsMfaFactorOnTheLoginSuccess() {
        users.given(ScimIdentities.user("eACTIVE"));

        login.logInFromEpic("eACTIVE", null, AuditMfaFactor.OTP);

        assertThat(audit.mfaFactors()).containsExactly(AuditMfaFactor.OTP);
    }

    /** D3: normalization lowercases, but Epic IDs are case-sensitive. */
    @Test
    void aCaseVariantOfAUsersPractitionerIdIsRefusedAsAnUnknownAccount() {
        users.given(ScimIdentities.user("eABC"));

        assertThat(refusalOf("eabc")).isEqualTo(EpicLoginFailureReason.UNKNOWN_ACCOUNT);
    }

    /** D6: recognised by its reservation marker; password Login is its recovery path. */
    @Test
    void theBootstrapAdminIsRefusedAsAnUnknownAccount() {
        users.createReserved(ScimIdentities.user("eRECOVERY"), ReservedResourceName.BOOTSTRAP_ADMIN);

        assertThat(refusalOf("eRECOVERY")).isEqualTo(EpicLoginFailureReason.UNKNOWN_ACCOUNT);
    }

    @Test
    void aDeactivatedUserIsRefusedAsADisabledAccount() {
        users.given(ScimIdentities.inactiveUser("eRETIRED"));

        assertThat(refusalOf("eRETIRED")).isEqualTo(EpicLoginFailureReason.ACCOUNT_DISABLED);
    }

    @Test
    void aUserLockedByAFailureRunIsRefusedAsALockedAccount() {
        users.given(ScimIdentities.userWithLoginState(
                "eLOCKED", new ScimLoginState("hash", 5, NOW)));

        assertThat(refusalOf("eLOCKED")).isEqualTo(EpicLoginFailureReason.ACCOUNT_LOCKED);
    }

    /** Locked for any cause (flow step 6): dormancy closes Epic Login as it closes password. */
    @Test
    void aUserLockedForDormancyIsRefusedAsALockedAccount() {
        users.given(ScimIdentities.userWithLoginState("eDORMANT",
                new ScimLoginState("hash", 0, NOW, LockCause.DORMANCY, null, null)));

        assertThat(refusalOf("eDORMANT")).isEqualTo(EpicLoginFailureReason.ACCOUNT_LOCKED);
    }

    // ---- a refusal is audited, and never lengthens a failure run (D12) ------------------------

    @Test
    void aRefusedLockedUserIsAuditedAsALoginFailureNamingItAndItsReason() {
        ScimUser locked = users.given(ScimIdentities.userWithLoginState(
                "eLOCKED", new ScimLoginState("hash", 5, NOW)));

        refusalOf("eLOCKED");

        assertThat(audit.recorded()).containsExactly(new RecordingAuditTrail.Recorded(
                AuditOperation.LOGIN_FAILURE, null, locked.id(), "ACCOUNT_LOCKED"));
    }

    @Test
    void aRefusedDeactivatedUserIsAuditedAsALoginFailureNamingItAndItsReason() {
        ScimUser retired = users.given(ScimIdentities.inactiveUser("eRETIRED"));

        refusalOf("eRETIRED");

        assertThat(audit.recorded()).containsExactly(new RecordingAuditTrail.Recorded(
                AuditOperation.LOGIN_FAILURE, null, retired.id(), "ACCOUNT_DISABLED"));
    }

    /**
     * An unknown ID is not recorded: not the ID, and not the User a case variant resembles,
     * which the normalized lookup found but the Login did not name.
     */
    @Test
    void anUnknownAccountRefusalIsAuditedNamingNoSubject() {
        users.given(ScimIdentities.user("eABC"));

        refusalOf("eabc");

        assertThat(audit.recorded()).containsExactly(new RecordingAuditTrail.Recorded(
                AuditOperation.LOGIN_FAILURE, null, null, "UNKNOWN_ACCOUNT"));
    }

    @Test
    void aRefusedDeactivatedUserKeepsTheFailureRunItHad() {
        ScimUser retired = users.given(new ScimUser(UUID.randomUUID(),
                ScimIdentities.profile("eRETIRED", false), new ScimLoginState("hash", 2, null),
                null, ScimUser.INITIAL_VERSION, NOW, NOW));

        refusalOf("eRETIRED");

        assertThat(failureRunOf(retired)).isEqualTo(2);
    }

    @Test
    void aRefusedLockedUserKeepsTheFailureRunItHad() {
        ScimUser locked = users.given(ScimIdentities.userWithLoginState(
                "eLOCKED", new ScimLoginState("hash", 5, NOW)));

        refusalOf("eLOCKED");

        assertThat(failureRunOf(locked)).isEqualTo(5);
    }

    /** The User a case variant resembles is the one a counted failure would have landed on. */
    @Test
    void aCaseVariantNeverLengthensTheFailureRunOfTheUserItResembles() {
        ScimUser provisioned = users.given(ScimIdentities.user("eABC"));

        refusalOf("eabc");

        assertThat(failureRunOf(provisioned)).isZero();
    }

    /** Refusals in a row, past the lockout limit, still lock nobody. */
    @Test
    void repeatedRefusalsNeverLockTheUser() {
        ScimUser provisioned = users.given(ScimIdentities.user("eABC"));

        for (int attempt = 0; attempt < 6; attempt++) {
            refusalOf("eabc");
        }

        assertThat(users.findById(provisioned.id()).orElseThrow().login().isLocked()).isFalse();
    }

    // ---- a refusal is logged generically: the reason is audit-only (ADR 0012) ----------------

    /**
     * A refusal is recorded by {@link LoginOutcomeService}, whose tests own what the record says
     * — one generic WARN naming no reason and no user. What the decision adds is what it hands
     * over: an SSO refusal, under its reason, of the session the launch arrived on.
     */
    @Test
    void aRefusalIsRecordedOnceAsAnSsoRefusalOfTheSessionItArrivedOn() {
        users.given(ScimIdentities.inactiveUser("eRETIRED"));

        try (CapturedLog captured = CapturedLog.attach()) {
            login.logInFromEpic("eRETIRED", "launch-session", AuditMfaFactor.IDP_ATTESTED);

            assertThat(captured.withAction(Level.WARN, LogEvent.ACTION, "user-authentication"))
                    .singleElement()
                    .satisfies(record -> assertThat(CapturedLog.fields(record))
                            .containsEntry(LogEvent.LOGIN_METHOD, "sso")
                            .containsEntry(LogEvent.SESSION_HASH, SessionHash.of("launch-session")));
        }
        assertThat(counts.moved()).containsExactly("sso:refused:ACCOUNT_DISABLED");
        assertThat(audit.loginMethods()).containsExactly(AuditLoginMethod.SSO);
    }

    @Test
    void aRefusalsLogRecordNeverNamesThePractitionerId() {
        ILoggingEvent record = refusalRecord("eNOBODY");

        assertThat(record.getFormattedMessage() + CapturedLog.fields(record) + record.getMDCPropertyMap())
                .doesNotContain("eNOBODY");
    }

    /** D17: the decision hands back what the accepted Login is recorded as, factor included. */
    @Test
    void anAcceptedEpicLoginEndsSignedInAsItsUserWithItsMfaFactor() {
        ScimUser active = users.given(ScimIdentities.user("eACTIVE"));

        LoginDecision decision = login.logInFromEpic("eACTIVE", null, AuditMfaFactor.OTP);

        assertThat(decision.outcome())
                .isEqualTo(new LoginOutcome.SignedIn(active.id(), AuditLoginMethod.SSO, AuditMfaFactor.OTP));
    }

    /**
     * An acceptance is recorded by the web adapter once the session is signed in, after this
     * transaction commits: the decision writes no {@code user-authentication} record of it and
     * moves no count.
     */
    @Test
    void anAcceptedEpicLoginIsNotRecordedByTheDecision() {
        users.given(ScimIdentities.user("eACTIVE"));

        try (CapturedLog captured = CapturedLog.attach()) {
            attested("eACTIVE");

            assertThat(captured.withAction(Level.INFO, LogEvent.ACTION, "user-authentication"))
                    .isEmpty();
        }
        assertThat(counts.moved()).isEmpty();
    }

    /** Exactly a signed-in decision carries the Login a session is established from. */
    @Test
    void onlyASignedInDecisionCarriesAnAcceptedLogin() {
        users.given(ScimIdentities.user("eACTIVE"));
        LoginService.AcceptedLogin accepted = attested("eACTIVE");
        LoginOutcome.SignedIn signedIn =
                new LoginOutcome.SignedIn(accepted.userId(), AuditLoginMethod.SSO, AuditMfaFactor.MFA);
        LoginOutcome refused =
                LoginOutcome.EpicRefused.because(EpicLoginFailureReason.UNKNOWN_ACCOUNT);

        assertThatThrownBy(() -> new LoginDecision(signedIn, Optional.empty()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new LoginDecision(refused, Optional.of(accepted)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /** A decision ends in its accepted Login's own outcome, never another User's or method's. */
    @Test
    void aSignedInDecisionCannotEndInAnotherAcceptedLoginsOutcome() {
        users.given(ScimIdentities.user("eACTIVE"));
        LoginService.AcceptedLogin accepted = attested("eACTIVE");
        LoginOutcome.SignedIn otherMethod = LoginOutcome.SignedIn.password(accepted.userId());

        assertThatThrownBy(() -> new LoginDecision(otherMethod, Optional.of(accepted)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /** The accepted Login carries the outcome the decision ended in, for its caller to record. */
    @Test
    void anAcceptedEpicLoginCarriesTheOutcomeItsDecisionEndedIn() {
        ScimUser active = users.given(ScimIdentities.user("eACTIVE"));

        assertThat(attested("eACTIVE").signedIn()).isEqualTo(
                new LoginOutcome.SignedIn(active.id(), AuditLoginMethod.SSO, AuditMfaFactor.IDP_ATTESTED));
    }

    /** The one {@code WARN} authentication record {@code practitionerId}'s refusal wrote. */
    private ILoggingEvent refusalRecord(String practitionerId) {
        try (CapturedLog captured = CapturedLog.attach()) {
            refusalOf(practitionerId);
            List<ILoggingEvent> records =
                    captured.withAction(Level.WARN, LogEvent.ACTION, "user-authentication");
            assertThat(records).as("one refusal record").hasSize(1);
            return records.getFirst();
        }
    }

    /** The failure run {@code user} holds now, read back through the repository. */
    private int failureRunOf(ScimUser user) {
        return users.findById(user.id()).orElseThrow().login().failedLoginAttempts();
    }

    // ---- the User is read once, and named by its stable id afterwards -------------------------

    /**
     * A directory rename landing after the decision read the linked User moves nothing: the
     * accepted Login is still the linked User's, not that of whoever takes the name.
     */
    @Test
    void anAcceptedEpicLoginNamesTheLinkedUserWhenAnotherUserTakesItsName() {
        ScimUser linked = users.given(ScimIdentities.user("eACTIVE"));
        users.afterNextNameLookup(() -> giveTheNameToAnother(linked, "eACTIVE"));

        assertThat(attested("eACTIVE").userId()).isEqualTo(linked.id());
    }

    @Test
    void anAcceptedEpicLoginRecordsItsSuccessForTheLinkedUserWhenAnotherUserTakesItsName() {
        ScimUser linked = users.given(ScimIdentities.user("eACTIVE"));
        users.afterNextNameLookup(() -> giveTheNameToAnother(linked, "eACTIVE"));

        attested("eACTIVE");

        assertThat(audit.of(AuditOperation.LOGIN_SUCCESS))
                .extracting(RecordingAuditTrail.Recorded::subjectId)
                .containsExactly(linked.id());
    }

    @Test
    void aRefusedLockedUserIsAuditedByItsOwnIdWhenAnotherUserTakesItsName() {
        ScimUser locked = users.given(ScimIdentities.userWithLoginState(
                "eLOCKED", new ScimLoginState("hash", 5, NOW)));
        users.afterNextNameLookup(() -> giveTheNameToAnother(locked, "eLOCKED"));

        refusalOf("eLOCKED");

        assertThat(audit.of(AuditOperation.LOGIN_FAILURE))
                .extracting(RecordingAuditTrail.Recorded::subjectId)
                .containsExactly(locked.id());
    }

    /** {@code user} leaves {@code userName}, and another User is provisioned under it. */
    private void giveTheNameToAnother(ScimUser user, String userName) {
        users.rename(user.id(), userName + "-moved");
        users.given(ScimIdentities.user(userName));
    }

    /** The reason {@code practitionerId}'s Epic Login was refused for. */
    // ---- a Lockout or a deactivation landing before the success is recorded ----------------

    /**
     * The User was read unlocked, and a Lockout committed before the success took the User's lock:
     * the Epic Login is refused as a locked account's is, and lifts nothing (ADR 0007).
     */
    @Test
    void aLockoutLandingBeforeTheSuccessIsRecordedRefusesTheEpicLoginAsLocked() {
        ScimUser user = users.given(ScimIdentities.user("eACTIVE"));
        locks.beforeNextGrant(() -> users.given(ScimIdentities.withActive(user, true,
                new ScimLoginState("hash", 5, NOW, LockCause.FAILURES, null, null))));

        assertThat(refusalOf("eACTIVE")).isEqualTo(EpicLoginFailureReason.ACCOUNT_LOCKED);
    }

    @Test
    void aLockoutLandingBeforeTheSuccessIsRecordedStandsAfterTheEpicLogin() {
        ScimUser user = users.given(ScimIdentities.user("eACTIVE"));
        locks.beforeNextGrant(() -> users.given(ScimIdentities.withActive(user, true,
                new ScimLoginState("hash", 5, NOW, LockCause.FAILURES, null, null))));

        login.logInFromEpic("eACTIVE", null, AuditMfaFactor.IDP_ATTESTED);

        assertThat(users.require("eACTIVE").login().isLocked()).isTrue();
    }

    /** Recorded as a locked account's Epic refusal is, naming the User, with no success. */
    @Test
    void anEpicLoginRefusedOnALockoutLandingMidwayIsAuditedAsALockedAccountsRefusal() {
        ScimUser user = users.given(ScimIdentities.user("eACTIVE"));
        locks.beforeNextGrant(() -> users.given(ScimIdentities.withActive(user, true,
                new ScimLoginState("hash", 5, NOW, LockCause.FAILURES, null, null))));

        login.logInFromEpic("eACTIVE", null, AuditMfaFactor.IDP_ATTESTED);

        assertThat(audit.recorded())
                .extracting(RecordingAuditTrail.Recorded::operation,
                        RecordingAuditTrail.Recorded::subjectId)
                .containsExactly(org.assertj.core.groups.Tuple.tuple(
                        AuditOperation.LOGIN_FAILURE, user.id()));
    }

    @Test
    void aDeactivationLandingBeforeTheSuccessIsRecordedRefusesTheEpicLoginAsDisabled() {
        ScimUser user = users.given(ScimIdentities.user("eACTIVE"));
        locks.beforeNextGrant(() ->
                users.given(ScimIdentities.withActive(user, false, user.login())));

        assertThat(refusalOf("eACTIVE")).isEqualTo(EpicLoginFailureReason.ACCOUNT_DISABLED);
    }

    private EpicLoginFailureReason refusalOf(String practitionerId) {
        LoginDecision decision =
                login.logInFromEpic(practitionerId, null, AuditMfaFactor.IDP_ATTESTED);
        assertThat(decision.accepted()).as("the Epic Login was refused").isEmpty();
        assertThat(decision.outcome()).isInstanceOf(LoginOutcome.EpicRefused.class);
        return ((LoginOutcome.EpicRefused) decision.outcome()).reason();
    }

    /** An accepted Epic Login for {@code practitionerId}, its MFA attested by the Epic organisation. */
    private LoginService.AcceptedLogin attested(String practitionerId) {
        return login.logInFromEpic(practitionerId, null, AuditMfaFactor.IDP_ATTESTED)
                .accepted().orElseThrow();
    }
}

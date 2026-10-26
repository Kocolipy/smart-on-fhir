package com.example.backend.auth.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.backend.audit.RecordingAuditTrail;
import com.example.backend.audit.RecordingOperationalAlerts;
import com.example.backend.audit.domain.AuditOperation;
import com.example.backend.auth.InMemoryAccountSessions;
import com.example.backend.auth.LoginStateModules;
import com.example.backend.auth.MutableClock;
import com.example.backend.auth.PendingCommit;
import com.example.backend.auth.RecordingLoginCounts;
import com.example.backend.auth.application.LoginOutcome.PasswordRefused;
import com.example.backend.auth.application.LoginService.LoginDecision;
import com.example.backend.auth.config.SecurityConfig;
import com.example.backend.authorization.TestGroupPermissions;
import com.example.backend.scim.InMemoryScimGroupRepository;
import com.example.backend.scim.InMemoryScimUserRepository;
import com.example.backend.scim.ScimIdentities;
import com.example.backend.scim.domain.LockoutPolicy;
import com.example.backend.scim.domain.NormalizedUserName;
import com.example.backend.scim.domain.ScimUser;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * A password Login whose username begins or ends with whitespace is an unknown account, decided
 * before any lookup (ADR 0013): the same refusal, audit event and count as a name that matches
 * nobody, and nothing recorded against the User the trimmed name would have found.
 *
 * <p>The store here compares names as SQL Server does, trailing spaces ignored, so {@code ada }
 * would find {@code ada} if it were ever looked up: only a store that pads can show that the
 * refusal is decided before the lookup rather than by it.
 */
class PasswordLoginEdgeWhitespaceTests {

    private static final Instant NOW = Instant.parse("2026-10-13T07:00:00Z");

    private static final String CORRECT_PASSWORD = "correct-password";

    private final InMemoryScimUserRepository users = new InMemoryScimUserRepository();
    private final InMemoryScimGroupRepository groups = new InMemoryScimGroupRepository(users);
    private final RecordingAuditTrail audit = new RecordingAuditTrail();
    private final RecordingLoginCounts counts = new RecordingLoginCounts();

    private LoginService login;

    @BeforeEach
    void setUp() {
        users.ignoreTrailingSpacesInNameLookupsAsSqlServerDoes();
        SecurityConfig config = new SecurityConfig();
        PasswordEncoder passwordEncoder = config.passwordEncoder();
        users.given(ScimUser.created(UUID.randomUUID(), ScimIdentities.profile("ada", true),
                passwordEncoder.encode(CORRECT_PASSWORD), ScimIdentities.NOW));

        LoginIdentityService identities = new LoginIdentityService(
                users, groups, TestGroupPermissions.superuserOnly());
        SessionRevocationService revocation = new SessionRevocationService(
                new InMemoryAccountSessions(), new PendingCommit(), audit,
                new RecordingOperationalAlerts());
        LoginAttemptService attempts = new LoginAttemptService(users,
                LoginStateModules.over(
                        users, revocation, new LockoutPolicy(5), audit,
                        new MutableClock(NOW)),
                revocation, audit);
        login = new LoginService(
                config.authenticationManager(identities, passwordEncoder),
                attempts,
                identities,
                new LoginOutcomeService(attempts, audit, counts, counts));
    }

    /** What the store stands in for: a padded name finds the User when it is looked up. */
    @Test
    void theStoreFindsAPaddedNameAsSqlServerWould() {
        assertThat(users.findByNormalizedUserName(NormalizedUserName.of("ada "))).isPresent();
    }

    @ParameterizedTest(name = "[{index}] \"{0}\"")
    @ValueSource(strings = {"ada ", " ada", "ada\t", " ada", "ada　"})
    void aBorderedNameWithTheCorrectPasswordSignsNobodyIn(String username) {
        assertThat(login.logIn(username, CORRECT_PASSWORD).accepted()).isEmpty();
    }

    /**
     * Its audit event and its count are an unknown account's, with no subject. U+3000 is the
     * case NFKC folds into a plain space, so it would pad onto {@code ada} too.
     */
    @ParameterizedTest(name = "[{index}] \"{0}\"")
    @ValueSource(strings = {"ada ", " ada", "ada\t", " ada", "ada　"})
    void aBorderedNameIsAuditedAndCountedAsAnUnknownAccount(String username) {
        login.logIn(username, CORRECT_PASSWORD);

        assertThat(audit.recorded()).containsExactly(new RecordingAuditTrail.Recorded(
                AuditOperation.LOGIN_FAILURE, null, null, "UNKNOWN_ACCOUNT"));
        assertThat(counts.moved()).containsExactly("password:refused:UNKNOWN_ACCOUNT");
    }

    /**
     * The decision the caller gets is the one a name matching nobody gets: a refusal reported
     * as bad credentials, which the web adapter answers with its one bare {@code 401}.
     */
    @Test
    void aBorderedNameIsRefusedAsANameMatchingNobodyIs() {
        LoginDecision nobody = login.logIn("nobody", CORRECT_PASSWORD);
        LoginDecision bordered = login.logIn("ada ", CORRECT_PASSWORD);

        assertThat(bordered.outcome()).isInstanceOfSatisfying(PasswordRefused.class,
                refused -> assertThat(refused.reason())
                        .isEqualTo(((PasswordRefused) nobody.outcome()).reason())
                        .isEqualTo(PasswordRefusalReason.BAD_CREDENTIALS));
    }

    /** Nothing was counted, so its {@code LOGIN_FAILURE} names no changed path. */
    @ParameterizedTest(name = "[{index}] \"{0}\"")
    @ValueSource(strings = {"ada ", " ada", "ada\t", "ada　"})
    void aBorderedNamesRefusalNamesNoChangedPath(String username) {
        login.logIn(username, "wrong");

        assertThat(audit.changedPaths(AuditOperation.LOGIN_FAILURE)).containsExactly(List.of());
    }

    @ParameterizedTest(name = "[{index}] \"{0}\"")
    @ValueSource(strings = {"ada ", "ada　"})
    void aBorderedNameLeavesTheTrimmedNamesFailureRunAlone(String username) {
        login.logIn(username, "wrong");

        assertThat(users.require("ada").login().failedLoginAttempts()).isZero();
    }
}

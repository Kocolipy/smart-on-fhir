package com.example.backend.auth.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.backend.authorization.TestRoleMappings;
import com.example.backend.audit.RecordingAuditTrail;
import com.example.backend.audit.domain.AuditRefusalReason;
import com.example.backend.auth.InMemoryAccountSessions;
import com.example.backend.auth.MutableClock;
import com.example.backend.auth.PendingCommit;
import com.example.backend.auth.RecordingLoginCounts;
import com.example.backend.auth.application.LoginOutcome.PasswordRefused;
import com.example.backend.auth.application.LoginService.LoginDecision;
import com.example.backend.auth.config.SecurityConfig;
import com.example.backend.scim.InMemoryScimGroupRepository;
import com.example.backend.scim.InMemoryScimUserRepository;
import com.example.backend.scim.ScimIdentities;
import com.example.backend.scim.domain.LockoutPolicy;
import com.example.backend.scim.domain.ScimLoginState;
import com.example.backend.scim.domain.ScimUser;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * Structural equivalence of the three refusal categories this ticket names: an unknown
 * userName, a credentialless identity, and a real identity given the wrong password must
 * each drive exactly one real verification against a dummy hash before the refusal —
 * asserted by counting calls into the encoder, not by measuring wall-clock time, which is
 * what the acceptance criteria for this ticket asks for.
 *
 * <p>Counting rather than comparing is load-bearing, not a stylistic choice. The encoder is
 * deterministic, so a marker recomputed on every attempt is byte-identical to a cached one:
 * only the call count can tell the two apart, and only the call count can catch a
 * credentialless identity that started paying nothing.
 *
 * <p>The unknown-userName case is not code this module wrote: it is
 * {@code DaoAuthenticationProvider}'s own built-in
 * {@code prepareTimingAttackProtection}/{@code mitigateAgainstTimingAttack}, which this
 * suite exercises through the real {@link SecurityConfig}-built
 * {@code AuthenticationManager} rather than re-implementing — a duplicate dummy-hash path
 * here would drift from the one actually wired in production the first time either changed
 * independently.
 */
class RefusalTimingEquivalenceTests {

    private static final Instant NOW = Instant.parse("2026-09-24T07:00:00Z");

    private final InMemoryScimUserRepository users = new InMemoryScimUserRepository();
    private final InMemoryScimGroupRepository groups = new InMemoryScimGroupRepository(users);
    private final MutableClock clock = new MutableClock(NOW);

    private CountingPasswordEncoder passwordEncoder;
    private LoginService login;

    @BeforeEach
    void setUp() {
        SecurityConfig config = new SecurityConfig();
        passwordEncoder = new CountingPasswordEncoder(config.passwordEncoder());
        users.given(ScimUser.created(
                UUID.randomUUID(),
                ScimIdentities.profile("ada", true),
                passwordEncoder.encode("correct-password"),
                ScimIdentities.NOW));
        users.given(ScimIdentities.credentiallessUser("nopass"));
        users.given(new ScimUser(
                UUID.randomUUID(),
                ScimIdentities.profile("locked", true),
                new ScimLoginState(passwordEncoder.encode("correct-password"), 5, NOW),
                null,
                ScimUser.INITIAL_VERSION,
                ScimIdentities.NOW,
                ScimIdentities.NOW));
        users.given(ScimUser.created(
                UUID.randomUUID(),
                ScimIdentities.profile("deactivated", false),
                passwordEncoder.encode("correct-password"),
                ScimIdentities.NOW));
        LoginIdentityService identities =
                new LoginIdentityService(users, groups, passwordEncoder, TestRoleMappings.superuserOnly());
        RecordingAuditTrail audit = new RecordingAuditTrail();
        LoginAttemptService attempts = new LoginAttemptService(
                        users,
                        new InMemoryAccountSessions(),
                        new PendingCommit(),
                        new LockoutPolicy(5),
                        audit,
                        clock);
        login = new LoginService(
                config.authenticationManager(identities, passwordEncoder),
                attempts,
                identities,
                RecordingLoginCounts.uncounted(attempts, audit));
    }

    @Test
    void aWrongPasswordOnARealIdentityRunsExactlyOneVerification() {
        passwordEncoder.matchCalls.set(0);

        refuse("ada", "wrong-password");

        assertThat(passwordEncoder.matchCalls).hasValue(1);
    }

    @Test
    void aCredentiallessIdentityRunsExactlyOneVerification() {
        passwordEncoder.matchCalls.set(0);

        refuse("nopass", "anything");

        assertThat(passwordEncoder.matchCalls).hasValue(1);
    }

    @Test
    void anUnknownUsernameRunsExactlyOneVerification() {
        passwordEncoder.matchCalls.set(0);

        refuse("nobody", "anything");

        assertThat(passwordEncoder.matchCalls).hasValue(1);
    }

    /**
     * A locked or deactivated User is refused for its state, not its password, yet pays the
     * same one verification first: a refusal that skipped the comparison would answer faster,
     * and so tell an attacker which accounts are locked or deactivated (ADR 0007).
     */
    @ParameterizedTest(name = "{0} with {1} runs exactly one verification")
    @CsvSource({
        "locked, wrong-password",
        "locked, correct-password",
        "deactivated, wrong-password",
        "deactivated, correct-password"})
    void aLockedOrDeactivatedIdentityRunsExactlyOneVerification(String username, String password) {
        passwordEncoder.matchCalls.set(0);

        refuse(username, password);

        assertThat(passwordEncoder.matchCalls).hasValue(1);
    }

    /** The comparison runs first, but the account's state still refuses the right password. */
    @Test
    void aLockedIdentityIsRefusedAsLockedWhateverThePassword() {
        assertThat(List.of(refuse("locked", "correct-password"), refuse("locked", "wrong-password")))
                .containsOnly(AuditRefusalReason.ACCOUNT_LOCKED);
    }

    /** The comparison runs first, but deactivation still refuses the right password. */
    @Test
    void aDeactivatedIdentityIsRefusedAsDisabledWhateverThePassword() {
        assertThat(List.of(refuse("deactivated", "correct-password"),
                        refuse("deactivated", "wrong-password")))
                .containsOnly(AuditRefusalReason.ACCOUNT_DISABLED);
    }

    /**
     * Not just "one call each" but against a dummy hash of the ticket's required shape:
     * every category compares against an {@code {argon2id}}-prefixed hash, so none of them
     * can be picked out by running a cheaper or differently-shaped comparison. The three
     * dummy hashes are not required to be byte-identical — the unknown-userName path is
     * {@code DaoAuthenticationProvider}'s own cached comparand, encoded once from a
     * different fixed passphrase than the credentialless path's — only that each is a real
     * Argon2id verification at the same parameters.
     */
    @Test
    void allThreeCategoriesCompareAgainstAnArgon2idHash() {
        passwordEncoder.lastEncodedPasswordSeen = null;
        refuse("ada", "wrong-password");
        String realIdentityHash = passwordEncoder.lastEncodedPasswordSeen;

        passwordEncoder.lastEncodedPasswordSeen = null;
        refuse("nopass", "anything");
        String credentiallessHash = passwordEncoder.lastEncodedPasswordSeen;

        passwordEncoder.lastEncodedPasswordSeen = null;
        refuse("nobody", "anything");
        String unknownUsernameHash = passwordEncoder.lastEncodedPasswordSeen;

        assertThat(realIdentityHash).startsWith("{argon2id}");
        assertThat(credentiallessHash).startsWith("{argon2id}");
        assertThat(unknownUsernameHash).startsWith("{argon2id}");
    }

    /**
     * The verification a credentialless identity pays is the same work every time, and the
     * marker behind it is encoded once — Argon2id is deliberately expensive, so recomputing
     * it per attempt would hand an unauthenticated caller a way to spend this service's CPU
     * at will.
     *
     * <p>Both halves are asserted from the ENCODER's counters, because that is the only
     * place the difference shows: three attempts cost three verifications and exactly one
     * encode of the marker.
     */
    @Test
    void repeatedCredentiallessRefusalsEncodeTheMarkerOnceAndVerifyEveryTime() {
        passwordEncoder.matchCalls.set(0);

        refuse("nopass", "one");
        refuse("nopass", "two");
        refuse("nopass", "three");

        assertThat(passwordEncoder.matchCalls).hasValue(3);
        assertThat(passwordEncoder.encodeCountOf("no-password-set")).isEqualTo(1);
    }

    /** Submits a Login expected to be refused, returning the reason Spring Security refused it for. */
    private AuditRefusalReason refuse(String username, String password) {
        LoginDecision decision = login.logIn(username, password);
        assertThat(decision.accepted()).as("the Login was refused").isEmpty();
        return ((PasswordRefused) decision.outcome()).reason();
    }

    /**
     * Wraps the real encoder, counting {@code matches} calls, counting {@code encode} calls
     * per raw value, and recording the last comparand.
     */
    private static final class CountingPasswordEncoder implements PasswordEncoder {

        private final PasswordEncoder delegate;
        private final AtomicInteger matchCalls = new AtomicInteger();
        private final Map<String, Integer> encodeCounts = new HashMap<>();
        private volatile String lastEncodedPasswordSeen;

        CountingPasswordEncoder(PasswordEncoder delegate) {
            this.delegate = delegate;
        }

        @Override
        public String encode(CharSequence rawPassword) {
            synchronized (encodeCounts) {
                encodeCounts.merge(rawPassword.toString(), 1, Integer::sum);
            }
            return delegate.encode(rawPassword);
        }

        @Override
        public boolean matches(CharSequence rawPassword, String encodedPassword) {
            matchCalls.incrementAndGet();
            lastEncodedPasswordSeen = encodedPassword;
            return delegate.matches(rawPassword, encodedPassword);
        }

        /**
         * How many times this raw value was encoded. Counting rather than comparing the
         * result, because the encoder is deterministic in what it accepts: only the call
         * count distinguishes a cached marker from one recomputed on every call.
         */
        int encodeCountOf(String rawPassword) {
            synchronized (encodeCounts) {
                return encodeCounts.getOrDefault(rawPassword, 0);
            }
        }
    }
}

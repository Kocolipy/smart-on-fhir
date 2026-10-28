package com.example.backend.auth.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.backend.auth.LoginHarness;
import com.example.backend.auth.application.LoginOutcome.PasswordRefused;
import com.example.backend.auth.application.LoginService.LoginDecision;
import com.example.backend.auth.config.SecurityConfig;
import com.example.backend.scim.InMemoryScimUserRepository;
import com.example.backend.scim.ScimIdentities;
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
 * Structural equivalence of the refusal categories: an unknown userName, a credentialless
 * identity, and a real identity given the wrong password must each drive exactly one real
 * verification against a dummy hash before the refusal — asserted by counting calls into the
 * encoder, not by measuring wall-clock time (ADR 0007, "The failure lockout").
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
 * independently. The decision is the one {@link LoginHarness} assembles, over the counting
 * encoder.
 */
class RefusalTimingEquivalenceTests {

    private static final Instant NOW = LoginHarness.NOW;

    private final CountingPasswordEncoder passwordEncoder =
            new CountingPasswordEncoder(new SecurityConfig().passwordEncoder());
    private final LoginHarness harness = LoginHarness.builder()
            .passwordEncoder(passwordEncoder)
            .build();
    private final InMemoryScimUserRepository users = harness.users();
    private final LoginService login = harness.login();

    @BeforeEach
    void setUp() {
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
     * A name with whitespace at an edge is refused as unknown before any lookup (ADR 0013), and
     * pays the same one verification an unknown name does rather than answering faster.
     */
    @Test
    void aUsernameBorderedByWhitespaceRunsExactlyOneVerification() {
        passwordEncoder.matchCalls.set(0);

        refuse("ada ", "correct-password");

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
                .containsOnly(PasswordRefusalReason.ACCOUNT_LOCKED);
    }

    /** The comparison runs first, but deactivation still refuses the right password. */
    @Test
    void aDeactivatedIdentityIsRefusedAsDisabledWhateverThePassword() {
        assertThat(List.of(refuse("deactivated", "correct-password"),
                        refuse("deactivated", "wrong-password")))
                .containsOnly(PasswordRefusalReason.ACCOUNT_DISABLED);
    }

    /**
     * Not just "one call each" but against a dummy hash of the ticket's required shape:
     * every category compares against an {@code {argon2id}}-prefixed hash, so none of them
     * can be picked out by running a cheaper or differently-shaped comparison. A credentialless
     * identity is refused as an unknown name is, so both compare against
     * {@code DaoAuthenticationProvider}'s own cached comparand: a real Argon2id verification at
     * the same parameters as a stored hash.
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
     * The verification a credentialless identity pays is the same work every time, and nothing
     * is encoded for it per attempt — Argon2id is deliberately expensive, so encoding a dummy
     * hash per attempt would hand an unauthenticated caller a way to spend this service's CPU
     * at will.
     *
     * <p>Both halves are asserted from the ENCODER's counters, because that is the only
     * place the difference shows: after the first refusal, which may prepare the provider's
     * dummy hash, three attempts cost three verifications and no encode.
     */
    @Test
    void repeatedCredentiallessRefusalsEncodeNothingAndVerifyEveryTime() {
        refuse("nopass", "first");
        passwordEncoder.matchCalls.set(0);
        int encodedBefore = passwordEncoder.totalEncodes();

        refuse("nopass", "one");
        refuse("nopass", "two");
        refuse("nopass", "three");

        assertThat(passwordEncoder.matchCalls).hasValue(3);
        assertThat(passwordEncoder.totalEncodes()).isEqualTo(encodedBefore);
    }

    /** Submits a Login expected to be refused, returning the reason Spring Security refused it for. */
    private PasswordRefusalReason refuse(String username, String password) {
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
         * How many encodes there have been, of any value. Counting rather than comparing
         * results, because the encoder is deterministic in what it accepts: only the call
         * count distinguishes a cached dummy hash from one recomputed on every call.
         */
        int totalEncodes() {
            synchronized (encodeCounts) {
                return encodeCounts.values().stream().mapToInt(Integer::intValue).sum();
            }
        }
    }
}

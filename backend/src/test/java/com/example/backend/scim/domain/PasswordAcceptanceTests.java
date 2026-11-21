package com.example.backend.scim.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.backend.auth.config.PasswordEncoderFixtures;
import com.example.backend.scim.InMemoryScimPasswordHistoryRepository;
import com.example.backend.scim.ScimIdentities;
import com.example.backend.scim.config.ScimPasswordAcceptanceConfig;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * The rules every password-setting path shares, exercised at the one interface that owns them,
 * over the in-memory history adapter and the deployment's normalizing Argon2id encoder at
 * Argon2's minimum costs ({@link PasswordEncoderFixtures}).
 */
class PasswordAcceptanceTests {

    private static final PasswordEncoder ENCODER = PasswordEncoderFixtures.cheapArgon2id();
    private static final Instant AT = ScimIdentities.NOW;

    private static final String FIRST = "first-correct-horse";
    private static final String SECOND = "second-battery-staple";
    private static final String THIRD = "third-purple-monkey";
    private static final String FOURTH = "fourth-dishwasher-x";

    /** The history adapter, counting what it is asked. */
    private static final class CountingHistory implements ScimPasswordHistoryRepository {

        final InMemoryScimPasswordHistoryRepository delegate = new InMemoryScimPasswordHistoryRepository();
        int lookups;
        int records;

        @Override
        public List<String> findRecentHashes(UUID userId) {
            lookups++;
            return delegate.findRecentHashes(userId);
        }

        @Override
        public void record(UUID userId, String passwordHash, Instant setAt) {
            records++;
            delegate.record(userId, passwordHash, setAt);
        }
    }

    /** The production hasher adapter, counting what it is asked. */
    private static final class CountingHasher implements PasswordHasher {

        final PasswordHasher delegate = ScimPasswordAcceptanceConfig.hasher(ENCODER);
        int hashes;
        int comparisons;

        @Override
        public String hash(String password) {
            hashes++;
            return delegate.hash(password);
        }

        @Override
        public boolean matches(String candidate, String storedHash) {
            comparisons++;
            return delegate.matches(candidate, storedHash);
        }
    }

    private final CountingHistory history = new CountingHistory();
    private final CountingHasher hasher = new CountingHasher();
    private final PasswordAcceptance acceptance = new PasswordAcceptance(history, hasher);

    /** A User holding {@code password}, its history starting with that credential. */
    private ScimUser userHolding(String userName, String password) {
        String hash = ENCODER.encode(password);
        ScimUser user = ScimIdentities.userWithLoginState(
                userName, new ScimLoginState(hash, 0, null, null, null));
        history.delegate.record(user.id(), hash, AT);
        return user;
    }

    /** {@code user} after {@code password} was accepted, written and remembered. */
    private ScimUser changed(ScimUser user, String password) {
        PasswordAcceptance.Accepted accepted = accepted(acceptance.acceptFor(user, password, "ada"));
        acceptance.remember(user.id(), accepted, AT);
        return new ScimUser(user.id(), user.profile(),
                new ScimLoginState(accepted.passwordHash(), 0, null, null, null),
                null, user.version() + 1, user.createdAt(), AT);
    }

    private static PasswordAcceptance.Accepted accepted(PasswordAcceptance.Decision decision) {
        assertThat(decision).isInstanceOf(PasswordAcceptance.Accepted.class);
        return (PasswordAcceptance.Accepted) decision;
    }

    private static PasswordAcceptance.Decision refused(PasswordPolicy.Rule rule) {
        return new PasswordAcceptance.Refused(rule);
    }

    private void resetCounts() {
        history.lookups = 0;
        history.records = 0;
        hasher.hashes = 0;
        hasher.comparisons = 0;
    }

    @Test
    void anIntrinsicRefusalIsDecidedBeforeTheHistoryIsReadOrAnythingIsHashed() {
        ScimUser ada = userHolding("ada", FIRST);
        resetCounts();

        assertThat(acceptance.acceptFor(ada, "short", "ada"))
                .isEqualTo(refused(PasswordPolicy.Rule.TOO_SHORT));
        assertThat(acceptance.acceptFor(ada, "x".repeat(PasswordPolicy.MAX_LENGTH + 1), "ada"))
                .isEqualTo(refused(PasswordPolicy.Rule.TOO_LONG));
        // FIRST is also the current password, so this proves the intrinsic rule wins over reuse.
        assertThat(acceptance.acceptFor(ada, FIRST, "first"))
                .isEqualTo(refused(PasswordPolicy.Rule.CONTAINS_USER_NAME));

        assertThat(history.lookups).isZero();
        assertThat(hasher.comparisons).isZero();
        assertThat(hasher.hashes).isZero();
        assertThat(history.records).isZero();
    }

    @Test
    void theUserNameExcludedIsTheOneTheUserWillHold() {
        ScimUser ada = userHolding("ada", FIRST);

        assertThat(acceptance.acceptFor(ada, "hello-grace-hopper", "Grace"))
                .isEqualTo(refused(PasswordPolicy.Rule.CONTAINS_USER_NAME));
        accepted(acceptance.acceptFor(ada, "hello-ada-lovelace", "grace"));
    }

    @Test
    void theCurrentPasswordIsRefusedEvenWhenTheHistoryDoesNotHoldIt() {
        ScimUser seeded = ScimIdentities.userWithLoginState(
                "ada", new ScimLoginState(ENCODER.encode(FIRST), 0, null, null, null));
        assertThat(history.delegate.findRecentHashes(seeded.id())).isEmpty();

        assertThat(acceptance.acceptFor(seeded, FIRST, "ada"))
                .isEqualTo(refused(PasswordPolicy.Rule.REUSED));
        accepted(acceptance.acceptFor(seeded, SECOND, "ada"));
    }

    @Test
    void everyRetainedPasswordIsRefusedAndTheOneBeforeThemIsAcceptedAgain() {
        ScimUser ada = changed(changed(changed(userHolding("ada", FIRST), SECOND), THIRD), FOURTH);
        assertThat(history.delegate.findRecentHashes(ada.id())).hasSize(PasswordHistoryPolicy.RETAINED);

        // Three most recent, the current one included: FOURTH, THIRD, SECOND.
        for (String retained : List.of(FOURTH, THIRD, SECOND)) {
            assertThat(acceptance.acceptFor(ada, retained, "ada"))
                    .as(retained).isEqualTo(refused(PasswordPolicy.Rule.REUSED));
        }
        accepted(acceptance.acceptFor(ada, FIRST, "ada"));
    }

    /** "é" precomposed and "e" + combining acute are one password once normalized by the encoder. */
    @Test
    void aCanonicallyEquivalentSpellingOfARetainedPasswordIsRefused() {
        String composed = "caf\u00e9-au-lait-2026";
        String decomposed = "cafe\u0301-au-lait-2026";
        ScimUser ada = changed(userHolding("ada", composed), SECOND);

        assertThat(acceptance.acceptFor(ada, decomposed, "ada"))
                .isEqualTo(refused(PasswordPolicy.Rule.REUSED));
        ScimUser bea = userHolding("bea", decomposed);
        assertThat(acceptance.acceptFor(bea, composed, "bea"))
                .isEqualTo(refused(PasswordPolicy.Rule.REUSED));
    }

    @Test
    void aUserWithoutACredentialIsComparedWithItsHistoryAlone() {
        ScimUser cleared = ScimIdentities.credentiallessUser("ada");
        accepted(acceptance.acceptFor(cleared, FIRST, "ada"));

        history.delegate.record(cleared.id(), ENCODER.encode(SECOND), AT);
        assertThat(acceptance.acceptFor(cleared, SECOND, "ada"))
                .isEqualTo(refused(PasswordPolicy.Rule.REUSED));
    }

    @Test
    void aNewUsersFirstPasswordIsJudgedOnTheIntrinsicRulesAloneAndReadsNoHistory() {
        assertThat(acceptance.acceptForNewUser("short", "ada"))
                .isEqualTo(refused(PasswordPolicy.Rule.TOO_SHORT));
        assertThat(acceptance.acceptForNewUser("hello-ada-lovelace", "Ada"))
                .isEqualTo(refused(PasswordPolicy.Rule.CONTAINS_USER_NAME));
        assertThat(hasher.hashes).isZero();

        PasswordAcceptance.Accepted first = accepted(acceptance.acceptForNewUser(FIRST, "ada"));

        assertThat(ENCODER.matches(FIRST, first.passwordHash())).isTrue();
        assertThat(hasher.hashes).isEqualTo(1);
        assertThat(history.lookups).isZero();
        assertThat(hasher.comparisons).isZero();
    }

    @Test
    void anAcceptedPasswordIsEncodedOnceAndDecidingAloneRemembersNothing() {
        ScimUser ada = userHolding("ada", FIRST);
        List<String> before = history.delegate.findRecentHashes(ada.id());
        resetCounts();

        PasswordAcceptance.Accepted accepted = accepted(acceptance.acceptFor(ada, SECOND, "ada"));

        assertThat(hasher.hashes).isEqualTo(1);
        assertThat(accepted.passwordHash()).startsWith("{argon2id}").doesNotContain(SECOND);
        assertThat(ENCODER.matches(SECOND, accepted.passwordHash())).isTrue();
        assertThat(history.records).isZero();
        assertThat(history.delegate.findRecentHashes(ada.id())).isEqualTo(before);
        // A refusal remembers nothing either.
        acceptance.acceptFor(ada, FIRST, "ada");
        assertThat(history.records).isZero();
    }

    @Test
    void rememberingRecordsTheAcceptedHashAsTheNewestOnceAndOnlyOnce() {
        ScimUser ada = userHolding("ada", FIRST);
        PasswordAcceptance.Accepted accepted = accepted(acceptance.acceptFor(ada, SECOND, "ada"));
        resetCounts();

        acceptance.remember(ada.id(), accepted, AT);

        assertThat(history.records).isEqualTo(1);
        assertThat(history.delegate.findRecentHashes(ada.id()).getFirst())
                .isEqualTo(accepted.passwordHash());
        assertThatThrownBy(() -> acceptance.remember(ada.id(), accepted, AT))
                .isInstanceOf(IllegalStateException.class);
        assertThat(history.records).isEqualTo(1);
        assertThat(history.delegate.findRecentHashes(ada.id())).hasSize(2);
    }

    @Test
    void neitherDecisionPrintsAPasswordOrAHash() {
        ScimUser ada = userHolding("ada", FIRST);
        PasswordAcceptance.Accepted accepted = accepted(acceptance.acceptFor(ada, SECOND, "ada"));

        assertThat(accepted.toString())
                .isEqualTo("Accepted[passwordHash=<redacted>]")
                .doesNotContain(SECOND)
                .doesNotContain(accepted.passwordHash())
                .doesNotContain("argon2");
        assertThat(acceptance.acceptFor(ada, FIRST, "ada").toString()).doesNotContain(FIRST);
    }

    @Test
    void aRefusalAlwaysNamesItsRule() {
        assertThatThrownBy(() -> new PasswordAcceptance.Refused(null))
                .isInstanceOf(NullPointerException.class);
    }
}

package com.example.backend.scim.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * The dormancy verdict (ADR 0007), decided without a clock: given a User and "now", which of the
 * job's steps it owes. Every instant here is a literal offset from {@link #NOW}, so each boundary
 * is reached exactly — at it, one nanosecond before it and one after — for each basis a User can
 * be measured from: its last login, its creation, and an Unlock.
 *
 * <p>The windows are non-default (10 and 20 days), so a verdict that read the defaults instead of
 * the policy's own windows fails every row. What the job then does with each verdict — the lock,
 * the membership removal, the audit and the sessions — is {@code DormancyServiceTests}.
 */
class DormancyVerdictTests {

    private static final Instant NOW = Instant.parse("2026-06-01T12:00:00Z");

    private static final Duration LOCKOUT = Duration.ofDays(10);

    private static final Duration MEMBERSHIP_REVOCATION = Duration.ofDays(20);

    private static final DormancyPolicy POLICY = new DormancyPolicy(LOCKOUT, MEMBERSHIP_REVOCATION);

    /** Long before every window: a basis that, if the verdict read it, would always be due. */
    private static final Instant LONG_AGO = NOW.minus(MEMBERSHIP_REVOCATION.multipliedBy(10));

    private static final Duration NANO = Duration.ofNanos(1);

    /** How the User came to have its basis — each one a real shape a User can be in. */
    enum Basis {

        /** Logged in at the basis instant, having been created long before. */
        LAST_LOGIN {
            @Override
            ScimUser userWithBasis(Instant at) {
                return user(LONG_AGO, new ScimLoginState("hash", 0, null, at), null);
            }
        },

        /** Created at the basis instant and never authenticated since. */
        CREATION {
            @Override
            ScimUser userWithBasis(Instant at) {
                return user(at, ScimLoginState.of("hash"), null);
            }
        },

        /**
         * Unlocked at the basis instant: created and last logged in long before, then locked for
         * dormancy, then unlocked — the Unlock's restarted basis is the one measured.
         */
        UNLOCK {
            @Override
            ScimUser userWithBasis(Instant at) {
                ScimLoginState unlocked = new ScimLoginState("hash", 0, null, LONG_AGO)
                        .withDormancyLock(LONG_AGO)
                        .withFailureRunCleared()
                        .withDormancyBasisReset(at);
                return user(LONG_AGO, unlocked, null);
            }
        };

        abstract ScimUser userWithBasis(Instant at);
    }

    /**
     * Each basis, at each boundary and one nanosecond either side of it. "Older" is one nanosecond
     * further in the past than the boundary, so past the window; "newer" is one nanosecond inside
     * it. Exactly at a boundary the window has not yet been exceeded.
     */
    static Stream<Arguments> boundaries() {
        Instant lockoutBoundary = NOW.minus(LOCKOUT);
        Instant revocationBoundary = NOW.minus(MEMBERSHIP_REVOCATION);
        List<Object[]> rows = List.of(
                new Object[] {"newer than the lockout boundary", lockoutBoundary.plus(NANO),
                        DormancyVerdict.NOT_DUE},
                new Object[] {"exactly at the lockout boundary", lockoutBoundary,
                        DormancyVerdict.NOT_DUE},
                new Object[] {"older than the lockout boundary", lockoutBoundary.minus(NANO),
                        DormancyVerdict.LOCKOUT},
                new Object[] {"newer than the membership-revocation boundary",
                        revocationBoundary.plus(NANO), DormancyVerdict.LOCKOUT},
                new Object[] {"exactly at the membership-revocation boundary", revocationBoundary,
                        DormancyVerdict.LOCKOUT},
                new Object[] {"older than the membership-revocation boundary",
                        revocationBoundary.minus(NANO),
                        DormancyVerdict.LOCKOUT_AND_MEMBERSHIP_REVOCATION},
                new Object[] {"exactly now", NOW, DormancyVerdict.NOT_DUE});
        return Stream.of(Basis.values()).flatMap(basis -> rows.stream()
                .map(row -> Arguments.of(basis, row[0], row[1], row[2])));
    }

    @ParameterizedTest(name = "{0}, {1} -> {3}")
    @MethodSource("boundaries")
    void eachBasisIsDecidedAtEachBoundary(
            Basis basis, String where, Instant at, DormancyVerdict expected) {
        ScimUser user = basis.userWithBasis(at);

        assertThat(user.dormancyBasis()).as("the basis measured").isEqualTo(at);
        assertThat(POLICY.verdict(user, NOW)).isEqualTo(expected);
        assertThat(POLICY.verdict(user.createdAt(), user.login().lastAuthenticatedAt(), NOW))
                .as("the same verdict from the User's instants alone").isEqualTo(expected);
    }

    // ---- choosing the basis -----------------------------------------------------------------

    /** A last authentication is the basis however much older the creation is. */
    @Test
    void aLastAuthenticationIsMeasuredInsteadOfAnOlderCreation() {
        assertThat(POLICY.verdict(LONG_AGO, NOW, NOW)).isEqualTo(DormancyVerdict.NOT_DUE);
        assertThat(DormancyPolicy.basis(LONG_AGO, NOW)).isEqualTo(NOW);
    }

    /**
     * And however much newer the creation is: the last authentication always wins when there is
     * one, so the choice is "the last authentication, else creation", not "the later of the two".
     */
    @Test
    void aLastAuthenticationIsMeasuredInsteadOfANewerCreation() {
        assertThat(POLICY.verdict(NOW, LONG_AGO, NOW))
                .isEqualTo(DormancyVerdict.LOCKOUT_AND_MEMBERSHIP_REVOCATION);
        assertThat(DormancyPolicy.basis(NOW, LONG_AGO)).isEqualTo(LONG_AGO);
    }

    /** With no authentication at all, creation is the basis. */
    @Test
    void creationIsMeasuredWhenTheUserNeverAuthenticated() {
        assertThat(POLICY.verdict(LONG_AGO, null, NOW))
                .isEqualTo(DormancyVerdict.LOCKOUT_AND_MEMBERSHIP_REVOCATION);
        assertThat(POLICY.verdict(NOW, null, NOW)).isEqualTo(DormancyVerdict.NOT_DUE);
        assertThat(DormancyPolicy.basis(LONG_AGO, null)).isEqualTo(LONG_AGO);
    }

    /** An Unlock restarts the basis even for a User whose login and creation are long past. */
    @Test
    void anUnlockRestartsTheBasis() {
        ScimUser unlocked = Basis.UNLOCK.userWithBasis(NOW);

        assertThat(unlocked.createdAt()).isEqualTo(LONG_AGO);
        assertThat(POLICY.verdict(unlocked, NOW)).isEqualTo(DormancyVerdict.NOT_DUE);
    }

    /** The verdict is about time alone: a lock already standing does not change it. */
    @Test
    void aStandingLockDoesNotChangeTheVerdict() {
        ScimUser locked = user(
                LONG_AGO, new ScimLoginState("hash", 5, LONG_AGO, LONG_AGO), null);

        assertThat(locked.login().isLocked()).isTrue();
        assertThat(POLICY.verdict(locked, NOW))
                .isEqualTo(DormancyVerdict.LOCKOUT_AND_MEMBERSHIP_REVOCATION);
    }

    // ---- the exemption ----------------------------------------------------------------------

    /** The Bootstrap Admin is never due, however long past both windows its basis lies. */
    @Test
    void theBootstrapAdminIsNeverDue() {
        ScimUser bootstrap = user(
                LONG_AGO, ScimLoginState.of("hash"), ReservedResourceName.BOOTSTRAP_ADMIN);

        assertThat(POLICY.verdict(bootstrap, NOW)).isEqualTo(DormancyVerdict.NOT_DUE);
        assertThat(POLICY.verdict(bootstrap.createdAt(), null, NOW))
                .as("the same instants, unreserved, would be due")
                .isEqualTo(DormancyVerdict.LOCKOUT_AND_MEMBERSHIP_REVOCATION);
    }

    /** Another reservation protects from writes but not from dormancy. */
    @Test
    void anotherReservationIsNotExempt() {
        for (ReservedResourceName name : ReservedResourceName.values()) {
            if (name == ReservedResourceName.BOOTSTRAP_ADMIN) {
                continue;
            }
            assertThat(POLICY.verdict(user(LONG_AGO, ScimLoginState.of("hash"), name), NOW))
                    .as("reserved as %s", name)
                    .isEqualTo(DormancyVerdict.LOCKOUT_AND_MEMBERSHIP_REVOCATION);
        }
    }

    // ---- the windows ------------------------------------------------------------------------

    /** A policy that configures nothing decides at 90 and 180 days. */
    @Test
    void theDefaultPolicyDecidesAtNinetyAndOneHundredEightyDays() {
        DormancyPolicy defaults = DormancyPolicy.defaults();

        assertThat(defaults.verdict(NOW.minus(Duration.ofDays(90)), null, NOW))
                .isEqualTo(DormancyVerdict.NOT_DUE);
        assertThat(defaults.verdict(NOW.minus(Duration.ofDays(90)).minus(NANO), null, NOW))
                .isEqualTo(DormancyVerdict.LOCKOUT);
        assertThat(defaults.verdict(NOW.minus(Duration.ofDays(180)), null, NOW))
                .isEqualTo(DormancyVerdict.LOCKOUT);
        assertThat(defaults.verdict(NOW.minus(Duration.ofDays(180)).minus(NANO), null, NOW))
                .isEqualTo(DormancyVerdict.LOCKOUT_AND_MEMBERSHIP_REVOCATION);
    }

    /** "Now" is the verdict's input, not a clock it reads: the same basis ages with it. */
    @Test
    void theSameBasisAgesWithNow() {
        Instant basis = NOW;

        assertThat(POLICY.verdict(basis, null, NOW)).isEqualTo(DormancyVerdict.NOT_DUE);
        assertThat(POLICY.verdict(basis, null, NOW.plus(LOCKOUT).plus(NANO)))
                .isEqualTo(DormancyVerdict.LOCKOUT);
        assertThat(POLICY.verdict(basis, null, NOW.plus(MEMBERSHIP_REVOCATION).plus(NANO)))
                .isEqualTo(DormancyVerdict.LOCKOUT_AND_MEMBERSHIP_REVOCATION);
    }

    // ---- what each verdict asks of the job --------------------------------------------------

    @Test
    void eachVerdictNamesTheStepsItApplies() {
        assertThat(DormancyVerdict.NOT_DUE.locksOut()).isFalse();
        assertThat(DormancyVerdict.NOT_DUE.revokesMemberships()).isFalse();
        assertThat(DormancyVerdict.LOCKOUT.locksOut()).isTrue();
        assertThat(DormancyVerdict.LOCKOUT.revokesMemberships()).isFalse();
        assertThat(DormancyVerdict.LOCKOUT_AND_MEMBERSHIP_REVOCATION.locksOut()).isTrue();
        assertThat(DormancyVerdict.LOCKOUT_AND_MEMBERSHIP_REVOCATION.revokesMemberships()).isTrue();
    }

    private static ScimUser user(
            Instant createdAt, ScimLoginState login, ReservedResourceName reservedName) {
        return new ScimUser(
                UUID.randomUUID(),
                new ScimUserProfile("ada", null, null, null, null, null, true, List.of()),
                login,
                reservedName,
                ScimUser.INITIAL_VERSION,
                createdAt,
                createdAt);
    }
}

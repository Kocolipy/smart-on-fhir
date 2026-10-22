package com.example.backend.scim.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/**
 * The two dormancy windows: what a deployment that configures nothing gets, what a configured
 * value becomes, and which values stop startup. The startup half — that a refusal here actually
 * fails the context — is {@code DormancyPolicyStartupTests}.
 */
class DormancyPolicyTests {

    private static final Instant NOW = Instant.parse("2026-06-01T12:00:00Z");

    @Test
    void anUnconfiguredPolicyLocksAtNinetyDaysAndRevokesRolesAtOneHundredEighty() {
        DormancyPolicy policy = new DormancyPolicy(null, null);

        assertThat(policy.lockoutWindow()).isEqualTo(Duration.ofDays(90));
        assertThat(policy.membershipRevocationWindow()).isEqualTo(Duration.ofDays(180));
        assertThat(DormancyPolicy.defaults()).isEqualTo(policy);
    }

    /** Asserted against literals: comparing two constructions would pass if both defaulted. */
    @Test
    void configuredWindowsAreKeptAsGivenAndIndependently() {
        DormancyPolicy onlyLockout = new DormancyPolicy(Duration.ofDays(30), null);
        assertThat(onlyLockout.lockoutWindow()).isEqualTo(Duration.ofDays(30));
        assertThat(onlyLockout.membershipRevocationWindow()).isEqualTo(Duration.ofDays(180));

        DormancyPolicy onlyRevocation = new DormancyPolicy(null, Duration.ofDays(120));
        assertThat(onlyRevocation.lockoutWindow()).isEqualTo(Duration.ofDays(90));
        assertThat(onlyRevocation.membershipRevocationWindow()).isEqualTo(Duration.ofDays(120));
    }

    @Test
    void eachCutoffIsNowMinusItsOwnWindow() {
        DormancyPolicy policy = new DormancyPolicy(Duration.ofDays(10), Duration.ofDays(20));

        assertThat(policy.lockoutCutoff(NOW)).isEqualTo(NOW.minus(Duration.ofDays(10)));
        assertThat(policy.membershipRevocationCutoff(NOW))
                .isEqualTo(NOW.minus(Duration.ofDays(20)));
    }

    /**
     * Zero or negative would make every User dormant on the next run, so it is refused, naming the
     * window and the value. The smallest positive windows are allowed — there is no floor.
     */
    @Test
    void aZeroOrNegativeWindowIsRefusedAndTheSmallestPositiveOnesAreNot() {
        assertThatThrownBy(() -> new DormancyPolicy(Duration.ZERO, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("lockout window")
                .hasMessageContaining(Duration.ZERO.toString())
                .hasMessageContaining("must be positive");
        assertThatThrownBy(() -> new DormancyPolicy(Duration.ofDays(-1), null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("lockout window");
        assertThatThrownBy(() -> new DormancyPolicy(null, Duration.ofDays(-1)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("membership revocation window")
                .hasMessageContaining(Duration.ofDays(-1).toString())
                .hasMessageContaining("must be positive");
        assertThatThrownBy(() -> new DormancyPolicy(null, Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("membership revocation window");

        DormancyPolicy smallest = new DormancyPolicy(Duration.ofNanos(1), Duration.ofNanos(2));
        assertThat(smallest.lockoutWindow()).isEqualTo(Duration.ofNanos(1));
        assertThat(smallest.membershipRevocationWindow()).isEqualTo(Duration.ofNanos(2));
    }

    /**
     * Membership revocation is the later step: a window equal to or shorter than the lockout window is
     * refused, naming both values; one nanosecond longer is accepted.
     */
    @Test
    void aMembershipRevocationWindowNotLongerThanTheLockoutWindowIsRefused() {
        assertThatThrownBy(() -> new DormancyPolicy(Duration.ofDays(90), Duration.ofDays(90)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must be longer than the lockout window")
                .hasMessageContaining(Duration.ofDays(90).toString());
        assertThatThrownBy(() -> new DormancyPolicy(Duration.ofDays(90), Duration.ofDays(60)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(Duration.ofDays(60).toString())
                .hasMessageContaining(Duration.ofDays(90).toString());
        assertThatThrownBy(() -> new DormancyPolicy(Duration.ofDays(200), null))
                .as("a lockout window past the default membership revocation window")
                .isInstanceOf(IllegalArgumentException.class);

        assertThat(new DormancyPolicy(Duration.ofDays(90), Duration.ofDays(90).plusNanos(1))
                .membershipRevocationWindow()).isEqualTo(Duration.ofDays(90).plusNanos(1));
    }
}

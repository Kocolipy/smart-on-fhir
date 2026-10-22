package com.example.backend.audit.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.Test;

/**
 * The retention rule itself: what is kept by default, and how short a window the
 * application refuses to run with.
 *
 * <p>Exercised here rather than only through a started application because the
 * refusal is what makes a misconfigured deployment fail, and a rule that can only
 * be observed by starting a context is one nobody can pin down to a boundary. The
 * startup half — that this refusal actually stops the context — is
 * {@code AuditRetentionStartupTests}.
 */
class AuditRetentionPolicyTests {

    @Test
    void anUnconfiguredWindowIsOneYear() {
        assertThat(new AuditRetentionPolicy(null, null).period())
                .isEqualTo(Duration.ofDays(365));
    }

    @Test
    void anUnconfiguredScheduleIsTheDailyDefault() {
        assertThat(new AuditRetentionPolicy(null, null).schedule()).isEqualTo("0 30 3 * * *");
        assertThat(new AuditRetentionPolicy(null, "   ").schedule()).isEqualTo("0 30 3 * * *");
    }

    @Test
    void aConfiguredWindowIsKeptAsGiven() {
        assertThat(new AuditRetentionPolicy(Duration.ofDays(400), null).period())
                .isEqualTo(Duration.ofDays(400));
        // Asserted against the literal rather than against a second policy built the
        // same way: comparing two constructions would pass even if the constructor
        // replaced both with the default.
        assertThat(new AuditRetentionPolicy(Duration.ofDays(400), "0 0 4 * * *").schedule())
                .isEqualTo("0 0 4 * * *");
    }

    @Test
    void ninetyDaysIsTheFloorAndIsItselfAllowed() {
        assertThat(new AuditRetentionPolicy(Duration.ofDays(90), null).period())
                .isEqualTo(Duration.ofDays(90));
    }

    /**
     * One day under is refused, not rounded up and not warned about. A window that
     * is nearly long enough is the case a rounding rule would hide.
     */
    @Test
    void aWindowBelowNinetyDaysIsRefused() {
        assertThatThrownBy(() -> new AuditRetentionPolicy(Duration.ofDays(89), null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("below")
                // The message names the value that was configured and the floor it
                // missed, so an operator can fix it without reading this code.
                .hasMessageContaining(Duration.ofDays(89).toString())
                .hasMessageContaining(AuditRetentionPolicy.MINIMUM_PERIOD.toString());

        assertThatThrownBy(() -> new AuditRetentionPolicy(Duration.ofDays(30), null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(Duration.ofDays(30).toString());

        assertThatThrownBy(() -> new AuditRetentionPolicy(Duration.ZERO, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

}

package com.example.backend.scim.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The lockout policy's one invariant: at least one attempt has to be possible.
 *
 * <p>Load-bearing rather than defensive: {@code maxAttempts} comes from deployment configuration,
 * so a zero or a negative value is a real thing an operator can write. Without the guard a zero
 * would mean the very first login attempt already exceeds the allowance, locking every User out of
 * a freshly configured deployment — including, but for its exemption, the recovery identity.
 */
class LockoutPolicyTests {

    /** Zero and negative allowances are rejected at construction, not at first login. */
    @ParameterizedTest
    @ValueSource(ints = {0, -1, Integer.MIN_VALUE})
    void an_allowance_below_one_attempt_is_refused(int maxAttempts) {
        assertThatThrownBy(() -> new LockoutPolicy(maxAttempts))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("A lockout policy needs at least one attempt");
    }

    /**
     * One attempt is the boundary and it is ALLOWED: a deployment that locks on the first failure
     * is making a policy choice, not a mistake, so the guard must not impose a minimum of its own
     * beyond "at least one".
     */
    @Test
    void a_single_attempt_is_a_legitimate_policy() {
        assertThat(new LockoutPolicy(1).maxAttempts()).isEqualTo(1);
    }
}

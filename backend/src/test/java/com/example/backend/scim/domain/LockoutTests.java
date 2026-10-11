package com.example.backend.scim.domain;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import org.junit.jupiter.api.Test;

/**
 * A lock travels with its cause and its instant, or not at all: the store is never handed half a
 * lock to write.
 */
class LockoutTests {

    private static final Instant NOW = Instant.parse("2026-05-01T08:00:00Z");

    @Test
    void aLockWithoutAnInstantIsRefused() {
        assertThatThrownBy(() -> new Lockout(null, LockCause.DORMANCY))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("a lock has an instant");
    }

    @Test
    void aLockWithoutACauseIsRefused() {
        assertThatThrownBy(() -> new Lockout(NOW, null))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("a lock has a cause");
    }
}

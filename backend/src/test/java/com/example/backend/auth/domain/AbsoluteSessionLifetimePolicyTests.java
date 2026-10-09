package com.example.backend.auth.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class AbsoluteSessionLifetimePolicyTests {

    private static final Instant CREATED_AT = Instant.parse("2026-09-24T07:00:00Z");

    private static final AbsoluteSessionLifetimePolicy POLICY =
            new AbsoluteSessionLifetimePolicy(Duration.ofHours(8));

    @Test
    void aSessionYoungerThanTheLifetimeIsNotExpired() {
        assertThat(POLICY.isExpired(CREATED_AT, CREATED_AT.plus(Duration.ofHours(7))))
                .isFalse();
    }

    @Test
    void aSessionExactlyAtTheLifetimeIsNotYetExpired() {
        assertThat(POLICY.isExpired(CREATED_AT, CREATED_AT.plus(Duration.ofHours(8))))
                .isFalse();
    }

    @Test
    void aSessionPastTheLifetimeIsExpired() {
        assertThat(POLICY.isExpired(
                CREATED_AT, CREATED_AT.plus(Duration.ofHours(8)).plusMillis(1)))
                .isTrue();
    }

    @Test
    void aPolicyMustHaveAPositiveLifetime() {
        assertThatThrownBy(() -> new AbsoluteSessionLifetimePolicy(Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AbsoluteSessionLifetimePolicy(Duration.ofMinutes(-1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AbsoluteSessionLifetimePolicy(null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ---- the idle bound a session may be stored under ------------------------------------------

    private static final Duration IDLE = Duration.ofMinutes(15);

    @Test
    void theIdleBoundStandsWhileTheLifetimeIsFurtherOff() {
        assertThat(POLICY.idleBoundAt(IDLE, CREATED_AT, CREATED_AT.plus(Duration.ofHours(1))))
                .isEqualTo(Duration.ofMinutes(15));
    }

    @Test
    void theIdleBoundStandsWhenTheLifetimeRemainingIsExactlyIt() {
        assertThat(POLICY.idleBoundAt(IDLE, CREATED_AT,
                CREATED_AT.plus(Duration.ofHours(7).plusMinutes(45))))
                .isEqualTo(Duration.ofMinutes(15));
    }

    /** Not rounded to whole seconds when it stands: only what remains of the lifetime is. */
    @Test
    void anIdleBoundOfExactlyTheLifetimeRemainingStandsToTheMillisecond() {
        Duration idle = Duration.ofMinutes(15).plusMillis(500);

        assertThat(POLICY.idleBoundAt(idle, CREATED_AT,
                CREATED_AT.plus(Duration.ofHours(8)).minus(idle)))
                .isEqualTo(Duration.ofMinutes(15).plusMillis(500));
    }

    @Test
    void theIdleBoundIsCutToTheLifetimeRemainingOnceThatIsShorter() {
        assertThat(POLICY.idleBoundAt(IDLE, CREATED_AT,
                CREATED_AT.plus(Duration.ofHours(7).plusMinutes(55))))
                .isEqualTo(Duration.ofMinutes(5));
    }

    /** Rounded down, so the store's expiry never lands past the lifetime's end. */
    @Test
    void theLifetimeRemainingIsCountedInWholeSecondsRoundedDown() {
        assertThat(POLICY.idleBoundAt(IDLE, CREATED_AT,
                CREATED_AT.plus(Duration.ofHours(7).plusMinutes(55)).plusMillis(500)))
                .isEqualTo(Duration.ofSeconds(299));
    }

    /** Zero or less is "never times out" to the servlet API, the opposite of what is meant. */
    @Test
    void theIdleBoundIsNeverLessThanOneSecond() {
        assertThat(POLICY.idleBoundAt(IDLE, CREATED_AT, CREATED_AT.plus(Duration.ofHours(8))))
                .isEqualTo(Duration.ofSeconds(1));
    }

    /** An idle bound of zero or less never times out, so the lifetime remaining is the bound. */
    @Test
    void aSessionThatNeverIdlesOutIsBoundedByTheLifetimeRemaining() {
        assertThat(POLICY.idleBoundAt(Duration.ZERO, CREATED_AT,
                CREATED_AT.plus(Duration.ofHours(7))))
                .isEqualTo(Duration.ofHours(1));
    }

    @Test
    void anIdleBoundIsRequired() {
        assertThatThrownBy(() -> POLICY.idleBoundAt(null, CREATED_AT, CREATED_AT))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("idleBound");
    }

    @Test
    void aCreationInstantIsRequired() {
        assertThatThrownBy(() -> POLICY.isExpired(null, CREATED_AT))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("createdAt");
    }

    @Test
    void aCurrentInstantIsRequired() {
        assertThatThrownBy(() -> POLICY.isExpired(CREATED_AT, null))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("now");
    }

    @Test
    void aNegativeIdleBoundIsBoundedByTheLifetimeRemainingToo() {
        assertThat(POLICY.idleBoundAt(Duration.ofSeconds(-1), CREATED_AT,
                CREATED_AT.plus(Duration.ofHours(7))))
                .isEqualTo(Duration.ofHours(1));
    }
}

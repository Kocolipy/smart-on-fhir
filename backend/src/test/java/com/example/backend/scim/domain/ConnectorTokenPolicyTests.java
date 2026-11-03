package com.example.backend.scim.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** The lifetime ceiling and the rotation-overlap arithmetic. */
class ConnectorTokenPolicyTests {

    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");

    @Nested
    class Lifetime {

        @Test
        void an_unstated_lifetime_is_the_default_year() {
            assertThat(ConnectorTokenPolicy.lifetime(null)).isEqualTo(Duration.ofDays(365));
        }

        @Test
        void a_shorter_lifetime_is_honoured() {
            assertThat(ConnectorTokenPolicy.lifetime(Duration.ofDays(30)))
                    .isEqualTo(Duration.ofDays(30));
        }

        @Test
        void the_ceiling_itself_is_allowed() {
            assertThat(ConnectorTokenPolicy.lifetime(Duration.ofDays(365)))
                    .isEqualTo(Duration.ofDays(365));
        }

        /**
         * Refused rather than clamped, so an Admin never walks away believing their
         * integration has two years before it breaks.
         */
        @Test
        void a_lifetime_past_the_ceiling_is_refused_rather_than_shortened() {
            assertThatThrownBy(() -> ConnectorTokenPolicy.lifetime(Duration.ofDays(366)))
                    .isInstanceOf(InvalidConnectorTokenLifetimeException.class)
                    .hasMessageContaining("365");
        }

        @ParameterizedTest
        @ValueSource(strings = {"PT0S", "-P1D"})
        void a_zero_or_negative_lifetime_is_refused(Duration lifetime) {
            assertThatThrownBy(() -> ConnectorTokenPolicy.lifetime(lifetime))
                    .isInstanceOf(InvalidConnectorTokenLifetimeException.class);
        }
    }

    @Nested
    class RotationOverlap {

        @Test
        void a_requested_overlap_within_the_cap_and_the_remaining_life_is_honoured() {
            Instant end = ConnectorTokenPolicy.overlapEnd(
                    NOW, Duration.ofDays(7), NOW.plus(Duration.ofDays(300)));
            assertThat(end).isEqualTo(NOW.plus(Duration.ofDays(7)));
        }

        @Test
        void an_overlap_past_the_fourteen_day_cap_is_clamped_to_it() {
            Instant end = ConnectorTokenPolicy.overlapEnd(
                    NOW, Duration.ofDays(90), NOW.plus(Duration.ofDays(300)));
            assertThat(end).isEqualTo(NOW.plus(Duration.ofDays(14)));
        }

        @Test
        void the_cap_itself_is_allowed_whole() {
            Instant end = ConnectorTokenPolicy.overlapEnd(
                    NOW, Duration.ofDays(14), NOW.plus(Duration.ofDays(300)));
            assertThat(end).isEqualTo(NOW.plus(Duration.ofDays(14)));
        }

        /** The criterion in the ticket, stated as its own case. */
        @Test
        void an_overlap_longer_than_the_token_had_left_ends_at_the_tokens_own_expiry() {
            Instant originalExpiry = NOW.plus(Duration.ofDays(3));
            Instant end = ConnectorTokenPolicy.overlapEnd(
                    NOW, Duration.ofDays(14), originalExpiry);
            assertThat(end).isEqualTo(originalExpiry);
        }

        /**
         * A second rotation may not undo the first one's shortening. The ceiling is the
         * expiry as it currently stands, which is what makes "never extends" hold across
         * repeated rotations rather than only across the first.
         */
        @Test
        void rotating_an_already_shortened_token_cannot_give_it_time_back() {
            Instant alreadyShortenedTo = NOW.plus(Duration.ofDays(2));
            Instant end = ConnectorTokenPolicy.overlapEnd(
                    NOW, Duration.ofDays(14), alreadyShortenedTo);
            assertThat(end).isEqualTo(alreadyShortenedTo);
        }

        @Test
        void an_already_expired_token_stays_expired() {
            Instant expiredAt = NOW.minus(Duration.ofDays(1));
            assertThat(ConnectorTokenPolicy.overlapEnd(NOW, Duration.ofDays(14), expiredAt))
                    .isEqualTo(expiredAt);
        }

        @Test
        void no_overlap_ends_the_old_token_now() {
            assertThat(ConnectorTokenPolicy.overlapEnd(
                            NOW, null, NOW.plus(Duration.ofDays(300))))
                    .isEqualTo(NOW);
            assertThat(ConnectorTokenPolicy.overlapEnd(
                            NOW, Duration.ZERO, NOW.plus(Duration.ofDays(300))))
                    .isEqualTo(NOW);
        }

        @Test
        void a_negative_overlap_ends_the_old_token_now_rather_than_in_the_past() {
            assertThat(ConnectorTokenPolicy.overlapEnd(
                            NOW, Duration.ofDays(-5), NOW.plus(Duration.ofDays(300))))
                    .isEqualTo(NOW);
        }
    }
}

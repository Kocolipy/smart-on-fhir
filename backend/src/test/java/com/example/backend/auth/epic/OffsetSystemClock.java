package com.example.backend.auth.epic;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;

/**
 * The system clock, in whole microseconds as the production clock is, plus an offset a test
 * moves forward: time passes as it does, and hours pass at once when a test asks.
 *
 * <p>For a test whose Epic Login must run on the system's time — Epic's {@code id_token} and our
 * client assertion are both checked against it — and whose session must then age at once.
 */
final class OffsetSystemClock extends Clock {

    private volatile Duration offset = Duration.ZERO;

    void advanceBy(Duration amount) {
        offset = offset.plus(amount);
    }

    void reset() {
        offset = Duration.ZERO;
    }

    @Override
    public Instant instant() {
        return Instant.now().truncatedTo(ChronoUnit.MICROS).plus(offset);
    }

    @Override
    public ZoneId getZone() {
        return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
        return this;
    }
}

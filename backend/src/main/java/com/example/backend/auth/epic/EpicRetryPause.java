package com.example.backend.auth.epic;

import java.time.Duration;

/**
 * The wait before each of D26's JWKS refetches — 1, 2 and 4 seconds. A seam of its own, as the
 * {@link java.time.Clock} is for time, so a test can see the waits without spending them.
 */
@FunctionalInterface
public interface EpicRetryPause {

    /** The pause a deployment makes: the calling thread sleeps. */
    EpicRetryPause SLEEP = Thread::sleep;

    /**
     * Waits {@code delay}.
     *
     * @throws InterruptedException when the thread is interrupted while it waits
     */
    void pause(Duration delay) throws InterruptedException;
}

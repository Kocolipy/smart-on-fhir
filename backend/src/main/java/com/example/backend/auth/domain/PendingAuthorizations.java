package com.example.backend.auth.domain;

import java.time.Duration;
import java.util.Optional;

/**
 * The pending authorization request of an Epic Login — its {@code state}, nonce and PKCE
 * verifier — held for the one browser session that began it, between the authorize hop and the
 * callback (D27).
 *
 * <p>Single use, across every node and every concurrent request: {@link #take} hands the held
 * value to exactly one caller and removes it in the same step, so of two callbacks racing on one
 * session — on one node or two — at most one receives it, and the other finds none and is
 * refused before Epic is called. A read followed by a separate delete would let both through.
 *
 * <p>The value is opaque here: the adapter that holds it neither reads nor logs it (D22).
 */
public interface PendingAuthorizations {

    /**
     * Holds {@code pending} for the session {@code sessionId}, replacing whatever it held, for
     * at most {@code lifetime}.
     */
    void hold(String sessionId, String pending, Duration lifetime);

    /**
     * Takes what the session {@code sessionId} holds, removing it atomically, or empty when it
     * holds nothing — nothing ever held, already taken, or expired.
     */
    Optional<String> take(String sessionId);
}

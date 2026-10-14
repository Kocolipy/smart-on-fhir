package com.example.backend.observability;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;

/**
 * A throwable and its causes, outermost first — the walk every reader of a database failure
 * makes ({@link DatabaseFailureCategory}, {@link SqlExceptionRedaction}).
 * A cause chain may be cyclic, so the walk ends at the first throwable it has already met,
 * compared by identity: two throwables that are {@code equals} are still two links.
 */
final class CauseChain {

    private CauseChain() {
    }

    /** {@code failure}, then each cause beneath it, each throwable once. */
    static List<Throwable> of(Throwable failure) {
        List<Throwable> chain = new ArrayList<>();
        Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Throwable link = failure; link != null && seen.add(link); link = link.getCause()) {
            chain.add(link);
        }
        return chain;
    }
}

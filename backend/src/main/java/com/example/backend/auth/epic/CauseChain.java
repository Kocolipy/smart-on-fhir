package com.example.backend.auth.epic;

import java.util.Optional;

/**
 * A failure's cause chain, walked for the first link of a type: how Epic Login tells apart what
 * failed underneath a framework's own exception — an Epic call, a client error, an
 * {@code id_token} that did not decode — without reading a message, which can quote what Epic
 * sent.
 */
public final class CauseChain {

    private CauseChain() {
    }

    /**
     * The first {@code type} in {@code failure}'s cause chain, {@code failure} itself included,
     * if there is one. A cause that points to itself ends the walk.
     */
    public static <T extends Throwable> Optional<T> firstOf(Throwable failure, Class<T> type) {
        for (Throwable link = failure; link != null; link = link.getCause()) {
            if (type.isInstance(link)) {
                return Optional.of(type.cast(link));
            }
            if (link.getCause() == link) {
                break;
            }
        }
        return Optional.empty();
    }
}

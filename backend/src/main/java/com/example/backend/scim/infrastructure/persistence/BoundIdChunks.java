package com.example.backend.scim.infrastructure.persistence;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * Splits a collection of ids into chunks small enough to bind as one {@code IN} list.
 *
 * <p>SQL Server refuses a request carrying more than 2,100 parameters (error 8003), and
 * Hibernate binds each element of a collection parameter as a parameter of its own. So a
 * statement whose {@code IN} list a SCIM client sizes — a Group's members, which nothing caps,
 * or every Group in the directory — must be issued once per chunk. {@link #MAX_IDS} leaves room
 * for the statement's other parameters.
 *
 * <p>Only for lists with no cap of their own: a page of resources is at most
 * {@code ScimPageRequest.MAX_COUNT} ids and binds whole.
 */
final class BoundIdChunks {

    /** The most ids one statement binds: under 2,100 with room for its other parameters. */
    static final int MAX_IDS = 2_000;

    private BoundIdChunks() {
    }

    /**
     * These ids in chunks of at most {@link #MAX_IDS}, in iteration order; none when there are
     * none, since an empty {@code IN} list is not portable SQL.
     */
    static List<List<UUID>> of(Collection<UUID> ids) {
        List<UUID> all = List.copyOf(ids);
        List<List<UUID>> chunks = new ArrayList<>();
        for (int from = 0; from < all.size(); from += MAX_IDS) {
            chunks.add(all.subList(from, Math.min(from + MAX_IDS, all.size())));
        }
        return chunks;
    }
}

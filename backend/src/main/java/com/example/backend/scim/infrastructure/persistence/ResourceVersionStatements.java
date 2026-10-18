package com.example.backend.scim.infrastructure.persistence;

import com.example.backend.scim.domain.ResourceVersionAdvance;
import java.util.List;
import java.util.UUID;

/**
 * Carries out a {@link ResourceVersionAdvance}: the one caller of
 * {@link ScimResourceJpaRepository#advanceVersions}.
 *
 * <p>Decides nothing. Which resources a write advances is the domain module's decision, made from
 * the write's description; the adapters ask it and hand its answer here, so no adapter names the
 * ids itself and no adapter issues the statement itself.
 *
 * <p>Locking is not its concern, but it relies on the lock module ({@link ScimWriteLockAdapter}):
 * every resource an advance names is one the write already holds, locked in the canonical order
 * before the write began, so the bulk update takes no new lock and the order it updates rows in
 * cannot matter.
 */
final class ResourceVersionStatements {

    private ResourceVersionStatements() {
    }

    /**
     * Advances every resource the decision names, and none when it names none — an empty
     * {@code IN} list is not portable SQL, and there would be nothing for it to match.
     *
     * <p>One statement per {@link BoundIdChunks chunk} of at most {@link BoundIdChunks#MAX_IDS}
     * ids, all in the caller's transaction: SQL Server refuses a request binding more than 2,100
     * parameters, and nothing caps how many resources a write changes — renaming or deleting a
     * Group advances every member, and a Group's membership has no limit. Chunking adds no
     * deadlock risk: every row named here is already locked, in the canonical order, by
     * {@link ScimWriteLockAdapter}, so the statements take no new lock. Each statement flushes
     * before and clears after, as one did.
     */
    static void advance(ResourceVersionAdvance decision, ScimResourceJpaRepository resources) {
        for (List<UUID> chunk : BoundIdChunks.of(decision.advanced())) {
            resources.advanceVersions(chunk, decision.at());
        }
    }
}

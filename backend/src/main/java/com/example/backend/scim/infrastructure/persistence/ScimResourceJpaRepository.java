package com.example.backend.scim.infrastructure.persistence;

import com.example.backend.scim.infrastructure.persistence.entity.ScimResourceEntity;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Spring Data access to the row every SCIM resource's identity and version live in.
 *
 * <p>Shared by the User and Group adapters rather than owned by either, because the
 * version is shared: a Group's membership change advances the versions of Users, and a
 * User's deactivation advances its own. One place that knows how to advance a version is
 * what keeps the two adapters from growing two subtly different ways to do it.
 */
interface ScimResourceJpaRepository extends JpaRepository<ScimResourceEntity, UUID> {

    /**
     * Locks this one resource row for update ({@code WITH (UPDLOCK, ROWLOCK)}) until the
     * transaction ends. The result is
     * the statement's, not the caller's: the adapter ignores it, because a missing resource is
     * the write's own 404 to find with its first read, and locking nothing is then harmless.
     *
     * <p>Called only by {@link ScimWriteLockAdapter}, the one place a SCIM write's locks are taken;
     * {@code ArchitectureTest} holds that. One row by primary key, and so no order of its own: the
     * order of a write's locks is the order the adapter issues these statements in, which it
     * decides ({@code ScimWriteLocks.ORDER}). No engine can reorder a statement that locks a
     * single row, so the guarantee holds whatever the database (ADR 0013).
     *
     * <p>The serialization point of a conditional write. A second writer holding the same
     * precondition blocks here until the first commits, and its next statement then reads the
     * version the first one produced, so its precondition fails instead of both writes
     * succeeding. Native and returning the id alone, so it loads no entity: the reads after it are
     * the transaction's first, and see what the previous lock holder committed.
     */
    @Query(value = "SELECT id FROM scim_resources WITH (UPDLOCK, ROWLOCK) WHERE id = :id",
            nativeQuery = true)
    List<UUID> lockResource(@Param("id") UUID id);

    /**
     * Advances the version of every named resource and records when it changed.
     *
     * <p>Called only by {@link RepresentationChange}, which decides which resources a write
     * changed; {@code ArchitectureTest} holds that, so an adapter cannot work the ids out for
     * itself and miss one.
     *
     * <p>A bulk statement rather than a read-modify-write per row, and that is the point:
     * renaming a Group with two hundred members has to advance two hundred and one
     * versions, and doing it row by row would both cost two hundred round trips and leave
     * a window in which some members' ETags had moved and others' had not.
     *
     * <p>{@code version = version + 1} in the database rather than a value computed here,
     * so a concurrent bump cannot be lost: two statements incrementing the same row
     * serialize on it, where two writes of a locally-computed number would not.
     *
     * <p>The persistence context is flushed before and cleared after, for the reason the
     * account port's narrow writes were: the statement bypasses any managed copy of these
     * rows, so a stale one must not survive the call.
     *
     * <p>Each id is a bound parameter of its own, and SQL Server refuses a request with more
     * than 2,100, so {@code ids} holds at most {@link BoundIdChunks#MAX_IDS}:
     * {@link RepresentationChange#advanceIn} issues one statement per chunk, in one transaction.
     *
     * @return how many rows were advanced
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            update ScimResourceEntity r
               set r.version = r.version + 1,
                   r.lastModifiedAt = :now
             where r.id in :ids""")
    int advanceVersions(
            @Param("ids") Collection<UUID> ids, @Param("now") Instant now);

    /**
     * Deletes one resource row, and with it — through the {@code ON DELETE CASCADE} foreign keys
     * every related table declares — the User or Group row, its emails, credential history,
     * connector aliases and application-owned rows. Not its memberships: their foreign keys take
     * no action (ADR 0013), so the caller deletes them first or this statement is refused.
     *
     * <p>A bulk statement rather than {@code deleteById}, so the managed copies of the rows the
     * cascade removes cannot be flushed back afterwards: the context is flushed before and
     * cleared after, as for {@link #advanceVersions}.
     *
     * @return how many rows were deleted — zero when no resource had that id
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("delete from ScimResourceEntity r where r.id = :id")
    int deleteResource(@Param("id") UUID id);
}

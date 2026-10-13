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
     * Locks these resource rows {@code FOR UPDATE} until the transaction ends, one at a time in
     * ascending id order, and reports the ids that exist.
     *
     * <p>Called only by {@link ScimWriteLockAdapter}, the one place a SCIM write's locks are taken;
     * {@code ArchitectureTest} holds that. The order is the statement's own: Postgres locks rows as
     * the sort returns them, so every caller acquires in the database's {@code uuid} order — not
     * {@link UUID#compareTo}'s, which compares signed halves and disagrees with it. That guarantee
     * is Postgres's, not SQL's: an engine that locks rows as the scan reads them, before any sort
     * (SQL Server under {@code UPDLOCK}, ADR 0015), takes them in access-path order whatever the
     * {@code ORDER BY} says, so a switchover must re-establish the order here, the one place.
     *
     * <p>The serialization point of a conditional write. A second writer holding the same
     * precondition blocks here until the first commits, and its next statement then reads the
     * version the first one produced, so its precondition fails instead of both writes
     * succeeding. Native and returning ids alone, so it loads no entity: the reads after it are
     * the transaction's first, and see what the previous lock holder committed.
     */
    @Query(value = "SELECT id FROM scim_resources WHERE id IN (:ids) ORDER BY id FOR UPDATE",
            nativeQuery = true)
    List<UUID> lockInIdOrder(@Param("ids") Collection<UUID> ids);

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
     * memberships, connector aliases and application-owned rows.
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

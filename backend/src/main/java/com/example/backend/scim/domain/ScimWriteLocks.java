package com.example.backend.scim.domain;

import java.util.Collection;
import java.util.Comparator;
import java.util.UUID;

/**
 * Takes every resource lock a SCIM write needs, before the write reads or changes anything, in
 * the one canonical order: {@link #ORDER}, which this module decides and every engine obeys.
 *
 * <h2>Why one order</h2>
 *
 * <p>A SCIM write advances more than its own version. A User rename advances every Group the
 * User is in, a User deletion every Group it leaves, a Group write the members whose rendering of
 * it moved — and advancing a version locks that resource row. Two writers that each locked their
 * own subject first and the other's rows later would hold exactly what the other waits for: a
 * User DELETE racing a Group PUT on a shared membership deadlocked that way, as did the dormancy
 * job racing the DELETE. Every multi-row writer therefore locks its whole set here, sorted by
 * one rule, so no two of them can each hold a row the other still needs.
 *
 * <h2>Whose order</h2>
 *
 * <p>The module's, and that is the guarantee this interface makes: within one call, rows are
 * locked one after another in {@link #ORDER}, whatever order the caller named them in and
 * whatever database is underneath. The order is not left to a statement's {@code ORDER BY}: an
 * engine that locks rows as its scan reads them, before any sort (SQL Server under
 * {@code UPDLOCK}, ADR 0013), would take a batch in access-path order, and two writers would be
 * back to holding what the other waits for. Every caller locks through this module, so any total
 * order serves, provided there is exactly one.
 *
 * <h2>Which rows</h2>
 *
 * <p>A superset of the rows the write advances, because the exact set — decided by
 * {@link ResourceVersionAdvance} — depends on state that may only be read under the lock. Locking a
 * row the write then leaves alone costs some concurrency and nothing else:
 *
 * <ul>
 *   <li>a User write or deletion locks the User and every Group it is a direct member of —
 *       whether or not the write turns out to change the label those Groups render;
 *   <li>a Group write or deletion locks the Group, every current member, and every User the
 *       request names — the previous membership and the new one, whichever the write keeps.
 * </ul>
 *
 * <p>The related rows are read without a lock first, then locked with the subject; once the
 * subject is held no other writer can change its membership, so the set is read again and any
 * resource that joined in between is locked too. That second, later batch is out of the
 * canonical order, and the one window this leaves is a membership change committed between the
 * two reads by a writer this one waited for. A deadlock there still needs a third writer that
 * holds the joined row and waits on this one, and the database then detects it and fails one of
 * the two rather than hanging. Retrying the whole sorted batch would not close the window: a
 * transaction cannot give back row locks it already holds, so closing it means restarting the
 * transaction, which is the outermost transactional boundary's job (ADR 0013's deadlock retry),
 * not this module's.
 *
 * <p>Every call must come before the transaction's first read of the rows it locks, so no copy
 * read before the lock was granted can be served in place of the committed one. Two writers find
 * the row before they can name it, and say so: the startup seed's Admin-group restore, and the
 * login-state module, whose Login finds the User by name — that module decides from
 * {@link ScimLoginStateRepository#findLoginState}, a read no earlier copy can answer. A call must
 * also come before the transaction holds any other resource lock: a row already held when the
 * batch is asked for was taken outside the order. A resource that does not exist locks nothing,
 * and its absence is the following read's to report.
 */
public interface ScimWriteLocks {

    /**
     * The order every call takes its rows in: {@link UUID#compareTo}, Java's own.
     *
     * <p>Chosen because it is computed here and nowhere else, and deliberately not the engine's:
     * it compares the two halves as signed numbers, so it disagrees with SQL Server's
     * {@code uniqueidentifier} order (last byte group first, the first three groups from their
     * last byte). A lock statement that slid back to the engine's sort therefore breaks the
     * order, which {@code ScimWriteLockAdapterIntegrationTests} holds with two ids the orders
     * disagree on. Published so a test that must know which row a write takes second — to hold
     * it from elsewhere — can ask the module rather than a query.
     */
    Comparator<UUID> ORDER = Comparator.naturalOrder();

    /** A User PUT, PATCH or DELETE: the User, and every Group it is a direct member of. */
    void lockUserWrite(UUID userId);

    /**
     * A Group PUT, PATCH or DELETE: the Group, every current member, and every User the request
     * names — added, removed or kept.
     */
    void lockGroupWrite(UUID groupId, Collection<UUID> namedUsers);

    /**
     * Exactly these resources — for a writer that already knows every row it will advance: a
     * Group create's members, a password change's User, a dormancy run's candidates.
     */
    void lock(Collection<UUID> resourceIds);
}

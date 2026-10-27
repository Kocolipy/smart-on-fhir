package com.example.backend.scim.domain;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Persistence port for SCIM Users and the resource rows that carry their identity — the
 * directory's reads and writes. The login path's narrow writes to the same row are a port of their
 * own, {@link ScimLoginStateRepository}, which no SCIM use case depends on.
 *
 * <p>The test tree's {@code ScimUserRepositoryContract} holds the SQL Server adapter and the
 * in-memory store to the rules stated here alike.
 */
public interface ScimUserRepository {

    /**
     * Creates the resource row and the User row together, and reports the User as
     * stored.
     *
     * <p>Creates an UNRESERVED User. A reservation is applied only by
     * {@link #createReserved}, so no provisioning path can produce a resource that
     * protects itself from being changed.
     *
     * @throws DuplicateUserNameException when a live User already holds the
     *                                    normalized {@code userName}. Thrown from
     *                                    the adapter on the constraint violation
     *                                    rather than decided by a prior read, so two
     *                                    concurrent creates cannot both pass the
     *                                    check and then both insert.
     */
    ScimUser create(ScimUser user);

    /**
     * Creates a User the deployment reserves — the Bootstrap Admin — and reports it
     * with its marker set.
     *
     * <p>A separate operation rather than a reservation component on the value passed
     * to {@link #create}, because the two have different callers and only one of them
     * is allowed to exist: seeding. A single {@code create} that read a reservation off
     * its argument would be reachable from the SCIM create use case, and the only thing
     * stopping a connector from minting a protected resource would be that the use case
     * happens to pass null.
     *
     * <p>A reservation already held is not a taken {@code userName}: it fails with the store's
     * own integrity violation, which no caller translates. Either refusal is fatal to the calling
     * transaction, so seeding looks the reservation up first rather than catching one.
     *
     * @throws DuplicateUserNameException when a live User already holds the normalized
     *                                    {@code userName}
     */
    ScimUser createReserved(ScimUser user, ReservedResourceName reservedName);

    /** The live User with this id, or empty — including for an id of a Group. */
    Optional<ScimUser> findById(UUID id);

    /**
     * Replaces the User's profile with the given one, stores the given credential, change-required
     * flag and dormancy basis, and advances its version exactly once, reporting the User as it now
     * stands. When the write changed the label each of the User's Groups renders it with — its
     * {@code displayName}, or its {@code userName} when it has none — every one of those Groups'
     * versions advances with it, since each Group's document changed too.
     *
     * <p>Stores what it is given and decides nothing: the login state in {@code user} — a
     * Reactivation's reset basis and required change included — is the login-state module's
     * decision ({@link ScimUserLoginState#afterReplacement}), made by the caller from the User it read under
     * the resource lock.
     *
     * <p>Writes only what differs from that read. The login-state module now writes the failure
     * run and the dormancy basis on this row under the same resource lock, so a change cannot
     * land between the read and this write; writing only the changed columns still keeps the
     * store from depending on that, so an unchanged component is left to the module's writes
     * alone. The failure run and the lock in {@code user} are never stored here at all.
     *
     * @return empty when no live User has that id
     * @throws DuplicateUserNameException when the new {@code userName} is held by another live
     *                                    User — from the failed statement, not a prior read
     */
    Optional<ScimUser> replace(ScimUser user, Instant now);

    /**
     * Advances the User's own version and {@code meta.lastModified} and nothing else — for a
     * write whose only visible change is the calling connector's {@code externalId}, which no
     * User column records, so there is nothing for {@link #replace} to write.
     *
     * <p>No Group's version moves: a Group renders its members by id and label, never an alias.
     * A write matching no row advances nothing.
     */
    void advanceVersion(UUID id, Instant now);

    /**
     * The live User holding this normalized {@code userName}, or empty.
     *
     * <p>The login path's lookup. It takes the normalized form rather than the submitted
     * string so the comparison happens on the value the unique constraint is built on: a
     * lookup by the raw {@code userName} would be case-sensitive and would refuse a
     * correct password because of how the name was typed.
     */
    Optional<ScimUser> findByNormalizedUserName(NormalizedUserName normalizedUserName);

    /** The User the deployment reserves under this name, or empty before seeding has run. */
    Optional<ScimUser> findByReservedName(ReservedResourceName reservedName);

    /**
     * Every live User's stable id, in id order — the Bootstrap Admin's included. Ids only, for a
     * caller that has to visit every User's sessions and nothing else about it.
     */
    List<UUID> findAllIds();

    /**
     * Deletes the User — its resource row and, through the cascades, its profile, emails,
     * credential, password history, memberships and connector aliases — and advances the
     * version of every Group it was a member of, whose {@code members} just lost an entry.
     *
     * <p>A hard delete of the live rows: what survives a deletion is a tombstone, written by the
     * use case through {@link ScimTombstoneRepository}, and never this User in another state.
     * The Groups' versions advance BEFORE the delete, because afterwards no membership row is
     * left to read them from; both happen in the caller's transaction, so a failure leaves
     * neither.
     *
     * <p>Called only with the id of a User the caller holds under its resource lock, so there is
     * no "nothing to delete" answer to report: the lock is what guarantees the row is still there.
     */
    void deleteById(UUID id, Instant now);

    /**
     * The live Users with these ids, in the order the ids are given; an id naming no live User
     * is skipped.
     *
     * <p>How a query's page is loaded: the query port decided which Users and in what order,
     * and this assembles them without deciding either again. An id can name nothing by the
     * time it is loaded — the User was deleted between the two reads — and a page one short is
     * the stateless answer SCIM pagination already tells clients to tolerate.
     */
    List<ScimUser> findAllById(List<UUID> ids);
}

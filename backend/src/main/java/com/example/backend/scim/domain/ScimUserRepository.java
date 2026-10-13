package com.example.backend.scim.domain;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Persistence port for SCIM Users and the resource rows that carry their identity. */
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
     * @throws DuplicateUserNameException when a live User already holds the normalized
     *                                    {@code userName}, or when the reservation is
     *                                    already held — fatal to the calling transaction,
     *                                    so seeding looks first rather than catching this
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
     * The login state of the live User with this id as the database holds it, or empty — read by
     * a statement of its own, never served from a copy of the User the calling transaction read
     * earlier.
     *
     * <p>The login-state module's read under the User's resource lock ({@link ScimWriteLocks}).
     * Its callers may have read the User before the lock was granted — a Login has to find the
     * User by name before it can name the row to lock — so a re-read that could hand back that
     * earlier copy would decide from the state a concurrent writer has since replaced, which is
     * the lost update the lock exists to prevent.
     */
    Optional<ScimLoginState> findLoginState(UUID id);

    /**
     * Writes only the authentication-state columns of a User that already exists.
     *
     * <p>Narrow on purpose, and in two directions at once. It cannot revert a profile
     * attribute an administrator or a connector changed in between — the login path
     * writes this row on every rejected attempt, and a full-row write from a value read
     * at the start of the attempt would race them. And it deliberately does NOT advance
     * the version or {@code lastModified}: a failure run and a lock instant are not SCIM
     * attributes, so nothing a client can read has changed, and moving the ETag would
     * make every failed login invalidate every connector's cached copy.
     *
     * <p>Only the failure run and the lock instant are written; the state's password hash is
     * IGNORED. The login path reads the User at the start of an attempt and writes this at the
     * end, so writing the hash it read would revert a password a connector changed in between —
     * silently restoring a credential that had just been replaced. The credential is written by
     * {@link #replace} and by nothing else.
     */
    void updateLoginState(UUID id, ScimLoginState loginState);

    /**
     * Records use of the account — a login with no password change owed, or a completed
     * self-service change: writes {@code lastAuthenticatedAt} and nothing else.
     *
     * <p>Narrow for the reason {@link #updateLoginState} is, and like it this does NOT
     * advance the version: the dormancy basis is not a SCIM attribute, so a login changes
     * nothing a connector reads. A write matching no row is silently nothing — the User was
     * deleted between authenticating and this call.
     */
    void recordAuthentication(UUID id, Instant authenticatedAt);

    /**
     * Every live User's stable id, in id order — the Bootstrap Admin's included. Ids only, for a
     * caller that has to visit every User's sessions and nothing else about it.
     */
    List<UUID> findAllIds();

    /**
     * The ids of every unlocked, unreserved User whose dormancy basis — {@code lastAuthenticatedAt},
     * or the creation time when it has never authenticated — lies strictly before
     * {@code cutoff}, ordered by id. Active or not: the lock is the application's own, and
     * {@code active} is the directory's.
     *
     * <p>The dormancy job's lockout candidates. Candidates only: the job re-reads each under its
     * resource lock and decides again, so a User that logged in or was unlocked between this read
     * and that one is left alone. Reserved Users are excluded here so the Bootstrap Admin is never
     * so much as locked by the job; the job checks the exemption again regardless.
     */
    List<UUID> findDormantUnlockedUserIds(Instant cutoff);

    /**
     * Stores {@code lockout} — its instant and its cause — when, and only when, no lock stands,
     * and writes nothing else. The lock and its cause are the login-state module's decision; this
     * stores them.
     *
     * <p>Conditional in the statement itself, a compare-and-set rather than a rule: a lock that
     * stands by the write is kept with its own cause whatever the caller read, so the store does
     * not depend on the caller having serialized with the login path — though the login-state
     * module, its one caller, does. The failure run is never written here at all. Not a SCIM
     * attribute: the version does not move.
     *
     * @return whether this call stored the lock
     */
    boolean imposeLock(UUID id, Lockout lockout);

    /**
     * Restarts the dormancy window as of {@code at}: writes {@code lastAuthenticatedAt} and
     * nothing else. An administrator's Unlock, so that the next dormancy run does not lock again
     * a User that has not yet had the chance to sign in. Not a SCIM attribute: the version does
     * not move. A write matching no row is silently nothing.
     */
    void resetDormancyBasis(UUID id, Instant at);

    /**
     * Sets the change-required flag as of {@code since}, and nothing else.
     *
     * <p>Narrow for the reason {@link #updateLoginState} is: every setter of the flag — a
     * connector's password write, an Admin's forced change, an Unlock — is racing the login path's
     * own writes to the same row, and a whole-state write would revert whichever landed between the
     * read and this call. It does NOT advance the version: the flag is not a SCIM attribute, so
     * setting it changes nothing a connector reads. A write matching no row is silently nothing.
     */
    void requirePasswordChange(UUID id, Instant since);

    /**
     * Replaces the credential and clears the change-required flag in one write — the self-service
     * change, and the only operation that clears the flag. Advances the version and
     * {@code lastModified}, because {@code password} is a SCIM attribute even though its value is
     * never rendered.
     *
     * @return the User as it now stands, or empty when no User has that id
     */
    Optional<ScimUser> completePasswordChange(UUID id, String passwordHash, Instant now);

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
     * Every live User, ordered by the normalized {@code userName}.
     *
     * <p>For the administrative listing, which is a complete review of who can log in
     * rather than a page of a directory. Ordering is the port's promise because only the
     * adapter can push it into the query, and the normalized form is the key so the order
     * does not depend on case.
     */
    List<ScimUser> findAllOrderedByNormalizedUserName();

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

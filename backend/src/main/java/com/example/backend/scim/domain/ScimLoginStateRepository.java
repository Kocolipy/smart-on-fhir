package com.example.backend.scim.domain;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Persistence port for a SCIM User's login state: the failure run and the lock, the dormancy basis,
 * the change-required flag and the completed password change — the columns the login path writes
 * on the User's row, beside the directory's.
 *
 * <p>A port of its own, apart from {@link ScimUserRepository}, because its client is a different
 * one: the login-state module ({@code auth.application.LoginStateService}) is the one writer of
 * these columns, and the dormancy job reads its candidates here. No SCIM use case depends on it;
 * the directory reaches the login state only through the module.
 *
 * <p>Each write is narrow, and says which columns it touches and whether it advances the
 * Resource version. Those rules are not left to the javadoc alone: the test tree's
 * {@code ScimLoginStateRepositoryContract} holds the SQL Server adapter and the in-memory store to
 * them alike.
 */
public interface ScimLoginStateRepository {

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
     * {@link ScimUserRepository#replace} and by nothing else.
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
}

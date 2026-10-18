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
 * <p>Three writes. A login-state transition is written as what it changed ({@link #writeChange}):
 * the module encodes each transition once, on {@link ScimLoginState}, and hands this the state it
 * read and the state it decided. Two writes are not plain column diffs and stay operations of
 * their own: imposing a lock is a compare-and-set ({@link #imposeLock}), and completing a password
 * change replaces the credential and advances the version ({@link #completePasswordChange}).
 *
 * <p>Each write says which columns it touches and whether it advances the Resource version. Those
 * rules are not left to the javadoc alone: the test tree's {@code ScimLoginStateRepositoryContract}
 * holds the SQL Server adapter and the in-memory store to them alike.
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
     * Writes what one login-state transition changed: {@code before} is the state the login-state
     * module read under the User's lock ({@link #findLoginState}), {@code after} the state it
     * decided. Of the four login-state column sets — the failure run, the lock (its instant and its
     * cause), the dormancy basis, the change-required flag — exactly those on which the two differ
     * are written, as {@code after} holds them ({@link ScimLoginStateChange}); a set on which they
     * agree is not written at all, and when they agree everywhere nothing is.
     *
     * <p>Narrow on purpose, and in two directions at once. It cannot revert a profile attribute an
     * administrator or a connector changed in between, nor a login-state column the transition left
     * alone: the login path writes this row on every rejected attempt, and a full-row write — or a
     * whole-state write — from a value read at the start would race them. And it does NOT advance
     * the version or {@code lastModified}: none of these columns is a SCIM attribute, so nothing a
     * client can read has changed, and moving the ETag would make every failed login invalidate
     * every connector's cached copy.
     *
     * <p>The credential is never written here, whatever either state holds: the module reads the
     * hash with the rest of the state, so writing it would revert a password a connector changed in
     * between — silently restoring a credential that had just been replaced. The credential is
     * written by {@link ScimUserRepository#replace} and {@link #completePasswordChange}, and by
     * nothing else. A write matching no row is silently nothing — the User was deleted between the
     * read and the write.
     */
    void writeChange(UUID id, ScimLoginState before, ScimLoginState after);

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
     * Replaces the credential and clears the change-required flag in one write — the self-service
     * change, and the only transition that clears the flag. Advances the version and
     * {@code lastModified}, because {@code password} is a SCIM attribute even though its value is
     * never rendered.
     *
     * @return the User as it now stands, or empty when no User has that id
     */
    Optional<ScimUser> completePasswordChange(UUID id, String passwordHash, Instant now);
}

package com.example.backend.scim.infrastructure.persistence;

import com.example.backend.scim.infrastructure.persistence.entity.ScimLoginStateValue;
import com.example.backend.scim.infrastructure.persistence.entity.ScimUserEntity;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Spring Data access to the SCIM User tables.
 *
 * <h2>A User is read in one statement</h2>
 *
 * <p>Every query here that returns {@link ScimUserEntity} fetches its resource row and its emails
 * in the same statement, through an entity graph naming both. Left to the mapping, Hibernate reads the
 * User rows first and the eager resource and emails in later statements. Under READ COMMITTED each
 * statement sees what had committed when that statement began. A deletion that commits between
 * them therefore leaves a User row whose resource has gone, and the read fails with
 * {@code ObjectNotFoundException}: a {@code 500} for a User that was merely deleted while the
 * listing ran. One statement sees one snapshot, on PostgreSQL READ COMMITTED and on SQL Server
 * with {@code READ_COMMITTED_SNAPSHOT ON} alike (ADR 0015). The User is then read whole or not at
 * all. {@code findById} needs no graph: a load by id already joins both.
 */
interface ScimUserJpaRepository extends JpaRepository<ScimUserEntity, UUID> {

    /** Every User, in the given order, each read in the same statement as its resource. */
    @Override
    @EntityGraph(attributePaths = {"resource", "emails"})
    List<ScimUserEntity> findAll(Sort sort);

    /** The Users with these ids, a SCIM query's page, each read with its resource. */
    @Override
    @EntityGraph(attributePaths = {"resource", "emails"})
    List<ScimUserEntity> findAllById(Iterable<UUID> ids);

    /**
     * The User holding this normalized {@code userName}, or empty.
     *
     * <p>The login path's lookup, and the only one there is: the raw {@code userName}
     * column is deliberately not queried, because it is case-sensitive and the
     * uniqueness constraint is not — a lookup by it would refuse a correct password on
     * account of how the name was typed.
     */
    @EntityGraph(attributePaths = {"resource", "emails"})
    Optional<ScimUserEntity> findByNormalizedUserName(String normalizedUserName);

    /**
     * The User the deployment reserves under this name, or empty before seeding has run.
     *
     * <p>Left without the graph: a reserved User cannot be deleted, so no deletion can land
     * between this read and its resource's.
     */
    Optional<ScimUserEntity> findByResource_ReservedName(String reservedName);

    /**
     * The User's login columns as the row holds them, or empty when no User has that id.
     *
     * <p>Selected column by column into a new value rather than as the entity, and that is the
     * point: an entity the persistence context already manages is handed back as the context holds
     * it, whatever the row says now, while scalar columns are read from the result set. So a
     * caller that read the User before taking its lock still decides from the committed row.
     */
    @Query("""
            select new com.example.backend.scim.infrastructure.persistence.entity.ScimLoginStateValue(
                       u.login.passwordHash,
                       u.login.failedLoginAttempts,
                       u.login.lockedAt,
                       u.login.lockCause,
                       u.login.lastAuthenticatedAt,
                       u.login.passwordChangeRequiredSince)
              from ScimUserEntity u
             where u.resourceId = :id""")
    Optional<ScimLoginStateValue> findLoginState(@Param("id") UUID id);

    /**
     * Writes the failure run, the lock instant and the lock's cause, and nothing else.
     *
     * <p>Narrow for the reason the account port's writes were: the login path writes this
     * row on every rejected attempt, and a full-row write from a value read at the start
     * of the attempt could revert a profile change that landed in between.
     *
     * <p>It deliberately does not touch {@code scim_resources}. None of these three
     * columns is a SCIM attribute, so nothing a client can read has changed and the
     * resource's version must not move — otherwise every wrong password would invalidate
     * every connector's cached copy of the User.
     *
     * @return how many rows were written; zero when no User has that id
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            update ScimUserEntity u
               set u.login.failedLoginAttempts = :failedLoginAttempts,
                   u.login.lockedAt = :lockedAt,
                   u.login.lockCause = :lockCause
             where u.resourceId = :id""")
    int updateLoginState(
            @Param("id") UUID id,
            @Param("failedLoginAttempts") int failedLoginAttempts,
            @Param("lockedAt") Instant lockedAt,
            @Param("lockCause") String lockCause);

    /**
     * Stores the given lock instant and cause where no lock stands, and writes nothing else. The
     * values are the caller's; the {@code lockedAt is null} condition is a compare-and-set, so a
     * lock imposed in the meantime, and its cause, are kept as they are. Not a SCIM attribute, so
     * the resource row and its version are untouched.
     *
     * @return how many rows were written; zero when the User is already locked or gone
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            update ScimUserEntity u
               set u.login.lockedAt = :lockedAt,
                   u.login.lockCause = :lockCause
             where u.resourceId = :id
               and u.login.lockedAt is null""")
    int imposeLock(
            @Param("id") UUID id,
            @Param("lockedAt") Instant lockedAt,
            @Param("lockCause") String lockCause);

    /**
     * Writes the dormancy basis alone. Not a SCIM attribute, so the resource row — and with it
     * the version — is untouched.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            update ScimUserEntity u
               set u.login.lastAuthenticatedAt = :authenticatedAt
             where u.resourceId = :id""")
    int recordAuthentication(
            @Param("id") UUID id, @Param("authenticatedAt") Instant authenticatedAt);

    /**
     * Unlocked, unreserved Users — active or not — whose dormancy basis, the last authentication
     * or creation when there has been none, is strictly before the cutoff.
     */
    @Query("""
            select u.resourceId
              from ScimUserEntity u
             where u.login.lockedAt is null
               and u.resource.reservedName is null
               and coalesce(u.login.lastAuthenticatedAt, u.resource.createdAt) < :cutoff
             order by u.resourceId""")
    List<UUID> findDormantUnlockedUserIds(@Param("cutoff") Instant cutoff);

    /** Every live User's stable id, in id order. */
    @Query("select u.resourceId from ScimUserEntity u order by u.resourceId")
    List<UUID> findAllIds();

    /** Sets the change-required flag alone; the resource row and its version are untouched. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            update ScimUserEntity u
               set u.login.passwordChangeRequiredSince = :since
             where u.resourceId = :id""")
    int requirePasswordChange(@Param("id") UUID id, @Param("since") Instant since);

    /**
     * Replaces the credential and clears the change-required flag in one statement. The caller
     * advances the version, because {@code password} is a SCIM attribute.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            update ScimUserEntity u
               set u.login.passwordHash = :passwordHash,
                   u.login.passwordChangeRequiredSince = null
             where u.resourceId = :id""")
    int completePasswordChange(
            @Param("id") UUID id, @Param("passwordHash") String passwordHash);
}

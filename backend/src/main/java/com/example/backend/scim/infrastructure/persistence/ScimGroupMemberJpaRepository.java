package com.example.backend.scim.infrastructure.persistence;

import com.example.backend.scim.domain.ScimGroupMembership;
import com.example.backend.scim.domain.ScimGroupReference;
import com.example.backend.scim.infrastructure.persistence.entity.ScimGroupMemberEntity;
import com.example.backend.scim.infrastructure.persistence.entity.ScimGroupMemberId;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Spring Data access to Group membership.
 *
 * <p>Every read here is a projection rather than a fetch of entities, and that is the
 * reason membership is not mapped as an association on {@code ScimGroupEntity}: each of
 * the three shapes membership is read in wants two or three columns, and an association
 * would load whole Users — each with its own eager resource row and email collection — to
 * produce them.
 */
interface ScimGroupMemberJpaRepository
        extends JpaRepository<ScimGroupMemberEntity, ScimGroupMemberId> {

    /**
     * The memberships of these Groups, each with its member's rendered label.
     *
     * <p>Takes a collection so a page of Groups costs one query rather than one per Group.
     *
     * <p>The label is {@code coalesce(displayName, userName)}, the SQL statement of
     * {@link com.example.backend.scim.domain.ScimUserProfile#memberLabel(String, String)}: the
     * two must agree, because the Java rule decides whether a User write changed what this
     * query renders. Resolved here, in the same statement as the membership, because it is
     * read-only — RFC 7643 §4.2 derives it from the referenced resource, so there is no stored
     * value it could come from.
     *
     * <p>Ordered by the member's normalized {@code userName} so a Group's membership renders
     * the same way on every read. SCIM does not require it; a set that reshuffled between
     * reads would make a diff-based client rewrite the resource forever.
     */
    @Query("""
            select new com.example.backend.scim.infrastructure.persistence.ScimGroupMemberRow(
                       m.id.groupId, m.id.userId, coalesce(u.displayName, u.userName))
              from ScimGroupMemberEntity m
              join ScimUserEntity u on u.resourceId = m.id.userId
             where m.id.groupId in :groupIds
             order by u.normalizedUserName asc""")
    List<ScimGroupMemberRow> findMembersOfGroups(@Param("groupIds") Collection<UUID> groupIds);

    /**
     * The Groups this User is a direct member of — the read-only reverse view.
     *
     * <p>Returns references rather than Groups so one User's read does not carry every
     * other member of every Group it belongs to.
     */
    @Query("""
            select new com.example.backend.scim.domain.ScimGroupReference(
                       g.resourceId, g.displayName)
              from ScimGroupMemberEntity m
              join ScimGroupEntity g on g.resourceId = m.id.groupId
             where m.id.userId = :userId
             order by g.normalizedDisplayName asc""")
    List<ScimGroupReference> findGroupsOfUser(@Param("userId") UUID userId);

    /**
     * The member ids of one Group, for deciding whose versions a write has to advance.
     *
     * <p>No label, because nothing renders these: they are read immediately before the
     * membership is replaced, so that the set of Users whose representation is about to
     * change can be computed against the set it is being replaced with.
     */
    @Query("select m.id.userId from ScimGroupMemberEntity m where m.id.groupId = :groupId")
    List<UUID> findMemberIds(@Param("groupId") UUID groupId);

    /**
     * The ids of the Groups this User is a direct member of, for deciding whose versions a
     * User's deletion has to advance — each of them is about to lose a member.
     */
    @Query("select m.id.groupId from ScimGroupMemberEntity m where m.id.userId = :userId")
    List<UUID> findGroupIdsOfUser(@Param("userId") UUID userId);

    /**
     * Whether this User is a direct member of the Group carrying this reservation.
     *
     * <p>How administrative authority is derived, in one statement. The reservation is the
     * join condition rather than the Group's {@code displayName}, so authority does not
     * depend on a value that — for any other Group — a connector may change.
     */
    @Query("""
            select count(m) > 0
              from ScimGroupMemberEntity m
              join ScimGroupEntity g on g.resourceId = m.id.groupId
             where m.id.userId = :userId
               and g.resource.reservedName = :reservedName""")
    boolean isMemberOfReservedGroup(
            @Param("userId") UUID userId, @Param("reservedName") String reservedName);

    /**
     * Removes every membership of one Group, for a replacement or a delete.
     *
     * <p>Delete-then-insert rather than a computed diff. The rows have no attributes, so
     * nothing is lost by rewriting them, and a diff would be a second implementation of
     * "what changed" beside the one the version bumps already need.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("delete from ScimGroupMemberEntity m where m.id.groupId = :groupId")
    int deleteMembershipsOf(@Param("groupId") UUID groupId);

    /**
     * Removes every membership this User holds, for its deletion.
     *
     * <p>The member-side foreign keys take no action (ADR 0013), so the User's resource row
     * cannot be deleted while a membership still names it; nothing removes these rows but this.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("delete from ScimGroupMemberEntity m where m.id.userId = :userId")
    int deleteMembershipsHeldBy(@Param("userId") UUID userId);

    /**
     * Removes one membership row and nothing else.
     *
     * @return how many rows were removed — zero when the User was not a member
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            delete from ScimGroupMemberEntity m
             where m.id.groupId = :groupId
               and m.id.userId = :userId""")
    int deleteMembership(@Param("groupId") UUID groupId, @Param("userId") UUID userId);

    /**
     * The direct memberships of these Groups held by unreserved Users whose dormancy basis — the
     * last authentication, or creation when there has been none — is strictly before the cutoff.
     * Active or locked or not: Roles are revoked from a dormant User whatever its standing.
     */
    @Query("""
            select new com.example.backend.scim.domain.ScimGroupMembership(
                       u.resourceId, m.id.groupId)
              from ScimGroupMemberEntity m
              join ScimUserEntity u on u.resourceId = m.id.userId
             where m.id.groupId in :groupIds
               and u.resource.reservedName is null
               and coalesce(u.login.lastAuthenticatedAt, u.resource.createdAt) < :cutoff
             order by u.resourceId, m.id.groupId""")
    List<ScimGroupMembership> findDormantMemberships(
            @Param("groupIds") Collection<UUID> groupIds, @Param("cutoff") Instant cutoff);
}

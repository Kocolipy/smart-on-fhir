package com.example.backend.scim.infrastructure.persistence;

import com.example.backend.scim.infrastructure.persistence.entity.ScimGroupEntity;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Spring Data access to the SCIM Group table.
 *
 * <p>A page by id reads each Group in the same statement as its resource row, for the reason
 * {@link ScimUserJpaRepository} gives. Left to the mapping, a Group deleted between the two
 * statements would fail the read with {@code ObjectNotFoundException}. {@code findById} already
 * joins the resource.
 */
interface ScimGroupJpaRepository extends JpaRepository<ScimGroupEntity, UUID> {

    /** The Groups with these ids, a SCIM query's page, each read with its resource. */
    @Override
    @EntityGraph(attributePaths = "resource")
    List<ScimGroupEntity> findAllById(Iterable<UUID> ids);

    /**
     * The Groups with these ids, each with its stored reservation and its direct member count, in
     * no particular order: one statement, the membership counted by a correlated subquery rather
     * than read.
     */
    @Query("""
            select new com.example.backend.scim.infrastructure.persistence.ScimGroupHeadcountRow(
                       g.resourceId, g.displayName, r.reservedName,
                       (select count(m) from ScimGroupMemberEntity m
                         where m.id.groupId = g.resourceId))
              from ScimGroupEntity g
              join g.resource r
             where g.resourceId in :ids""")
    List<ScimGroupHeadcountRow> findHeadcountsById(@Param("ids") Collection<UUID> ids);

    /**
     * The Group the deployment reserves under this name, or empty before seeding has run.
     *
     * <p>Left without the graph: the reserved Group cannot be deleted, so no deletion can land
     * between this read and its resource's.
     */
    Optional<ScimGroupEntity> findByResource_ReservedName(String reservedName);

    /**
     * Writes the two name columns and nothing else.
     *
     * <p>Narrow for the reason every other write here is: it cannot revert the reservation
     * marker or the creation timestamp, which a full-row write assembled from a value read
     * earlier could.
     *
     * @return how many rows were written; zero when no Group has that id
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            update ScimGroupEntity g
               set g.displayName = :displayName,
                   g.normalizedDisplayName = :normalizedDisplayName
             where g.resourceId = :id""")
    int updateDisplayName(
            @Param("id") UUID id,
            @Param("displayName") String displayName,
            @Param("normalizedDisplayName") String normalizedDisplayName);
}

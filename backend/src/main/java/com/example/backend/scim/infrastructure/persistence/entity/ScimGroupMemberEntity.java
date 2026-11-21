package com.example.backend.scim.infrastructure.persistence.entity;

import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.util.UUID;

/**
 * Database representation of one Group membership.
 *
 * <p>Nothing but its key: a membership relates a Group to a User and carries no attribute
 * of its own, because SCIM derives every rendered sub-attribute of {@code members} other
 * than {@code value} from the referenced resource.
 *
 * <p>An entity of its own rather than a collection mapped on {@link ScimGroupEntity},
 * because membership is read and written in three different shapes that a single mapping
 * would serve badly: rendered for one Group with each member's label joined in, rendered
 * in reverse for one User, and asked as a one-row authority question at login. Each is a
 * statement over this table; none of them wants a Group's whole object graph.
 *
 * <p>The foreign keys are in the schema rather than as associations here, deliberately.
 * {@code user_id} references the User table and not the shared resource table, which is
 * what makes "direct User members only" a constraint the database enforces: a Group's id,
 * a deleted User's id and an id that names nothing are all the same violation.
 */
@Entity
@Table(name = "scim_group_members")
public class ScimGroupMemberEntity {

    @EmbeddedId
    private ScimGroupMemberId id;

    protected ScimGroupMemberEntity() {
    }

    public ScimGroupMemberEntity(UUID groupId, UUID userId) {
        this.id = new ScimGroupMemberId(groupId, userId);
    }

    /*
     * No accessor for the id, deliberately. A membership is never read through this entity:
     * every read of a Group's members goes through a JPQL projection on
     * ScimGroupMemberJpaRepository that selects `m.id.userId` (or joins to the User table) so
     * it fetches exactly the columns it renders, and a Group's memberships are written by one
     * set-based statement there rather than an entity each. The entity is what those JPQL
     * statements name. Hibernate reads the @EmbeddedId field directly, so nothing needs a
     * getter.
     */
}

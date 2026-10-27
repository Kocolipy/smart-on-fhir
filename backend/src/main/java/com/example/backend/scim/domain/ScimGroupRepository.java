package com.example.backend.scim.domain;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Persistence port for SCIM Groups, their membership, and the authority it confers. */
public interface ScimGroupRepository {

    /**
     * Creates the resource row, the Group row and its memberships together, and reports
     * the Group as stored.
     *
     * <p>Advances the version of every User the new Group names, for the reason
     * {@link #replace} does: a create WITH members is a membership change for those Users,
     * whose rendered {@code groups} gains an entry, and a representation that changed behind
     * an unmoved ETag is one a connector will never re-read. The new Group's own version is 1
     * by definition and is not advanced.
     *
     * @throws DuplicateDisplayNameException when a live Group already holds the normalized
     *                                       {@code displayName}
     * @throws UnknownGroupMemberException    when a member id does not name a live User —
     *                                       which covers a Group's id and an id that names
     *                                       nothing, because the membership row's foreign
     *                                       key points at the User table
     */
    ScimGroup create(ScimGroup group);

    /**
     * Creates the Group the deployment reserves — the Admin group — and reports it with its
     * marker set.
     *
     * <p>A separate operation rather than a reservation component on the value passed to
     * {@link #create}, for the reason the User port splits the two: a single {@code create}
     * that read a reservation off its argument would be reachable from the SCIM create use
     * case, and the only thing stopping a connector from minting a Group that claims
     * administrative authority would be that the use case happens to pass null.
     *
     * <p>A reservation already held is not a taken {@code displayName}: it fails with the store's
     * own integrity violation, which no caller translates. Either refusal is fatal to the calling
     * transaction, so seeding looks the reservation up first rather than catching one.
     *
     * @throws DuplicateDisplayNameException when a live Group already holds the normalized
     *                                       {@code displayName}
     */
    ScimGroup createReserved(ScimGroup group, ReservedResourceName reservedName);

    /** The live Group with this id, or empty — including for an id of a User. */
    Optional<ScimGroup> findById(UUID id);

    /**
     * The Group the deployment reserves under this name, or empty before seeding has run.
     *
     * <p>How authority is derived: the Admin group is resolved through its reservation and
     * never through its {@code displayName}, so derivation does not depend on an attribute
     * that — for any other Group — a connector could change.
     */
    Optional<ScimGroup> findByReservedName(ReservedResourceName reservedName);

    /**
     * Replaces everything a connector may write — the display name and the whole
     * membership — and advances the version of every resource the change is visible in.
     *
     * <p><strong>The version bumps are this port's promise, not the caller's.</strong>
     * A membership change advances the Group's version and the version of every User
     * added or removed, because a User's rendered {@code groups} attribute changed. A
     * display-name change advances the Group's version and the version of every CURRENT
     * member, because the label rendered inside their {@code groups} changed. Only the
     * adapter can do that in one statement per affected set, and a caller that had to
     * remember it would be a caller that eventually forgets — leaving a User whose
     * representation changed behind an ETag that says it did not.
     *
     * <p>Narrow on purpose, like the User port's login-state write: it touches the
     * attributes a Group write owns and nothing else, so it cannot revert the reservation
     * marker or the creation timestamp.
     *
     * <p>Returns the Group as STORED, whose version is the one the database computed —
     * empty when no Group has that id, which happens when it was deleted between the use
     * case's read and this write. Reporting absence rather than throwing keeps a race
     * indistinguishable from an ordinary {@code 404}, which is what a caller should see.
     *
     * @throws DuplicateDisplayNameException when another live Group already holds the
     *                                       normalized {@code displayName}
     * @throws UnknownGroupMemberException    when a member id does not name a live User
     */
    Optional<ScimGroup> replace(ScimGroup group);

    /**
     * Advances the Group's own version and {@code meta.lastModified} and nothing else — for a
     * write whose only visible change is the calling connector's {@code externalId}, which
     * {@link #replace} cannot see because the alias is not a Group column.
     *
     * <p>No member's version moves: a User's rendered {@code groups} carries the Group's id and
     * label, never an alias, so nothing a member renders has changed.
     *
     * @return the Group as stored, or empty when no Group has that id
     */
    Optional<ScimGroup> advanceVersion(UUID id, Instant now);

    /**
     * Deletes the Group and its memberships, advancing the version of every User that was
     * a member — their {@code groups} attribute just lost an entry.
     *
     * <p>Reports whether a Group was there to delete, rather than throwing: the use case
     * turns absence into a {@code 404}, and a port that threw would make "already gone"
     * indistinguishable from a failure.
     *
     * <p>No tombstone is written here. Tombstones and reusable identifiers arrive with
     * their own ticket; this is a hard delete of the rows, which is what makes the
     * {@code displayName} immediately reusable in the meantime.
     *
     * @param now when the deletion happened, for the {@code lastModified} of the Users
     *            whose versions it advances. Passed in rather than read from a clock here,
     *            because a port that read the system clock could not be exercised without
     *            one.
     */
    boolean deleteById(UUID id, Instant now);

    /**
     * The live Groups with these ids, in the order the ids are given; an id naming no live
     * Group is skipped.
     *
     * <p>How a query's page is loaded. The query port decided which Groups and in what order;
     * this assembles them — memberships included, in one membership read for the whole page —
     * without deciding either again.
     */
    List<ScimGroup> findAllById(List<UUID> ids);

    /**
     * The live Groups with these ids, each with its direct member count, in the order the ids are
     * given; an id naming no live Group is skipped.
     *
     * <p>How a page of the Groups projection is loaded, which shows how big each Group is and not
     * who is in it: the query port decided which Groups and in what order, and this counts their
     * memberships where they are stored, never reading them to be counted. A page is at most
     * {@code ScimPageRequest.MAX_COUNT} ids, so it binds whole.
     */
    List<ScimGroupHeadcount> findHeadcountsById(List<UUID> ids);

    /**
     * The direct Groups of each of these Users, by User — each User's in normalized
     * {@code displayName} order, as {@link #findGroupsOfUser} answers one — and no entry for a
     * User in no Group.
     *
     * <p>How a page of the Users projection reads its Groups column: one membership read for the
     * page's Users, not one per User, and never the membership of every Group. A page is at most
     * {@code ScimPageRequest.MAX_COUNT} ids, so it binds whole.
     */
    Map<UUID, List<ScimGroupReference>> findGroupsOfUsers(Collection<UUID> userIds);

    /**
     * Whether a live Group has this id — {@code false} for an id of a User — without reading its
     * membership.
     */
    boolean existsById(UUID id);

    /**
     * The Groups this User is a direct member of, for the read-only reverse view on User.
     *
     * <p>Returns references rather than Groups: rendering a User's {@code groups} needs the
     * id and the label and nothing else, and returning whole Groups would carry every
     * other member of every Group the User belongs to into a single User's read.
     *
     * <p>There is deliberately no write counterpart. The reverse view is computed, so a
     * submitted {@code groups} attribute is ignored — and it is ignored because no port
     * operation exists that could apply it, rather than because a handler remembers to
     * drop it.
     */
    List<ScimGroupReference> findGroupsOfUser(UUID userId);

    /**
     * Every direct membership, of any Group, held by an unreserved User whose dormancy basis lies
     * strictly before {@code cutoff}, ordered by User id and then Group id — active or not, locked
     * or not.
     *
     * <p>The dormancy job's membership-revocation candidates, on the same basis as
     * {@link ScimLoginStateRepository#findDormantUnlockedUserIds}: {@code lastAuthenticatedAt}, or
     * the creation time for a User that has never authenticated. Candidates only; the job re-reads
     * each User under its lock and decides again.
     */
    List<ScimGroupMembership> findDormantMemberships(Instant cutoff);

    /**
     * Removes one User's direct membership of one Group and advances the version of both, exactly
     * as a connector-driven removal does: the Group's {@code members} and the User's
     * {@code groups} each lost an entry.
     *
     * <p>Narrow on purpose: every other membership of the Group, and every other Group the User
     * belongs to, is untouched — which is what a wholesale {@link #replace} could not promise
     * without re-reading the whole membership inside the caller's lock.
     *
     * @return whether a membership was there to remove; nothing is advanced when it was not
     */
    boolean removeMember(UUID groupId, UUID userId, Instant now);
}

package com.example.backend.scim.domain;

import java.time.Instant;
import java.util.Collection;
import java.util.UUID;

/**
 * Membership revocation — the dormancy job's second step (ADR 0007) — as the directory carries it
 * out.
 *
 * <p>A port in the directory's domain for the login surface to call, because the dependency
 * between the two slices runs one way: the dormancy job lives in {@code auth} and decides
 * <em>whom</em> to revoke, while what removing a Group membership implies — the versions it
 * advances, the event it records and the Sessions it ends — is decided and carried out by the
 * directory, through the same apply step as a connector's write to the Group.
 */
public interface ScimMembershipRevocation {

    /**
     * Removes the User's direct membership of each of these Groups and carries out what that
     * implies: each Group's version and the User's advance, one actorless
     * {@code DORMANCY_MEMBERSHIP_REVOCATION} event and log record names every Group removed and the
     * User's effective Permissions before and after, and the User's Sessions end once the calling
     * transaction commits. A Group whose membership is already gone is skipped, and a User that
     * lost no membership records nothing.
     *
     * <p>The caller holds the resource locks of the User and of every one of these Groups — taken
     * together through {@link ScimWriteLocks}, in its one order — and has decided on a read under
     * them that the User is due.
     *
     * @param groupIds Groups the User was a candidate member of
     * @return whether the User lost a membership
     */
    boolean revokeMemberships(UUID userId, Collection<UUID> groupIds, Instant now);
}

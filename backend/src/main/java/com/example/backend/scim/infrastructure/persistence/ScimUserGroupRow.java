package com.example.backend.scim.infrastructure.persistence;

import java.util.UUID;

/**
 * One direct membership as the batched reverse view reads it: which User, and the Group it is in
 * by id and name.
 *
 * <p>The reverse of {@link ScimGroupMemberRow}: a page of Users costs one membership query
 * instead of one per User, and a batch read needs the User's id beside each Group, which the
 * domain's {@code ScimGroupReference} — answered for one User already known — does not carry.
 * Package-private for the same reason: the shape of a query, grouped by {@link #userId()} and
 * thrown away.
 *
 * @param userId      the member User's stable id
 * @param groupId     the Group's stable id
 * @param displayName the Group's display name
 */
record ScimUserGroupRow(UUID userId, UUID groupId, String displayName) {
}

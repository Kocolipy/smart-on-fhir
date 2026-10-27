package com.example.backend.scim.infrastructure.persistence;

import java.util.UUID;

/**
 * One Group as the counted listing reads it: its identity, its stored reservation and its
 * membership counted in the same statement.
 *
 * <p>Package-private for the reason {@link ScimGroupMemberRow} is: it is the shape of a query,
 * carrying the reservation as the stored text, and is mapped to the domain's
 * {@link com.example.backend.scim.domain.ScimGroupHeadcount} as soon as it is read.
 *
 * @param id           the Group's stable id
 * @param displayName  its display name
 * @param reservedName the stored reservation marker, or {@code null}
 * @param memberCount  how many membership rows name it
 */
record ScimGroupHeadcountRow(UUID id, String displayName, String reservedName, long memberCount) {
}

package com.example.backend.scim.domain;

import java.util.UUID;

/**
 * A Group as a page of the Groups projection lists it: who it is and how many direct members it
 * has, with none of them read.
 *
 * <p>A count rather than a {@link ScimGroup}, whose {@code members} is every membership with its
 * label: a page of two hundred Groups loaded to count those would read every membership each of
 * them holds.
 *
 * @param id           the Group's stable id
 * @param displayName  the directory's name for it
 * @param reservedName the reservation protecting it — the Admin group's — or {@code null}
 * @param memberCount  how many Users are its direct members
 */
public record ScimGroupHeadcount(
        UUID id, String displayName, ReservedResourceName reservedName, int memberCount) {
}

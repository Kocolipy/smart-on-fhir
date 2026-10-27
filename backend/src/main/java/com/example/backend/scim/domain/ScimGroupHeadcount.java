package com.example.backend.scim.domain;

import java.util.UUID;

/**
 * A Group as the Groups projection lists it: who it is and how many direct members it has, with
 * none of them read.
 *
 * <p>A count rather than a {@link ScimGroup}, whose {@code members} is every membership with its
 * label: listing every Group in the directory to count those would read every membership there is.
 *
 * @param id           the Group's stable id
 * @param displayName  the directory's name for it
 * @param reservedName the reservation protecting it — the Admin group's — or {@code null}
 * @param memberCount  how many Users are its direct members
 */
public record ScimGroupHeadcount(
        UUID id, String displayName, ReservedResourceName reservedName, int memberCount) {
}

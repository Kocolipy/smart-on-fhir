package com.example.backend.auth.application;

import java.util.List;
import java.util.UUID;

/**
 * What an administrator is shown about a Group: the Groups projection of the Accounts page.
 *
 * <p>Read-only here. Groups and their membership are the directory's, written by a connector over
 * SCIM, and the administration adapter exposes no operation on them. A Group's Permissions are
 * the application's (ADR 0015), shown so a holder of {@code group:read} can review what every
 * Group grants.
 *
 * <p>A count rather than the member list: the question this view answers is "which Groups exist
 * and how big are they", and the per-User answer to "who is in it" is already each User row's
 * {@code groups}.
 *
 * @param id          the SCIM resource id
 * @param displayName the directory's name for it
 * @param memberCount how many Users are its direct members
 * @param adminGroup  whether this is the server-seeded, protected Admin group, whose direct members
 *                    are the Superuser. Decided by the reservation marker, never by the name — a
 *                    Group a connector calls "Admins" confers only what it carries
 * @param permissions what the Group confers on its direct members, by name, sorted by name; empty
 *                    for a Group that confers nothing, and for the Admin group, which carries none
 *                    because the Superuser is not a Permission
 */
public record GroupSummary(
        UUID id, String displayName, int memberCount, boolean adminGroup, List<String> permissions) {

    public GroupSummary {
        permissions = List.copyOf(permissions);
    }
}

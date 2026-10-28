package com.example.backend;

import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Removes a User or Group a test created, straight through SQL, as a test's cleanup does.
 *
 * <p>The memberships naming the resource go first. Their foreign keys take no action
 * (ADR 0013) — the application's delete paths remove a resource's memberships themselves — so a
 * resource row still named by one is refused rather than taking it along.
 */
public final class ScimTestRows {

    private ScimTestRows() {
    }

    /**
     * A name no other test has used: {@code prefix}, a hyphen and a random UUID. A SCIM
     * {@code userName} or Group {@code displayName} collides across the classes sharing a
     * context's database, so a test mints its names with this rather than fixing them.
     */
    public static String unique(String prefix) {
        return prefix + "-" + UUID.randomUUID();
    }

    /** Deletes this resource, whatever it is, with the memberships naming it. */
    public static void delete(JdbcTemplate jdbc, UUID id) {
        jdbc.update("DELETE FROM scim_group_members WHERE user_id = ? OR group_id = ?", id, id);
        jdbc.update("DELETE FROM scim_resources WHERE id = ?", id);
    }

    /**
     * Deletes this resource with the memberships naming it, unless it is reserved — the Admin
     * group or the Bootstrap Admin — in which case it and its memberships are left untouched.
     */
    public static void deleteUnreserved(JdbcTemplate jdbc, UUID id) {
        jdbc.update("""
                DELETE FROM scim_group_members
                 WHERE (user_id = ? OR group_id = ?)
                   AND NOT EXISTS (SELECT 1 FROM scim_resources
                                    WHERE id = ? AND reserved_name IS NOT NULL)""", id, id, id);
        jdbc.update("DELETE FROM scim_resources WHERE id = ? AND reserved_name IS NULL", id);
    }
}

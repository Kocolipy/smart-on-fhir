package com.example.backend;

import com.example.backend.scim.domain.ScimUser;
import com.example.backend.scim.domain.ScimUserProfile;
import com.example.backend.scim.domain.ScimUserRepository;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Stores the Users a test signs in as, and removes a User or Group a test created, straight
 * through SQL, as a test's cleanup does.
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

    /**
     * Stores an active User, in no Group, under {@code id} and {@code userName}, signing in with
     * {@code password}, in the database {@code context} uses.
     */
    public static void createUser(ApplicationContext context, UUID id, String userName,
            String password) {
        String hash = context.getBean(PasswordEncoder.class).encode(password);
        new TransactionTemplate(context.getBean(PlatformTransactionManager.class))
                .executeWithoutResult(status -> context.getBean(ScimUserRepository.class).create(
                        ScimUser.created(id,
                                new ScimUserProfile(userName, null, userName, null, null, null,
                                        true, List.of()),
                                hash, Instant.now())));
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

    /**
     * {@link #deleteUnreserved(JdbcTemplate, UUID)} for every one of {@code ids}, in two
     * statements rather than two per id: the ids travel as one comma-separated parameter, which
     * no statement's parameter limit bounds.
     */
    public static void deleteUnreserved(JdbcTemplate jdbc, Collection<UUID> ids) {
        if (ids.isEmpty()) {
            return;
        }
        String list = ids.stream().map(UUID::toString).collect(Collectors.joining(","));
        jdbc.update("""
                DELETE FROM scim_group_members
                 WHERE EXISTS (SELECT 1 FROM STRING_SPLIT(?, ',') AS ids
                                WHERE CAST(ids.value AS UNIQUEIDENTIFIER) IN (user_id, group_id)
                                  AND NOT EXISTS (SELECT 1 FROM scim_resources
                                                   WHERE id = CAST(ids.value AS UNIQUEIDENTIFIER)
                                                     AND reserved_name IS NOT NULL))""", list);
        jdbc.update("""
                DELETE FROM scim_resources
                 WHERE id IN (SELECT CAST(value AS UNIQUEIDENTIFIER) FROM STRING_SPLIT(?, ','))
                   AND reserved_name IS NULL""", list);
    }
}

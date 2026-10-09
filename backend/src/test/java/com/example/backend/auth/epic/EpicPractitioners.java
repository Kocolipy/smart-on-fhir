package com.example.backend.auth.epic;

import com.example.backend.scim.ScimIdentities;
import com.example.backend.scim.domain.ScimLoginState;
import com.example.backend.scim.domain.ScimUser;
import com.example.backend.scim.domain.ScimUserRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Users an Epic Login integration test provisions under a fresh Practitioner ID each, through the
 * directory's own port, and removes again with {@link #removeAll()} after the test.
 */
final class EpicPractitioners {

    /** The password every User provisioned here has. */
    static final String PASSWORD = "a-perfectly-good-passphrase";

    private final ScimUserRepository users;

    private final PasswordEncoder passwordEncoder;

    private final TransactionTemplate transactions;

    private final JdbcTemplate jdbc;

    private final List<UUID> seeded = new ArrayList<>();

    EpicPractitioners(ScimUserRepository users, PasswordEncoder passwordEncoder,
            PlatformTransactionManager transactionManager, JdbcTemplate jdbc) {
        this.users = users;
        this.passwordEncoder = passwordEncoder;
        this.transactions = new TransactionTemplate(transactionManager);
        this.jdbc = jdbc;
    }

    /** An active User whose userName is a fresh, mixed-case Practitioner ID, with a password. */
    String provision() {
        String practitioner = "ePract" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        ScimUser created = transactions.execute(status -> users.create(
                ScimUser.created(
                        UUID.randomUUID(),
                        ScimIdentities.profile(practitioner, true),
                        passwordEncoder.encode(PASSWORD),
                        ScimIdentities.NOW)));
        seeded.add(created.id());
        return practitioner;
    }

    /** {@link #provision()}, then deactivated, as the directory would. */
    String provisionDeactivated() {
        String practitioner = provision();
        UUID id = idOf(practitioner);
        transactions.executeWithoutResult(status -> users.updateActive(id, false, Instant.now()));
        return practitioner;
    }

    /**
     * {@link #provision()}, then with the change-required flag set since {@code since}, as a
     * connector password write, a forced password change or an Unlock leaves it.
     */
    String provisionRequiredToChangePassword(Instant since) {
        String practitioner = provision();
        UUID id = idOf(practitioner);
        transactions.executeWithoutResult(status -> users.requirePasswordChange(id, since));
        return practitioner;
    }

    /** {@link #provision()}, then locked by a failure run reaching the limit. */
    String provisionLocked() {
        String practitioner = provision();
        UUID id = idOf(practitioner);
        transactions.executeWithoutResult(status -> {
            ScimLoginState login = users.findById(id).orElseThrow().login();
            users.updateLoginState(id, new ScimLoginState(login.passwordHash(), 5, Instant.now()));
        });
        return practitioner;
    }

    /** A fresh Practitioner ID no User is provisioned under. */
    static String unprovisioned() {
        return "eNobody" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
    }

    /** The id of the User named {@code userName}. */
    UUID idOf(String userName) {
        return jdbc.queryForObject(
                "SELECT resource_id FROM scim_users WHERE user_name = ?", UUID.class, userName);
    }

    /** Removes every User provisioned here, with any Group membership it was given. */
    void removeAll() {
        for (UUID id : seeded) {
            jdbc.update("DELETE FROM scim_group_members WHERE user_id = ?", id);
            jdbc.update("DELETE FROM scim_resources WHERE id = ?", id);
        }
        seeded.clear();
    }
}

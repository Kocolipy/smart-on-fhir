package com.example.backend.auth.infrastructure.session;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.backend.auth.application.PasswordRefusalReason;
import com.example.backend.auth.application.LoginAttemptService;
import com.example.backend.scim.ScimIdentities;
import com.example.backend.scim.domain.NormalizedUserName;
import com.example.backend.scim.domain.ScimUser;
import com.example.backend.scim.domain.ScimUserRepository;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.session.FindByIndexNameSessionRepository;
import org.springframework.session.Session;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The Redis half of "session revocation still works under the new key", proved against a real,
 * indexed Spring Session repository rather than the
 * {@link com.example.backend.auth.InMemoryAccountSessions} fake other tests use.
 *
 * <p>{@code AGENTS.md} requires an integration-level check against Redis for
 * changes to Redis-backed session persistence — a servlet-mock controller test
 * cannot see the indexed lookup {@link AccountSessionsAdapter} depends on, only
 * that a fake reported the right calls.
 *
 * <p>This class writes the session index entry the same way {@code AuthController}
 * does on a real login: the identity's stable SCIM resource id, not its
 * {@code userName}, into
 * {@link FindByIndexNameSessionRepository#PRINCIPAL_NAME_INDEX_NAME}. That is
 * exactly the behavior the rekeyed adapter exists to prove —
 * a session survives a rename because nothing about it was ever keyed by the
 * name that changed.
 */
@SpringBootTest
@Import(com.example.backend.ContainerTestConfiguration.class)
class RedisSessionRevocationIntegrationTests {

    @Autowired
    private ScimUserRepository users;

    @Autowired
    private LoginAttemptService attempts;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Value("${app.auth.lockout.max-attempts}")
    private int maxAttempts;

    @Autowired
    private AccountSessionsAdapter sessionsAdapter;

    @Autowired
    private FindByIndexNameSessionRepository<? extends Session> sessionRepository;

    private final List<UUID> seeded = new ArrayList<>();

    /**
     * The full journey the acceptance criterion names: an identity is renamed
     * directly against the store (no rename API exists — the User port writes only
     * the login state and {@code active}), and its live Redis session must still be
     * found afterward, under the same stable id the session was opened with.
     */
    @Test
    void aLiveSessionSurvivesAUsernameChangeMadeDirectlyAgainstTheStore() {
        ScimUser created = create("before-rename");
        UUID stableId = created.id();

        Session session = openSessionFor(stableId);

        rename(stableId, "after-rename");

        assertThat(users.findByNormalizedUserName(NormalizedUserName.of("after-rename")))
                .get()
                .extracting(ScimUser::id)
                .isEqualTo(stableId);
        assertThat(sessionRepository.findById(session.getId())).isNotNull();
        assertThat(sessionsAdapter.revokeAll(stableId)).isEqualTo(1);
        assertThat(sessionRepository.findById(session.getId())).isNull();
    }

    /**
     * Session-revocation-on-lockout, driven through the real login-attempt
     * counter and the real Redis-indexed repository. The lock is imposed by
     * counting failures, not by writing the row directly, so the revocation is
     * observed on the path a real brute-force attempt takes: the identity's live
     * session must be gone once the lock lands, or a locked identity would keep
     * acting through a session it already held.
     */
    @Test
    void imposingALockoutRevokesTheAccountsRealRedisBackedSession() {
        ScimUser created = create("session-lockout-target");
        Session session = openSessionFor(created.id());

        for (int attempt = 0; attempt < maxAttempts; attempt++) {
            attempts.recordFailure(
                    created.profile().userName(), PasswordRefusalReason.BAD_CREDENTIALS);
        }

        assertThat(require(created.profile().userName()).login().isLocked()).isTrue();
        assertThat(sessionRepository.findById(session.getId())).isNull();
    }

    /** An ordinary active identity with a credential, written through the real port. */
    private ScimUser create(String userName) {
        ScimUser created = new TransactionTemplate(transactionManager).execute(status -> users.create(
                ScimUser.created(
                        UUID.randomUUID(),
                        ScimIdentities.profile(userName, true),
                        "hash",
                        ScimIdentities.NOW)));
        seeded.add(created.id());
        return created;
    }

    /**
     * Removes the resources this test seeded, by stable id so a rename does not
     * hide the row. Deleting the {@code scim_resources} row cascades to
     * {@code scim_users} and everything keyed off it. Without this the fixed
     * {@code userName}s here (e.g. "before-rename") survive into a second run
     * against a reused database and collide on {@code uq_scim_users_normalized_user_name}.
     */
    @AfterEach
    void removeSeededIdentities() {
        for (UUID id : seeded) {
            jdbc.update("DELETE FROM scim_resources WHERE id = ?", id);
        }
        seeded.clear();
    }

    private ScimUser require(String userName) {
        return users.findByNormalizedUserName(NormalizedUserName.of(userName)).orElseThrow();
    }

    /**
     * Renames the identity in the database. There is no production path that
     * changes a {@code userName}, which is why this writes the columns directly —
     * the assertion is that the session index does not care how the name changed.
     */
    private void rename(UUID userId, String newUserName) {
        jdbc.update(
                "update scim_users set user_name = ?, normalized_user_name = ?"
                        + " where resource_id = ?",
                newUserName,
                NormalizedUserName.of(newUserName).value(),
                userId);
    }

    /** Opens a session indexed by the identity's stable id, as a real login would. */
    private Session openSessionFor(UUID userId) {
        Session session = sessionRepository.createSession();
        session.setAttribute(
                FindByIndexNameSessionRepository.PRINCIPAL_NAME_INDEX_NAME,
                userId.toString());
        @SuppressWarnings("unchecked")
        FindByIndexNameSessionRepository<Session> repository =
                (FindByIndexNameSessionRepository<Session>) sessionRepository;
        repository.save(session);
        return session;
    }
}

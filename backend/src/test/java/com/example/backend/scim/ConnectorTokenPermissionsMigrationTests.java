package com.example.backend.scim;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.backend.ContainerTestConfiguration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/**
 * The migration that moves a token's Permissions out of the array column ({@code V5}, ADR 0015)
 * carries every stored token's Permissions with it: a database migrated only as far as the
 * array, holding a token, is migrated the rest of the way and read back.
 *
 * <p>Drives Flyway itself on a database of its own rather than through a context, because a
 * context only ever sees the schema at its latest version. It migrates as a deployment does: as
 * the owner login, on a database that login owns, with the two runtime logins as the
 * placeholders the login migration grants their roles to.
 */
class ConnectorTokenPermissionsMigrationTests {

    /** The last version before {@code V5}, the migration under test. */
    private static final String BEFORE_THE_MOVE = "4";

    @Test
    void a_stored_tokens_permissions_survive_the_move_to_their_own_table() throws Exception {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                ContainerTestConfiguration.emptyDatabase(),
                ContainerTestConfiguration.OWNER_LOGIN, ContainerTestConfiguration.OWNER_PASSWORD);
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        migrate(dataSource, BEFORE_THE_MOVE);
        UUID connector = UUID.randomUUID();
        UUID token = UUID.randomUUID();
        jdbc.update("INSERT INTO scim_connectors (id, display_name, created_at) VALUES (?, ?, now())",
                connector, "Migrating Okta");
        jdbc.update("""
                INSERT INTO scim_connector_tokens (id, connector_id, lookup_id, token_hash,
                        permissions, issued_at, expires_at, original_expires_at)
                VALUES (?, ?, 'migration-lookup', '\\x00'::bytea,
                        ARRAY['user:write', 'group:read']::TEXT[], now(), now(), now())""",
                token, connector);

        migrate(dataSource, "latest");

        List<String> permissions = jdbc.queryForList("""
                SELECT permission FROM scim_connector_token_permissions
                 WHERE token_id = ? ORDER BY permission""", String.class, token);
        assertThat(permissions).containsExactly("group:read", "user:write");
    }

    private static void migrate(DriverManagerDataSource dataSource, String target) {
        Flyway.configure()
                .dataSource(dataSource)
                .placeholders(Map.of(
                        "app_login", ContainerTestConfiguration.APP_LOGIN,
                        "retention_login", ContainerTestConfiguration.RETENTION_LOGIN))
                .target(target)
                .load()
                .migrate();
    }
}

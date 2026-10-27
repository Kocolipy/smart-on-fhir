package com.example.backend;

import static org.assertj.core.api.Assertions.assertThat;

import javax.sql.DataSource;
import java.sql.ResultSet;
import java.sql.Statement;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.web.SecurityFilterChain;

/**
 * The application as it starts: its own security chain, and a schema Flyway alone built.
 *
 * <p>It joins the configuration most classes share rather than evicting it first. Both claims are
 * facts the startup of that configuration fixed — the beans it wired, the migrations it ran — so
 * the cached context reports exactly what a fresh one of the same configuration would, and no
 * test writes either.
 */
@SpringBootTest
@Import(ContainerTestConfiguration.class)
class BackendApplicationTests {

    /**
     * Injected rather than looked up so startup fails when the context has no
     * chain of its own. Dropping {@code SecurityConfig}'s contribution is
     * otherwise silent: Spring Security falls back to its default chain, and
     * the application still starts.
     */
    @Autowired
    private SecurityFilterChain securityFilterChain;

    @Autowired
    private DataSource dataSource;

    @Test
    void contextLoadsWithTheApplicationSecurityFilterChain() {
        assertThat(securityFilterChain).isNotNull();
        assertThat(securityFilterChain.getFilters()).isNotEmpty();
    }

    /**
     * The acceptance criterion this app boots against: a fresh database, with
     * {@code ddl-auto: validate} refusing to start if the schema disagreed with
     * the entities, and {@code flyway_schema_history} recording the migrations
     * that actually built it rather than a generated schema. Reading the table
     * directly — not just trusting that the context started — is what tells
     * "Flyway ran the migrations" apart from "some other mechanism happened to
     * leave a schema Hibernate's validation was satisfied by". The expected list
     * grows with each migration deliberately: a new one that forgot to land here
     * fails this test rather than passing silently. V1 is the whole pre-production
     * schema; a change from here on is V2 onward.
     */
    @Test
    void theSchemaCameFromFlywayMigrationsAloneOnAFreshDatabase() throws Exception {
        try (var connection = dataSource.getConnection();
                Statement statement = connection.createStatement();
                ResultSet rows = statement.executeQuery(
                        "select version, script, success"
                                + " from flyway_schema_history"
                                + " order by installed_rank")) {
            java.util.List<String> versions = new java.util.ArrayList<>();
            while (rows.next()) {
                assertThat(rows.getBoolean("success")).isTrue();
                versions.add(rows.getString("version"));
            }
            // One T-SQL V1 (ADR 0013): the preparation's migrations, the seeding lock's row
            // (ADR 0013, "Collation and the seed lock"), the patients table (ADR 0014) and the
            // audit
            // trail and Group Permissions in Permissions (ADR 0015) were folded into it.
            assertThat(versions).containsExactly("1");
        }
    }
}

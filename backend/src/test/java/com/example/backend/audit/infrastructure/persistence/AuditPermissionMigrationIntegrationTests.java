package com.example.backend.audit.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import com.example.backend.ContainerTestConfiguration;
import com.example.backend.audit.domain.AuditEvent;
import com.example.backend.audit.domain.AuditEventQuery;
import com.example.backend.audit.domain.AuditOperation;
import com.example.backend.audit.domain.AuditOutcome;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/**
 * V5 upgrades an audit trail recorded in Roles to one recorded in Permissions, in place: the rows
 * stored under {@code ROLE_GRANT}, {@code ROLE_REVOKE} and {@code DORMANCY_ROLE_REVOCATION} are
 * rewritten to the operations that replace them, each Role's name moves to the side of the change
 * it was on, and {@code role_name} is dropped — so the listing reads a database migrated from the
 * previous schema without error, as the service's own login.
 *
 * <p>The database is migrated to V4 and given its old rows through Flyway itself, so the upgrade
 * validates V1 to V4's recorded checksums as a real one does. No Spring context: a context migrates
 * its own database to the latest version before a test could step in.
 */
class AuditPermissionMigrationIntegrationTests {

    private static final UUID GRANT = UUID.fromString("00000000-0000-4000-8000-0000000e0001");
    private static final UUID REVOKE = UUID.fromString("00000000-0000-4000-8000-0000000e0002");
    private static final UUID DORMANCY = UUID.fromString("00000000-0000-4000-8000-0000000e0003");
    private static final UUID LOGIN = UUID.fromString("00000000-0000-4000-8000-0000000e0004");

    /** Four rows as the service wrote them before V5, a Role's name in {@code role_name}. */
    private static final String RECORDED_BEFORE_V5 = """
            INSERT INTO audit_events (id, occurred_at, operation, outcome, resource_type,
                                      changed_paths, status_class, role_name)
            VALUES
              ('00000000-0000-4000-8000-0000000e0001', '2026-10-01T00:00:01Z', 'ROLE_GRANT',
               'SUCCESS', 'Group', 'members', 'ok', 'Account admin'),
              ('00000000-0000-4000-8000-0000000e0002', '2026-10-01T00:00:02Z', 'ROLE_REVOKE',
               'SUCCESS', 'Group', 'members', 'ok', 'Auditor'),
              ('00000000-0000-4000-8000-0000000e0003', '2026-10-01T00:00:03Z',
               'DORMANCY_ROLE_REVOCATION', 'SUCCESS', 'User', 'groups', 'ok',
               'Account admin,Superuser'),
              ('00000000-0000-4000-8000-0000000e0004', '2026-10-01T00:00:04Z', 'LOGIN_SUCCESS',
               'SUCCESS', 'User', NULL, 'ok', NULL)""";

    private String url;

    private JdbcTemplate asOwner;

    private static Flyway flyway(String url, String target) {
        return Flyway.configure()
                .dataSource(url, ContainerTestConfiguration.OWNER_LOGIN,
                        ContainerTestConfiguration.OWNER_PASSWORD)
                .placeholders(Map.of(
                        "app_login", ContainerTestConfiguration.APP_LOGIN,
                        "retention_login", ContainerTestConfiguration.RETENTION_LOGIN))
                .target(target)
                .load();
    }

    @BeforeEach
    void aTrailRecordedBeforeV5() throws SQLException {
        url = ContainerTestConfiguration.emptyDatabase();
        flyway(url, "4").migrate();
        asOwner = new JdbcTemplate(new DriverManagerDataSource(url,
                ContainerTestConfiguration.OWNER_LOGIN, ContainerTestConfiguration.OWNER_PASSWORD));
        asOwner.update(RECORDED_BEFORE_V5);
        flyway(url, "latest").migrate();
    }

    /** The service's own login: what the listing reads with, under V1's grants alone. */
    private DataSource asApp() {
        return new DriverManagerDataSource(url,
                ContainerTestConfiguration.APP_LOGIN, ContainerTestConfiguration.APP_PASSWORD);
    }

    private Map<UUID, AuditEvent> listed() {
        return new AuditEventReadAdapter(asApp())
                .find(new AuditEventQuery(null, null, null, null, null, null, 0, 10))
                .events().stream()
                .collect(Collectors.toMap(AuditEvent::id, event -> event));
    }

    @Test
    void theListingReadsEveryRowRecordedBeforeV5() {
        assertThat(listed()).containsOnlyKeys(GRANT, REVOKE, DORMANCY, LOGIN);
    }

    @Test
    void eachRoleOperationIsRewrittenToTheOperationReplacingIt() {
        assertThat(listed()).extractingByKeys(GRANT, REVOKE, DORMANCY, LOGIN)
                .extracting(AuditEvent::operation)
                .containsExactly(AuditOperation.PERMISSION_GRANT, AuditOperation.PERMISSION_REVOKE,
                        AuditOperation.DORMANCY_MEMBERSHIP_REVOCATION,
                        AuditOperation.LOGIN_SUCCESS);
    }

    /** What a grant gained is after it; what a revocation lost is before it. */
    @Test
    void eachRoleNameMovesToTheSideOfTheChangeItWasOn() {
        Map<UUID, AuditEvent> listed = listed();

        assertThat(List.of(listed.get(GRANT), listed.get(REVOKE), listed.get(DORMANCY)))
                .extracting(AuditEvent::permissionsBefore, AuditEvent::permissionsAfter)
                .containsExactly(
                        tuple(List.of(), List.of("Account admin")),
                        tuple(List.of("Auditor"), List.of()),
                        tuple(
                                List.of("Account admin", "Superuser"), List.of()));
    }

    /** A migrated row cannot know its Superuser marker or the Groups removed: neither is set. */
    @Test
    void aMigratedRowCarriesNoSuperuserMarkerAndNoGroups() {
        assertThat(listed().get(DORMANCY))
                .extracting(AuditEvent::superuserChanged, AuditEvent::groupIds)
                .containsExactly(null, List.of());
    }

    @Test
    void roleNameIsDropped() {
        assertThat(asOwner.queryForObject("""
                SELECT count(*) FROM INFORMATION_SCHEMA.COLUMNS
                 WHERE TABLE_NAME = 'audit_events' AND COLUMN_NAME = 'role_name'""",
                Integer.class)).isZero();
    }

    /** The service still appends every new column, under V1's grants on the table. */
    @Test
    void theAppLoginAppendsAMembershipChangeAndReadsItBack() {
        UUID group = UUID.fromString("00000000-0000-4000-8000-0000000e00aa");
        AuditEvent appended = new AuditEvent(UUID.fromString(
                "00000000-0000-4000-8000-0000000e0005"),
                Instant.parse("2026-10-02T00:00:00Z"),
                AuditOperation.DORMANCY_MEMBERSHIP_REVOCATION,
                AuditOutcome.SUCCESS,
                null, LOGIN, AuditEvent.USER_RESOURCE_TYPE, LOGIN, List.of("groups"),
                AuditEvent.STATUS_OK, null, null, null, null, null, null, List.of(), null, null,
                null, null, List.of("audit:read"), List.of(), true, List.of(group));

        new IsolatedAuditEventPersistenceAdapter(new JdbcTemplate(asApp())).append(appended);

        assertThat(listed().get(appended.id())).isEqualTo(appended);
    }

    /** The rewrite enabled the append-only guard again: no row can be updated afterwards. */
    @Test
    void theAppendOnlyGuardStandsAfterTheRewrite() {
        assertThatThrownBy(() -> asOwner.update(
                "UPDATE audit_events SET outcome = 'FAILURE' WHERE id = ?", LOGIN))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("append-only");
    }

    /** No grant on the table changed: the app login still holds INSERT and SELECT alone. */
    @Test
    void theAppLoginKeepsExactlyItsGrantsOnTheTrail() {
        assertThat(asOwner.queryForList("""
                SELECT p.permission_name FROM sys.database_permissions p
                WHERE p.major_id = OBJECT_ID('audit_events')
                  AND USER_NAME(p.grantee_principal_id) = 'backend_app'
                ORDER BY p.permission_name""", String.class))
                .containsExactly("INSERT", "SELECT");
    }
}

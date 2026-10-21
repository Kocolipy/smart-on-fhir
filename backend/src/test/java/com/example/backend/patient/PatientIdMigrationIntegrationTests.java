package com.example.backend.patient;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.backend.ContainerTestConfiguration;
import com.example.backend.patient.application.DevPatientFixtures;
import com.example.backend.patient.domain.Patient;
import com.example.backend.patient.domain.PatientId;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/**
 * V4 upgrades a database that already ran V1 to V3 in place, as a local database seeded before
 * Patients took their EHR's FHIR ids is: {@code patients.id} becomes a {@code VARCHAR(64)} primary
 * key, and the four development fixtures' UUID ids become the Epic sandbox ids they now carry — so
 * the seeder, which inserts a fixture only when none is stored under its id, finds all four and
 * inserts none, rather than colliding on their NRICs.
 *
 * <p>The database is migrated to V3 first and seeded as the seeder did then, through Flyway itself,
 * so the upgrade validates V1 to V3's recorded checksums as a real one does. No Spring context: a
 * context migrates its own database to the latest version before a test could step in.
 */
class PatientIdMigrationIntegrationTests {

    /** The four fixture rows as the seeder wrote them before V4, under their UUID ids. */
    private static final String SEEDED_BEFORE_V4 = """
            INSERT INTO patients (id, nric, name_text, name_family, name_given, gender, birth_date)
            VALUES
              ('00000000-0000-4000-8000-00000000c001', 'S1234567A', 'Tan Wei Ming', 'Tan',
               'Wei Ming', 'male', '1985-03-12'),
              ('00000000-0000-4000-8000-00000000c002', 'S9212345A', 'Siti Nurhaliza binte Ahmad',
               NULL, NULL, 'female', '1992-08-21'),
              ('00000000-0000-4000-8000-00000000c003', 'T0312345C', 'Kumaravel', NULL,
               'Kumaravel', 'unknown', '2003-11-05'),
              ('00000000-0000-4000-8000-00000000c004', 'S7812345B', 'Marcus Anthony de Souza',
               'de Souza', 'Marcus Anthony', 'other', '1978-06-30')""";

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
    void aDatabaseSeededBeforeV4() throws SQLException {
        url = ContainerTestConfiguration.emptyDatabase();
        flyway(url, "3").migrate();
        asOwner = new JdbcTemplate(new DriverManagerDataSource(url,
                ContainerTestConfiguration.OWNER_LOGIN, ContainerTestConfiguration.OWNER_PASSWORD));
        asOwner.update(SEEDED_BEFORE_V4);
    }

    @Test
    void theUpgradeAppliesV4AloneOverTheRecordedMigrations() {
        flyway(url, "4").migrate();

        assertThat(asOwner.queryForList(
                "SELECT version FROM flyway_schema_history WHERE success = 1 ORDER BY installed_rank",
                String.class))
                .containsExactly("1", "2", "3", "4");
    }

    @Test
    void eachFixtureRowNowCarriesItsEpicSandboxId() {
        flyway(url, "latest").migrate();

        assertThat(asOwner.queryForList("SELECT nric, id FROM patients ORDER BY nric"))
                .containsExactly(
                        Map.of("nric", "S1234567A", "id", "eq081-VQEgP8drUUqCWzHfw3"),
                        Map.of("nric", "S7812345B", "id", "egqBHVfQlt4Bw3XGXoxVxHg3"),
                        Map.of("nric", "S9212345A", "id", "erXuFYUfucBZaryVksYEcMg3"),
                        Map.of("nric", "T0312345C", "id", "e0w0LEDCYtfckT6N.CkJKCw3"));
    }

    /**
     * What the seeder asks of each fixture it holds: is a row stored under its id. Asked of
     * {@link DevPatientFixtures#ALL} itself, so the migration's ids and the seeder's cannot drift
     * apart: every one is present, so the seeder inserts none.
     */
    @Test
    void theSeederFindsEveryFixtureAlreadyStoredUnderItsId() {
        flyway(url, "latest").migrate();

        assertThat(DevPatientFixtures.ALL).extracting(Patient::id).extracting(PatientId::value)
                .allSatisfy(id -> assertThat(asOwner.queryForObject(
                        "SELECT count(*) FROM patients WHERE id = ?", Integer.class, id))
                        .as("rows under %s", id)
                        .isEqualTo(1));
    }

    @Test
    void theIdIsAVarchar64PrimaryKey() {
        flyway(url, "latest").migrate();

        assertThat(asOwner.queryForList("""
                SELECT c.DATA_TYPE AS type, c.CHARACTER_MAXIMUM_LENGTH AS length,
                       c.IS_NULLABLE AS nullable, k.CONSTRAINT_NAME AS pk
                FROM INFORMATION_SCHEMA.COLUMNS c
                JOIN INFORMATION_SCHEMA.KEY_COLUMN_USAGE k
                  ON k.TABLE_NAME = c.TABLE_NAME AND k.COLUMN_NAME = c.COLUMN_NAME
                JOIN INFORMATION_SCHEMA.TABLE_CONSTRAINTS t
                  ON t.CONSTRAINT_NAME = k.CONSTRAINT_NAME AND t.CONSTRAINT_TYPE = 'PRIMARY KEY'
                WHERE c.TABLE_NAME = 'patients'"""))
                .containsExactly(Map.of("type", "varchar", "length", 64, "nullable", "NO",
                        "pk", "pk_patients"));
    }

    /** The app login keeps exactly the read and the insert V2 granted it. */
    @Test
    void theAppLoginKeepsItsGrantsOnPatients() {
        flyway(url, "latest").migrate();

        assertThat(asOwner.queryForList("""
                SELECT p.permission_name FROM sys.database_permissions p
                WHERE p.major_id = OBJECT_ID('patients')
                  AND USER_NAME(p.grantee_principal_id) = 'backend_app'
                ORDER BY p.permission_name""", String.class))
                .isEqualTo(List.of("INSERT", "SELECT"));
    }
}

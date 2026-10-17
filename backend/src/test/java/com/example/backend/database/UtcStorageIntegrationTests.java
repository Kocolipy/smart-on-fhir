package com.example.backend.database;

import static com.example.backend.scim.ScimConnectorClient.SCIM_JSON;
import static com.example.backend.scim.ScimConnectorClient.id;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.example.backend.ContainerTestConfiguration;
import com.example.backend.DatabaseLogins;
import com.example.backend.observability.RequestIdFilter;
import com.example.backend.scim.ScimConnectorClient;
import com.example.backend.scim.application.ConnectorAdministrationService;
import jakarta.servlet.Filter;
import java.util.List;
import java.util.TimeZone;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Storage is UTC only (ADR 0013): every instant the service writes is stored at offset
 * {@code +00:00} in its {@code DATETIMEOFFSET} column, whatever zone the JVM runs in, the API
 * renders it with {@code Z}, and no column default reads the server's clock.
 *
 * <p>The JVM's default zone is set to {@value #NOT_UTC} for the length of a write, so a write that
 * fell back to the JVM's zone — a {@code java.sql.Timestamp} bind, or Hibernate without its
 * {@code jdbc.time_zone} pin — would be stored at {@code +08:00} and fail here, whatever zone the
 * machine running the suite is in.
 */
@SpringBootTest
@Import(ContainerTestConfiguration.class)
class UtcStorageIntegrationTests {

    private static final String NOT_UTC = "Asia/Singapore";

    private static final String OFFSETS_OF_RESOURCE = """
            SELECT DATEPART(TZOFFSET, created_at), DATEPART(TZOFFSET, last_modified_at)
              FROM scim_resources WHERE id = ?""";

    private static final String OFFSET_OF_CREATION_EVENT = """
            SELECT DATEPART(TZOFFSET, occurred_at) FROM audit_events
             WHERE operation = 'SCIM_USER_CREATE' AND subject_id = ?""";

    /**
     * Every column default in the schema, by its definition text — but Flyway's own history
     * table's, whose {@code installed_on} is Flyway's record of when it ran, not data of the
     * service's.
     */
    private static final String COLUMN_DEFAULTS = """
            SELECT definition FROM sys.default_constraints
             WHERE OBJECT_NAME(parent_object_id) <> 'flyway_schema_history'""";

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private ConnectorAdministrationService connectors;

    @Autowired
    private JdbcTemplate jdbc;

    /** The owner login, which may read the schema's definitions; the app login sees none. */
    @Autowired
    private DatabaseLogins logins;

    @Autowired
    private RequestIdFilter requestIdFilter;

    @Autowired
    @Qualifier("springSecurityFilterChain")
    private Filter springSecurityFilterChain;

    private final JsonMapper json = JsonMapper.builder().build();

    private ScimConnectorClient connector;

    private UUID created;

    @BeforeEach
    void setUp() {
        connector = ScimConnectorClient.create(context, connectors, requestIdFilter,
                springSecurityFilterChain, "UTC storage");
    }

    @AfterEach
    void removeWhatThisTestCreated() {
        if (created != null) {
            jdbc.update("DELETE FROM scim_resources WHERE id = ?", created);
        }
    }

    @Test
    void every_instant_a_write_stores_is_at_offset_zero() throws Exception {
        created = createUserInAZoneThatIsNotUtc();

        List<Integer> offsets = jdbc.queryForObject(OFFSETS_OF_RESOURCE,
                (row, number) -> List.of(row.getInt(1), row.getInt(2)), created);
        assertThat(offsets).containsExactly(0, 0);
        assertThat(jdbc.queryForObject(OFFSET_OF_CREATION_EVENT, Integer.class, created))
                .isZero();
    }

    @Test
    void the_api_renders_a_stored_instant_in_utc() throws Exception {
        created = createUserInAZoneThatIsNotUtc();

        MvcResult read = connector.perform(get("/scim/v2/Users/" + created));

        JsonNode meta = json.readTree(read.getResponse().getContentAsString()).get("meta");
        assertThat(List.of(meta.get("created").asText(), meta.get("lastModified").asText()))
                .allSatisfy(instant -> assertThat(instant).endsWith("Z"));
    }

    /** The application's clock is the only clock: no default reads the server's. */
    @Test
    void no_column_default_reads_the_servers_clock() {
        assertThat(logins.owner().jdbc().queryForList(COLUMN_DEFAULTS, String.class))
                .isNotEmpty()
                .noneMatch(definition -> definition.toUpperCase().matches(
                        ".*(SYSDATETIMEOFFSET|SYSDATETIME|SYSUTCDATETIME|GETDATE|GETUTCDATE"
                                + "|CURRENT_TIMESTAMP).*"));
    }

    private UUID createUserInAZoneThatIsNotUtc() throws Exception {
        TimeZone zone = TimeZone.getDefault();
        TimeZone.setDefault(TimeZone.getTimeZone(NOT_UTC));
        try {
            MvcResult result = connector.perform(post("/scim/v2/Users")
                    .contentType(SCIM_JSON)
                    .content("""
                            {"schemas":["urn:ietf:params:scim:schemas:core:2.0:User"],
                             "userName":"utc-%s"}""".formatted(UUID.randomUUID())));
            assertThat(result.getResponse().getStatus())
                    .as(result.getResponse().getContentAsString()).isEqualTo(201);
            return id(result);
        } finally {
            TimeZone.setDefault(zone);
        }
    }
}

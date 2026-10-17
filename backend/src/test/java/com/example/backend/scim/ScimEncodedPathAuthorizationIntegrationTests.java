package com.example.backend.scim;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.backend.ContainerTestConfiguration;
import com.example.backend.auth.epic.EpicLoginOnContext;
import com.example.backend.TokenPermissions;
import com.example.backend.UuidColumnRows;
import com.example.backend.authorization.domain.Permission;
import com.example.backend.observability.EcsLogCapture;
import com.example.backend.scim.application.ConnectorAdministrationService;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.micrometer.metrics.test.autoconfigure.AutoConfigureMetrics;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * A SCIM operation's Permission holds however its path is spelled (ADR 0010).
 *
 * <p>The handler that serves a request is what declares the Permission it needs, so a path the
 * dispatcher resolves to the Users or Groups endpoints is held to that endpoint's Permission
 * whatever form the request line took — here, with a percent-encoded letter in the resource
 * type's segment. A running server rather than MockMvc, because the request line has to reach a
 * real container's decoding for the case to mean anything.
 *
 * <p>Each refused case also shows the operation had no effect, read back through the API with a
 * token holding every directory Permission. Written before the SCIM handlers declared their
 * Permissions, when every case here was admitted.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(ContainerTestConfiguration.class)
@AutoConfigureMetrics
class ScimEncodedPathAuthorizationIntegrationTests extends EpicLoginOnContext {

    private static final String SCIM_JSON = "application/scim+json";

    private static final String INSUFFICIENT_SCOPE = "Bearer error=\"insufficient_scope\"";

    private static final String REQUEST_RECORD = "HTTP request completed";

    private static final String EVENTS =
            "SELECT * FROM audit_events WHERE operation = 'ACCESS_DENIED' AND subject_id = ?";

    private final JsonMapper json = JsonMapper.builder().build();

    private final HttpClient client = HttpClient.newHttpClient();

    private final List<String> createdUsers = new ArrayList<>();

    private final List<String> createdGroups = new ArrayList<>();

    @LocalServerPort
    private int port;

    @Autowired
    private Environment environment;

    @Autowired
    private ConnectorAdministrationService connectors;

    @Autowired
    private JdbcTemplate jdbc;

    private UUID connector;

    private String everyPermission;

    @BeforeEach
    void setUp() {
        connector = connectors.create("encoded-path-" + UUID.randomUUID(), null).id();
        everyPermission = token(TokenPermissions.ALL);
    }

    @AfterEach
    void tearDown() throws Exception {
        for (String id : createdUsers) {
            send(scim("/Users/" + id, everyPermission).DELETE());
        }
        for (String id : createdGroups) {
            send(scim("/Groups/" + id, everyPermission).DELETE());
        }
    }

    @Test
    void creating_a_user_at_an_encoded_users_path_needs_user_write() throws Exception {
        String userName = "encoded-path-user-" + UUID.randomUUID();

        HttpResponse<String> response = send(scim("/%55sers", token(TokenPermissions.READ))
                .POST(HttpRequest.BodyPublishers.ofString("""
                        {"schemas":["urn:ietf:params:scim:schemas:core:2.0:User"],
                         "userName":"%s"}""".formatted(userName))));
        remember(response, createdUsers);

        assertRefused(response);
        assertThat(usersNamed(userName)).as("Users created").isZero();
    }

    @Test
    void deleting_a_group_at_an_encoded_groups_path_needs_group_write() throws Exception {
        String groupId = createGroup();

        HttpResponse<String> response = send(
                scim("/%47roups/" + groupId, token(TokenPermissions.READ)).DELETE());

        assertRefused(response);
        assertThat(send(scim("/Groups/" + groupId, everyPermission).GET()).statusCode())
                .as("the Group, read back").isEqualTo(200);
    }

    @Test
    void replacing_a_group_at_an_encoded_groups_path_needs_group_write() throws Exception {
        String groupId = createGroup();
        String renamed = "encoded-path-renamed-" + UUID.randomUUID();

        HttpResponse<String> response = send(scim("/%47roups/" + groupId,
                token(Set.of(Permission.GROUP_READ, Permission.USER_WRITE)))
                .PUT(HttpRequest.BodyPublishers.ofString("""
                        {"schemas":["urn:ietf:params:scim:schemas:core:2.0:Group"],
                         "displayName":"%s"}""".formatted(renamed))));

        assertRefused(response);
        JsonNode group = json.readTree(
                send(scim("/Groups/" + groupId, everyPermission).GET()).body());
        assertThat(group.get("displayName").asText()).isNotEqualTo(renamed);
    }

    @Test
    void reading_users_at_an_encoded_users_path_needs_user_read() throws Exception {
        HttpResponse<String> response = send(
                scim("/%55sers", token(Set.of(Permission.USER_WRITE, Permission.GROUP_READ)))
                        .GET());

        assertRefused(response);
    }

    @Test
    void searching_groups_at_an_encoded_groups_path_needs_group_read() throws Exception {
        HttpResponse<String> response = send(scim("/%47roups/.search",
                token(Set.of(Permission.USER_READ, Permission.GROUP_WRITE)))
                .POST(HttpRequest.BodyPublishers.ofString("""
                        {"schemas":["urn:ietf:params:scim:api:messages:2.0:SearchRequest"]}""")));

        assertRefused(response);
    }

    /**
     * The refusal is the SCIM chain's one refusal: one {@code insufficient-permissions} record
     * naming the connector, and one access-denied audit row naming the operation by its route
     * template — neither naming the Permission that was missing.
     */
    @Test
    void a_refusal_at_an_encoded_path_is_recorded_and_audited_as_the_chains_refusal()
            throws Exception {
        String reader = token(TokenPermissions.READ);
        int auditedBefore = jdbc.queryForList(EVENTS, connector).size();
        List<JsonNode> refusals;
        try (EcsLogCapture logs = EcsLogCapture.attach(environment)) {
            HttpResponse<String> response = send(scim("/%55sers", reader).POST(
                    HttpRequest.BodyPublishers.ofString(
                            "{\"schemas\":[\"urn:ietf:params:scim:schemas:core:2.0:User\"],"
                                    + "\"userName\":\"encoded-path-audit-" + UUID.randomUUID()
                                    + "\"}")));
            remember(response, createdUsers);
            assertRefused(response);
            logs.awaitRequestRecords(1);
            refusals = logs.records().stream()
                    .filter(record -> !REQUEST_RECORD.equals(record.at("/message").asText()))
                    .filter(record -> List.of("WARN", "ERROR")
                            .contains(record.at("/log/level").asText()))
                    .toList();
            assertThat(logs.lines()).as("no Permission in any record")
                    .doesNotContain("user:write");
        }

        assertThat(refusals).as("records besides the request record").hasSize(1);
        JsonNode refusal = refusals.getFirst();
        assertThat(refusal.at("/event/reason").asText()).isEqualTo("insufficient-permissions");
        assertThat(refusal.at("/http/response/status_code").asInt()).isEqualTo(403);
        assertThat(refusal.at("/scim/connector/id").asText()).isEqualTo(connector.toString());

        List<Map<String, Object>> audited = jdbc.query(EVENTS, UuidColumnRows.ROWS, connector);
        assertThat(audited).hasSize(auditedBefore + 1);
        Map<String, Object> row = audited.getLast();
        assertThat(row.get("actor_id")).hasToString(connector.toString());
        assertThat(row.get("error_code")).isEqualTo("INSUFFICIENT_PERMISSIONS");
        assertThat(row.get("http_method")).isEqualTo("POST");
        assertThat(row.get("http_path")).isEqualTo("/scim/v2/Users");
        assertThat(row.get("permissions")).as("never the missing Permission").isNull();
    }

    // ---- harness -----------------------------------------------------------------------------

    /** The chain's {@code 403}: the bearer {@code insufficient_scope} challenge and no body. */
    private static void assertRefused(HttpResponse<String> response) {
        assertThat(response.statusCode()).as("status").isEqualTo(403);
        assertThat(response.headers().firstValue("WWW-Authenticate"))
                .hasValue(INSUFFICIENT_SCOPE);
        assertThat(response.body()).as("body").isEmpty();
    }

    private String createGroup() throws Exception {
        HttpResponse<String> created = send(scim("/Groups", everyPermission)
                .POST(HttpRequest.BodyPublishers.ofString("""
                        {"schemas":["urn:ietf:params:scim:schemas:core:2.0:Group"],
                         "displayName":"encoded-path-group-%s"}""".formatted(UUID.randomUUID()))));
        assertThat(created.statusCode()).as("Group created").isEqualTo(201);
        String id = json.readTree(created.body()).get("id").asText();
        createdGroups.add(id);
        return id;
    }

    private int usersNamed(String userName) throws Exception {
        String filter = URLEncoder.encode(
                "userName eq \"" + userName + "\"", StandardCharsets.UTF_8);
        HttpResponse<String> found = send(
                scim("/Users?filter=" + filter, everyPermission).GET());
        assertThat(found.statusCode()).isEqualTo(200);
        return json.readTree(found.body()).get("totalResults").asInt();
    }

    /** Keeps the id of anything an admitted create made, so the class leaves no row behind. */
    private void remember(HttpResponse<String> response, List<String> ids) {
        if (response.statusCode() == 201) {
            ids.add(json.readTree(response.body()).get("id").asText());
        }
    }

    private String token(Set<Permission> permissions) {
        return connectors.issueToken(connector, permissions, null, null, TokenPermissions.ALL)
                .presentedValue();
    }

    private HttpRequest.Builder scim(String path, String token) {
        return HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/scim/v2" + path))
                .header("Authorization", "Bearer " + token)
                .header("Content-Type", SCIM_JSON)
                .header("Accept", SCIM_JSON);
    }

    private HttpResponse<String> send(HttpRequest.Builder request) throws Exception {
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }
}

package com.example.backend.scim;

import static com.example.backend.ScimHttp.asConnector;
import static com.example.backend.TestJson.body;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.example.backend.ContainerTestConfiguration;
import com.example.backend.SessionCsrf;
import com.example.backend.authorization.TestGroupPermissions;
import com.example.backend.observability.RequestIdFilter;
import jakarta.servlet.Filter;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.JsonNode;

/**
 * Connector tokens carry Permissions, and the SCIM chain enforces them (#117, ADR 0015) —
 * driven over HTTP through both real security chains against real SQL Server, with the token
 * rows and audit rows read back with SQL.
 *
 * <p>The administrator's session is built directly with exactly the authorities each case needs,
 * which is what lets one class show that only the Superuser administers connectors — a session
 * holding every Permission is refused (ADR 0015).
 */
@SpringBootTest
@Import(ContainerTestConfiguration.class)
class ConnectorTokenPermissionsIntegrationTests {

    private static final String ADMIN = "test-admin";

    private static final String CONNECTORS = "/api/admin/connectors";

    /** The Superuser's session: the only one that administers connectors. */
    private static final String[] SUPERUSER = TestGroupPermissions.SUPERUSER_AUTHORITIES;

    /** A User holding every Permission there is, and not the Superuser. */
    private static final String[] EVERY_PERMISSION_NOT_SUPERUSER = java.util.stream.Stream.concat(
                    java.util.stream.Stream.of("ROLE_USER"),
                    TestGroupPermissions.EVERY_PERMISSION.stream())
            .toArray(String[]::new);

    private static final String TOKEN_PERMISSIONS =
            "SELECT STRING_AGG(permission, ',') WITHIN GROUP (ORDER BY permission) AS permissions"
            + " FROM scim_connector_token_permissions WHERE token_id = ?";

    private static final String PERMISSION_ROWS =
            "SELECT count(*) FROM scim_connector_token_permissions WHERE token_id = ?";

    private static final String EVENTS =
            "SELECT * FROM audit_events WHERE operation = ? AND subject_id = ?"
            + " ORDER BY occurred_at";

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    @Qualifier("springSecurityFilterChain")
    private Filter springSecurityFilterChain;

    @Autowired
    private RequestIdFilter requestIdFilter;

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context)
                .addFilters(requestIdFilter, springSecurityFilterChain)
                .build();
    }

    // ---- issue and rotation ------------------------------------------------------------------

    @Test
    void a_token_is_issued_with_the_permissions_asked_for_and_the_issue_records_them()
            throws Exception {
        UUID connector = createConnector("permissions-issue");

        MvcResult issued = issue(connector, "[\"group:write\",\"group:read\"]", SUPERUSER);

        assertThat(issued.getResponse().getStatus()).isEqualTo(201);
        JsonNode body = body(issued);
        assertThat(strings(body.get("permissions"))).containsExactly("group:read", "group:write");
        UUID tokenId = UUID.fromString(body.get("tokenId").asText());
        assertThat(jdbc.queryForObject(TOKEN_PERMISSIONS, String.class, tokenId))
                .isEqualTo("group:read,group:write");
        assertThat(listedTokens(connector)).singleElement()
                .satisfies(token -> assertThat(strings(token.get("permissions")))
                        .containsExactly("group:read", "group:write"));
        assertThat(events("CONNECTOR_TOKEN_ISSUE", connector)).singleElement()
                .satisfies(row -> {
                    assertThat(row.get("outcome")).isEqualTo("SUCCESS");
                    assertThat(row.get("permissions")).isEqualTo("group:read,group:write");
                });
    }

    /**
     * Only the Superuser administers connectors: a User holding every Permission is refused with
     * the generic 403 on create, delete and on each token operation — and mints, changes and
     * deletes nothing — while {@code connector:read} still lists.
     */
    @Test
    void a_user_holding_every_permission_but_not_the_superuser_is_refused_every_administration()
            throws Exception {
        UUID connector = createConnector("permissions-not-superuser");
        UUID token = tokenId(issue(connector, "[\"user:read\"]", SUPERUSER));

        List<MvcResult> refused = List.of(
                mvc.perform(asRole(post(CONNECTORS), EVERY_PERMISSION_NOT_SUPERUSER)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"displayName\":\"permissions-refused\"}")).andReturn(),
                mvc.perform(asRole(delete(CONNECTORS + "/" + connector),
                        EVERY_PERMISSION_NOT_SUPERUSER)).andReturn(),
                issue(connector, "[\"user:read\"]", EVERY_PERMISSION_NOT_SUPERUSER),
                rotate(connector, token, null, EVERY_PERMISSION_NOT_SUPERUSER),
                mvc.perform(asRole(post(CONNECTORS + "/" + connector + "/tokens/" + token
                        + "/revoke"), EVERY_PERMISSION_NOT_SUPERUSER)).andReturn());

        assertThat(refused).extracting(result -> result.getResponse().getStatus())
                .containsOnly(403);
        assertThat(refused).extracting(result -> result.getResponse().getContentAsString())
                .allSatisfy(answer -> assertThat(answer)
                        .doesNotContain("superuser", "SUPERUSER", "connector:", "Permission"));
        assertThat(listedTokens(connector)).as("nothing was minted, rotated or revoked")
                .singleElement().satisfies(held -> {
                    assertThat(held.get("active").asBoolean()).isTrue();
                    assertThat(held.get("revokedAt").isNull()).isTrue();
                });
        assertThat(mvc.perform(asRole(get(CONNECTORS), "ROLE_USER", "connector:read"))
                .andReturn().getResponse().getStatus()).isEqualTo(200);
    }

    /** The Superuser may mint a token with any token-grantable Permission, the write halves included. */
    @Test
    void the_superuser_mints_a_token_with_every_token_grantable_permission() throws Exception {
        UUID connector = createConnector("permissions-every");

        MvcResult issued = issue(connector,
                "[\"user:read\",\"user:write\",\"group:read\",\"group:write\"]", SUPERUSER);

        assertThat(issued.getResponse().getStatus()).isEqualTo(201);
        assertThat(strings(body(issued).get("permissions")))
                .containsExactly("group:read", "group:write", "user:read", "user:write");
    }

    @Test
    void an_empty_unknown_or_non_directory_permission_list_is_a_bad_request() throws Exception {
        UUID connector = createConnector("permissions-malformed");

        for (String permissions : List.of(
                "[]", "[\"connector:token\"]", "[\"patient:read\"]", "[\"user:read\",\"audit:read\"]",
                "[\"scim.write\"]", "[\"USER_READ\"]", "[null]")) {
            assertThat(issue(connector, permissions, SUPERUSER)
                    .getResponse().getStatus())
                    .as("permissions %s", permissions)
                    .isEqualTo(400);
        }
        MvcResult missing = mvc.perform(asRole(post(CONNECTORS + "/" + connector + "/tokens"),
                        SUPERUSER)
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andReturn();
        assertThat(missing.getResponse().getStatus()).isEqualTo(400);
        assertThat(listedTokens(connector)).isEmpty();
        assertThat(events("CONNECTOR_TOKEN_ISSUE", connector))
                .as("a malformed request mints and records nothing").isEmpty();
    }

    @Test
    void rotation_keeps_the_permissions_unless_new_ones_are_given() throws Exception {
        UUID connector = createConnector("permissions-rotation");
        UUID original = tokenId(issue(connector, "[\"user:read\",\"user:write\"]", SUPERUSER));

        MvcResult kept = rotate(connector, original, "{\"overlapDays\":1}", SUPERUSER);
        assertThat(kept.getResponse().getStatus()).isEqualTo(201);
        assertThat(strings(body(kept).get("permissions"))).containsExactly("user:read", "user:write");

        MvcResult replaced = rotate(connector, tokenId(kept),
                "{\"permissions\":[\"group:read\"]}", SUPERUSER);
        assertThat(replaced.getResponse().getStatus()).isEqualTo(201);
        assertThat(strings(body(replaced).get("permissions"))).containsExactly("group:read");
        assertThat(jdbc.queryForObject(TOKEN_PERMISSIONS, String.class, tokenId(replaced)))
                .isEqualTo("group:read");

        assertThat(events("CONNECTOR_TOKEN_ROTATE", connector))
                .extracting(row -> row.get("permissions"))
                .containsExactly("user:read,user:write", "group:read");
    }

    // ---- the stored shape -------------------------------------------------------------------

    /**
     * The table's own check is the last line behind the application's refusal: a Permission
     * outside the four directory ones, written straight to the database, is refused there.
     */
    @Test
    void the_database_refuses_a_token_permission_outside_the_directory_four() throws Exception {
        UUID connector = createConnector("permissions-stored-check");
        UUID token = tokenId(issue(connector, "[\"user:read\"]", SUPERUSER));

        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO scim_connector_token_permissions (token_id, permission) VALUES (?, ?)",
                token, "connector:token"))
                .isInstanceOf(DataIntegrityViolationException.class)
                .rootCause()
                .hasMessageContaining("ck_scim_connector_token_permissions_directory_permission");
    }

    /** A token's Permission rows go with the token, whichever path deletes it. */
    @Test
    void deleting_a_token_removes_its_permission_rows() throws Exception {
        UUID connector = createConnector("permissions-stored-delete");
        UUID token = tokenId(issue(connector, "[\"group:read\",\"group:write\"]", SUPERUSER));
        assertThat(jdbc.queryForObject(PERMISSION_ROWS, Integer.class, token))
                .as("the fixture's token holds two Permission rows").isEqualTo(2);

        jdbc.update("DELETE FROM scim_connector_tokens WHERE id = ?", token);

        assertThat(jdbc.queryForObject(PERMISSION_ROWS, Integer.class, token)).isZero();
    }

    // ---- the SCIM chain ---------------------------------------------------------------------

    /** The ticket's demo: a {@code group:write}-only token writes Groups and not Users. */
    @Test
    void a_group_write_only_token_writes_groups_and_is_refused_on_users_writes()
            throws Exception {
        UUID connector = createConnector("permissions-group-writer");
        String token = presented(issue(connector, "[\"group:write\"]", SUPERUSER));

        MvcResult created = mvc.perform(asConnector(post("/scim/v2/Groups"), token)
                        .contentType("application/scim+json")
                        .content("{\"schemas\":[\"urn:ietf:params:scim:schemas:core:2.0:Group\"],"
                                + "\"displayName\":\"permissions-group-" + UUID.randomUUID() + "\"}"))
                .andReturn();
        assertThat(created.getResponse().getStatus()).isEqualTo(201);
        String groupId = body(created).get("id").asText();

        assertThat(mvc.perform(asConnector(patch("/scim/v2/Groups/" + groupId), token)
                        .contentType("application/scim+json")
                        .content("{\"schemas\":[\"urn:ietf:params:scim:api:messages:2.0:PatchOp\"],"
                                + "\"Operations\":[{\"op\":\"replace\",\"path\":\"displayName\","
                                + "\"value\":\"permissions-renamed-" + UUID.randomUUID() + "\"}]}"))
                .andReturn().getResponse().getStatus()).isEqualTo(200);

        MvcResult userWrite = mvc.perform(asConnector(post("/scim/v2/Users"), token)
                        .contentType("application/scim+json")
                        .content("{\"schemas\":[\"urn:ietf:params:scim:schemas:core:2.0:User\"],"
                                + "\"userName\":\"permissions-refused\"}"))
                .andReturn();
        assertThat(userWrite.getResponse().getStatus()).isEqualTo(403);
        assertThat(userWrite.getResponse().getHeader(HttpHeaders.WWW_AUTHENTICATE))
                .isEqualTo("Bearer error=\"insufficient_scope\"");
        assertThat(userWrite.getResponse().getContentAsString()).isEmpty();

        // No group:read: reading the Group it just wrote is refused too.
        assertThat(mvc.perform(asConnector(get("/scim/v2/Groups/" + groupId), token))
                .andReturn().getResponse().getStatus()).isEqualTo(403);

        assertThat(events("ACCESS_DENIED", connector)).hasSize(2).allSatisfy(row -> {
            // SQL Server renders a UNIQUEIDENTIFIER read back as text upper-case.
            assertThat(String.valueOf(row.get("actor_id")))
                    .isEqualToIgnoringCase(connector.toString());
            assertThat(row.get("resource_type")).isEqualTo("ScimConnector");
            assertThat(row.get("error_code")).isEqualTo("INSUFFICIENT_PERMISSIONS");
            assertThat(row.get("permissions")).as("never the missing Permission").isNull();
        });
        assertThat(events("ACCESS_DENIED", connector)).extracting(row -> row.get("http_path"))
                .containsExactly("/scim/v2/Users", "/scim/v2/Groups/{id}");
    }

    /** A {@code user:read}-only token's base search returns Users and no Groups. */
    @Test
    void a_user_read_only_token_searches_users_and_no_groups() throws Exception {
        UUID connector = createConnector("permissions-user-reader");
        String userReader = presented(issue(connector, "[\"user:read\"]", SUPERUSER));
        String bothReader = presented(issue(connector, "[\"user:read\",\"group:read\"]",
                SUPERUSER));
        String writer = presented(issue(connector, "[\"user:write\",\"group:write\"]",
                SUPERUSER));
        String search = "{\"schemas\":[\"urn:ietf:params:scim:api:messages:2.0:SearchRequest\"],"
                + "\"count\":200}";

        JsonNode users = body(mvc.perform(asConnector(post("/scim/v2/.search"), userReader)
                .contentType("application/scim+json").content(search)).andReturn());
        JsonNode both = body(mvc.perform(asConnector(post("/scim/v2/.search"), bothReader)
                .contentType("application/scim+json").content(search)).andReturn());

        assertThat(both.get("Resources").valueStream()
                .map(resource -> resource.get("meta").get("resourceType").asText()))
                .as("the fixture holds Groups, so their absence below means something")
                .contains("Group", "User");
        assertThat(users.get("Resources").valueStream()
                .map(resource -> resource.get("meta").get("resourceType").asText()))
                .isNotEmpty()
                .containsOnly("User");
        assertThat(users.get("totalResults").asLong())
                .isLessThan(both.get("totalResults").asLong());

        assertThat(mvc.perform(asConnector(post("/scim/v2/.search"), writer)
                        .contentType("application/scim+json").content(search))
                .andReturn().getResponse().getStatus()).isEqualTo(403);
    }

    // ---- helpers ------------------------------------------------------------------------------

    private UUID createConnector(String displayName) throws Exception {
        MvcResult created = mvc.perform(asRole(post(CONNECTORS), SUPERUSER)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"displayName\":\"" + displayName + "\"}"))
                .andReturn();
        assertThat(created.getResponse().getStatus()).isEqualTo(201);
        return UUID.fromString(body(created).get("id").asText());
    }

    private MvcResult issue(UUID connector, String permissions, String... authorities)
            throws Exception {
        return mvc.perform(asRole(post(CONNECTORS + "/" + connector + "/tokens"), authorities)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"permissions\":" + permissions + "}"))
                .andReturn();
    }

    private MvcResult rotate(UUID connector, UUID token, String body, String... authorities)
            throws Exception {
        MockHttpServletRequestBuilder request = asRole(
                post(CONNECTORS + "/" + connector + "/tokens/" + token + "/rotate"), authorities);
        if (body != null) {
            request.contentType(MediaType.APPLICATION_JSON).content(body);
        }
        return mvc.perform(request).andReturn();
    }

    private List<JsonNode> listedTokens(UUID connector) throws Exception {
        JsonNode listed = body(mvc.perform(asRole(get(CONNECTORS),
                SUPERUSER)).andReturn());
        for (JsonNode summary : listed) {
            if (summary.get("id").asText().equals(connector.toString())) {
                return summary.get("tokens").valueStream().toList();
            }
        }
        throw new AssertionError("No listed connector with id " + connector);
    }

    private List<Map<String, Object>> events(String operation, UUID connector) {
        return jdbc.queryForList(EVENTS, operation, connector);
    }

    private UUID tokenId(MvcResult issued) throws Exception {
        assertThat(issued.getResponse().getStatus()).isEqualTo(201);
        return UUID.fromString(body(issued).get("tokenId").asText());
    }

    private String presented(MvcResult issued) throws Exception {
        assertThat(issued.getResponse().getStatus()).isEqualTo(201);
        return body(issued).get("presentedValue").asText();
    }

    private static List<String> strings(JsonNode array) {
        return array.valueStream().map(JsonNode::asText).toList();
    }

    private MockHttpServletRequestBuilder asRole(
            MockHttpServletRequestBuilder request, String... authorities) {
        SecurityContext securityContext = SecurityContextHolder.createEmptyContext();
        securityContext.setAuthentication(new TestingAuthenticationToken(ADMIN, null, authorities));
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(
                HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY, securityContext);
        return SessionCsrf.withCsrf(mvc, request.session(session));
    }
}

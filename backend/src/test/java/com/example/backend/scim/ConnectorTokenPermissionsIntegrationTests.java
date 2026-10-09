package com.example.backend.scim;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.example.backend.ContainerTestConfiguration;
import com.example.backend.SessionCsrf;
import com.example.backend.authorization.TestRoleMappings;
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
import tools.jackson.databind.json.JsonMapper;

/**
 * Connector tokens carry Permissions, and the SCIM chain enforces them (#117, ADR 0010) —
 * driven over HTTP through both real security chains against real Postgres, with the token
 * rows and audit rows read back with SQL.
 *
 * <p>The administrator's session is built directly with exactly the Permissions each case
 * needs, which is what lets one class show the no-escalation rule for callers holding less than
 * a Superuser without a Role per combination.
 */
@SpringBootTest
@Import(ContainerTestConfiguration.class)
class ConnectorTokenPermissionsIntegrationTests {

    private static final String ADMIN = "test-admin";

    private static final String CONNECTORS = "/api/admin/connectors";

    /** What the development mapping's Connector admin Role holds. */
    private static final String[] CONNECTOR_ADMIN = {
        "ROLE_USER", "connector:read", "connector:write", "connector:token",
        "user:read", "user:write", "group:read", "group:write",
    };

    /** An administrator who may mint tokens but manages Groups alone. */
    private static final String[] GROUPS_ONLY_MINTER = {
        "ROLE_USER", "connector:read", "connector:write", "connector:token",
        "group:read", "group:write",
    };

    private static final String TOKEN_ROW =
            "SELECT array_to_string(permissions, ',') AS permissions"
            + " FROM scim_connector_tokens WHERE id = ?";

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

    private final JsonMapper json = JsonMapper.builder().build();

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

        MvcResult issued = issue(connector, "[\"group:write\",\"group:read\"]", CONNECTOR_ADMIN);

        assertThat(issued.getResponse().getStatus()).isEqualTo(201);
        JsonNode body = body(issued);
        assertThat(strings(body.get("permissions"))).containsExactly("group:read", "group:write");
        UUID tokenId = UUID.fromString(body.get("tokenId").asText());
        assertThat(jdbc.queryForObject(TOKEN_ROW, String.class, tokenId))
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

    /** No escalation: the caller cannot mint a Permission it does not hold itself. */
    @Test
    void a_token_carrying_a_permission_the_caller_lacks_is_refused_and_audited()
            throws Exception {
        UUID connector = createConnector("permissions-escalation");

        MvcResult refused = issue(connector, "[\"group:write\",\"user:write\"]", GROUPS_ONLY_MINTER);

        assertThat(refused.getResponse().getStatus()).isEqualTo(403);
        assertThat(refused.getResponse().getContentAsString()).isEmpty();
        assertThat(listedTokens(connector)).isEmpty();
        assertThat(events("CONNECTOR_TOKEN_ISSUE", connector)).singleElement()
                .satisfies(row -> {
                    assertThat(row.get("outcome")).isEqualTo("FAILURE");
                    assertThat(row.get("error_code")).isEqualTo("PERMISSION_ESCALATION");
                    assertThat(row.get("permissions")).isEqualTo("group:write,user:write");
                    assertThat(row.get("resource_type")).isEqualTo("ScimConnector");
                });

        // The same caller may mint what it does hold.
        assertThat(issue(connector, "[\"group:write\"]", GROUPS_ONLY_MINTER)
                .getResponse().getStatus()).isEqualTo(201);
    }

    @Test
    void an_empty_unknown_or_non_directory_permission_list_is_a_bad_request() throws Exception {
        UUID connector = createConnector("permissions-malformed");

        for (String permissions : List.of(
                "[]", "[\"connector:token\"]", "[\"user:read\",\"audit:read\"]",
                "[\"scim.write\"]", "[\"USER_READ\"]", "[null]")) {
            assertThat(issue(connector, permissions, TestRoleMappings.SUPERUSER_AUTHORITIES)
                    .getResponse().getStatus())
                    .as("permissions %s", permissions)
                    .isEqualTo(400);
        }
        MvcResult missing = mvc.perform(asRole(post(CONNECTORS + "/" + connector + "/tokens"),
                        TestRoleMappings.SUPERUSER_AUTHORITIES)
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andReturn();
        assertThat(missing.getResponse().getStatus()).isEqualTo(400);
        assertThat(listedTokens(connector)).isEmpty();
        assertThat(events("CONNECTOR_TOKEN_ISSUE", connector))
                .as("a malformed request is not an escalation").isEmpty();
    }

    @Test
    void rotation_keeps_the_permissions_unless_new_ones_are_given() throws Exception {
        UUID connector = createConnector("permissions-rotation");
        UUID original = tokenId(issue(connector, "[\"user:read\",\"user:write\"]", CONNECTOR_ADMIN));

        MvcResult kept = rotate(connector, original, "{\"overlapDays\":1}", CONNECTOR_ADMIN);
        assertThat(kept.getResponse().getStatus()).isEqualTo(201);
        assertThat(strings(body(kept).get("permissions"))).containsExactly("user:read", "user:write");

        MvcResult replaced = rotate(connector, tokenId(kept),
                "{\"permissions\":[\"group:read\"]}", CONNECTOR_ADMIN);
        assertThat(replaced.getResponse().getStatus()).isEqualTo(201);
        assertThat(strings(body(replaced).get("permissions"))).containsExactly("group:read");
        assertThat(jdbc.queryForObject(TOKEN_ROW, String.class, tokenId(replaced)))
                .isEqualTo("group:read");

        assertThat(events("CONNECTOR_TOKEN_ROTATE", connector))
                .extracting(row -> row.get("permissions"))
                .containsExactly("user:read,user:write", "group:read");
    }

    /** Rotating is minting: kept Permissions the caller lacks are an escalation too. */
    @Test
    void a_rotation_keeping_permissions_the_caller_lacks_is_refused_and_audited()
            throws Exception {
        UUID connector = createConnector("permissions-rotation-escalation");
        UUID original = tokenId(issue(connector, "[\"user:write\",\"group:write\"]", CONNECTOR_ADMIN));

        MvcResult refused = rotate(connector, original, null, GROUPS_ONLY_MINTER);

        assertThat(refused.getResponse().getStatus()).isEqualTo(403);
        assertThat(listedTokens(connector)).as("no replacement was minted").hasSize(1);
        assertThat(events("CONNECTOR_TOKEN_ROTATE", connector)).singleElement()
                .satisfies(row -> {
                    assertThat(row.get("outcome")).isEqualTo("FAILURE");
                    assertThat(row.get("error_code")).isEqualTo("PERMISSION_ESCALATION");
                    assertThat(row.get("permissions")).isEqualTo("group:write,user:write");
                });
    }

    // ---- the SCIM chain ---------------------------------------------------------------------

    /** The ticket's demo: a {@code group:write}-only token writes Groups and not Users. */
    @Test
    void a_group_write_only_token_writes_groups_and_is_refused_on_users_writes()
            throws Exception {
        UUID connector = createConnector("permissions-group-writer");
        String token = presented(issue(connector, "[\"group:write\"]", CONNECTOR_ADMIN));

        MvcResult created = mvc.perform(bearer(post("/scim/v2/Groups"), token)
                        .contentType("application/scim+json")
                        .content("{\"schemas\":[\"urn:ietf:params:scim:schemas:core:2.0:Group\"],"
                                + "\"displayName\":\"permissions-group-" + UUID.randomUUID() + "\"}"))
                .andReturn();
        assertThat(created.getResponse().getStatus()).isEqualTo(201);
        String groupId = body(created).get("id").asText();

        assertThat(mvc.perform(bearer(patch("/scim/v2/Groups/" + groupId), token)
                        .contentType("application/scim+json")
                        .content("{\"schemas\":[\"urn:ietf:params:scim:api:messages:2.0:PatchOp\"],"
                                + "\"Operations\":[{\"op\":\"replace\",\"path\":\"displayName\","
                                + "\"value\":\"permissions-renamed-" + UUID.randomUUID() + "\"}]}"))
                .andReturn().getResponse().getStatus()).isEqualTo(200);

        MvcResult userWrite = mvc.perform(bearer(post("/scim/v2/Users"), token)
                        .contentType("application/scim+json")
                        .content("{\"schemas\":[\"urn:ietf:params:scim:schemas:core:2.0:User\"],"
                                + "\"userName\":\"permissions-refused\"}"))
                .andReturn();
        assertThat(userWrite.getResponse().getStatus()).isEqualTo(403);
        assertThat(userWrite.getResponse().getHeader(HttpHeaders.WWW_AUTHENTICATE))
                .isEqualTo("Bearer error=\"insufficient_scope\"");
        assertThat(userWrite.getResponse().getContentAsString()).isEmpty();

        // No group:read: reading the Group it just wrote is refused too.
        assertThat(mvc.perform(bearer(get("/scim/v2/Groups/" + groupId), token))
                .andReturn().getResponse().getStatus()).isEqualTo(403);

        assertThat(events("ACCESS_DENIED", connector)).hasSize(2).allSatisfy(row -> {
            assertThat(row.get("actor_id")).hasToString(connector.toString());
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
        String userReader = presented(issue(connector, "[\"user:read\"]", CONNECTOR_ADMIN));
        String bothReader = presented(issue(connector, "[\"user:read\",\"group:read\"]",
                CONNECTOR_ADMIN));
        String writer = presented(issue(connector, "[\"user:write\",\"group:write\"]",
                CONNECTOR_ADMIN));
        String search = "{\"schemas\":[\"urn:ietf:params:scim:api:messages:2.0:SearchRequest\"],"
                + "\"count\":200}";

        JsonNode users = body(mvc.perform(bearer(post("/scim/v2/.search"), userReader)
                .contentType("application/scim+json").content(search)).andReturn());
        JsonNode both = body(mvc.perform(bearer(post("/scim/v2/.search"), bothReader)
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

        assertThat(mvc.perform(bearer(post("/scim/v2/.search"), writer)
                        .contentType("application/scim+json").content(search))
                .andReturn().getResponse().getStatus()).isEqualTo(403);
    }

    /** Discovery takes a valid token and no Permission; with no token it is challenged. */
    @Test
    void discovery_needs_a_token_and_no_permission() throws Exception {
        UUID connector = createConnector("permissions-discovery");
        String token = presented(issue(connector, "[\"group:read\"]", CONNECTOR_ADMIN));

        for (String path : List.of("/scim/v2/ServiceProviderConfig", "/scim/v2/ResourceTypes",
                "/scim/v2/Schemas")) {
            assertThat(mvc.perform(get(path)).andReturn().getResponse().getStatus())
                    .as("%s with no token", path).isEqualTo(401);
            assertThat(mvc.perform(bearer(get(path), token)).andReturn().getResponse().getStatus())
                    .as("%s with a token", path).isEqualTo(200);
        }
    }

    /** A token cannot reach the application chain, whatever it carries. */
    @Test
    void a_token_cannot_reach_the_application_chain() throws Exception {
        UUID connector = createConnector("permissions-api");
        String token = presented(issue(connector,
                "[\"user:read\",\"user:write\",\"group:read\",\"group:write\"]", CONNECTOR_ADMIN));

        for (String path : List.of("/api/admin/accounts", "/api/admin/groups", CONNECTORS)) {
            assertThat(mvc.perform(bearer(get(path), token)).andReturn().getResponse().getStatus())
                    .as("GET %s with a token", path).isEqualTo(401);
        }
    }

    // ---- helpers ------------------------------------------------------------------------------

    private UUID createConnector(String displayName) throws Exception {
        MvcResult created = mvc.perform(asRole(post(CONNECTORS), TestRoleMappings.SUPERUSER_AUTHORITIES)
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
                TestRoleMappings.SUPERUSER_AUTHORITIES)).andReturn());
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

    private static MockHttpServletRequestBuilder bearer(
            MockHttpServletRequestBuilder request, String token) {
        return request.header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
    }

    private static List<String> strings(JsonNode array) {
        return array.valueStream().map(JsonNode::asText).toList();
    }

    private JsonNode body(MvcResult result) throws Exception {
        return json.readTree(result.getResponse().getContentAsString());
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

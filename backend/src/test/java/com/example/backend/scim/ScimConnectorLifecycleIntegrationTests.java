package com.example.backend.scim;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.example.backend.SessionCsrf;
import com.example.backend.TokenPermissions;
import com.example.backend.authorization.TestRoleMappings;
import com.example.backend.ContainerTestConfiguration;
import com.example.backend.audit.domain.AuditOperation;
import com.example.backend.observability.RequestIdFilter;
import com.example.backend.scim.domain.NormalizedUserName;
import com.example.backend.scim.domain.ScimConnectorTokenRepository;
import com.example.backend.scim.domain.ScimExternalIdRepository;
import com.example.backend.scim.domain.ScimUser;
import com.example.backend.scim.domain.ScimUserRepository;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import jakarta.servlet.Filter;
import jakarta.servlet.http.Cookie;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.WebApplicationContext;

/**
 * The whole connector lifecycle driven through the real filter chains against a real
 * Postgres, with the audit rows read back out of the database with SQL.
 *
 * <p>This is the ticket's demo oracle. It is deliberately not a service-level test:
 * what is claimed is that an Admin's browser request and a connector's bearer request
 * travel two DIFFERENT security chains and each gets the right answer — which only
 * exists once the chains are wired, and which a test of the use cases could not
 * observe. The SCIM path called is a resource endpoint, so the signal is the
 * authentication outcome rather than a response body: {@code 401} before a token and
 * after its revocation, and anything else in between.
 *
 * <p>Audit rows are read with SQL rather than through the repository for the same
 * reason the existing recording test does: what is asserted is the bytes that landed,
 * not a mapping's opinion of them.
 */
@SpringBootTest
@Import(ContainerTestConfiguration.class)
class ScimConnectorLifecycleIntegrationTests {

    /** A SCIM path that exists as a namespace but has no handler yet. */
    private static final String SCIM_PATH = "/scim/v2/Users";

    private static final String ADMIN = "test-admin";

    private static final String CONNECTORS = "/api/admin/connectors";

    /**
     * Events of one operation for ONE connector.
     *
     * <p>Scoped by subject rather than read whole and cleared between tests, which is
     * what lets this class leave the append-only table alone: emptying it would need the
     * retention role, and reaching for that role from a test of something else is the
     * arrangement {@code semgrep/rules/service-security.yml} exists to keep to one
     * place. A per-connector query is also the stronger assertion — "this connector's
     * history is exactly these events" rather than "the table happens to hold one row".
     */
    private static final String EVENTS_BY_OPERATION_AND_SUBJECT =
            "SELECT * FROM audit_events WHERE operation = ? AND subject_id = ?"
            + " ORDER BY occurred_at";

    private static final String ALL_EVENTS = "SELECT * FROM audit_events";

    private static final String ALIAS_COUNT =
            "SELECT count(*) FROM scim_external_ids WHERE connector_id = ?";

    private static final String INSERT_ALIAS =
            "INSERT INTO scim_external_ids (connector_id, resource_id, external_id)"
            + " VALUES (?, ?, ?)";

    /**
     * A bare resource row for an alias to point at.
     *
     * <p>Needed because {@code scim_external_ids.resource_id} became a foreign key into
     * {@code scim_resources} when the User tables landed: an alias for an invented id is
     * now refused by the database, where before it was accepted. The row is written with
     * SQL rather than through the User use case because what this test is about is the
     * alias cascade, and a real create would drag a whole provisioning request into it.
     */
    private static final String INSERT_RESOURCE =
            "INSERT INTO scim_resources (id, resource_type, version, created_at,"
            + " last_modified_at) VALUES (?, 'User', 1, ?, ?)";

    private static final String TOKEN_ROWS =
            "SELECT * FROM scim_connector_tokens WHERE connector_id = ?";

    /** The token a rotation superseded, which is the one carrying the overlap window. */
    private static final String ROTATED_AWAY_TOKEN_ROW =
            "SELECT * FROM scim_connector_tokens"
            + " WHERE connector_id = ? AND replaced_by_token_id IS NOT NULL";

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private ScimUserRepository users;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ScimConnectorTokenRepository tokens;

    @Autowired
    private ScimExternalIdRepository aliases;

    @Autowired
    private TransactionTemplate transactions;

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

    /**
     * The ticket's end-to-end criterion, as one walk: create, issue, call as the
     * connector, revoke, call again, and confirm each step left its event.
     */
    @Test
    void an_admin_creates_a_connector_issues_a_token_and_a_revocation_ends_its_access()
            throws Exception {
        // Before any credential exists, the namespace challenges.
        mvc.perform(get(SCIM_PATH))
                .andExpect(result -> assertThat(result.getResponse().getStatus()).isEqualTo(401));

        UUID connectorId = createConnector("Okta");
        String presentedValue = issueToken(connectorId, TokenPermissions.ALL_JSON);

        // As that connector: past authentication, so no longer a 401. The path has no
        // handler yet, which is what a 404 here means — and a 404 is only reachable
        // once the bearer filter has accepted the credential.
        mvc.perform(get(SCIM_PATH).header(HttpHeaders.AUTHORIZATION, "Bearer " + presentedValue))
                .andExpect(result -> assertThat(result.getResponse().getStatus())
                        .as("an authenticated connector is past the 401")
                        .isNotEqualTo(401));

        revokeFirstToken(connectorId);

        mvc.perform(get(SCIM_PATH).header(HttpHeaders.AUTHORIZATION, "Bearer " + presentedValue))
                .andExpect(result -> {
                    assertThat(result.getResponse().getStatus()).isEqualTo(401);
                    assertThat(result.getResponse().getHeader(HttpHeaders.WWW_AUTHENTICATE))
                            .isEqualTo("Bearer error=\"invalid_token\"");
                });

        assertOneEvent(AuditOperation.CONNECTOR_CREATE, connectorId);
        assertOneEvent(AuditOperation.CONNECTOR_TOKEN_ISSUE, connectorId);
        assertOneEvent(AuditOperation.CONNECTOR_TOKEN_REVOKE, connectorId);
    }

    /** No credential at all gets a bare challenge naming the scheme. */
    @Test
    void a_scim_request_with_no_credential_is_challenged_for_bearer() throws Exception {
        mvc.perform(get(SCIM_PATH))
                .andExpect(result -> {
                    assertThat(result.getResponse().getStatus()).isEqualTo(401);
                    assertThat(result.getResponse().getHeader(HttpHeaders.WWW_AUTHENTICATE))
                            .isEqualTo("Bearer");
                });
    }

    @Test
    void a_malformed_token_is_refused_as_invalid_rather_than_challenged() throws Exception {
        mvc.perform(get(SCIM_PATH).header(HttpHeaders.AUTHORIZATION, "Bearer garbage"))
                .andExpect(result -> {
                    assertThat(result.getResponse().getStatus()).isEqualTo(401);
                    assertThat(result.getResponse().getHeader(HttpHeaders.WWW_AUTHENTICATE))
                            .isEqualTo("Bearer error=\"invalid_token\"");
                });
    }

    /**
     * The same value that authenticates in the header must not authenticate anywhere
     * else. Each of the three is presented over the real chain, so this is the
     * end-to-end form of the filter's unit claim.
     */
    @Test
    void a_token_is_never_accepted_from_a_query_string_a_form_body_or_a_cookie()
            throws Exception {
        UUID connectorId = createConnector("Okta");
        String presentedValue = issueToken(connectorId, TokenPermissions.ALL_JSON);

        mvc.perform(get(SCIM_PATH).param("access_token", presentedValue))
                .andExpect(result -> assertThat(result.getResponse().getStatus()).isEqualTo(401));

        mvc.perform(post(SCIM_PATH)
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("access_token", presentedValue))
                .andExpect(result -> assertThat(result.getResponse().getStatus()).isEqualTo(401));

        mvc.perform(get(SCIM_PATH)
                        .cookie(new Cookie("access_token", presentedValue)))
                .andExpect(result -> assertThat(result.getResponse().getStatus()).isEqualTo(401));
    }

    /** The plaintext is disclosed once, on a response a cache may not keep. */
    @Test
    void the_issue_response_discloses_the_plaintext_and_forbids_storing_it() throws Exception {
        UUID connectorId = createConnector("Okta");

        MvcResult issued = mvc.perform(asAdmin(post(CONNECTORS + "/" + connectorId + "/tokens"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"permissions\":" + TokenPermissions.READ_JSON + "}"))
                .andReturn();

        assertThat(issued.getResponse().getStatus()).isEqualTo(201);
        assertThat(issued.getResponse().getHeader(HttpHeaders.CACHE_CONTROL))
                .contains("no-store");
        assertThat(body(issued).get("presentedValue").asText()).isNotBlank();
    }

    /** And it is never disclosed again: the listing carries no value for it. */
    @Test
    void the_listing_never_discloses_a_token_value() throws Exception {
        UUID connectorId = createConnector("Okta");
        String presentedValue = issueToken(connectorId, TokenPermissions.READ_JSON);

        MvcResult listed = mvc.perform(asAdmin(get(CONNECTORS))).andReturn();

        String rendered = listed.getResponse().getContentAsString();
        assertThat(rendered).doesNotContain(presentedValue);
        assertThat(rendered).doesNotContain("presentedValue");
        assertThat(rendered).contains(connectorId.toString());
    }

    /** The criterion: tokens and aliases go together, and the aliases genuinely existed. */
    @Test
    void deleting_a_connector_revokes_its_tokens_and_removes_its_aliases() throws Exception {
        UUID connectorId = createConnector("Okta");
        issueToken(connectorId, TokenPermissions.READ_JSON);
        issueToken(connectorId, TokenPermissions.ALL_JSON);
        insertAliasForNewResource(connectorId, "okta-user-1");
        insertAliasForNewResource(connectorId, "okta-user-2");

        // The search-for-absence below is worth nothing unless there was something to
        // find first.
        assertThat(jdbc.queryForObject(ALIAS_COUNT, Integer.class, connectorId)).isEqualTo(2);
        assertThat(jdbc.queryForList(TOKEN_ROWS, connectorId)).hasSize(2);

        mvc.perform(asAdmin(delete(CONNECTORS + "/" + connectorId)))
                .andExpect(result -> assertThat(result.getResponse().getStatus()).isEqualTo(204));

        assertThat(jdbc.queryForObject(ALIAS_COUNT, Integer.class, connectorId)).isZero();
        assertThat(jdbc.queryForList(TOKEN_ROWS, connectorId))
                .hasSize(2)
                .allSatisfy(row -> assertThat(row.get("revoked_at")).isNotNull());
        // One revocation event per token actually taken down — the count the cascade
        // reported, not a fixed number and not one event for the whole act.
        assertThat(eventsOf(AuditOperation.CONNECTOR_TOKEN_REVOKE, connectorId)).hasSize(2);
    }

    /**
     * Every connector and token event that landed, checked for shape: named by the
     * connector, carrying the acting Admin's stable id, and with no column anywhere in
     * the row holding the plaintext.
     */
    @Test
    void every_lifecycle_event_is_shaped_correctly_and_holds_no_token_value() throws Exception {
        UUID connectorId = createConnector("Okta");
        String presentedValue = issueToken(connectorId, TokenPermissions.ALL_JSON);
        rotateFirstToken(connectorId);
        revokeFirstToken(connectorId);
        mvc.perform(asAdmin(delete(CONNECTORS + "/" + connectorId)));

        UUID adminId = users.findByNormalizedUserName(NormalizedUserName.of(ADMIN))
                .map(ScimUser::id)
                .orElseThrow();
        List<AuditOperation> adminAttributed = List.of(
                AuditOperation.CONNECTOR_CREATE,
                AuditOperation.CONNECTOR_TOKEN_ISSUE,
                AuditOperation.CONNECTOR_TOKEN_ROTATE,
                AuditOperation.CONNECTOR_DELETE);

        for (AuditOperation operation : adminAttributed) {
            List<Map<String, Object>> rows = eventsOf(operation, connectorId);
            assertThat(rows).as("%s", operation).isNotEmpty();
            assertThat(rows).allSatisfy(row -> {
                assertThat(row.get("resource_type")).isEqualTo("ScimConnector");
                assertThat(row.get("subject_id")).hasToString(connectorId.toString());
                assertThat(row.get("resource_id")).hasToString(connectorId.toString());
                assertThat(row.get("actor_id")).hasToString(adminId.toString());
                assertThat(row.get("outcome")).isEqualTo("SUCCESS");
                assertThat(row.get("status_class")).isEqualTo("ok");
                assertThat(row.get("request_id")).isNotNull();
                assertThat(row.get("http_path")).asString().startsWith("/api/admin/connectors");
            });
        }

        // No row anywhere holds the credential, whichever column somebody reached for.
        assertThat(jdbc.queryForList(ALL_EVENTS)).isNotEmpty()
                .allSatisfy(row -> row.values().forEach(value ->
                        assertThat(String.valueOf(value)).doesNotContain(presentedValue)));
    }

    /** A rotation event exists and the old token's expiry only moved earlier. */
    @Test
    void rotation_records_its_event_and_never_lengthens_the_old_token() throws Exception {
        UUID connectorId = createConnector("Okta");
        issueToken(connectorId, TokenPermissions.ALL_JSON);

        rotateFirstToken(connectorId);

        assertOneEvent(AuditOperation.CONNECTOR_TOKEN_ROTATE, connectorId);
        assertThat(jdbc.queryForList(TOKEN_ROWS, connectorId)).hasSize(2).allSatisfy(row ->
                assertThat((java.sql.Timestamp) row.get("expires_at"))
                        .isBeforeOrEqualTo((java.sql.Timestamp) row.get("original_expires_at")));
    }

    /**
     * The overlap the Admin ASKED for is the one the old token gets.
     *
     * <p>Separate from the test above, which asserts only that the expiry never moves
     * later. That bound holds just as well when the request body is ignored entirely and
     * the old token is ended at the rotation instant — so on its own it cannot tell a
     * working overlap from a discarded one. Here the window itself is the claim: seven
     * requested days land as seven days, materially later than "now" and materially
     * earlier than the year the token was issued with.
     */
    @Test
    void the_requested_overlap_is_the_one_the_old_token_keeps() throws Exception {
        UUID connectorId = createConnector("Okta");
        issueToken(connectorId, TokenPermissions.ALL_JSON);
        Instant beforeRotation = Instant.now();

        rotateFirstToken(connectorId);

        Map<String, Object> rotatedAway =
                jdbc.queryForMap(ROTATED_AWAY_TOKEN_ROW, connectorId);
        Instant expiry = ((Timestamp) rotatedAway.get("expires_at")).toInstant();
        assertThat(expiry)
                .as("seven requested days, not the rotation instant and not the issued year")
                .isAfter(beforeRotation.plus(Duration.ofDays(6)))
                .isBefore(beforeRotation.plus(Duration.ofDays(8)));
    }

    /**
     * The cascade's two bulk operations report what they actually touched.
     *
     * <p>Asserted through the ports rather than only through the DELETE endpoint, because
     * the counts are the only evidence either statement matched the rows it was aimed at:
     * a {@code WHERE} clause that matched nothing returns zero and raises nothing, so a
     * caller reading the return value is what distinguishes "revoked two" from "revoked
     * silently none". The unknown-connector case pins the other direction, so the counts
     * cannot be a constant.
     */
    @Test
    void the_cascade_reports_how_many_tokens_and_aliases_it_touched() throws Exception {
        UUID connectorId = createConnector("Okta");
        issueToken(connectorId, TokenPermissions.READ_JSON);
        issueToken(connectorId, TokenPermissions.ALL_JSON);
        insertAliasForNewResource(connectorId, "okta-user-1");
        insertAliasForNewResource(connectorId, "okta-user-2");

        UUID neverExisted = UUID.randomUUID();
        // The bulk statements are @Modifying queries, so they need a transaction of
        // their own here — the DELETE endpoint supplies one in the production path.
        transactions.executeWithoutResult(status -> {
            assertThat(tokens.revokeAllForConnector(connectorId, Instant.now())).isEqualTo(2);
            assertThat(aliases.deleteAllForConnector(connectorId)).isEqualTo(2);
            assertThat(tokens.revokeAllForConnector(neverExisted, Instant.now())).isZero();
            assertThat(aliases.deleteAllForConnector(neverExisted)).isZero();
        });
    }

    /**
     * Discovery and the resource endpoints alike are challenged without a credential (ADR
     * 0010): discovery needs a valid token, though no Permission.
     */
    @Test
    void discovery_needs_a_credential_like_every_other_scim_path() throws Exception {
        mvc.perform(get("/scim/v2/ServiceProviderConfig"))
                .andExpect(result -> assertThat(result.getResponse().getStatus())
                        .as("no token, so challenged")
                        .isEqualTo(401));

        mvc.perform(get(SCIM_PATH))
                .andExpect(result -> assertThat(result.getResponse().getStatus()).isEqualTo(401));
    }

    /**
     * An unknown SCIM path must not become the SPA shell. Without {@code /scim} being a
     * reserved server path this would be a 200 carrying HTML, which a provisioning
     * client would read as a successful empty response.
     */
    @Test
    void an_unknown_scim_path_is_not_answered_with_the_single_page_application() throws Exception {
        mvc.perform(get("/scim/v2/NotAResourceType"))
                .andExpect(result -> {
                    assertThat(result.getResponse().getStatus()).isEqualTo(401);
                    assertThat(result.getResponse().getContentAsString())
                            .doesNotContain("<html");
                });
    }

    /** Connector administration is an Admin capability on the session chain. */
    @Test
    void connector_administration_refuses_a_caller_who_is_not_an_admin() throws Exception {
        mvc.perform(get(CONNECTORS))
                .andExpect(result -> assertThat(result.getResponse().getStatus()).isEqualTo(401));

        mvc.perform(asRole(get(CONNECTORS), "ROLE_USER"))
                .andExpect(result -> assertThat(result.getResponse().getStatus()).isEqualTo(403));
    }

    /** A bearer token buys nothing on the administration API. */
    @Test
    void a_connector_token_does_not_authenticate_the_administration_api() throws Exception {
        UUID connectorId = createConnector("Okta");
        String presentedValue = issueToken(connectorId, TokenPermissions.ALL_JSON);

        mvc.perform(get(CONNECTORS).header(HttpHeaders.AUTHORIZATION, "Bearer " + presentedValue))
                .andExpect(result -> assertThat(result.getResponse().getStatus()).isEqualTo(401));
    }

    @Test
    void a_lifetime_past_the_ceiling_is_a_bad_request() throws Exception {
        UUID connectorId = createConnector("Okta");

        mvc.perform(asAdmin(post(CONNECTORS + "/" + connectorId + "/tokens"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"permissions\":" + TokenPermissions.READ_JSON + ",\"lifetimeDays\":400}"))
                .andExpect(result -> assertThat(result.getResponse().getStatus()).isEqualTo(400));
    }

    private UUID createConnector(String displayName) throws Exception {
        MvcResult created = mvc.perform(asAdmin(post(CONNECTORS))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"displayName\":\"" + displayName + "\"}"))
                .andReturn();
        assertThat(created.getResponse().getStatus()).isEqualTo(201);
        return UUID.fromString(body(created).get("id").asText());
    }

    private String issueToken(UUID connectorId, String permissionsJson) throws Exception {
        MvcResult issued = mvc.perform(asAdmin(post(CONNECTORS + "/" + connectorId + "/tokens"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"permissions\":" + permissionsJson + "}"))
                .andReturn();
        assertThat(issued.getResponse().getStatus()).isEqualTo(201);
        return body(issued).get("presentedValue").asText();
    }

    private void revokeFirstToken(UUID connectorId) throws Exception {
        UUID tokenId = firstTokenId(connectorId);
        mvc.perform(asAdmin(post(
                        CONNECTORS + "/" + connectorId + "/tokens/" + tokenId + "/revoke")))
                .andExpect(result -> assertThat(result.getResponse().getStatus()).isEqualTo(204));
    }

    private void rotateFirstToken(UUID connectorId) throws Exception {
        UUID tokenId = firstTokenId(connectorId);
        mvc.perform(asAdmin(post(
                                CONNECTORS + "/" + connectorId + "/tokens/" + tokenId + "/rotate"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"overlapDays\":7}"))
                .andExpect(result -> assertThat(result.getResponse().getStatus()).isEqualTo(201));
    }

    private UUID firstTokenId(UUID connectorId) throws Exception {
        MvcResult listed = mvc.perform(asAdmin(get(CONNECTORS))).andReturn();
        JsonNode connectors = json.readTree(listed.getResponse().getContentAsString());
        for (JsonNode connector : connectors) {
            if (connector.get("id").asText().equals(connectorId.toString())) {
                return UUID.fromString(connector.get("tokens").get(0).get("id").asText());
            }
        }
        throw new AssertionError("No listed connector with id " + connectorId);
    }

    private JsonNode body(MvcResult result) throws Exception {
        return json.readTree(result.getResponse().getContentAsString());
    }

    private List<Map<String, Object>> eventsOf(AuditOperation operation, UUID connectorId) {
        return jdbc.queryForList(
                EVENTS_BY_OPERATION_AND_SUBJECT, operation.name(), connectorId);
    }

    private void assertOneEvent(AuditOperation operation, UUID connectorId) {
        assertThat(eventsOf(operation, connectorId)).as("%s", operation).singleElement()
                .satisfies(row -> {
                    assertThat(row.get("resource_type")).isEqualTo("ScimConnector");
                    assertThat(row.get("subject_id")).hasToString(connectorId.toString());
                });
    }

    /**
     * The SCIM chain is registered BEFORE the catch-all application chain.
     *
     * <p>Worth its own assertion because getting it wrong is easy and the symptom is
     * remote from the cause: the {@code @Order} has to sit on the {@code @Bean} method,
     * since a class-level one leaves the chain at lowest precedence. Spring Security
     * happens to refuse to start in that case, which is a good failure — but it refuses
     * because the catch-all becomes unreachable, and a future edit that gave the
     * application chain a {@code securityMatcher} would remove that protection while
     * leaving the inverted order in place.
     */
    @Test
    void the_scim_chain_is_registered_ahead_of_the_application_chain() {
        List<SecurityFilterChain> chains =
                ((FilterChainProxy) springSecurityFilterChain).getFilterChains();

        MockHttpServletRequest scim = new MockHttpServletRequest("GET", SCIM_PATH);
        scim.setRequestURI(SCIM_PATH);

        assertThat(chains).hasSizeGreaterThanOrEqualTo(2);
        assertThat(chains.getFirst().matches(scim))
                .as("the first chain owns the SCIM namespace")
                .isTrue();
        assertThat(chains.getLast().matches(new MockHttpServletRequest("GET", "/anything")))
                .as("the last chain is the catch-all")
                .isTrue();
    }

    /** An authenticated Admin request, with a session and a matching CSRF token. */
    private MockHttpServletRequestBuilder asAdmin(MockHttpServletRequestBuilder request) {
        return asRole(request, TestRoleMappings.SUPERUSER_AUTHORITIES);
    }

    private MockHttpServletRequestBuilder asRole(
            MockHttpServletRequestBuilder request, String... authorities) {
        SecurityContext securityContext = SecurityContextHolder.createEmptyContext();
        securityContext.setAuthentication(
                new TestingAuthenticationToken(ADMIN, null, authorities));
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(
                HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY,
                securityContext);

        return SessionCsrf.withCsrf(mvc, request.session(session));
    }

    /** An alias pointing at a real resource row, which the foreign key now requires. */
    private void insertAliasForNewResource(UUID connectorId, String externalId) {
        UUID resourceId = UUID.randomUUID();
        Timestamp now = Timestamp.from(Instant.now());
        jdbc.update(INSERT_RESOURCE, resourceId, now, now);
        jdbc.update(INSERT_ALIAS, connectorId, resourceId, externalId);
    }

}

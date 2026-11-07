package com.example.backend.scim;

import static com.example.backend.ScimTestRows.unique;
import static com.example.backend.TestJson.JSON;
import static com.example.backend.TestJson.body;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.example.backend.SessionCsrf;
import com.example.backend.TokenPermissions;
import com.example.backend.UuidColumnRows;
import com.example.backend.authorization.TestGroupPermissions;
import com.example.backend.ContainerTestConfiguration;
import com.example.backend.audit.domain.AuditOperation;
import com.example.backend.observability.RequestIdFilter;
import com.example.backend.scim.domain.NormalizedUserName;
import com.example.backend.scim.domain.ScimConnectorTokenRepository;
import com.example.backend.scim.domain.ScimExternalIdRepository;
import com.example.backend.scim.domain.ScimUser;
import com.example.backend.scim.domain.ScimUserRepository;
import tools.jackson.databind.JsonNode;
import jakarta.servlet.Filter;
import jakarta.servlet.http.Cookie;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
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
import org.springframework.session.FindByIndexNameSessionRepository;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * The whole connector lifecycle driven through the real filter chains against a real
 * SQL Server, with the audit rows read back out of the database with SQL.
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

        UUID connectorId = createConnector(unique("Okta"));
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

    /**
     * The same value that authenticates in the header must not authenticate anywhere
     * else. Each of the three is presented over the real chain, so this is the
     * end-to-end form of the filter's unit claim.
     */
    @Test
    void a_token_is_never_accepted_from_a_query_string_a_form_body_or_a_cookie()
            throws Exception {
        UUID connectorId = createConnector(unique("Okta"));
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

    /** And it is never disclosed again: neither the listing nor the token read carries it. */
    @Test
    void the_listing_and_the_token_read_never_disclose_a_token_value() throws Exception {
        UUID connectorId = createConnector(unique("Okta"));
        String presentedValue = issueToken(connectorId, TokenPermissions.READ_JSON);

        String rendered = mvc.perform(asAdmin(get(CONNECTORS))).andReturn()
                .getResponse().getContentAsString()
                + mvc.perform(asAdmin(get(tokensOf(connectorId)))).andReturn()
                .getResponse().getContentAsString();

        assertThat(rendered).doesNotContain(presentedValue);
        assertThat(rendered).doesNotContain("presentedValue");
        assertThat(rendered).contains(connectorId.toString());
    }

    /** The connector listing names each connector and no token: each is read on its own. */
    @Test
    void the_connector_listing_carries_no_tokens() throws Exception {
        UUID connectorId = createConnector(unique("Okta"));
        issueToken(connectorId, TokenPermissions.READ_JSON);

        assertThat(listedConnector(connectorId).propertyNames())
                .containsExactlyInAnyOrder("id", "displayName", "createdAt");
    }

    /**
     * The token read pages as every administrative read does (ADR 0017): an out-of-range
     * {@code startIndex} and {@code count} are coerced, never refused, and the page reports where
     * it starts and how many active tokens there are.
     */
    @Test
    void the_token_read_coerces_its_paging_and_reports_the_active_total() throws Exception {
        UUID connectorId = createConnector(unique("Okta"));
        issueToken(connectorId, TokenPermissions.READ_JSON);
        issueToken(connectorId, TokenPermissions.ALL_JSON);
        revokeFirstToken(connectorId);

        MvcResult read = mvc.perform(asAdmin(get(tokensOf(connectorId))
                        .param("startIndex", "0")
                        .param("count", "-3")))
                .andReturn();

        assertThat(read.getResponse().getStatus()).isEqualTo(200);
        assertThat(JSON.readTree(read.getResponse().getContentAsString()))
                .isEqualTo(JSON.readTree("{\"totalResults\":1,\"startIndex\":1,\"tokens\":[]}"));
    }

    /**
     * A paging value too large for an {@code int} is only further out of range, so the token read
     * coerces it too, never refusing it: the furthest page there is, empty, beside the true total.
     */
    @Test
    void the_token_read_coerces_paging_values_beyond_the_integer_range() throws Exception {
        UUID connectorId = createConnector(unique("Okta"));
        issueToken(connectorId, TokenPermissions.READ_JSON);

        MvcResult read = mvc.perform(asAdmin(get(tokensOf(connectorId))
                        .param("startIndex", "99999999999")
                        .param("count", "99999999999")))
                .andReturn();

        assertThat(List.of(read.getResponse().getStatus(),
                        JSON.readTree(read.getResponse().getContentAsString())))
                .containsExactly(200, JSON.readTree(
                        "{\"totalResults\":1,\"startIndex\":2147483647,\"tokens\":[]}"));
    }

    /**
     * An unknown or deleted connector is the path's bodiless {@code 404}, and, a read being no
     * administrative act, nothing is audited.
     */
    @Test
    void the_token_read_of_an_unknown_or_deleted_connector_is_not_found() throws Exception {
        UUID deleted = createConnector(unique("Okta"));
        mvc.perform(asAdmin(delete(CONNECTORS + "/" + deleted)));
        UUID unknown = UUID.randomUUID();
        int deletedsEventsBefore = eventsAbout(deleted);

        MvcResult ofDeleted = mvc.perform(asAdmin(get(tokensOf(deleted)))).andReturn();
        MvcResult ofUnknown = mvc.perform(asAdmin(get(tokensOf(unknown)))).andReturn();

        assertThat(List.of(ofDeleted.getResponse().getStatus(),
                        ofDeleted.getResponse().getContentAsString(),
                        ofUnknown.getResponse().getStatus(),
                        eventsAbout(deleted), eventsAbout(unknown)))
                .containsExactly(404, "", 404, deletedsEventsBefore, 0);
    }

    /** The criterion: tokens and aliases go together, and the aliases genuinely existed. */
    @Test
    void deleting_a_connector_revokes_its_tokens_and_removes_its_aliases() throws Exception {
        UUID connectorId = createConnector(unique("Okta"));
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
        UUID connectorId = createConnector(unique("Okta"));
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
        UUID connectorId = createConnector(unique("Okta"));
        issueToken(connectorId, TokenPermissions.ALL_JSON);

        rotateFirstToken(connectorId);

        assertOneEvent(AuditOperation.CONNECTOR_TOKEN_ROTATE, connectorId);
        record Expiry(Instant current, Instant original) {}
        assertThat(jdbc.query(TOKEN_ROWS, (row, rowNum) -> new Expiry(
                        row.getObject("expires_at", OffsetDateTime.class).toInstant(),
                        row.getObject("original_expires_at", OffsetDateTime.class).toInstant()),
                connectorId))
                .hasSize(2)
                .allSatisfy(expiry ->
                        assertThat(expiry.current()).isBeforeOrEqualTo(expiry.original()));
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
        UUID connectorId = createConnector(unique("Okta"));
        issueToken(connectorId, TokenPermissions.ALL_JSON);
        Instant beforeRotation = Instant.now();

        rotateFirstToken(connectorId);

        Instant expiry = jdbc.queryForObject(ROTATED_AWAY_TOKEN_ROW, (row, rowNum) ->
                row.getObject("expires_at", OffsetDateTime.class).toInstant(), connectorId);
        assertThat(expiry)
                .as("seven requested days, not the rotation instant and not the issued year")
                .isAfter(beforeRotation.plus(Duration.ofDays(6)))
                .isBefore(beforeRotation.plus(Duration.ofDays(8)));
    }

    /**
     * A second rotation of one token is refused {@code 409} without a body, and the administrative
     * edge records it on the audit pool once the rotation's transaction has rolled back: a failed
     * {@code CONNECTOR_TOKEN_ROTATE} naming the administrator, the token addressed and why.
     */
    @Test
    void a_second_rotation_is_refused_and_audited_naming_the_token() throws Exception {
        UUID connectorId = createConnector(unique("Okta"));
        issueToken(connectorId, TokenPermissions.ALL_JSON);
        UUID tokenId = firstTokenId(connectorId);
        rotateFirstToken(connectorId);

        MvcResult refused = mvc.perform(asAdmin(post(
                        CONNECTORS + "/" + connectorId + "/tokens/" + tokenId + "/rotate")))
                .andReturn();

        assertThat(refused.getResponse().getStatus()).isEqualTo(409);
        assertThat(refused.getResponse().getContentAsString()).isEmpty();
        assertThat(eventsOf(AuditOperation.CONNECTOR_TOKEN_ROTATE, connectorId))
                .filteredOn(row -> "FAILURE".equals(row.get("outcome")))
                .singleElement()
                .satisfies(row -> assertThat(List.of(String.valueOf(row.get("actor_id")),
                                String.valueOf(row.get("token_id")), row.get("error_code")))
                        .containsExactly(adminId().toString(), tokenId.toString(),
                                "TOKEN_ALREADY_ROTATED"));
    }

    /**
     * An operation addressing a connector or a token that names nothing is refused {@code 404}
     * without a body, and audited as that operation's failure with {@code UNKNOWN_RESOURCE},
     * naming the administrator and what it addressed.
     */
    @Test
    void an_operation_on_an_unknown_connector_or_token_is_refused_and_audited() throws Exception {
        UUID connectorId = UUID.randomUUID();
        UUID tokenId = UUID.randomUUID();

        List<Integer> statuses = List.of(
                status(asAdmin(delete(CONNECTORS + "/" + connectorId))),
                status(asAdmin(post(CONNECTORS + "/" + connectorId + "/tokens"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"permissions\":" + TokenPermissions.READ_JSON + "}")),
                status(asAdmin(post(
                        CONNECTORS + "/" + connectorId + "/tokens/" + tokenId + "/revoke"))));

        assertThat(statuses).containsExactly(404, 404, 404);
        assertThat(List.of(
                        refusalOf(AuditOperation.CONNECTOR_DELETE, "subject_id", connectorId),
                        refusalOf(AuditOperation.CONNECTOR_TOKEN_ISSUE, "subject_id", connectorId),
                        refusalOf(AuditOperation.CONNECTOR_TOKEN_REVOKE, "token_id", tokenId)))
                .allSatisfy(row -> assertThat(List.of(row.get("outcome"), row.get("error_code"),
                                String.valueOf(row.get("actor_id"))))
                        .containsExactly("FAILURE", "UNKNOWN_RESOURCE", adminId().toString()));
    }

    /**
     * A token is addressed within the connector the path names: one of another connector is
     * refused {@code 404} without a body by both rotate and revoke, as an unknown token is, and
     * stays usable; each refusal is audited naming the connector addressed and the token.
     */
    @Test
    void another_connectors_token_is_refused_by_rotate_and_revoke_and_stays_usable()
            throws Exception {
        UUID named = createConnector(unique("Okta"));
        UUID owner = createConnector(unique("Entra"));
        issueToken(owner, TokenPermissions.ALL_JSON);
        UUID othersToken = firstTokenId(owner);

        MvcResult revoked = mvc.perform(asAdmin(post(
                        CONNECTORS + "/" + named + "/tokens/" + othersToken + "/revoke")))
                .andReturn();
        MvcResult rotated = mvc.perform(asAdmin(post(
                        CONNECTORS + "/" + named + "/tokens/" + othersToken + "/rotate")))
                .andReturn();

        assertThat(List.of(revoked.getResponse().getStatus(),
                        revoked.getResponse().getContentAsString(),
                        rotated.getResponse().getStatus(),
                        rotated.getResponse().getContentAsString(),
                        listsToken(owner, othersToken)))
                .containsExactly(404, "", 404, "", true);
        assertThat(List.of(
                        refusalOf(AuditOperation.CONNECTOR_TOKEN_REVOKE, "token_id", othersToken),
                        refusalOf(AuditOperation.CONNECTOR_TOKEN_ROTATE, "token_id", othersToken)))
                .allSatisfy(row -> assertThat(List.of(row.get("outcome"), row.get("error_code"),
                                String.valueOf(row.get("subject_id"))))
                        .containsExactly("FAILURE", "UNKNOWN_RESOURCE", named.toString()));
    }

    /**
     * A token of a deleted connector is refused {@code 404}, as the document promises: the delete
     * revoked it and recorded so, and no second {@code CONNECTOR_TOKEN_REVOKE} success is added.
     */
    @Test
    void a_deleted_connectors_token_is_refused_by_revoke_and_records_no_second_revocation()
            throws Exception {
        UUID connectorId = createConnector(unique("Okta"));
        issueToken(connectorId, TokenPermissions.ALL_JSON);
        UUID tokenId = firstTokenId(connectorId);
        mvc.perform(asAdmin(delete(CONNECTORS + "/" + connectorId)))
                .andExpect(result -> assertThat(result.getResponse().getStatus()).isEqualTo(204));

        int status = status(asAdmin(post(
                CONNECTORS + "/" + connectorId + "/tokens/" + tokenId + "/revoke")));

        assertThat(List.of(status, eventsOf(AuditOperation.CONNECTOR_TOKEN_REVOKE, connectorId)
                        .stream().filter(row -> "SUCCESS".equals(row.get("outcome"))).count()))
                .containsExactly(404, 1L);
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

    // ---- a live connector's name is its own -----------------------------------------------

    /** The name is stored without the whitespace at its edges, so it is compared without it. */
    @Test
    void a_created_connectors_name_is_stored_trimmed() throws Exception {
        String name = unique("Trimmed");

        MvcResult created = create("  " + name + "\u00A0\t");

        assertThat(List.of(created.getResponse().getStatus(),
                        body(created).get("displayName").asText()))
                .containsExactly(201, name);
    }

    /** A name of whitespace alone is blank once trimmed, so it fails validation as blank. */
    @Test
    void a_name_of_whitespace_alone_is_refused_as_an_invalid_request() throws Exception {
        MvcResult refused = create(" \u00A0\u3000 ");

        assertThat(List.of(refused.getResponse().getStatus(),
                        body(refused).get("code").asText()))
                .containsExactly(400, "invalid-request");
    }

    /**
     * A second live connector under a live one's name, edge whitespace aside, is refused
     * {@code 409 CONNECTOR_NAME_TAKEN}, and nothing is created: the listing still holds the one.
     */
    @Test
    void a_live_connectors_name_is_refused_as_taken_and_creates_nothing() throws Exception {
        String name = unique("Taken");
        createConnector(name);

        MvcResult refused = create(" " + name + " ");

        assertThat(List.of(refused.getResponse().getStatus(),
                        body(refused).get("code").asText(),
                        listedNamed(name)))
                .containsExactly(409, "CONNECTOR_NAME_TAKEN", 1L);
    }

    /** A Deleted SCIM connector holds no name: a new connector may be created under it. */
    @Test
    void a_deleted_connectors_name_is_free_to_reuse() throws Exception {
        String name = unique("Reused");
        UUID gone = createConnector(name);
        mvc.perform(asAdmin(delete(CONNECTORS + "/" + gone)))
                .andExpect(result -> assertThat(result.getResponse().getStatus()).isEqualTo(204));

        MvcResult created = create(name);

        assertThat(List.of(created.getResponse().getStatus(), listedNamed(name)))
                .containsExactly(201, 1L);
    }

    /**
     * Two creates racing for one name produce one {@code 201} and one {@code 409}: the unique index
     * decides, which no read before the insert could. Repeated, because a race that happens to
     * serialize once proves little.
     */
    @Test
    void two_creates_racing_for_one_name_produce_one_201_and_one_409() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            for (int round = 0; round < 5; round++) {
                String name = unique("Race " + round);
                CountDownLatch start = new CountDownLatch(1);
                Future<MvcResult> first = pool.submit(() -> {
                    start.await();
                    return create(name);
                });
                Future<MvcResult> second = pool.submit(() -> {
                    start.await();
                    return create(name);
                });
                start.countDown();

                MvcResult a = first.get(30, TimeUnit.SECONDS);
                MvcResult b = second.get(30, TimeUnit.SECONDS);
                assertThat(List.of(a.getResponse().getStatus(), b.getResponse().getStatus(),
                                listedNamed(name)))
                        .as("round " + round)
                        .containsExactlyInAnyOrder(201, 409, 1L);
            }
        } finally {
            pool.shutdownNow();
        }
    }

    /** A create of {@code displayName}, as the Superuser, whatever it is answered. */
    private MvcResult create(String displayName) throws Exception {
        return mvc.perform(asAdmin(post(CONNECTORS))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(JSON.writeValueAsString(Map.of("displayName", displayName))))
                .andReturn();
    }

    /** How many live connectors the listing shows under exactly {@code displayName}. */
    private long listedNamed(String displayName) throws Exception {
        MvcResult listed = mvc.perform(asAdmin(get(CONNECTORS))).andReturn();
        long count = 0;
        for (JsonNode connector : JSON.readTree(listed.getResponse().getContentAsString())) {
            if (connector.get("displayName").asText().equals(displayName)) {
                count++;
            }
        }
        return count;
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
        return UUID.fromString(firstTokenOf(connectorId).get("id").asText());
    }

    /** Whether the connector's token read lists {@code tokenId}: whether it is still active. */
    private boolean listsToken(UUID connectorId, UUID tokenId) throws Exception {
        for (JsonNode token : tokenPage(connectorId).get("tokens")) {
            if (token.get("id").asText().equals(tokenId.toString())) {
                return true;
            }
        }
        return false;
    }

    /** The first token the connector's token read shows: its newest active one. */
    private JsonNode firstTokenOf(UUID connectorId) throws Exception {
        return tokenPage(connectorId).get("tokens").get(0);
    }

    /** The first page of the connector's active tokens, as the token read answers it. */
    private JsonNode tokenPage(UUID connectorId) throws Exception {
        MvcResult read = mvc.perform(asAdmin(get(tokensOf(connectorId)))).andReturn();
        assertThat(read.getResponse().getStatus()).isEqualTo(200);
        return JSON.readTree(read.getResponse().getContentAsString());
    }

    private static String tokensOf(UUID connectorId) {
        return CONNECTORS + "/" + connectorId + "/tokens";
    }

    /** The connector listing's entry for {@code connectorId}. */
    private JsonNode listedConnector(UUID connectorId) throws Exception {
        MvcResult listed = mvc.perform(asAdmin(get(CONNECTORS))).andReturn();
        for (JsonNode connector : JSON.readTree(listed.getResponse().getContentAsString())) {
            if (connector.get("id").asText().equals(connectorId.toString())) {
                return connector;
            }
        }
        throw new AssertionError("No listed connector with id " + connectorId);
    }

    /** How many audit events name {@code subjectId} as their subject. */
    private int eventsAbout(UUID subjectId) {
        return jdbc.queryForList("SELECT * FROM audit_events WHERE subject_id = ?", subjectId)
                .size();
    }

    private List<Map<String, Object>> eventsOf(AuditOperation operation, UUID connectorId) {
        return jdbc.query(EVENTS_BY_OPERATION_AND_SUBJECT, UuidColumnRows.ROWS,
                operation.name(), connectorId);
    }

    /** The one refused {@code operation} whose {@code column} names {@code id}. */
    private Map<String, Object> refusalOf(AuditOperation operation, String column, UUID id) {
        List<Map<String, Object>> rows = jdbc.query(
                "SELECT * FROM audit_events WHERE operation = ? AND " + column + " = ?",
                UuidColumnRows.ROWS, operation.name(), id);
        assertThat(rows).as("%s", operation).hasSize(1);
        return rows.getFirst();
    }

    private int status(MockHttpServletRequestBuilder request) throws Exception {
        return mvc.perform(request).andReturn().getResponse().getStatus();
    }

    private UUID adminId() {
        return users.findByNormalizedUserName(NormalizedUserName.of(ADMIN)).orElseThrow().id();
    }

    private void assertOneEvent(AuditOperation operation, UUID connectorId) {
        assertThat(eventsOf(operation, connectorId)).as("%s", operation).singleElement()
                .satisfies(row -> {
                    assertThat(row.get("resource_type")).isEqualTo("ScimConnector");
                    assertThat(row.get("subject_id")).hasToString(connectorId.toString());
                });
    }

    /** An authenticated Admin request, with a session and a matching CSRF token. */
    private MockHttpServletRequestBuilder asAdmin(MockHttpServletRequestBuilder request) {
        return asRole(request, TestGroupPermissions.SUPERUSER_AUTHORITIES);
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
        // The principal index a Login writes: the acting Admin's stable id.
        session.setAttribute(FindByIndexNameSessionRepository.PRINCIPAL_NAME_INDEX_NAME,
                users.findByNormalizedUserName(NormalizedUserName.of(ADMIN))
                        .orElseThrow().id().toString());

        return SessionCsrf.withCsrf(mvc, request.session(session));
    }

    /** An alias pointing at a real resource row, which the foreign key now requires. */
    private void insertAliasForNewResource(UUID connectorId, String externalId) {
        UUID resourceId = UUID.randomUUID();
        OffsetDateTime now = Instant.now().atOffset(ZoneOffset.UTC);
        jdbc.update(INSERT_RESOURCE, resourceId, now, now);
        jdbc.update(INSERT_ALIAS, connectorId, resourceId, externalId);
    }

}

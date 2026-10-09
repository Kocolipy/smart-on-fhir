package com.example.backend.scim;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import com.example.backend.ContainerTestConfiguration;
import com.example.backend.TokenPermissions;
import com.example.backend.observability.RequestIdFilter;
import com.example.backend.scim.application.ConnectorAdministrationService;
import com.example.backend.scim.application.ScimUserService;
import com.example.backend.scim.domain.AuthenticatedConnector;
import com.example.backend.scim.domain.ReservedResourceName;
import com.example.backend.scim.domain.ScimUserRepository;
import com.example.backend.scim.domain.ScimVersionPrecondition;
import jakarta.servlet.Filter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.stream.StreamSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.session.FindByIndexNameSessionRepository;
import org.springframework.session.Session;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * SCIM DELETE driven over the real filter chain, against a real Postgres and a real, indexed
 * Redis session store: the {@code 204}, the {@code 404} for everything that follows, the
 * tombstone the schema lets exist, the reusable {@code userName}, and whether a live session
 * survives.
 *
 * <p>Sessions are opened the way {@code AuthController} opens them — indexed by the User's stable
 * id — so a revocation is observed as a session the store no longer returns.
 *
 * <p>Every runtime connection assumes the least-privilege application role, which may insert and
 * read tombstones but not remove them; this class therefore leaves its tombstones in place. They
 * name random ids no other class creates, and one test relies on the refusal.
 */
@SpringBootTest
@Import(ContainerTestConfiguration.class)
class ScimDeletionIntegrationTests {

    private static final String USERS = "/scim/v2/Users";

    private static final String GROUPS = "/scim/v2/Groups";

    private static final String USER_SCHEMA = "urn:ietf:params:scim:schemas:core:2.0:User";

    private static final String GROUP_SCHEMA = "urn:ietf:params:scim:schemas:core:2.0:Group";

    private static final String PATCH_OP = "urn:ietf:params:scim:api:messages:2.0:PatchOp";

    private static final MediaType SCIM_JSON = MediaType.valueOf("application/scim+json");

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private ConnectorAdministrationService connectors;

    @Autowired
    private ScimUserService userService;

    @Autowired
    private ScimUserRepository userRepository;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private FindByIndexNameSessionRepository<? extends Session> sessionRepository;

    @Autowired
    private RequestIdFilter requestIdFilter;

    @Autowired
    @Qualifier("springSecurityFilterChain")
    private Filter springSecurityFilterChain;

    private final JsonMapper json = JsonMapper.builder().build();

    private MockMvc mvc;

    private UUID connectorA;

    private String tokenA;

    private String readOnlyToken;

    private final List<UUID> created = new ArrayList<>();

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context)
                .addFilters(requestIdFilter, springSecurityFilterChain)
                .build();
        connectorA = connectors.create("Deleting Okta", "test-admin").id();
        tokenA = connectors.issueToken(connectorA, TokenPermissions.ALL, null,
                "test-admin", TokenPermissions.ALL).presentedValue();
        readOnlyToken = connectors.issueToken(connectorA, TokenPermissions.READ, null,
                "test-admin", TokenPermissions.ALL).presentedValue();
    }

    @AfterEach
    void removeOnlyWhatThisTestCreated() {
        for (UUID id : created) {
            jdbc.update("DELETE FROM scim_resources WHERE id = ? AND reserved_name IS NULL", id);
        }
    }

    // ---- the demo oracle ------------------------------------------------------------------

    /** 204 with no body, and every later operation on the id is a 404. */
    @Test
    void a_deleted_user_is_204_then_not_found_for_every_later_operation() throws Exception {
        UUID user = createUser("delete-oracle");
        long version = versionColumn(user);

        MvcResult deleted = mvc.perform(as(tokenA, delete(USERS + "/" + user))
                .header(HttpHeaders.IF_MATCH, "\"" + version + "\"")).andReturn();

        assertThat(deleted.getResponse().getStatus()).isEqualTo(204);
        assertThat(deleted.getResponse().getContentAsString()).isEmpty();
        String anyVersion = "\"" + version + "\"";
        for (MockHttpServletRequestBuilder later : List.of(
                get(USERS + "/" + user),
                withBody(put(USERS + "/" + user), minimalUser("delete-oracle"))
                        .header(HttpHeaders.IF_MATCH, anyVersion),
                withBody(patch(USERS + "/" + user),
                        patchOp("{\"op\":\"replace\",\"path\":\"active\",\"value\":false}"))
                        .header(HttpHeaders.IF_MATCH, anyVersion),
                delete(USERS + "/" + user).header(HttpHeaders.IF_MATCH, anyVersion),
                delete(USERS + "/" + user))) {
            MvcResult result = mvc.perform(as(tokenA, later)).andReturn();
            assertThat(result.getResponse().getStatus())
                    .as(later.buildRequest(context.getServletContext()).getMethod())
                    .isEqualTo(404);
            assertThat(body(result).get("status").asText()).isEqualTo("404");
        }
    }

    /**
     * Gone from the unfiltered listing. The page is proven complete (its total fits in it) and
     * non-empty with a survivor created alongside, so the absence is not an artefact of paging.
     */
    @Test
    void a_deleted_user_no_longer_appears_in_the_unfiltered_listing() throws Exception {
        UUID survivor = createUser("delete-listing-survivor");
        UUID user = createUser("delete-listing-gone");
        assertThat(listedUserIds()).contains(survivor, user);

        assertThat(status(conditional(tokenA, delete(USERS + "/" + user), user))).isEqualTo(204);

        assertThat(listedUserIds()).contains(survivor).doesNotContain(user);
    }

    /** The former userName is accepted by a later create, in any case, with no conflict. */
    @Test
    void the_former_user_name_is_accepted_by_a_subsequent_creation() throws Exception {
        UUID first = createUser("delete-reuse");
        MvcResult taken = mvc.perform(as(tokenA, withBody(post(USERS),
                minimalUser("DELETE-REUSE")))).andReturn();
        assertThat(taken.getResponse().getStatus())
                .as("while the first is live, the name is taken").isEqualTo(409);

        assertThat(status(conditional(tokenA, delete(USERS + "/" + first), first))).isEqualTo(204);

        UUID second = createUser("DELETE-REUSE");
        assertThat(second).isNotEqualTo(first);
        assertThat(mvc.perform(as(tokenA, get(USERS + "/" + second))).andReturn()
                .getResponse().getStatus()).isEqualTo(200);
        assertThat(mvc.perform(as(tokenA, get(USERS + "/" + first))).andReturn()
                .getResponse().getStatus()).isEqualTo(404);
    }

    // ---- the tombstone ----------------------------------------------------------------------

    /**
     * The tombstone table's structure: exactly an id, a constrained type and a time. No column
     * of a type that could hold a readable profile, credential or membership value exists; the
     * list is read from the live schema Flyway built, not from a fixture.
     */
    @Test
    void the_tombstone_table_has_no_column_that_could_hold_a_readable_value() {
        List<Map<String, Object>> columns = jdbc.queryForList("""
                SELECT column_name, data_type, character_maximum_length
                  FROM information_schema.columns
                 WHERE table_schema = 'public' AND table_name = 'scim_tombstones'
                 ORDER BY ordinal_position""");

        assertThat(columns).extracting(column -> column.get("column_name") + ":"
                        + column.get("data_type"))
                .containsExactly(
                        "resource_id:uuid",
                        "resource_type:character varying",
                        "deleted_at:timestamp with time zone");
        assertThat(columns.get(1).get("character_maximum_length")).isEqualTo(16);
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO scim_tombstones (resource_id, resource_type, deleted_at)
                VALUES (?, 'ada@work.example', now())""", UUID.randomUUID()))
                .as("the only text column accepts the two resource type names and nothing else")
                .isInstanceOf(DataAccessException.class);
    }

    /**
     * A deletion leaves exactly one tombstone naming the id and type, and nothing readable about
     * the User anywhere: the rows that held its profile, emails, credential history, memberships
     * and connector alias are gone. Each was checked present first, so their absence is a
     * deletion rather than a query that never matched.
     */
    @Test
    void deletion_leaves_only_a_tombstone_and_removes_every_readable_row() throws Exception {
        UUID user = createUser("delete-tombstone");
        UUID group = createGroupWith("Delete Tombstone Group", user);
        Map<String, String> ownRows = Map.of(
                "scim_users", "SELECT count(*) FROM scim_users WHERE resource_id = ?",
                "scim_user_emails", "SELECT count(*) FROM scim_user_emails WHERE resource_id = ?",
                "scim_user_password_history",
                "SELECT count(*) FROM scim_user_password_history WHERE user_id = ?",
                "scim_group_members", "SELECT count(*) FROM scim_group_members WHERE user_id = ?",
                "scim_external_ids", "SELECT count(*) FROM scim_external_ids WHERE resource_id = ?",
                "scim_resources", "SELECT count(*) FROM scim_resources WHERE id = ?");
        ownRows.forEach((table, query) -> assertThat(count(query, user))
                .as("fixture has a %s row", table).isPositive());
        long groupVersion = versionColumn(group);

        assertThat(status(conditional(tokenA, delete(USERS + "/" + user), user))).isEqualTo(204);

        ownRows.forEach((table, query) -> assertThat(count(query, user))
                .as("%s after deletion", table).isZero());
        Map<String, Object> tombstone = jdbc.queryForMap(
                "SELECT resource_type, deleted_at FROM scim_tombstones WHERE resource_id = ?",
                user);
        assertThat(tombstone.get("resource_type")).isEqualTo("User");
        assertThat(tombstone.get("deleted_at")).isNotNull();
        assertThat(versionColumn(group))
                .as("the Group lost a member, so its representation changed")
                .isEqualTo(groupVersion + 1);
        JsonNode members = body(mvc.perform(as(tokenA, get(GROUPS + "/" + group))).andReturn())
                .path("members");
        assertThat(members.isMissingNode() || members.isEmpty()).isTrue();
    }

    /**
     * The application role may insert and read tombstones, never change or remove one.
     *
     * <p>The test's own connection is the owning role, which the grants do not bind, so
     * each statement assumes {@code backend_app} -- the role the deployed application sets
     * on every connection -- inside a transaction of its own.
     */
    @Test
    void the_application_cannot_update_or_delete_a_tombstone() throws Exception {
        UUID user = createUser("delete-append-only");
        assertThat(status(conditional(tokenA, delete(USERS + "/" + user), user))).isEqualTo(204);

        assertThat(asApplicationRole(() -> jdbc.queryForObject(
                "SELECT count(*) FROM scim_tombstones WHERE resource_id = ?",
                Integer.class, user)))
                .as("the application role reads tombstones")
                .isEqualTo(1);
        assertThatThrownBy(() -> asApplicationRole(() -> jdbc.update(
                "DELETE FROM scim_tombstones WHERE resource_id = ?", user)))
                .isInstanceOf(DataAccessException.class)
                .rootCause().hasMessageContaining("permission denied for table scim_tombstones");
        assertThatThrownBy(() -> asApplicationRole(() -> jdbc.update(
                "UPDATE scim_tombstones SET deleted_at = now() WHERE resource_id = ?", user)))
                .isInstanceOf(DataAccessException.class)
                .rootCause().hasMessageContaining("permission denied for table scim_tombstones");
        assertThat(count("SELECT count(*) FROM scim_tombstones WHERE resource_id = ?", user))
                .as("both refused statements left the tombstone in place")
                .isEqualTo(1);
    }

    private <T> T asApplicationRole(Supplier<T> work) {
        return new TransactionTemplate(transactionManager).execute(status -> {
            jdbc.execute("SET LOCAL ROLE backend_app");
            return work.get();
        });
    }

    /** A Group deletion leaves a Group tombstone and frees its displayName the same way. */
    @Test
    void a_group_deletion_leaves_a_group_tombstone_and_frees_its_display_name() throws Exception {
        UUID group = createGroupWith("Delete Group Tombstone", createUser("delete-group-member"));

        assertThat(status(conditional(tokenA, delete(GROUPS + "/" + group), group)))
                .isEqualTo(204);

        assertThat(jdbc.queryForObject(
                "SELECT resource_type FROM scim_tombstones WHERE resource_id = ?",
                String.class, group)).isEqualTo("Group");
        assertThat(createGroup("Delete Group Tombstone")).isNotEqualTo(group);
    }

    // ---- a deleted id as a Group member reference ---------------------------------------

    /**
     * A deleted User's id is no longer a member reference: a Group create naming it and a
     * PATCH adding it are both {@code 400 invalidValue}, and neither leaves anything behind.
     *
     * <p>The same id is first accepted as a member while the User is live, so the refusals
     * afterwards are the deletion's doing rather than a malformed reference.
     */
    @Test
    void a_deleted_users_id_cannot_become_a_group_member() throws Exception {
        UUID user = createUser("delete-member-ref");
        UUID target = createGroup("Delete Member Target");
        MvcResult accepted = mvc.perform(conditional(tokenA, withBody(
                patch(GROUPS + "/" + target),
                patchOp("{\"op\":\"add\",\"path\":\"members\",\"value\":[{\"value\":\""
                        + user + "\"}]}")), target)).andReturn();
        assertThat(accepted.getResponse().getStatus())
                .as("while the User is live, its id is a valid member").isEqualTo(200);
        assertThat(status(conditional(tokenA, delete(USERS + "/" + user), user))).isEqualTo(204);
        long targetVersion = versionColumn(target);

        MvcResult created = mvc.perform(as(tokenA, withBody(post(GROUPS),
                "{\"schemas\":[\"" + GROUP_SCHEMA + "\"],\"displayName\":\"Delete Member Ref\","
                        + "\"members\":[{\"value\":\"" + user + "\"}]}"))).andReturn();
        assertThat(created.getResponse().getStatus()).isEqualTo(400);
        assertThat(body(created).get("scimType").asText()).isEqualTo("invalidValue");
        assertThat(createGroup("Delete Member Ref"))
                .as("the refused create left no Group holding the displayName")
                .isNotNull();

        MvcResult patched = mvc.perform(conditional(tokenA, withBody(
                patch(GROUPS + "/" + target),
                patchOp("{\"op\":\"add\",\"path\":\"members\",\"value\":[{\"value\":\""
                        + user + "\"}]}")), target)).andReturn();
        assertThat(patched.getResponse().getStatus()).isEqualTo(400);
        assertThat(body(patched).get("scimType").asText()).isEqualTo("invalidValue");
        assertThat(versionColumn(target))
                .as("the refused PATCH changed nothing").isEqualTo(targetVersion);
        assertThat(count("SELECT count(*) FROM scim_group_members WHERE group_id = ?", target))
                .isZero();
    }

    // ---- preconditions, authorization, existence ----------------------------------------

    /**
     * {@code If-Match} is optional: a deletion without one deletes exactly as a conditional one
     * does — the row goes, a tombstone is written, and the id is a {@code 404} from then on.
     */
    @Test
    void a_deletion_without_if_match_deletes_and_leaves_a_tombstone() throws Exception {
        UUID user = createUser("delete-unconditional");

        assertThat(status(as(tokenA, delete(USERS + "/" + user)))).isEqualTo(204);

        assertThat(count("SELECT count(*) FROM scim_users WHERE resource_id = ?", user))
                .isZero();
        assertThat(count("SELECT count(*) FROM scim_tombstones WHERE resource_id = ?", user))
                .isEqualTo(1);
        assertThat(status(as(tokenA, get(USERS + "/" + user)))).isEqualTo(404);
    }

    @ParameterizedTest
    @ValueSource(strings = {"*", "\"1\", \"2\"", "1", "W/1"})
    void a_wildcard_list_or_malformed_if_match_is_invalid_value(String header) throws Exception {
        UUID user = createUser("delete-invalid-" + Math.abs(header.hashCode()));

        MvcResult refused = mvc.perform(as(tokenA, delete(USERS + "/" + user))
                .header(HttpHeaders.IF_MATCH, header)).andReturn();

        assertThat(refused.getResponse().getStatus()).isEqualTo(400);
        assertThat(body(refused).get("scimType").asText()).isEqualTo("invalidValue");
        assertStillThere(user);
    }

    @Test
    void a_stale_or_weak_if_match_is_412_and_deletes_nothing() throws Exception {
        UUID user = createUser("delete-412");
        long version = versionColumn(user);

        for (String stale : List.of("\"" + (version + 1) + "\"", "W/\"" + version + "\"")) {
            MvcResult refused = mvc.perform(as(tokenA, delete(USERS + "/" + user))
                    .header(HttpHeaders.IF_MATCH, stale)).andReturn();
            assertThat(refused.getResponse().getStatus()).as(stale).isEqualTo(412);
        }
        assertStillThere(user);
        assertThat(auditCount("SCIM_USER_DELETE", user, "SUCCESS")).isZero();
    }

    @Test
    void an_unknown_or_malformed_id_is_not_found_whatever_the_precondition() throws Exception {
        for (String id : List.of(UUID.randomUUID().toString(), "not-a-uuid")) {
            assertThat(status(as(tokenA, delete(USERS + "/" + id)))).as(id).isEqualTo(404);
        }
    }

    /** A Group's id names no User, so a User DELETE cannot remove a Group through it. */
    @Test
    void a_groups_id_is_not_a_user_to_delete() throws Exception {
        UUID group = createGroup("Delete Not A User");

        assertThat(status(conditional(tokenA, delete(USERS + "/" + group), group)))
                .isEqualTo(404);

        assertThat(count("SELECT count(*) FROM scim_groups WHERE resource_id = ?", group))
                .isEqualTo(1);
    }

    @Test
    void a_read_only_token_is_refused_for_its_scope_and_deletes_nothing() throws Exception {
        UUID user = createUser("delete-read-only");

        assertThat(status(conditional(readOnlyToken, delete(USERS + "/" + user), user)))
                .isEqualTo(403);
        assertStillThere(user);
    }

    @Test
    void the_bootstrap_admin_cannot_be_deleted_and_the_refusal_is_audited() throws Exception {
        UUID bootstrap = userRepository
                .findByReservedName(ReservedResourceName.BOOTSTRAP_ADMIN).orElseThrow().id();
        // Counted as a delta: the Bootstrap Admin is shared by every suite on this context and
        // audit rows are append-only, so another suite's refused delete is already there.
        int refusalsBefore = auditCount("SCIM_USER_DELETE", bootstrap, "FAILURE");

        MvcResult refused = mvc.perform(conditional(tokenA, delete(USERS + "/" + bootstrap),
                bootstrap)).andReturn();

        assertThat(refused.getResponse().getStatus()).isEqualTo(400);
        assertThat(body(refused).get("scimType").asText()).isEqualTo("mutability");
        assertStillThere(bootstrap);
        assertThat(auditCount("SCIM_USER_DELETE", bootstrap, "FAILURE"))
                .isEqualTo(refusalsBefore + 1);
    }

    // ---- sessions and audit -------------------------------------------------------------

    /**
     * The deletion ends the User's session in the real store after commit, and the deletion and
     * the revocation are each their own event, naming the connector and the stable id.
     */
    @Test
    void deletion_revokes_the_session_and_records_both_events() throws Exception {
        UUID user = createUser("delete-revoke");
        Session session = openSessionFor(user);
        assertThat(sessionRepository.findById(session.getId())).isNotNull();

        assertThat(status(conditional(tokenA, delete(USERS + "/" + user), user))).isEqualTo(204);

        assertThat(sessionRepository.findById(session.getId())).isNull();
        Map<String, Object> deleted = jdbc.queryForMap("""
                SELECT outcome, actor_id, resource_type, status_class FROM audit_events
                 WHERE operation = 'SCIM_USER_DELETE' AND subject_id = ?""", user);
        assertThat(deleted).containsEntry("outcome", "SUCCESS")
                .containsEntry("actor_id", connectorA)
                .containsEntry("resource_type", "User")
                .containsEntry("status_class", "ok");
        Map<String, Object> revoked = jdbc.queryForMap("""
                SELECT outcome, actor_id FROM audit_events
                 WHERE operation = 'USER_SESSIONS_REVOKE' AND subject_id = ?""", user);
        assertThat(revoked).containsEntry("outcome", "SUCCESS")
                .containsEntry("actor_id", connectorA);
    }

    /**
     * A deletion that rolls back revokes nothing and leaves nothing: the use case runs inside a
     * transaction that is then rolled back, so the after-commit revocation never fires, the
     * session survives, the User is still there and no tombstone or success event exists.
     */
    @Test
    void a_rolled_back_deletion_revokes_nothing_and_leaves_no_tombstone() throws Exception {
        UUID user = createUserThroughHttp("delete-rollback");
        Session session = openSessionFor(user);
        long version = versionColumn(user);

        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            assertThat(userService.delete(
                    new AuthenticatedConnector(connectorA, UUID.randomUUID(),
                            TokenPermissions.of(TokenPermissions.ALL)),
                    user,
                    ScimVersionPrecondition.ofIfMatch(List.of("\"" + version + "\""))))
                    .isTrue();
            status.setRollbackOnly();
        });

        assertThat(sessionRepository.findById(session.getId())).isNotNull();
        assertStillThere(user);
        assertThat(auditCount("SCIM_USER_DELETE", user, "SUCCESS")).isZero();
        assertThat(auditCount("USER_SESSIONS_REVOKE", user, "SUCCESS")).isZero();
    }

    /** A refused deletion revokes nothing either. */
    @Test
    void a_refused_deletion_revokes_nothing() throws Exception {
        UUID user = createUser("delete-refused-session");
        Session session = openSessionFor(user);

        assertThat(status(as(tokenA, delete(USERS + "/" + user))
                .header(HttpHeaders.IF_MATCH, "\"" + (versionColumn(user) + 1) + "\"")))
                .isEqualTo(412);

        assertThat(sessionRepository.findById(session.getId())).isNotNull();
    }

    // ---- helpers --------------------------------------------------------------------------

    private void assertStillThere(UUID user) throws Exception {
        assertThat(count("SELECT count(*) FROM scim_users WHERE resource_id = ?", user))
                .isEqualTo(1);
        assertThat(count("SELECT count(*) FROM scim_tombstones WHERE resource_id = ?", user))
                .isZero();
        assertThat(mvc.perform(as(tokenA, get(USERS + "/" + user))).andReturn()
                .getResponse().getStatus()).isEqualTo(200);
    }

    private List<UUID> listedUserIds() throws Exception {
        JsonNode page = body(mvc.perform(as(tokenA, get(USERS).param("count", "200")))
                .andReturn());
        assertThat(page.get("totalResults").asInt())
                .as("the whole collection fits one page, so absence is not paging")
                .isLessThanOrEqualTo(200)
                .isEqualTo(page.get("itemsPerPage").asInt());
        return StreamSupport.stream(page.get("Resources").spliterator(), false)
                .map(resource -> UUID.fromString(resource.get("id").asText()))
                .toList();
    }

    private UUID createUser(String userName) {
        return createUserThroughHttp(userName);
    }

    /** A User with a password, an email, an alias and so a history row — every readable row. */
    private UUID createUserThroughHttp(String userName) {
        try {
            MvcResult result = mvc.perform(as(tokenA, withBody(post(USERS), """
                    {"schemas":["%s"],"userName":"%s","password":"first-correct-horse",
                     "displayName":"Shown","externalId":"ext-%s",
                     "emails":[{"value":"%s@work.example","type":"work","primary":true}]}"""
                    .formatted(USER_SCHEMA, userName, userName, userName.toLowerCase()))))
                    .andReturn();
            assertThat(result.getResponse().getStatus()).as(userName).isEqualTo(201);
            UUID id = UUID.fromString(body(result).get("id").asText());
            created.add(id);
            return id;
        } catch (Exception failed) {
            throw new AssertionError(failed);
        }
    }

    private UUID createGroup(String displayName) throws Exception {
        return group("{\"schemas\":[\"" + GROUP_SCHEMA + "\"],\"displayName\":\""
                + displayName + "\"}");
    }

    private UUID createGroupWith(String displayName, UUID member) throws Exception {
        return group("{\"schemas\":[\"" + GROUP_SCHEMA + "\"],\"displayName\":\"" + displayName
                + "\",\"members\":[{\"value\":\"" + member + "\"}]}");
    }

    private UUID group(String body) throws Exception {
        MvcResult result = mvc.perform(as(tokenA, withBody(post(GROUPS), body))).andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(201);
        UUID id = UUID.fromString(body(result).get("id").asText());
        created.add(id);
        return id;
    }

    private static String minimalUser(String userName) {
        return "{\"schemas\":[\"" + USER_SCHEMA + "\"],\"userName\":\"" + userName + "\"}";
    }

    private static String patchOp(String... operations) {
        return "{\"schemas\":[\"" + PATCH_OP + "\"],\"Operations\":["
                + String.join(",", operations) + "]}";
    }

    private static MockHttpServletRequestBuilder withBody(
            MockHttpServletRequestBuilder request, String body) {
        return request.contentType(SCIM_JSON).content(body);
    }

    private static MockHttpServletRequestBuilder as(
            String token, MockHttpServletRequestBuilder request) {
        return request.header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
    }

    /** The request with the resource's current version, as a conforming connector sends it. */
    private MockHttpServletRequestBuilder conditional(
            String token, MockHttpServletRequestBuilder request, UUID id) {
        return as(token, request).header(HttpHeaders.IF_MATCH, "\"" + versionColumn(id) + "\"");
    }

    private int status(MockHttpServletRequestBuilder request) throws Exception {
        return mvc.perform(request).andReturn().getResponse().getStatus();
    }

    private long versionColumn(UUID id) {
        return jdbc.queryForObject("SELECT version FROM scim_resources WHERE id = ?", Long.class, id);
    }

    private int count(String query, UUID id) {
        return jdbc.queryForObject(query, Integer.class, id);
    }

    private int auditCount(String operation, UUID subject, String outcome) {
        return jdbc.queryForObject("""
                SELECT count(*) FROM audit_events
                 WHERE operation = ? AND subject_id = ? AND outcome = ?""",
                Integer.class, operation, subject, outcome);
    }

    private JsonNode body(MvcResult result) throws Exception {
        return json.readTree(result.getResponse().getContentAsString());
    }

    /** Opens a session indexed by the User's stable id, exactly as a real login does. */
    private Session openSessionFor(UUID userId) {
        Session session = sessionRepository.createSession();
        session.setAttribute(
                FindByIndexNameSessionRepository.PRINCIPAL_NAME_INDEX_NAME, userId.toString());
        @SuppressWarnings("unchecked")
        FindByIndexNameSessionRepository<Session> repository =
                (FindByIndexNameSessionRepository<Session>) sessionRepository;
        repository.save(session);
        return session;
    }
}

package com.example.backend.authorization;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import com.example.backend.ContainerTestConfiguration;
import com.example.backend.DevFixtures;
import com.example.backend.SessionCsrf;
import com.example.backend.TokenPermissions;
import com.example.backend.UuidColumnRows;
import com.example.backend.observability.RequestIdFilter;
import com.example.backend.scim.application.ConnectorAdministrationService;
import com.example.backend.scim.domain.NormalizedUserName;
import com.example.backend.scim.domain.ScimUserRepository;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.Filter;
import jakarta.servlet.http.Cookie;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.annotation.DirtiesContext.ClassMode;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Group Permissions made visible, the Admin group's protections, and a change of a Group's
 * membership reaching the sessions it was issued to — over HTTP, against the shipped development
 * fixtures and their {@code local} Group Permissions, real SQL Server and the indexed Redis
 * session store (ADR 0015).
 *
 * <p>Its context is dirtied after the class, because these tests change who belongs to the
 * fixture Groups and delete one of them, which no other class sharing the development fixtures'
 * context — and so its database — could survive. Its configuration is the same as
 * {@code DevelopmentFixturesIntegrationTests}', so without the eviction whichever runs second
 * would reuse it. Each test that moves a fixture membership puts it back; the Monitoring Group is
 * deleted by one test only, and no other test here depends on it.
 */
@SpringBootTest
@Import(ContainerTestConfiguration.class)
@ActiveProfiles({"dev-fixtures", "local"})
@DirtiesContext(classMode = ClassMode.AFTER_CLASS)
@TestPropertySource(properties = {
    "app.dev-fixtures.enabled=true",
    "app.dev-fixtures.password=" + GroupPermissionPropagationIntegrationTests.FIXTURE_PASSWORD})
class GroupPermissionPropagationIntegrationTests {

    static final String FIXTURE_PASSWORD = DevFixtures.PASSWORD;

    private static final UUID SUPERUSERS = TestGroupPermissions.SUPERUSER_GROUP_ID;

    private static final UUID ACCOUNT_ADMINS =
            UUID.fromString("00000000-0000-4000-8000-00000000a002");

    private static final UUID MONITORING = UUID.fromString("00000000-0000-4000-8000-00000000a005");

    private static final String GROUPS = "/scim/v2/Groups/";

    private static final String GROUP_SCHEMA = "urn:ietf:params:scim:schemas:core:2.0:Group";

    private static final String PATCH_OP = "urn:ietf:params:scim:api:messages:2.0:PatchOp";

    private static final MediaType SCIM_JSON = MediaType.valueOf("application/scim+json");

    private final JsonMapper json = JsonMapper.builder().build();

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private RequestIdFilter requestIdFilter;

    @Autowired
    @Qualifier("springSecurityFilterChain")
    private Filter springSecurityFilterChain;

    @Autowired
    @Qualifier("springSessionRepositoryFilter")
    private Filter springSessionRepositoryFilter;

    @Autowired
    private ScimUserRepository users;

    @Autowired
    private ConnectorAdministrationService connectors;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private MeterRegistry meters;

    @Value("${server.servlet.session.cookie.name:SESSION}")
    private String sessionCookieName;

    private MockMvc mvc;

    private UUID connectorId;

    private String token;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context)
                .addFilters(requestIdFilter, springSessionRepositoryFilter, springSecurityFilterChain)
                .build();
        connectorId = connectors.create("Group Permission propagation", null).id();
        token = connectors
                .issueToken(connectorId, TokenPermissions.ALL, null, null)
                .presentedValue();
    }

    // ---- what a Group may carry, and what each carries ---------------------------------------

    /** A holder of {@code group:read} reads every delegatable Permission, sorted by name. */
    @Test
    void a_group_reader_lists_every_permission_a_group_may_carry() throws Exception {
        MvcResult listed = mvc.perform(get("/api/admin/permissions")
                        .cookie(logIn("account-admin"))).andReturn();

        assertThat(listed.getResponse().getStatus()).isEqualTo(200);
        assertThat(strings(json.readTree(listed.getResponse().getContentAsString())))
                .containsExactly("account:unlock", "audit:read", "connector:read", "group:read",
                        "ops:read", "user:read");
    }

    /**
     * Without {@code group:read} the read is refused; nothing writes the vocabulary; and the
     * retired roles endpoint answers no one, the Superuser included.
     */
    @Test
    void the_permissions_endpoint_requires_group_read_and_the_roles_endpoint_is_gone()
            throws Exception {
        assertThat(status(get("/api/admin/permissions").cookie(logIn("auditor"))))
                .isEqualTo(403);
        Cookie admin = logIn("test-admin", "test-admin-password");
        assertThat(status(get("/api/admin/roles").cookie(admin)))
                .as("no Role remains to list").isEqualTo(403);
        for (MockHttpServletRequestBuilder write : List.of(
                post("/api/admin/permissions"), put("/api/admin/permissions"),
                delete("/api/admin/permissions"))) {
            assertThat(mvc.perform(SessionCsrf.withCsrf(mvc, write.cookie(admin))
                                    .contentType(MediaType.APPLICATION_JSON).content("{}"))
                            .andReturn().getResponse().getStatus())
                    .as("the vocabulary is code's, so even the Superuser writes none")
                    .isEqualTo(403);
        }
    }

    /**
     * Each Groups row carries what the Group confers, read with {@code group:read}: the Account
     * admins their three, and the Admin group none — its members are the Superuser.
     */
    @Test
    void the_groups_rows_carry_each_groups_permissions() throws Exception {
        MvcResult listed = mvc.perform(get("/api/admin/groups")
                        .cookie(logIn("account-admin"))).andReturn();

        assertThat(listed.getResponse().getStatus()).isEqualTo(200);
        JsonNode rows = json.readTree(listed.getResponse().getContentAsString());
        assertThat(strings(row(rows, ACCOUNT_ADMINS).get("permissions")))
                .containsExactly("account:unlock", "group:read", "user:read");
        JsonNode adminGroup = row(rows, SUPERUSERS);
        assertThat(adminGroup.get("adminGroup").asBoolean()).isTrue();
        assertThat(strings(adminGroup.get("permissions"))).isEmpty();
    }

    // ---- the Admin group over SCIM -------------------------------------------------------------

    /**
     * The Admin group cannot be renamed or deleted, and the Bootstrap Admin's membership in it
     * cannot change. Every other member can be removed: there is no "last enabled administrator"
     * guard, because the frozen Bootstrap Admin is what keeps a Superuser.
     */
    @Test
    void the_admin_group_keeps_its_name_its_existence_and_the_bootstrap_admin()
            throws Exception {
        UUID superusers = SUPERUSERS;
        UUID bootstrapAdmin = jdbc.queryForObject(
                "SELECT id FROM scim_resources WHERE reserved_name = 'bootstrap-admin'", UUID.class);
        UUID testUser = userId("test-user");
        String name = displayName(superusers);

        assertThat(scimPatch(superusers, """
                {"op":"replace","path":"displayName","value":"Not superusers"}""")).isEqualTo(400);
        assertThat(scim(delete(GROUPS + superusers))).isEqualTo(400);
        assertThat(scimPatch(superusers, """
                {"op":"remove","path":"members[value eq \\"%s\\"]"}""".formatted(bootstrapAdmin)))
                .isEqualTo(400);
        assertThat(displayName(superusers)).isEqualTo(name);

        // Ordinary membership stays writable, down to the Bootstrap Admin alone.
        assertThat(scimPatch(superusers, """
                {"op":"add","path":"members","value":[{"value":"%s"}]}""".formatted(testUser)))
                .isEqualTo(200);
        assertThat(scimPut(superusers, name, List.of(bootstrapAdmin))).isEqualTo(200);
        assertThat(jdbc.queryForList(
                        "SELECT user_id FROM scim_group_members WHERE group_id = ?",
                        UUID.class, superusers))
                .containsExactly(bootstrapAdmin);
        assertThat(scimPut(superusers, name, List.of())).as("not even a PUT empties it")
                .isEqualTo(400);
    }

    /**
     * Every Group but the Admin group is writable and deletable — and deleting one that carries
     * Permissions takes them from every member, ending their sessions after the commit, and takes
     * its stored Permissions with it.
     */
    @Test
    void a_group_carrying_permissions_can_be_renamed_and_deleted_which_revokes_its_members()
            throws Exception {
        Cookie monitoring = logIn("monitoring");
        UUID member = userId("monitoring");

        assertThat(scimPatch(MONITORING, """
                {"op":"replace","path":"displayName","value":"Observability"}""")).isEqualTo(200);
        assertThat(status(get("/api/auth/me").cookie(monitoring)))
                .as("a rename moves no power").isEqualTo(200);

        assertThat(scim(delete(GROUPS + MONITORING))).isEqualTo(204);

        assertThat(status(get("/api/auth/me").cookie(monitoring))).isEqualTo(401);
        assertPermissionChange("PERMISSION_REVOKE", member, MONITORING, "ops:read", null, false);
        assertSessionsRevokedForGroups(member);
        assertThat(jdbc.queryForObject(
                        "SELECT COUNT(*) FROM group_permission WHERE group_id = ?",
                        Integer.class, MONITORING))
                .as("the Group's Permissions went with it").isZero();
    }

    // ---- propagation ------------------------------------------------------------------------

    /** Removing a User from a Group carrying Permissions ends its sessions once the write commits. */
    @Test
    void removal_from_a_group_carrying_permissions_by_patch_or_put_revokes_the_members_sessions()
            throws Exception {
        UUID accountAdmin = userId("account-admin");
        try {
            Cookie session = logIn("account-admin");
            assertThat(scimPatch(ACCOUNT_ADMINS, """
                    {"op":"remove","path":"members[value eq \\"%s\\"]"}"""
                    .formatted(accountAdmin))).isEqualTo(200);
            assertThat(status(get("/api/auth/me").cookie(session))).isEqualTo(401);
            assertPermissionChange("PERMISSION_REVOKE", accountAdmin, ACCOUNT_ADMINS,
                    "account:unlock,group:read,user:read", null, false);
            assertSessionsRevokedForGroups(accountAdmin);

            // Signed in again, the User no longer holds the Group's Permissions.
            Cookie again = logIn("account-admin");
            assertThat(status(get("/api/admin/accounts").cookie(again))).isEqualTo(403);

            // A PUT whose member list leaves the User out is the same removal.
            assertThat(scimPatch(ACCOUNT_ADMINS, """
                    {"op":"add","path":"members","value":[{"value":"%s"}]}"""
                    .formatted(accountAdmin))).isEqualTo(200);
            Cookie readmitted = logIn("account-admin");
            assertThat(scimPut(ACCOUNT_ADMINS, "Account admins", List.of())).isEqualTo(200);
            assertThat(status(get("/api/auth/me").cookie(readmitted))).isEqualTo(401);
        } finally {
            scimPut(ACCOUNT_ADMINS, "Account admins", List.of(accountAdmin));
        }
    }

    /**
     * Adding a User to a Group carrying Permissions leaves its live session alone, holding what it
     * held; the Group's Permissions apply from its next sign-in.
     */
    @Test
    void addition_to_a_group_carrying_permissions_applies_at_the_next_sign_in() throws Exception {
        UUID auditor = userId("auditor");
        try {
            Cookie live = logIn("auditor");

            assertThat(scimPatch(ACCOUNT_ADMINS, """
                    {"op":"add","path":"members","value":[{"value":"%s"}]}"""
                    .formatted(auditor))).isEqualTo(200);

            assertThat(status(get("/api/auth/me").cookie(live))).isEqualTo(200);
            assertThat(permissions(live)).doesNotContain("user:read");
            assertThat(status(get("/api/admin/accounts").cookie(live))).isEqualTo(403);
            assertPermissionChange("PERMISSION_GRANT", auditor, ACCOUNT_ADMINS, "audit:read",
                    "account:unlock,audit:read,group:read,user:read", false);

            Cookie next = logIn("auditor");
            assertThat(permissions(next)).contains("account:unlock", "audit:read", "user:read");
            assertThat(status(get("/api/admin/accounts").cookie(next))).isEqualTo(200);
        } finally {
            scimPatch(ACCOUNT_ADMINS, """
                    {"op":"remove","path":"members[value eq \\"%s\\"]"}""".formatted(auditor));
        }
    }

    /**
     * Adding a User to the Admin group makes it the Superuser: the grant's audit row says so, and
     * the {@code superuser-grant} count rises by exactly one.
     */
    @Test
    void addition_to_the_admin_group_is_a_counted_superuser_grant() throws Exception {
        UUID monitoring = userId("monitoring");
        double before = superuserGrants();
        try {
            assertThat(scimPatch(SUPERUSERS, """
                    {"op":"add","path":"members","value":[{"value":"%s"}]}"""
                    .formatted(monitoring))).isEqualTo(200);

            assertThat(superuserGrants()).isEqualTo(before + 1);
            assertPermissionChange("PERMISSION_GRANT", monitoring, SUPERUSERS, "ops:read",
                    "account:unlock,audit:read,connector:read,group:read,ops:read,user:read",
                    true);
        } finally {
            scimPatch(SUPERUSERS, """
                    {"op":"remove","path":"members[value eq \\"%s\\"]"}""".formatted(monitoring));
        }
    }

    // ---- a Superuser sets a Group's Permissions (#159) ---------------------------------------

    /**
     * The Superuser's PUT replaces the Group's set. Growth leaves the member's live session alone
     * and reaches it at its next sign-in; taking the Permission away again ends that session at
     * once. Both changes are audited with the Group's Permissions before and after, and counted.
     */
    @Test
    void a_superuser_sets_a_groups_permissions_growth_at_next_sign_in_shrink_at_once()
            throws Exception {
        Cookie superuser = logIn("test-admin", "test-admin-password");
        UUID actor = userId("test-admin");
        double counted = groupPermissionsChanges();
        OffsetDateTime started = OffsetDateTime.now(ZoneOffset.UTC);
        try {
            Cookie live = logIn("helpdesk");

            MvcResult grown = setPermissions(superuser, HELPDESK,
                    "[\"user:read\",\"account:unlock\",\"audit:read\",\"audit:read\"]");

            assertThat(grown.getResponse().getStatus()).isEqualTo(200);
            JsonNode row = json.readTree(grown.getResponse().getContentAsString());
            assertThat(strings(row.get("permissions")))
                    .as("the whole set, duplicates collapsed, sorted by name")
                    .containsExactly("account:unlock", "audit:read", "user:read");
            assertThat(status(get("/api/admin/audit-events").cookie(live)))
                    .as("the live session holds what it held").isEqualTo(403);
            Cookie next = logIn("helpdesk");
            assertThat(status(get("/api/admin/audit-events").cookie(next))).isEqualTo(200);

            MvcResult shrunk = setPermissions(superuser, HELPDESK,
                    "[\"user:read\",\"account:unlock\"]");

            assertThat(shrunk.getResponse().getStatus()).isEqualTo(200);
            assertThat(status(get("/api/auth/me").cookie(next))).isEqualTo(401);
            assertThat(groupPermissionsChanges()).isEqualTo(counted + 2);
            assertThat(groupPermissionsChangeRows(actor, HELPDESK, started))
                    .containsExactly(
                            List.of("SUCCESS", "account:unlock,user:read",
                                    "account:unlock,audit:read,user:read", "permissions"),
                            List.of("SUCCESS", "account:unlock,audit:read,user:read",
                                    "account:unlock,user:read", "permissions"));
            assertThat(jdbc.queryForList("""
                    SELECT error_code FROM audit_events
                     WHERE operation = 'USER_SESSIONS_REVOKE' AND subject_id = ? AND actor_id = ?
                       AND occurred_at >= ?""",
                    String.class, userId("helpdesk"), actor, started))
                    .containsExactly("GROUP_PERMISSIONS_REDUCED");
        } finally {
            setPermissions(superuser, HELPDESK, "[\"user:read\",\"account:unlock\"]");
        }
    }

    /** An empty set clears the Group: its member holds nothing of it from the next sign-in. */
    @Test
    void an_empty_set_clears_a_group() throws Exception {
        Cookie superuser = logIn("test-admin", "test-admin-password");
        try {
            assertThat(setPermissions(superuser, HELPDESK, "[]").getResponse().getStatus())
                    .isEqualTo(200);
            assertThat(jdbc.queryForObject(
                            "SELECT COUNT(*) FROM group_permission WHERE group_id = ?",
                            Integer.class, HELPDESK))
                    .isZero();
            assertThat(status(get("/api/admin/accounts").cookie(logIn("helpdesk"))))
                    .isEqualTo(403);
        } finally {
            setPermissions(superuser, HELPDESK, "[\"user:read\",\"account:unlock\"]");
        }
    }

    /**
     * Every refusal answers the API's one error body, changes nothing, and is audited as a
     * {@code GROUP_PERMISSIONS_CHANGE} failure with a generic reason; a caller who is not the
     * Superuser — whatever Permissions it holds — gets the generic 403.
     */
    @Test
    void every_refusal_answers_the_error_body_changes_nothing_and_is_audited() throws Exception {
        Cookie superuser = logIn("test-admin", "test-admin-password");
        UUID actor = userId("test-admin");
        UUID nowhere = UUID.randomUUID();
        OffsetDateTime started = OffsetDateTime.now(ZoneOffset.UTC);
        record Refusal(UUID group, String body, int status, String code, String reason) {
        }
        List<Refusal> refusals = List.of(
                new Refusal(HELPDESK, null, 400, "invalid-request", "INVALID_PERMISSIONS"),
                new Refusal(HELPDESK, "{\"permissions\":[]}", 400, "invalid-request",
                        "INVALID_PERMISSIONS"),
                new Refusal(HELPDESK, "[\"audit:read\",7]", 400, "invalid-request",
                        "INVALID_PERMISSIONS"),
                new Refusal(HELPDESK, "[\"user:write\"]", 400, "invalid-request",
                        "INVALID_PERMISSIONS"),
                new Refusal(HELPDESK, "[\"group:write\"]", 400, "invalid-request",
                        "INVALID_PERMISSIONS"),
                new Refusal(HELPDESK, "[\"audit:read\"", 400, "invalid-request",
                        "INVALID_PERMISSIONS"),
                new Refusal(nowhere, "[\"audit:read\"]", 404, "request-refused",
                        "UNKNOWN_RESOURCE"),
                new Refusal(SUPERUSERS, "[\"audit:read\"]", 409, "request-refused",
                        "PROTECTED_RESOURCE"));

        for (Refusal refusal : refusals) {
            MvcResult answered = setPermissions(superuser, refusal.group(), refusal.body());
            assertThat(answered.getResponse().getStatus()).as("%s", refusal)
                    .isEqualTo(refusal.status());
            JsonNode error = json.readTree(answered.getResponse().getContentAsString());
            assertThat(List.of(error.get("status").asInt(), error.get("code").asString()))
                    .as("%s", refusal).containsExactly(refusal.status(), refusal.code());
        }

        assertThat(jdbc.queryForList(
                        "SELECT permission FROM group_permission WHERE group_id IN (?, ?)",
                        String.class, HELPDESK, SUPERUSERS))
                .containsExactlyInAnyOrder("user:read", "account:unlock");
        assertThat(jdbc.queryForList("""
                SELECT error_code FROM audit_events
                 WHERE operation = 'GROUP_PERMISSIONS_CHANGE' AND outcome = 'FAILURE'
                   AND actor_id = ? AND subject_id IN (?, ?, ?) AND occurred_at >= ?
                 ORDER BY occurred_at, id""",
                String.class, actor, HELPDESK, nowhere, SUPERUSERS, started))
                .containsExactlyElementsOf(refusals.stream().map(Refusal::reason).toList());

        Cookie groupReader = logIn("account-admin");
        MvcResult forbidden = setPermissions(groupReader, HELPDESK, "[\"audit:read\"]");
        assertThat(forbidden.getResponse().getStatus()).isEqualTo(403);
        assertThat(json.readTree(forbidden.getResponse().getContentAsString())
                .get("code").asString()).isEqualTo("request-refused");
    }

    // ---- helpers ----------------------------------------------------------------------------

    private static final UUID HELPDESK = UUID.fromString("00000000-0000-4000-8000-00000000a006");

    /** {@code PUT /api/admin/groups/{id}/permissions} with {@code body}, or none when null. */
    private MvcResult setPermissions(Cookie session, UUID groupId, String body) throws Exception {
        MockHttpServletRequestBuilder request = SessionCsrf.withCsrf(mvc,
                put("/api/admin/groups/" + groupId + "/permissions").cookie(session));
        if (body != null) {
            request.contentType(MediaType.APPLICATION_JSON).content(body);
        }
        return mvc.perform(request).andReturn();
    }

    /** How many Group Permissions changes the {@code authorization.changes} counter has seen. */
    private double groupPermissionsChanges() {
        return meters.get("authorization.changes").tag("operation", "group-permissions-change")
                .counter().count();
    }

    /** This Superuser's successful changes of the Group: outcome, before, after and paths. */
    private List<List<String>> groupPermissionsChangeRows(
            UUID actor, UUID groupId, OffsetDateTime since) {
        return jdbc.query("""
                SELECT outcome, permissions_before, permissions_after, changed_paths
                  FROM audit_events
                 WHERE operation = 'GROUP_PERMISSIONS_CHANGE' AND outcome = 'SUCCESS'
                   AND actor_id = ? AND subject_id = ? AND resource_id = ? AND occurred_at >= ?
                 ORDER BY occurred_at, id""",
                (rs, index) -> List.of(rs.getString("outcome"), rs.getString("permissions_before"),
                        rs.getString("permissions_after"), rs.getString("changed_paths")),
                actor, groupId, groupId, since);
    }

    /** How many Superuser grants the {@code authorization.changes} counter has seen. */
    private double superuserGrants() {
        return meters.get("authorization.changes").tag("operation", "superuser-grant").counter()
                .count();
    }

    /**
     * The change of power is in the trail: the connector, the User, the Group, the User's
     * effective Permissions before and after — {@code null} for none — and the Superuser marker.
     */
    private void assertPermissionChange(String operation, UUID userId, UUID groupId,
            String before, String after, boolean superuser) {
        List<Map<String, Object>> rows = jdbc.query("""
                SELECT actor_id, resource_type, resource_id, permissions_before, permissions_after,
                       superuser_changed, outcome
                  FROM audit_events
                 WHERE operation = ? AND subject_id = ? AND actor_id = ?""",
                UuidColumnRows.ROWS, operation, userId, connectorId);
        assertThat(rows).singleElement().satisfies(row -> {
            assertThat(row.get("resource_type")).isEqualTo("Group");
            assertThat(row.get("resource_id")).isEqualTo(groupId);
            assertThat(row.get("permissions_before")).isEqualTo(before);
            assertThat(row.get("permissions_after")).isEqualTo(after);
            assertThat(row.get("superuser_changed")).isEqualTo(superuser);
            assertThat(row.get("outcome")).isEqualTo("SUCCESS");
        });
    }

    /** The after-commit revocation is audited as ending the sessions for a Group change. */
    private void assertSessionsRevokedForGroups(UUID userId) {
        assertThat(jdbc.queryForList("""
                SELECT changed_paths FROM audit_events
                 WHERE operation = 'USER_SESSIONS_REVOKE' AND subject_id = ? AND actor_id = ?""",
                String.class, userId, connectorId))
                .isNotEmpty()
                .allSatisfy(paths -> assertThat(paths).isEqualTo("groups"));
    }

    private int scimPatch(UUID groupId, String operation) throws Exception {
        return scim(patch(GROUPS + groupId).contentType(SCIM_JSON).content("""
                {"schemas":["%s"],"Operations":[%s]}""".formatted(PATCH_OP, operation)));
    }

    private int scimPut(UUID groupId, String displayName, List<UUID> members) throws Exception {
        String memberValues = String.join(",", members.stream()
                .map(id -> "{\"value\":\"" + id + "\"}").toList());
        return scim(put(GROUPS + groupId).contentType(SCIM_JSON).content("""
                {"schemas":["%s"],"displayName":"%s","members":[%s]}"""
                .formatted(GROUP_SCHEMA, displayName, memberValues)));
    }

    private int scim(MockHttpServletRequestBuilder request) throws Exception {
        return status(request.header(HttpHeaders.AUTHORIZATION, "Bearer " + token));
    }

    private int status(MockHttpServletRequestBuilder request) throws Exception {
        return mvc.perform(request).andReturn().getResponse().getStatus();
    }

    private String displayName(UUID groupId) {
        return jdbc.queryForObject(
                "SELECT display_name FROM scim_groups WHERE resource_id = ?", String.class, groupId);
    }

    private List<String> permissions(Cookie session) throws Exception {
        MvcResult me = mvc.perform(get("/api/auth/me").cookie(session)).andReturn();
        assertThat(me.getResponse().getStatus()).isEqualTo(200);
        return strings(json.readTree(me.getResponse().getContentAsString()).get("permissions"));
    }

    private static JsonNode row(JsonNode rows, UUID groupId) {
        return rows.valueStream()
                .filter(row -> groupId.toString().equals(row.get("id").asString()))
                .findFirst()
                .orElseThrow();
    }

    private static List<String> strings(JsonNode array) {
        List<String> values = new ArrayList<>();
        array.forEach(value -> values.add(value.asString()));
        return values;
    }

    private Cookie logIn(String userName) throws Exception {
        return logIn(userName, FIXTURE_PASSWORD);
    }

    private Cookie logIn(String userName, String password) throws Exception {
        MvcResult login = mvc.perform(SessionCsrf.withCsrf(mvc, post("/api/auth/login"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"%s\",\"password\":\"%s\"}"
                                .formatted(userName, password)))
                .andReturn();
        assertThat(login.getResponse().getStatus()).as("login as %s", userName).isEqualTo(200);
        Cookie session = login.getResponse().getCookie(sessionCookieName);
        assertThat(session).as("the login issued a session cookie").isNotNull();
        return new Cookie(session.getName(), session.getValue());
    }

    private UUID userId(String userName) {
        return users.findByNormalizedUserName(NormalizedUserName.of(userName)).orElseThrow().id();
    }
}

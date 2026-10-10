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
import com.example.backend.auth.domain.RoleMappingSessions;
import com.example.backend.authorization.domain.RoleMapping;
import com.example.backend.observability.RequestIdFilter;
import com.example.backend.scim.application.ConnectorAdministrationService;
import com.example.backend.scim.domain.NormalizedUserName;
import com.example.backend.scim.domain.ScimUserRepository;
import jakarta.servlet.Filter;
import jakarta.servlet.http.Cookie;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.session.FindByIndexNameSessionRepository;
import org.springframework.session.Session;
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
 * Roles made visible, the Superuser Group's protections, and a change of Role reaching the
 * sessions it was issued to — over HTTP, against the shipped development role mapping, real
 * Postgres and the indexed Redis session store.
 *
 * <p>Its context is dirtied after the class, because these tests change who belongs to the
 * fixture Groups and delete one of them, which no other class sharing the development mapping's
 * context — and so its database — could survive. Its configuration is the same as
 * {@code DevelopmentRoleMappingIntegrationTests}', so without the eviction whichever runs second
 * would reuse it. Each test that moves a fixture membership puts it back; the Monitoring Group is
 * deleted by one test only, and no other test here depends on it.
 */
@SpringBootTest
@Import(ContainerTestConfiguration.class)
@ActiveProfiles("dev-mapping")
@DirtiesContext(classMode = ClassMode.AFTER_CLASS)
@TestPropertySource(properties = {
    "app.dev-fixtures.enabled=true",
    "app.dev-fixtures.password=" + RolePropagationIntegrationTests.FIXTURE_PASSWORD})
class RolePropagationIntegrationTests {

    static final String FIXTURE_PASSWORD = DevFixtures.PASSWORD;

    private static final UUID SUPERUSERS = TestRoleMappings.SUPERUSER_GROUP_ID;

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
    private FindByIndexNameSessionRepository<? extends Session> sessions;

    @Autowired
    private ScimUserRepository users;

    @Autowired
    private ConnectorAdministrationService connectors;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private RoleMapping roleMapping;

    @Autowired
    @Qualifier("revokeSessionsIssuedUnderAnotherMapping")
    private ApplicationRunner revokeAtStartup;

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
        connectorId = connectors.create("Role propagation", "test-admin").id();
        token = connectors
                .issueToken(connectorId, TokenPermissions.ALL, null, "test-admin", TokenPermissions.ALL)
                .presentedValue();
    }

    // ---- the roles endpoint -------------------------------------------------------------------

    /**
     * A holder of {@code group:read} reads every Role, its Permissions and the Group conferring
     * it by id and current name, with the Superuser Group marked.
     */
    @Test
    void a_group_reader_lists_every_role_with_its_permissions_and_group() throws Exception {
        MvcResult listed = mvc.perform(get("/api/admin/roles")
                        .cookie(logIn("account-admin"))).andReturn();

        assertThat(listed.getResponse().getStatus()).isEqualTo(200);
        JsonNode roles = json.readTree(listed.getResponse().getContentAsString());
        assertThat(roles.valueStream().map(role -> role.get("name").asString()).toList())
                .containsExactly("Account admin", "Auditor", "Connector admin", "Monitoring",
                        "Superuser");

        JsonNode accountAdmin = role(roles, "Account admin");
        assertThat(strings(accountAdmin.get("permissions")))
                .containsExactly("group:read", "user:read", "user:write");
        assertThat(accountAdmin.get("groups")).singleElement().satisfies(group -> {
            assertThat(group.get("id").asString()).isEqualTo(ACCOUNT_ADMINS.toString());
            assertThat(group.get("displayName").asString()).isEqualTo("Account admins");
            assertThat(group.get("superuser").asBoolean()).isFalse();
        });

        JsonNode superuser = role(roles, "Superuser");
        assertThat(strings(superuser.get("permissions"))).hasSize(11);
        assertThat(superuser.get("groups")).singleElement().satisfies(group -> {
            assertThat(group.get("id").asString()).isEqualTo(SUPERUSERS.toString());
            assertThat(group.get("displayName").asString()).isNotBlank();
            assertThat(group.get("superuser").asBoolean()).isTrue();
        });
        assertThat(roles.valueStream()
                        .flatMap(role -> role.get("groups").valueStream())
                        .filter(group -> group.get("superuser").asBoolean()))
                .as("exactly one Superuser Group is marked")
                .hasSize(1);
    }

    /** Without {@code group:read} the read is refused; and nothing writes a Role. */
    @Test
    void the_roles_endpoint_requires_group_read_and_offers_no_write() throws Exception {
        Cookie auditor = logIn("auditor");

        assertThat(mvc.perform(get("/api/admin/roles").cookie(auditor))
                        .andReturn().getResponse().getStatus())
                .isEqualTo(403);
        Cookie admin = logIn("test-admin", "test-admin-password");
        for (MockHttpServletRequestBuilder write : List.of(
                post("/api/admin/roles"), put("/api/admin/roles"), delete("/api/admin/roles"))) {
            assertThat(mvc.perform(SessionCsrf.withCsrf(mvc, write.cookie(admin))
                                    .contentType(MediaType.APPLICATION_JSON).content("{}"))
                            .andReturn().getResponse().getStatus())
                    .as("a Superuser holding every Permission cannot write a Role either")
                    .isEqualTo(403);
        }
    }

    // ---- the Superuser Group over SCIM ------------------------------------------------------

    /**
     * The Superuser Group — the mapping's, resolved here by the mapping rather than by the
     * reservation marker — cannot be renamed or deleted, and the Bootstrap Admin's membership in
     * it cannot change. Every other member can be removed: there is no "last enabled
     * administrator" guard, because the frozen Bootstrap Admin is what keeps a holder of every
     * Permission.
     */
    @Test
    void the_superuser_group_keeps_its_name_its_existence_and_the_bootstrap_admin()
            throws Exception {
        UUID superusers = roleMapping.superuserGroupId();
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
     * Every mapped Group but the Superuser Group is writable and deletable — and deleting one takes
     * its Role from every member, ending their sessions.
     */
    @Test
    void another_mapped_group_can_be_renamed_and_deleted_which_revokes_its_members()
            throws Exception {
        Cookie monitoring = logIn("monitoring");
        UUID member = userId("monitoring");

        assertThat(scimPatch(MONITORING, """
                {"op":"replace","path":"displayName","value":"Observability"}""")).isEqualTo(200);
        assertThat(status(get("/api/auth/me").cookie(monitoring)))
                .as("a rename moves no power").isEqualTo(200);

        assertThat(scim(delete(GROUPS + MONITORING))).isEqualTo(204);

        assertThat(status(get("/api/auth/me").cookie(monitoring))).isEqualTo(401);
        assertRoleChange("ROLE_REVOKE", member, MONITORING, "Monitoring");
        assertSessionsRevokedForGroups(member);
    }

    // ---- propagation ------------------------------------------------------------------------

    /** Removing a User from a mapped Group ends its sessions once the write commits. */
    @Test
    void removal_from_a_mapped_group_by_patch_or_put_revokes_the_members_sessions()
            throws Exception {
        UUID accountAdmin = userId("account-admin");
        try {
            Cookie session = logIn("account-admin");
            assertThat(scimPatch(ACCOUNT_ADMINS, """
                    {"op":"remove","path":"members[value eq \\"%s\\"]"}"""
                    .formatted(accountAdmin))).isEqualTo(200);
            assertThat(status(get("/api/auth/me").cookie(session))).isEqualTo(401);
            assertRoleChange("ROLE_REVOKE", accountAdmin, ACCOUNT_ADMINS, "Account admin");
            assertSessionsRevokedForGroups(accountAdmin);

            // Signed in again, the User no longer holds the Role.
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
     * Adding a User to a mapped Group leaves its live session alone, holding what it held; the
     * Role applies from its next sign-in.
     */
    @Test
    void addition_to_a_mapped_group_applies_at_the_next_sign_in() throws Exception {
        UUID auditor = userId("auditor");
        try {
            Cookie live = logIn("auditor");

            assertThat(scimPatch(ACCOUNT_ADMINS, """
                    {"op":"add","path":"members","value":[{"value":"%s"}]}"""
                    .formatted(auditor))).isEqualTo(200);

            assertThat(status(get("/api/auth/me").cookie(live))).isEqualTo(200);
            assertThat(permissions(live)).doesNotContain("user:read");
            assertThat(status(get("/api/admin/accounts").cookie(live))).isEqualTo(403);
            assertRoleChange("ROLE_GRANT", auditor, ACCOUNT_ADMINS, "Account admin");

            Cookie next = logIn("auditor");
            assertThat(permissions(next)).contains("audit:read", "user:read", "user:write");
            assertThat(status(get("/api/admin/accounts").cookie(next))).isEqualTo(200);
        } finally {
            scimPatch(ACCOUNT_ADMINS, """
                    {"op":"remove","path":"members[value eq \\"%s\\"]"}""".formatted(auditor));
        }
    }

    /**
     * At startup, an authenticated session issued under a different role mapping — or under none
     * recorded — is ended, and one issued under the running mapping is kept.
     */
    @Test
    void a_session_issued_under_another_mapping_hash_is_revoked_at_startup() throws Exception {
        Cookie current = logIn("connector-admin");
        UUID connectorAdmin = userId("connector-admin");
        Cookie stale = logIn("test-user", "test-password");
        UUID testUser = userId("test-user");
        rewriteHash(testUser, "0".repeat(64));

        revokeAtStartup.run(new DefaultApplicationArguments());

        assertThat(sessions.findByPrincipalName(testUser.toString())).isEmpty();
        assertThat(status(get("/api/auth/me").cookie(stale))).isEqualTo(401);
        assertThat(sessions.findByPrincipalName(connectorAdmin.toString())).hasSize(1);
        assertThat(status(get("/api/auth/me").cookie(current))).isEqualTo(200);

        Cookie unrecorded = logIn("test-user", "test-password");
        rewriteHash(testUser, null);
        revokeAtStartup.run(new DefaultApplicationArguments());
        assertThat(status(get("/api/auth/me").cookie(unrecorded))).isEqualTo(401);
    }

    // ---- helpers ----------------------------------------------------------------------------

    /** Sets (or, for {@code null}, removes) the mapping hash on every session the User holds. */
    @SuppressWarnings("unchecked")
    private void rewriteHash(UUID userId, String hash) {
        FindByIndexNameSessionRepository<Session> store =
                (FindByIndexNameSessionRepository<Session>) sessions;
        Map<String, Session> held = store.findByPrincipalName(userId.toString());
        assertThat(held).isNotEmpty();
        for (Session session : held.values()) {
            if (hash == null) {
                session.removeAttribute(RoleMappingSessions.HASH_ATTRIBUTE);
            } else {
                session.setAttribute(RoleMappingSessions.HASH_ATTRIBUTE, hash);
            }
            store.save(session);
        }
    }

    /** The change of power is in the trail: the connector, the User, the Group and the Role. */
    private void assertRoleChange(String operation, UUID userId, UUID groupId, String role) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT actor_id, resource_type, resource_id, role_name, outcome
                  FROM audit_events
                 WHERE operation = ? AND subject_id = ? AND actor_id = ?""",
                operation, userId, connectorId);
        assertThat(rows).singleElement().satisfies(row -> {
            assertThat(row.get("resource_type")).isEqualTo("Group");
            assertThat(row.get("resource_id")).isEqualTo(groupId);
            assertThat(row.get("role_name")).isEqualTo(role);
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

    private static JsonNode role(JsonNode roles, String name) {
        return roles.valueStream()
                .filter(role -> name.equals(role.get("name").asString()))
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

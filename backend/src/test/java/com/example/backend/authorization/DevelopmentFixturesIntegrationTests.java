package com.example.backend.authorization;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.example.backend.ContainerTestConfiguration;
import com.example.backend.DevFixtures;
import com.example.backend.ScimTestRows;
import com.example.backend.SessionCsrf;
import com.example.backend.observability.RequestIdFilter;
import com.example.backend.scim.ScimIdentities;
import com.example.backend.scim.domain.NormalizedUserName;
import com.example.backend.scim.domain.ScimGroup;
import com.example.backend.scim.domain.ScimGroupMember;
import com.example.backend.scim.domain.ScimGroupRepository;
import com.example.backend.scim.domain.ScimUser;
import com.example.backend.scim.domain.ScimUserProfile;
import com.example.backend.scim.domain.ScimUserRepository;
import jakarta.servlet.Filter;
import jakarta.servlet.http.Cookie;
import java.time.Instant;
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
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.session.FindByIndexNameSessionRepository;
import org.springframework.session.Session;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
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
 * A User's Permissions, resolved at sign-in from the Groups' stored Permissions (ADR 0015), as the
 * caller sees them on {@code /api/auth/me} and as the session stores them.
 *
 * <p>Everything is real: the application started with {@code authorization.yaml}'s development
 * fixtures enabled under the {@code local} profile, so the Groups, their Permissions and the Users
 * signed in as here are the ones startup seeded into SQL Server; logins go through the real filter
 * chain, and the session is read back out of the indexed Redis store.
 */
@SpringBootTest
@Import(ContainerTestConfiguration.class)
@ActiveProfiles({"dev-fixtures", "local"})
@TestPropertySource(properties = {
    "app.dev-fixtures.enabled=true",
    "app.dev-fixtures.password=" + DevelopmentFixturesIntegrationTests.FIXTURE_PASSWORD})
class DevelopmentFixturesIntegrationTests {

    static final String FIXTURE_PASSWORD = DevFixtures.PASSWORD;

    private static final String MEMBER_PASSWORD = "group-permission-member-password";

    private static final UUID ACCOUNT_ADMINS =
            UUID.fromString("00000000-0000-4000-8000-00000000a002");

    /** Every Permission a session may hold, sorted: no session holds the token-only writes. */
    private static final List<String> EVERY_PERMISSION_SORTED = List.of(
            "account:unlock", "audit:read", "connector:read", "group:read", "ops:read",
            "patient:read", "user:read");

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
    private ScimGroupRepository groups;

    @Autowired
    private PlatformTransactionManager transactions;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Value("${server.servlet.session.cookie.name:SESSION}")
    private String sessionCookieName;

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context)
                .addFilters(requestIdFilter, springSessionRepositoryFilter, springSecurityFilterChain)
                .build();
    }

    /**
     * One fixture User per development Group, each holding exactly what its Group carries plus the
     * baseline Permissions every User holds, sorted by name.
     */
    @Test
    void eachDevelopmentGroupsUserSignsInHoldingThatGroupsPermissions() throws Exception {
        assertThat(permissionsOnMe(logIn("account-admin", FIXTURE_PASSWORD)))
                .containsExactly("account:unlock", "group:read", "patient:read", "user:read");
        assertThat(permissionsOnMe(logIn("auditor", FIXTURE_PASSWORD)))
                .containsExactly("audit:read", "patient:read");
        assertThat(permissionsOnMe(logIn("connector-admin", FIXTURE_PASSWORD)))
                .containsExactly("connector:read", "patient:read");
        assertThat(permissionsOnMe(logIn("monitoring", FIXTURE_PASSWORD)))
                .containsExactly("ops:read", "patient:read");
        assertThat(permissionsOnMe(logIn("helpdesk", FIXTURE_PASSWORD)))
                .containsExactly("account:unlock", "patient:read", "user:read");
    }

    /**
     * The Bootstrap Admin is a direct member of the Admin group, which carries no Permission: it
     * holds everything because it is the Superuser, expanded in code.
     */
    @Test
    void theBootstrapAdminHoldsEveryPermissionAsTheSuperuser() throws Exception {
        assertThat(permissionsOnMe(logIn("test-admin", "test-admin-password")))
                .containsExactlyElementsOf(EVERY_PERMISSION_SORTED);
    }

    /** Only a direct member of the Admin group is the Superuser, whatever its Groups carry. */
    @Test
    void onlyTheBootstrapAdminIsTheSuperuser() throws Exception {
        assertThat(me(logIn("test-admin", "test-admin-password")).get("superuser").asBoolean())
                .isTrue();
        for (String fixture : List.of(
                "account-admin", "auditor", "connector-admin", "monitoring", "helpdesk")) {
            assertThat(me(logIn(fixture, FIXTURE_PASSWORD)).get("superuser").asBoolean())
                    .as("%s is not the Superuser", fixture).isFalse();
        }
    }

    /** Belonging to an extra Group never takes a power away: the result is the union. */
    @Test
    void aUserInSeveralGroupsHoldsTheUnionOfWhatTheyCarry() throws Exception {
        UUID auditor = userId("auditor");
        addMember(ACCOUNT_ADMINS, auditor);
        try {
            assertThat(permissionsOnMe(logIn("auditor", FIXTURE_PASSWORD)))
                    .containsExactly("account:unlock", "audit:read", "group:read",
                            "patient:read", "user:read");
        } finally {
            removeMember(ACCOUNT_ADMINS, auditor);
        }
    }

    /**
     * A User in no Group holds only the baseline Permissions, and its baseline access.
     */
    @Test
    void aUserInNoGroupHoldsOnlyTheBaselineAndKeepsBaselineAccess() throws Exception {
        Cookie session = logIn("test-user", "test-password");

        JsonNode me = me(session);
        assertThat(me.get("permissions").isArray()).isTrue();
        assertThat(permissionsOnMe(session))
                .containsExactly("patient:read");
        assertThat(me.has("role")).as("there is no role field").isFalse();
        assertThat(mvc.perform(get("/api/self").cookie(session))
                        .andReturn().getResponse().getStatus())
                .isEqualTo(200);
    }

    /** The stored session carries the resolved Permissions, and no role mapping hash. */
    @Test
    void theSessionRecordsTheResolvedPermissions() throws Exception {
        logIn("account-admin", FIXTURE_PASSWORD);

        Map<String, ? extends Session> stored =
                sessions.findByPrincipalName(userId("account-admin").toString());
        assertThat(stored).hasSize(1);
        Session session = stored.values().iterator().next();

        assertThat((Object) session.getAttribute("app.authorization.roleMappingHash")).isNull();
        SecurityContext security = session.getAttribute("SPRING_SECURITY_CONTEXT");
        assertThat(security.getAuthentication().getAuthorities())
                .extracting(GrantedAuthority::getAuthority)
                .contains("account:unlock", "group:read", "user:read")
                .doesNotContain("audit:read", "ops:read");
    }

    /**
     * The helpdesk power through a Group: the Helpdesk fixture's Group confers {@code
     * account:unlock}, and with it its member Unlocks another User and forces its password change.
     */
    @Test
    void aMemberOfAGroupCarryingAccountUnlockUnlocksAndForcesAChangeOnAnotherUser()
            throws Exception {
        UUID other = anotherUser("helpdesk-target");
        Cookie helpdesk = logIn("helpdesk", FIXTURE_PASSWORD);

        assertThat(List.of(
                        status(post("/api/admin/accounts/{id}/unlock", other), helpdesk),
                        status(post("/api/admin/accounts/{id}/force-password-change", other),
                                helpdesk)))
                .containsExactly(200, 200);
    }

    /** A User whose Groups carry no {@code account:unlock} is refused both, with the bare 403. */
    @Test
    void aUserWithoutAccountUnlockIsRefusedUnlockAndTheForcedChange() throws Exception {
        UUID other = anotherUser("helpdesk-refused-target");
        Cookie auditor = logIn("auditor", FIXTURE_PASSWORD);

        assertThat(List.of(
                        status(post("/api/admin/accounts/{id}/unlock", other), auditor),
                        status(post("/api/admin/accounts/{id}/force-password-change", other),
                                auditor)))
                .containsExactly(403, 403);
    }

    /**
     * A Group made in the directory confers what is stored for it, with no configuration change:
     * a row inserted for a new Group reaches its member at the next sign-in, and a Group carrying
     * no row confers nothing.
     */
    @Test
    void aNewGroupConfersWhatIsStoredForItAtTheNextSignIn() throws Exception {
        String userName = "group-permission-member-" + UUID.randomUUID().toString().substring(0, 8);
        UUID member = new TransactionTemplate(transactions).execute(status -> users.create(
                ScimUser.created(UUID.randomUUID(),
                        new ScimUserProfile(userName, null, userName, null, null, null, true,
                                List.of()),
                        passwordEncoder.encode(MEMBER_PASSWORD), Instant.now())).id());
        UUID group = new TransactionTemplate(transactions).execute(status -> groups.create(
                        ScimGroup.created(UUID.randomUUID(),
                                "Stored " + UUID.randomUUID().toString().substring(0, 8),
                                List.of(ScimGroupMember.reference(member)), Instant.now()))
                .id());
        try {
            assertThat(permissionsOnMe(logIn(userName, MEMBER_PASSWORD)))
                    .as("a Group carrying no row confers nothing")
                    .containsExactly("patient:read");

            jdbc.update("INSERT INTO group_permission (group_id, permission) VALUES (?, ?)",
                    group, "ops:read");

            assertThat(permissionsOnMe(logIn(userName, MEMBER_PASSWORD)))
                    .containsExactly("ops:read", "patient:read");
        } finally {
            ScimTestRows.delete(jdbc, group);
            ScimTestRows.delete(jdbc, member);
        }
    }

    /** The {@code local} profile's rows, as startup stored them: the Admin group carries none. */
    @Test
    void theLocalProfileStoredEachDevelopmentGroupsPermissions() {
        assertThat(jdbc.queryForList("""
                        SELECT permission FROM group_permission
                         WHERE group_id = '00000000-0000-4000-8000-00000000a002'
                         ORDER BY permission""", String.class))
                .containsExactly("account:unlock", "group:read", "user:read");
        assertThat(jdbc.queryForObject("""
                        SELECT COUNT(*) FROM group_permission
                         WHERE group_id = '00000000-0000-4000-8000-00000000a001'""",
                        Integer.class))
                .isZero();
    }

    private int status(MockHttpServletRequestBuilder action, Cookie session) throws Exception {
        return mvc.perform(SessionCsrf.withCsrf(mvc, action.cookie(session)))
                .andReturn().getResponse().getStatus();
    }

    /** A User of this test's own, with a password and in no Group, so no other test depends on it. */
    private UUID anotherUser(String prefix) {
        String userName = prefix + "-" + UUID.randomUUID().toString().substring(0, 8);
        return new TransactionTemplate(transactions).execute(
                status -> users.create(ScimIdentities.user(userName)).id());
    }

    private List<String> permissionsOnMe(Cookie session) throws Exception {
        List<String> permissions = new ArrayList<>();
        me(session).get("permissions").forEach(name -> permissions.add(name.asString()));
        return permissions;
    }

    private JsonNode me(Cookie session) throws Exception {
        MvcResult me = mvc.perform(get("/api/auth/me").cookie(session)).andReturn();
        assertThat(me.getResponse().getStatus()).isEqualTo(200);
        return json.readTree(me.getResponse().getContentAsString());
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

    private void addMember(UUID groupId, UUID userId) {
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            ScimGroup group = groups.findById(groupId).orElseThrow();
            List<ScimGroupMember> members = new ArrayList<>(group.members());
            members.add(ScimGroupMember.reference(userId));
            groups.replace(group.replacedWith(group.displayName(), members, Instant.now()));
        });
    }

    private void removeMember(UUID groupId, UUID userId) {
        new TransactionTemplate(transactions).executeWithoutResult(
                status -> groups.removeMember(groupId, userId, Instant.now()));
    }
}

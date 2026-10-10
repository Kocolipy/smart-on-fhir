package com.example.backend.authorization;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.example.backend.ContainerTestConfiguration;
import com.example.backend.DevFixtures;
import com.example.backend.SessionCsrf;
import com.example.backend.auth.controller.AuthController;
import com.example.backend.authorization.domain.RoleMapping;
import com.example.backend.observability.RequestIdFilter;
import com.example.backend.scim.domain.NormalizedUserName;
import com.example.backend.scim.domain.ScimGroup;
import com.example.backend.scim.domain.ScimGroupMember;
import com.example.backend.scim.domain.ScimGroupRepository;
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
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.session.FindByIndexNameSessionRepository;
import org.springframework.session.Session;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * A User's Permissions, resolved at sign-in from the shipped development role mapping, as the
 * caller sees them on {@code /api/auth/me} and as the session stores them.
 *
 * <p>Everything is real: the application started with {@code authorization.yaml}'s mapping and
 * its development fixtures enabled, so the Groups and Users signed in as here are the ones startup
 * seeded into Postgres; logins go through the real filter chain, and the session is read back out
 * of the indexed Redis store. Starting at all is itself the evidence that every mapped Group id
 * resolved, since startup refuses a mapping naming a Group that does not exist.
 */
@SpringBootTest
@Import(ContainerTestConfiguration.class)
@ActiveProfiles("dev-mapping")
@TestPropertySource(properties = {
    "app.dev-fixtures.enabled=true",
    "app.dev-fixtures.password=" + DevelopmentRoleMappingIntegrationTests.FIXTURE_PASSWORD})
class DevelopmentRoleMappingIntegrationTests {

    static final String FIXTURE_PASSWORD = DevFixtures.PASSWORD;

    private static final UUID ACCOUNT_ADMINS =
            UUID.fromString("00000000-0000-4000-8000-00000000a002");

    private static final List<String> EVERY_PERMISSION_SORTED = List.of(
            "audit:read", "connector:read", "connector:token", "connector:write",
            "counter:read", "counter:write", "group:read", "group:write", "ops:read",
            "user:read", "user:write");

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
    private RoleMapping roleMapping;

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
     * One fixture User per Role, each holding exactly its Role's Permissions plus the baseline
     * counter Permissions every User holds, sorted by name.
     */
    @Test
    void eachDevelopmentRolesUserSignsInHoldingThatRolesPermissions() throws Exception {
        assertThat(permissionsOnMe(logIn("account-admin", FIXTURE_PASSWORD)))
                .containsExactly("counter:read", "counter:write", "group:read", "user:read",
                        "user:write");
        assertThat(permissionsOnMe(logIn("auditor", FIXTURE_PASSWORD)))
                .containsExactly("audit:read", "counter:read", "counter:write");
        assertThat(permissionsOnMe(logIn("connector-admin", FIXTURE_PASSWORD)))
                .containsExactly("connector:read", "connector:token", "connector:write",
                        "counter:read", "counter:write", "group:read", "group:write",
                        "user:read", "user:write");
        assertThat(permissionsOnMe(logIn("monitoring", FIXTURE_PASSWORD)))
                .containsExactly("counter:read", "counter:write", "ops:read");
    }

    /** The Superuser Group is the seeded Admin group, so the Bootstrap Admin holds everything. */
    @Test
    void theBootstrapAdminHoldsEveryPermissionThroughTheSuperuserGroup() throws Exception {
        assertThat(permissionsOnMe(logIn("test-admin", "test-admin-password")))
                .containsExactlyElementsOf(EVERY_PERMISSION_SORTED);
    }

    /** Holding an extra Role never takes a power away: the result is the union. */
    @Test
    void aUserInSeveralMappedGroupsHoldsTheUnionOfTheirRoles() throws Exception {
        UUID auditor = userId("auditor");
        addMember(ACCOUNT_ADMINS, auditor);
        try {
            assertThat(permissionsOnMe(logIn("auditor", FIXTURE_PASSWORD)))
                    .containsExactly("audit:read", "counter:read", "counter:write", "group:read",
                            "user:read", "user:write");
        } finally {
            removeMember(ACCOUNT_ADMINS, auditor);
        }
    }

    /**
     * A User in no mapped Group holds no Role's Permission: only the baseline counter Permissions,
     * and its baseline access.
     */
    @Test
    void aUserInNoMappedGroupHoldsNoneAndKeepsBaselineAccess() throws Exception {
        Cookie session = logIn("test-user", "test-password");

        JsonNode me = me(session);
        assertThat(me.get("permissions").isArray()).isTrue();
        assertThat(permissionsOnMe(session)).containsExactly("counter:read", "counter:write");
        assertThat(me.has("role")).as("there is no role field").isFalse();
        assertThat(mvc.perform(get("/api/self").cookie(session))
                        .andReturn().getResponse().getStatus())
                .isEqualTo(200);
    }

    /** The stored session carries the resolved Permissions and the running mapping's hash. */
    @Test
    void theSessionRecordsTheResolvedPermissionsAndTheMappingHash() throws Exception {
        logIn("account-admin", FIXTURE_PASSWORD);

        Map<String, ? extends Session> stored =
                sessions.findByPrincipalName(userId("account-admin").toString());
        assertThat(stored).hasSize(1);
        Session session = stored.values().iterator().next();

        assertThat((String) session.getAttribute(AuthController.ROLE_MAPPING_HASH_ATTRIBUTE))
                .isEqualTo(roleMapping.hash())
                .matches("[0-9a-f]{64}");
        SecurityContext security = session.getAttribute("SPRING_SECURITY_CONTEXT");
        assertThat(security.getAuthentication().getAuthorities())
                .extracting(GrantedAuthority::getAuthority)
                .contains("group:read", "user:read", "user:write")
                .doesNotContain("audit:read", "ops:read");
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

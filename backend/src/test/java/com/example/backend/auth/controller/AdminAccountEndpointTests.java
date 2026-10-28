package com.example.backend.auth.controller;

import static com.example.backend.SessionCsrf.withCsrf;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.backend.ContainerTestConfiguration;
import com.example.backend.InMemorySessionRegistryConfiguration;
import com.example.backend.authorization.TestGroupPermissions;
import com.example.backend.auth.infrastructure.session.AccountSessionsAdapter;
import com.example.backend.scim.domain.NormalizedUserName;
import com.example.backend.scim.domain.ReservedResourceName;
import com.example.backend.scim.domain.ScimGroupRepository;
import com.example.backend.scim.domain.ScimUser;
import com.example.backend.scim.domain.ScimUserRepository;
import jakarta.servlet.Filter;
import java.util.UUID;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.session.FindByIndexNameSessionRepository;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The endpoint as a caller meets it: the real filter chain, the real controller,
 * the application's own JSON converters, over the SCIM identities startup seeding
 * created.
 *
 * <p>{@code AdminAccountControllerTests} covers what the controller returns and
 * {@code AuthorizationContractTests} covers who may reach each operation. What
 * neither can see is the two composed — a response that is authorized but
 * carrying the wrong JSON.
 *
 * <p>The whole web application context is wired in rather than a standalone
 * controller precisely so the wire format is the deployed one: a hand-built
 * MockMvc would render {@code createdAt} as a number, and these assertions would
 * then describe a format the running service does not produce.
 *
 * <p>The seeded {@code test-admin} is an administrator because it is the reserved
 * Bootstrap Admin and a member of the reserved Admin group, not because a role
 * column says so — which is what makes the {@code admin} column below an assertion
 * about the derivation rather than about a stored value.
 *
 * <p>No test here flags or locks a seeded identity: they are shared with every other test
 * in this context, and a change-required flag cannot be cleared without changing the
 * password. The Unlock of a locked User is driven end to end in
 * {@code PasswordChangeLifecycleIntegrationTests}, on Users that test creates for itself.
 */
@SpringBootTest
@Import({ContainerTestConfiguration.class, InMemorySessionRegistryConfiguration.class})
class AdminAccountEndpointTests {

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private ScimUserRepository users;

    @Autowired
    private ScimGroupRepository groups;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    @Qualifier("springSecurityFilterChain")
    private Filter springSecurityFilterChain;

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context)
                .addFilters(springSecurityFilterChain)
                .build();
    }

    /**
     * The configuration Session revocation depends on, asserted rather
     * than assumed: a plain session repository cannot be searched by principal, so
     * {@code AccountSessionsAdapter} has nothing to inject and the deployed service
     * does not start. The fake registry is {@code @Primary}, so it would hide
     * the adapter's absence from every other test here.
     */
    @Test
    void theContextWiresTheIndexedSessionRepositoryTheDeployedServiceNeeds() {
        assertThat(context.getBean(FindByIndexNameSessionRepository.class)).isNotNull();
        assertThat(context.getBean(AccountSessionsAdapter.class)).isNotNull();
    }

    @Test
    void anAdministratorSeesEveryAccountWithItsRoleStatusAndCreationDate() throws Exception {
        mvc.perform(get("/api/admin/accounts").session(authenticatedSession(TestGroupPermissions.SUPERUSER_AUTHORITIES)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.users[*].userName")
                        .value(Matchers.hasItems("test-admin", "test-user")))
                .andExpect(jsonPath("$.users[?(@.userName == 'test-user')].admin")
                        .value(Matchers.contains(false)))
                .andExpect(jsonPath("$.users[?(@.userName == 'test-admin')].admin")
                        .value(Matchers.contains(true)))
                .andExpect(jsonPath("$.users[?(@.userName == 'test-user')].active")
                        .value(Matchers.contains(true)))
                // ISO-8601 rather than an epoch number, which is what the SPA and
                // the OpenAPI document both describe.
                .andExpect(jsonPath("$.users[?(@.userName == 'test-user')].createdAt")
                        .value(Matchers.contains(Matchers.matchesPattern(
                                "\\d{4}-\\d{2}-\\d{2}T.*Z"))));
    }

    /**
     * The derived columns, over the wire: the Bootstrap Admin is flagged so the page can show it
     * with no lockout state, and its direct Groups name the seeded Admin group — the same
     * membership the {@code admin} column is derived from.
     */
    @Test
    void theListingReportsTheBootstrapAdminAndEachUsersDirectGroups() throws Exception {
        String adminGroup = adminGroupName();

        mvc.perform(get("/api/admin/accounts").session(authenticatedSession(TestGroupPermissions.SUPERUSER_AUTHORITIES)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.users[?(@.userName == 'test-admin')].bootstrapAdmin")
                        .value(Matchers.contains(true)))
                .andExpect(jsonPath("$.users[?(@.userName == 'test-user')].bootstrapAdmin")
                        .value(Matchers.contains(false)))
                .andExpect(jsonPath("$.users[?(@.userName == 'test-admin')].groups[*].displayName")
                        .value(Matchers.hasItem(adminGroup)))
                .andExpect(jsonPath("$.users[?(@.userName == 'test-user')].groups[*].displayName")
                        .value(Matchers.not(Matchers.hasItem(adminGroup))));
    }

    /**
     * The acceptance criterion that matters most: no hash on the wire, ever. Asserted as the exact
     * field set of every listed identity, so any added field — a hash under whatever name — fails
     * here; {@code hasPassword} and {@code passwordChangeRequired} are booleans about the
     * credential, not the credential. The Argon2id check — the only hash format this service
     * stores — catches a hash smuggled into an existing field.
     */
    @Test
    void theListingNeverCarriesAPasswordHash() throws Exception {
        String body = mvc.perform(get("/api/admin/accounts")
                        .session(authenticatedSession(TestGroupPermissions.SUPERUSER_AUTHORITIES)))
                .andExpect(status().isOk())
                .andExpect(content().string(Matchers.not(Matchers.containsString("argon2id"))))
                .andReturn().getResponse().getContentAsString();

        JsonNode listing = JsonMapper.builder().build().readTree(body).get("users");
        assertThat(listing.size()).isPositive();
        listing.valueStream().forEach(identity -> assertThat(identity.propertyNames())
                .containsExactlyInAnyOrder("id", "userName", "displayName", "admin",
                        "bootstrapAdmin", "active", "locked", "lockCause", "hasPassword",
                        "passwordChangeRequired", "lastAuthenticatedAt", "createdAt", "groups"));
    }

    @Test
    void anAdministratorSeesEveryGroupWithItsMemberCountTheAdminMarkerAndItsPermissions()
            throws Exception {
        String adminGroup = adminGroupName();

        String body = mvc.perform(get("/api/admin/groups")
                        .session(authenticatedSession(TestGroupPermissions.SUPERUSER_AUTHORITIES)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.groups[?(@.displayName == '" + adminGroup + "')].adminGroup")
                        .value(Matchers.contains(true)))
                .andExpect(jsonPath("$.groups[?(@.displayName == '" + adminGroup + "')].memberCount")
                        .value(Matchers.contains(Matchers.greaterThanOrEqualTo(1))))
                .andExpect(jsonPath("$.groups[?(@.displayName == '" + adminGroup + "')].permissions")
                        .value(Matchers.contains(Matchers.empty())))
                .andReturn().getResponse().getContentAsString();

        JsonNode listing = JsonMapper.builder().build().readTree(body).get("groups");
        assertThat(listing.size()).isPositive();
        listing.valueStream().forEach(group -> assertThat(group.propertyNames())
                .containsExactlyInAnyOrder(
                        "id", "displayName", "memberCount", "adminGroup", "permissions"));
    }

    /**
     * An administrator's session that names no acting User by stable id is answered as an
     * anonymous request is: without that id the self-target rule cannot be decided.
     */
    @Test
    void anAdministratorSessionNamingNobodyIsUnauthorized() throws Exception {
        UUID id = require("test-user").id();
        MockHttpSession unindexed = authenticatedSession(TestGroupPermissions.SUPERUSER_AUTHORITIES);
        unindexed.removeAttribute(FindByIndexNameSessionRepository.PRINCIPAL_NAME_INDEX_NAME);

        mvc.perform(withCsrf(mvc, post("/api/admin/accounts/{id}/unlock", id)).session(unindexed))
                .andExpect(status().isUnauthorized());
    }

    /**
     * An Unlock of an id no User has is a {@code 404}, audited as a refused {@code LOCKOUT_LIFT}
     * naming the id it addressed, with {@code UNKNOWN_RESOURCE}.
     */
    @Test
    void anUnknownAccountIsNotFound() throws Exception {
        UUID nobody = UUID.randomUUID();

        mvc.perform(withCsrf(mvc, post("/api/admin/accounts/{id}/unlock", nobody))
                        .session(authenticatedSession(TestGroupPermissions.SUPERUSER_AUTHORITIES)))
                .andExpect(status().isNotFound());

        assertThat(jdbc.queryForList(
                "SELECT error_code FROM audit_events WHERE operation = 'LOCKOUT_LIFT'"
                        + " AND outcome = 'FAILURE' AND subject_id = ?",
                String.class, nobody))
                .containsExactly("UNKNOWN_RESOURCE");
    }

    /**
     * The operations are re-targeted to the stable id, so a {@code userName} in the id's place is
     * not a way of naming an account any more: it is a malformed id, and nothing is written.
     */
    @Test
    void aUserNameIsNotAnIdAndActsOnNobody() throws Exception {
        mvc.perform(withCsrf(mvc, post("/api/admin/accounts/test-user/unlock"))
                        .session(authenticatedSession(TestGroupPermissions.SUPERUSER_AUTHORITIES)))
                .andExpect(status().isBadRequest());
        assertThat(require("test-user").login().isPasswordChangeRequired()).isFalse();
    }

    /**
     * An Unlock has no effect on an account serving no lockout: it is refused {@code 409} with the
     * application API's generic error document, and the account reads back as it was.
     */
    @Test
    void unlockingAnAccountThatIsNotLockedIsRefusedWithAConflict() throws Exception {
        ScimUser before = require("test-user");

        mvc.perform(withCsrf(mvc, post("/api/admin/accounts/{id}/unlock", before.id()))
                        .session(authenticatedSession(TestGroupPermissions.SUPERUSER_AUTHORITIES)))
                .andExpect(status().isConflict())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.code").value("request-refused"));
        assertThat(require("test-user").login()).isEqualTo(before.login());
    }

    private String adminGroupName() {
        return groups.findByReservedName(ReservedResourceName.ADMIN_GROUP)
                .orElseThrow().displayName();
    }

    private ScimUser require(String userName) {
        return users.findByNormalizedUserName(NormalizedUserName.of(userName)).orElseThrow();
    }

    private MockHttpSession authenticatedSession(String... authorities) {
        return authenticatedSession("account", authorities);
    }

    private MockHttpSession authenticatedSession(String name, String[] authorities) {
        SecurityContext securityContext = SecurityContextHolder.createEmptyContext();
        securityContext.setAuthentication(new TestingAuthenticationToken(name, null, authorities));
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(
                HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY,
                securityContext);
        // The principal index a Login writes: the signed-in User's stable id, or — for a caller
        // this test did not provision — the id of somebody no row here carries.
        session.setAttribute(FindByIndexNameSessionRepository.PRINCIPAL_NAME_INDEX_NAME,
                users.findByNormalizedUserName(NormalizedUserName.of(name))
                        .map(ScimUser::id)
                        .orElseGet(UUID::randomUUID)
                        .toString());
        return session;
    }
}

package com.example.backend.authorization;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.example.backend.ContainerTestConfiguration;
import com.example.backend.ScimTestRows;
import com.example.backend.SessionCsrf;
import com.example.backend.TokenPermissions;
import com.example.backend.UuidColumnRows;
import com.example.backend.authorization.domain.InvalidGroupPermissionsException;
import com.example.backend.observability.RequestIdFilter;
import com.example.backend.scim.application.ConnectorAdministrationService;
import com.example.backend.scim.domain.ScimGroup;
import com.example.backend.scim.domain.ScimGroupMember;
import com.example.backend.scim.domain.ScimGroupRepository;
import com.example.backend.scim.domain.ScimUser;
import com.example.backend.scim.domain.ScimUserProfile;
import com.example.backend.scim.domain.ScimUserRepository;
import jakarta.servlet.Filter;
import jakarta.servlet.http.Cookie;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
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
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.WebApplicationContext;

/**
 * A Group's stored Permissions and the startup step that reads them, against SQL Server and the
 * indexed Redis session store, in the configuration most classes share: the seeded Admin group and
 * nothing else, with every Group Permission a test needs inserted by the test (ADR 0015).
 *
 * <p>The startup runner is re-run as a restart runs it. Group Permissions are rows a Group's
 * deletion takes with it, so a restart after one has nothing to refuse.
 */
@SpringBootTest
@Import(ContainerTestConfiguration.class)
class GroupPermissionStartupIntegrationTests {

    private static final String MEMBER_PASSWORD = "group-permission-startup-password";

    private static final MediaType SCIM_JSON = MediaType.valueOf("application/scim+json");

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
    @Qualifier("seedScimDirectory")
    private ApplicationRunner startup;

    @Autowired
    private ConnectorAdministrationService connectors;

    @Autowired
    private ScimUserRepository users;

    @Autowired
    private ScimGroupRepository groups;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private PlatformTransactionManager transactions;

    @Autowired
    private JdbcTemplate jdbc;

    @Value("${server.servlet.session.cookie.name:SESSION}")
    private String sessionCookieName;

    private MockMvc mvc;

    private UUID connectorId;

    private String token;

    private UUID member;

    private String memberName;

    private UUID group;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context)
                .addFilters(requestIdFilter, springSessionRepositoryFilter, springSecurityFilterChain)
                .build();
        connectorId = connectors.create("Group Permission startup " + unique(), null).id();
        token = connectors.issueToken(connectorId, TokenPermissions.ALL, null, null)
                .presentedValue();
        memberName = "group-permission-startup-" + unique();
        member = new TransactionTemplate(transactions).execute(status -> users.create(
                ScimUser.created(UUID.randomUUID(),
                        new ScimUserProfile(memberName, null, memberName, null, null, null, true,
                                List.of()),
                        passwordEncoder.encode(MEMBER_PASSWORD), Instant.now())).id());
        group = new TransactionTemplate(transactions).execute(status -> groups.create(
                ScimGroup.created(UUID.randomUUID(), "Startup auditors " + unique(),
                        List.of(ScimGroupMember.reference(member)), Instant.now())).id());
    }

    @AfterEach
    void tearDown() {
        ScimTestRows.delete(jdbc, group);
        ScimTestRows.delete(jdbc, member);
    }

    /**
     * Deleting over SCIM a Group carrying Permissions takes its rows with it, ends its member's
     * session after the commit, records the member's loss — and the application restarts cleanly.
     */
    @Test
    void deletingAGroupCarryingPermissionsEndsItsMembersSessionsAndTheRestartIsClean()
            throws Exception {
        jdbc.update("INSERT INTO group_permission (group_id, permission) VALUES (?, ?)",
                group, "audit:read");
        Cookie session = logIn();
        assertThat(status(get("/api/admin/audit-events").cookie(session)))
                .as("the Group's Permission reached the session").isEqualTo(200);

        assertThat(status(delete("/scim/v2/Groups/" + group)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)))
                .isEqualTo(204);

        assertThat(jdbc.queryForObject(
                        "SELECT COUNT(*) FROM group_permission WHERE group_id = ?",
                        Integer.class, group))
                .as("the cascade took the Group's Permissions").isZero();
        assertThat(status(get("/api/auth/me").cookie(session))).isEqualTo(401);
        assertThat(jdbc.query("""
                        SELECT permissions_before, permissions_after, superuser_changed
                          FROM audit_events
                         WHERE operation = 'PERMISSION_REVOKE' AND subject_id = ? AND actor_id = ?""",
                        UuidColumnRows.ROWS, member, connectorId))
                .singleElement()
                .satisfies(row -> {
                    assertThat(row.get("permissions_before")).isEqualTo("audit:read");
                    assertThat(row.get("permissions_after")).isNull();
                    assertThat(row.get("superuser_changed")).isEqualTo(false);
                });
        assertThatCode(() -> startup.run(new DefaultApplicationArguments()))
                .doesNotThrowAnyException();
    }

    /** Deleting a Group that carries nothing ends no session: it moved nobody's Permissions. */
    @Test
    void deletingAGroupCarryingNothingEndsNoSession() throws Exception {
        Cookie session = logIn();

        assertThat(status(delete("/scim/v2/Groups/" + group)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)))
                .isEqualTo(204);

        assertThat(status(get("/api/auth/me").cookie(session))).isEqualTo(200);
    }

    /**
     * A stored value naming no Permission — one removed from code whose rows no migration deleted
     * — fails startup with a message naming the Group and the value.
     */
    @Test
    void aStoredUnknownPermissionFailsStartupNamingIt() {
        jdbc.update("INSERT INTO group_permission (group_id, permission) VALUES (?, ?)",
                group, "connector:write");

        assertThatThrownBy(() -> startup.run(new DefaultApplicationArguments()))
                .isInstanceOf(InvalidGroupPermissionsException.class)
                .hasMessage("Invalid Group Permissions: Group " + group
                        + " carries unknown Permission 'connector:write'."
                        + " Delete the rows, or restore the Permission in code");
    }

    /** A stored Permission no Group may carry — a token-only write half — fails startup too. */
    @Test
    void aStoredNonDelegatablePermissionFailsStartupNamingIt() {
        jdbc.update("INSERT INTO group_permission (group_id, permission) VALUES (?, ?)",
                group, "user:write");

        assertThatThrownBy(() -> startup.run(new DefaultApplicationArguments()))
                .isInstanceOf(InvalidGroupPermissionsException.class)
                .hasMessageContaining("Group " + group
                        + " carries Permission 'user:write', which no Group may carry");
    }

    private Cookie logIn() throws Exception {
        MvcResult login = mvc.perform(SessionCsrf.withCsrf(mvc, post("/api/auth/login"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"%s\",\"password\":\"%s\"}"
                                .formatted(memberName, MEMBER_PASSWORD)))
                .andReturn();
        assertThat(login.getResponse().getStatus()).as("login").isEqualTo(200);
        Cookie session = login.getResponse().getCookie(sessionCookieName);
        assertThat(session).as("the login issued a session cookie").isNotNull();
        return new Cookie(session.getName(), session.getValue());
    }

    private int status(MockHttpServletRequestBuilder request) throws Exception {
        return mvc.perform(request.contentType(SCIM_JSON)).andReturn().getResponse().getStatus();
    }

    private static String unique() {
        return UUID.randomUUID().toString().substring(0, 8);
    }
}

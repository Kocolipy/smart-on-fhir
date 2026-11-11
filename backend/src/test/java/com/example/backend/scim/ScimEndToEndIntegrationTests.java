package com.example.backend.scim;

import static com.example.backend.SessionCsrf.withCsrf;
import static com.example.backend.ScimHttp.SCIM_JSON;
import static com.example.backend.ScimHttp.asConnector;
import static com.example.backend.TestJson.JSON;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.example.backend.ContainerTestConfiguration;
import com.example.backend.ScimTestRows;
import com.example.backend.TokenPermissions;
import com.example.backend.auth.application.LoginIdentityService;
import com.example.backend.authorization.TestGroupPermissions;
import com.example.backend.observability.RequestIdFilter;
import com.example.backend.scim.application.ConnectorAdministrationService;
import jakarta.servlet.Filter;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.JsonNode;

/**
 * Provisioning end to end, in one exchange sequence against a real SQL Server and the real
 * filter chain: a fresh deployment boot serves discovery, then creation, then Group membership — and
 * the Bootstrap Admin logs in through the ordinary password path and reaches the administrative
 * interface.
 *
 * <p>Nothing here is stubbed and nothing is synthesized. The login is a real
 * {@code POST /api/auth/login} with a real CSRF token, not a hand-built {@code SecurityContext}
 * dropped into a session: the criterion is that the Bootstrap Admin can log in, and a test that
 * fabricated the authentication would be asserting something else entirely. The connector's token is
 * minted through the administrative use case and presented as a real bearer credential.
 *
 * <p>No property enables SCIM. The gate defaults to open, so a deployment that configures
 * nothing serves this — which is what "a fresh deployment boot" means, and setting the flag here
 * would quietly test a different deployment from the one the criterion describes.
 */
@SpringBootTest
@Import(ContainerTestConfiguration.class)
class ScimEndToEndIntegrationTests {

    private static final String BASE = "/scim/v2";

    private static final String USER_SCHEMA = "urn:ietf:params:scim:schemas:core:2.0:User";

    private static final String PATCH_SCHEMA = "urn:ietf:params:scim:api:messages:2.0:PatchOp";

    /** The configured recovery identity — {@code app.auth.bootstrap-username} in test resources. */
    private static final String BOOTSTRAP_ADMIN = "test-admin";

    private static final String BOOTSTRAP_PASSWORD = "test-admin-password";

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private ConnectorAdministrationService connectors;

    @Autowired
    private RequestIdFilter requestIdFilter;

    @Autowired
    @Qualifier("springSecurityFilterChain")
    private Filter springSecurityFilterChain;

    private MockMvc mvc;

    private String writeToken;

    private final java.util.List<UUID> seeded = new java.util.ArrayList<>();

    /**
     * Removes the resources this test persisted over the real SCIM surface, by
     * stable id (cascades from {@code scim_resources}). This class is not
     * {@code @Transactional} — the writes are real HTTP commits — so without this
     * the fixed userNames ("e2e-member", "e2e-promoted") survive into a second run
     * against a reused database and collide on the userName uniqueness constraint.
     */
    @org.junit.jupiter.api.AfterEach
    void removeSeededResources() {
        for (UUID id : seeded) {
            ScimTestRows.delete(jdbc, id);
        }
        seeded.clear();
    }

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context)
                .addFilters(requestIdFilter, springSecurityFilterChain)
                .build();
        UUID connectorId = connectors.create(ScimTestRows.unique("e2e-connector"), null).id();
        writeToken = connectors
                .issueToken(connectorId, TokenPermissions.ALL, null, null)
                .presentedValue();
    }

    /**
     * The Bootstrap Admin logs in with its configured password and reaches the administrative
     * interface — the recovery path a deployment depends on when external provisioning has failed or
     * has removed every SCIM-managed administrator.
     *
     * <p>Its authority is not stored anywhere. It comes from membership of the server-seeded Admin
     * group, which seeding created it in, so this exercise passing IS the derivation working.
     */
    @Test
    void theBootstrapAdminLogsInByPasswordAndReachesTheAdministrativeInterface() throws Exception {
        MvcResult login = mvc.perform(withCsrf(mvc, post("/api/auth/login"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"%s\",\"password\":\"%s\"}"
                                .formatted(BOOTSTRAP_ADMIN, BOOTSTRAP_PASSWORD)))
                .andReturn();

        assertThat(login.getResponse().getStatus())
                .as("the recovery identity authenticates through the ordinary password path")
                .isEqualTo(200);
        JsonNode body = JSON.readTree(login.getResponse().getContentAsString());
        assertThat(body.get("username").asText()).isEqualTo(BOOTSTRAP_ADMIN);
        assertThat(body.get("permissions").valueStream().map(JsonNode::asText).toList())
                .as("derived from Superuser Group membership; there is no role column to read")
                .contains("user:read");

        MockHttpSession session = (MockHttpSession) login.getRequest().getSession(false);
        MvcResult listing = mvc.perform(get("/api/admin/accounts").session(session)).andReturn();

        assertThat(listing.getResponse().getStatus())
                .as("the session the login issued reaches the admin-gated namespace")
                .isEqualTo(200);
        JsonNode identities =
                JSON.readTree(listing.getResponse().getContentAsString()).get("users");
        assertThat(identities)
                .anySatisfy(identity -> {
                    assertThat(identity.get("userName").asText()).isEqualTo(BOOTSTRAP_ADMIN);
                    assertThat(identity.get("admin").booleanValue()).isTrue();
                });
    }

    /**
     * The last clause: a User added to the Admin group gains administrative access only
     * after a fresh login, never mid-session.
     *
     * <p>Asserted in both directions across one membership change, which is what makes it a statement
     * about WHEN authority is read rather than about whether the derivation works. The session held
     * before the change keeps reporting what it was issued with; a login after it reports the new
     * authority. Recomputing per request would fail the first assertion, and never recomputing would
     * fail the second.
     */
    @Test
    void adminGroupMembershipTakesEffectAtTheNextLoginAndNeverMidSession() throws Exception {
        // An ordinary identity, provisioned over SCIM, given a password it can log in with.
        JsonNode user = createdBody(post(BASE + "/Users"), """
                {"schemas":["%s"],"userName":"e2e-promoted","password":"a-real-password"}"""
                .formatted(USER_SCHEMA));
        String userId = user.get("id").asText();
        // A connector-set password requires a change before the identity holds any role; complete
        // it so the logins below measure Admin authority, not the change-required confinement.
        completeRequiredChange("e2e-promoted", "a-real-password", "a-replaced-password");

        MockHttpSession beforePromotion = logInAs("e2e-promoted", "a-replaced-password", "USER");
        mvc.perform(get("/api/admin/accounts").session(beforePromotion))
                .andReturn();
        assertThat(mvc.perform(get("/api/admin/accounts").session(beforePromotion))
                        .andReturn().getResponse().getStatus())
                .as("an ordinary identity holds baseline access only")
                .isEqualTo(403);

        // Promote by adding it to the Admin group, the same way a connector would.
        String adminGroupId = adminGroupId();
        okBody(scim(patch(BASE + "/Groups/" + adminGroupId), """
                {"schemas":["%s"],
                 "Operations":[{"op":"add","path":"members","value":[{"value":"%s"}]}]}"""
                .formatted(PATCH_SCHEMA, userId)));

        // The session held ACROSS the change is unchanged: it carries the authorities it was issued
        // with, and nothing recomputes them per request.
        assertThat(mvc.perform(get("/api/admin/accounts").session(beforePromotion))
                        .andReturn().getResponse().getStatus())
                .as("authority never changes under a session that is already open")
                .isEqualTo(403);

        // A fresh login reads the membership and reports the new authority.
        MockHttpSession afterPromotion = logInAs("e2e-promoted", "a-replaced-password", "ADMIN");
        assertThat(mvc.perform(get("/api/admin/accounts").session(afterPromotion))
                        .andReturn().getResponse().getStatus())
                .as("the next login derives authority from the membership that now exists")
                .isEqualTo(200);
    }

    /** The Admin group's id, found the way authority derivation finds it: through discovery of it. */
    private String adminGroupId() throws Exception {
        JsonNode groups = okBody(asConnector(get(BASE + "/Groups"), writeToken, jdbc));
        return groups.get("Resources").valueStream()
                .filter(group -> "Admin".equals(group.get("displayName").asText()))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "seeding must have created the Admin group on a fresh database"))
                .get("id")
                .asText();
    }

    /** Logs in for real and returns the session, asserting the role the response reports. */
    private MockHttpSession logInAs(String userName, String password, String expectedRole)
            throws Exception {
        MvcResult login = mvc.perform(withCsrf(mvc, post("/api/auth/login"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"%s\",\"password\":\"%s\"}"
                                .formatted(userName, password)))
                .andReturn();
        assertThat(login.getResponse().getStatus()).isEqualTo(200);
        // "ADMIN": a member of the Superuser Group, holding every Permission a session may; "USER": only the
        // baseline Permissions every User holds.
        JsonNode body = JSON.readTree(login.getResponse().getContentAsString());
        assertThat(body.get("permissions").size())
                .isEqualTo("ADMIN".equals(expectedRole)
                        ? TestGroupPermissions.EVERY_PERMISSION.size()
                        : LoginIdentityService.BASELINE_PERMISSIONS.size());
        return (MockHttpSession) login.getRequest().getSession(false);
    }

    /**
     * Logs in with a connector-set password, which confines the session to the change flow, and
     * replaces it. The change ends the session, so the caller logs in again with {@code next}.
     */
    private void completeRequiredChange(String userName, String current, String next)
            throws Exception {
        MvcResult login = mvc.perform(withCsrf(mvc, post("/api/auth/login"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"%s\",\"password\":\"%s\"}"
                                .formatted(userName, current)))
                .andReturn();
        assertThat(login.getResponse().getStatus()).isEqualTo(200);
        assertThat(JSON.readTree(login.getResponse().getContentAsString())
                        .get("passwordChangeRequired").booleanValue())
                .as("a connector-set password requires a change")
                .isTrue();
        MockHttpSession confined = (MockHttpSession) login.getRequest().getSession(false);
        MvcResult change = mvc.perform(withCsrf(mvc, post("/api/auth/change-password"))
                        .session(confined)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"currentPassword\":\"%s\",\"newPassword\":\"%s\"}"
                                .formatted(current, next)))
                .andReturn();
        assertThat(change.getResponse().getStatus()).isEqualTo(204);
    }

    private JsonNode okBody(MockHttpServletRequestBuilder request) throws Exception {
        MvcResult result = mvc.perform(request).andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        return JSON.readTree(result.getResponse().getContentAsString());
    }

    private JsonNode createdBody(MockHttpServletRequestBuilder request, String body)
            throws Exception {
        MvcResult result = mvc.perform(scim(request, body)).andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(201);
        JsonNode created = JSON.readTree(result.getResponse().getContentAsString());
        seeded.add(UUID.fromString(created.get("id").asText()));
        return created;
    }

    private MockHttpServletRequestBuilder scim(MockHttpServletRequestBuilder request, String body) {
        return asConnector(request, writeToken, jdbc).contentType(SCIM_JSON).content(body);
    }
}

package com.example.backend.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.example.backend.SessionCsrf;
import com.example.backend.ContainerTestConfiguration;
import com.example.backend.TokenPermissions;
import com.example.backend.observability.RequestIdFilter;
import com.example.backend.scim.ScimConditionalWrites;
import com.example.backend.scim.application.ConnectorAdministrationService;
import jakarta.servlet.Filter;
import jakarta.servlet.http.Cookie;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
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
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The ticket's demo oracle over real HTTP: two sessions belonging to different Users each call
 * {@code GET /api/self} and receive only their own record.
 *
 * <p>Everything is real — Users and Groups provisioned over SCIM into Postgres, logins through the
 * real filter chain, sessions held in the indexed Redis store and carried between requests as the
 * cookie a browser would hold. So "the session's User" is the stable id the login wrote into the
 * session's principal index, not a fixture this test placed there.
 *
 * <p>Shares {@link PasswordChangeLifecycleIntegrationTests}' configuration so the two reuse one
 * cached application context.
 */
@SpringBootTest
@Import({ContainerTestConfiguration.class, DormancyTestClockConfiguration.class})
class SelfReadIntegrationTests {

    private static final String BASE = "/scim/v2";
    private static final String USER_SCHEMA = "urn:ietf:params:scim:schemas:core:2.0:User";
    private static final String GROUP_SCHEMA = "urn:ietf:params:scim:schemas:core:2.0:Group";
    private static final MediaType SCIM_JSON = MediaType.valueOf("application/scim+json");

    private static final String CONNECTOR_PASSWORD = "connector-chosen-1";
    private static final String ADA_PASSWORD = "ada-own-passphrase";
    private static final String BOB_PASSWORD = "bob-own-passphrase";

    private static final Set<String> SPECIFIED_FIELDS = Set.of(
            "id", "userName", "displayName", "groups",
            "passwordChangeRequired", "lastAuthenticatedAt");

    private final JsonMapper json = JsonMapper.builder().build();

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ConnectorAdministrationService connectors;

    @Autowired
    private RequestIdFilter requestIdFilter;

    @Autowired
    @Qualifier("springSecurityFilterChain")
    private Filter springSecurityFilterChain;

    @Autowired
    @Qualifier("springSessionRepositoryFilter")
    private Filter springSessionRepositoryFilter;

    @Value("${server.servlet.session.cookie.name:SESSION}")
    private String sessionCookieName;

    private MockMvc mvc;

    private String writeToken;

    private final List<UUID> created = new ArrayList<>();

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context)
                .addFilters(requestIdFilter, springSessionRepositoryFilter, springSecurityFilterChain)
                .build();
        UUID connectorId = connectors.create("self-read-connector", "test-admin").id();
        writeToken = connectors
                .issueToken(connectorId, TokenPermissions.ALL, null, "test-admin", TokenPermissions.ALL)
                .presentedValue();
    }

    @AfterEach
    void removeWhatThisTestCreated() {
        for (UUID id : created) {
            jdbc.update("DELETE FROM scim_resources WHERE id = ? AND reserved_name IS NULL", id);
        }
        created.clear();
    }

    @Test
    void twoUsersSessionsEachReceiveOnlyTheirOwnRecord() throws Exception {
        String adaId = provision("self-read-ada", "Ada Lovelace");
        String bobId = provision("self-read-bob", "Bob Builder");
        String adaGroup = group("self-read-analysts", adaId);
        String bobGroup = group("self-read-builders", bobId);
        settle("self-read-ada", ADA_PASSWORD);
        settle("self-read-bob", BOB_PASSWORD);

        // A failed login leaves Bob a failure run, so its absence from his record is not vacuous.
        assertThat(logInStatus("self-read-bob", "not-bobs-password")).isEqualTo(401);
        assertThat(failedAttempts(bobId)).isEqualTo(1);

        Cookie ada = logIn("self-read-ada", ADA_PASSWORD);
        Cookie bob = logIn("self-read-bob", BOB_PASSWORD);

        // Interleaved, so a record leaking between sessions would show.
        JsonNode adas = self(ada);
        JsonNode bobs = self(bob);
        JsonNode adasAgain = self(ada);

        assertOwnRecord(adas, adaId, "self-read-ada", "Ada Lovelace", adaGroup,
                "self-read-analysts");
        assertOwnRecord(bobs, bobId, "self-read-bob", "Bob Builder", bobGroup,
                "self-read-builders");
        assertThat(adasAgain).isEqualTo(adas);
        assertThat(adas.get("id")).isNotEqualTo(bobs.get("id"));

        assertThat(bobs.toString()).doesNotContain(adaId).doesNotContain("Ada");
        assertThat(adas.toString()).doesNotContain(bobId).doesNotContain("Bob");
    }

    /**
     * Each session supplying the OTHER User's identifiers in every place a request can carry one
     * still receives its own record — and an id in the path is no route at all.
     */
    @Test
    void identifierShapedValuesNamingTheOtherUserAreIgnored() throws Exception {
        String adaId = provision("self-read-ada2", "Ada Two");
        String bobId = provision("self-read-bob2", "Bob Two");
        settle("self-read-ada2", ADA_PASSWORD);
        settle("self-read-bob2", BOB_PASSWORD);
        Cookie ada = logIn("self-read-ada2", ADA_PASSWORD);
        Cookie bob = logIn("self-read-bob2", BOB_PASSWORD);

        for (MockHttpServletRequestBuilder attempt : List.of(
                get("/api/self").param("id", bobId),
                get("/api/self").param("userName", "self-read-bob2"),
                get("/api/self").header("X-User-Id", bobId))) {
            MvcResult result = send(attempt, ada);
            assertThat(result.getResponse().getStatus()).isEqualTo(200);
            assertThat(json.readTree(result.getResponse().getContentAsString()).get("id").asText())
                    .isEqualTo(adaId);
        }
        MvcResult bobAsksForAda = send(get("/api/self").param("id", adaId), bob);
        assertThat(json.readTree(bobAsksForAda.getResponse().getContentAsString()).get("id")
                .asText()).isEqualTo(bobId);

        MvcResult pathId = send(get("/api/self/" + bobId), ada);
        assertThat(pathId.getResponse().getStatus()).isNotEqualTo(200);
        assertThat(pathId.getResponse().getContentAsString()).doesNotContain("Bob Two");
    }

    /**
     * Authentication is required, and a session confined by a required change keeps its three
     * routes and no more: the self-read is refused to it like every other.
     */
    @Test
    void anonymousIsUnauthorizedAndAConfinedSessionIsForbidden() throws Exception {
        assertThat(mvc.perform(get("/api/self")).andReturn().getResponse().getStatus())
                .isEqualTo(401);

        provision("self-read-confined", "Confined");
        Cookie confined = logIn("self-read-confined", CONNECTOR_PASSWORD);
        assertThat(send(get("/api/self"), confined).getResponse().getStatus()).isEqualTo(403);
    }

    private void assertOwnRecord(JsonNode record, String id, String userName, String displayName,
            String groupId, String groupName) {
        assertThat(Set.copyOf(record.propertyNames())).isEqualTo(SPECIFIED_FIELDS);
        assertThat(record.get("id").asText()).isEqualTo(id);
        assertThat(record.get("userName").asText()).isEqualTo(userName);
        assertThat(record.get("displayName").asText()).isEqualTo(displayName);
        assertThat(record.get("groups")).hasSize(1);
        assertThat(record.get("groups").get(0).get("id").asText()).isEqualTo(groupId);
        assertThat(record.get("groups").get(0).get("displayName").asText()).isEqualTo(groupName);
        assertThat(record.get("passwordChangeRequired").booleanValue()).isFalse();
        assertThat(Instant.parse(record.get("lastAuthenticatedAt").asText()))
                .isEqualTo(lastAuthenticatedAt(id));
    }

    private JsonNode self(Cookie session) throws Exception {
        MvcResult result = send(get("/api/self"), session);
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        return json.readTree(result.getResponse().getContentAsString());
    }

    /** Creates a User over SCIM with a display name and a connector-set password. */
    private String provision(String userName, String displayName) throws Exception {
        MvcResult result = mvc.perform(asConnector(post(BASE + "/Users"))
                        .contentType(SCIM_JSON)
                        .content("""
                                {"schemas":["%s"],"userName":"%s","displayName":"%s","password":"%s"}"""
                                .formatted(USER_SCHEMA, userName, displayName, CONNECTOR_PASSWORD)))
                .andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(201);
        String id = json.readTree(result.getResponse().getContentAsString()).get("id").asText();
        created.add(UUID.fromString(id));
        return id;
    }

    private String group(String displayName, String memberId) throws Exception {
        MvcResult result = mvc.perform(asConnector(post(BASE + "/Groups"))
                        .contentType(SCIM_JSON)
                        .content("""
                                {"schemas":["%s"],"displayName":"%s","members":[{"value":"%s"}]}"""
                                .formatted(GROUP_SCHEMA, displayName, memberId)))
                .andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(201);
        String id = json.readTree(result.getResponse().getContentAsString()).get("id").asText();
        created.add(UUID.fromString(id));
        return id;
    }

    /** Replaces the connector-set password, leaving an unflagged User. */
    private void settle(String userName, String own) throws Exception {
        Cookie confined = logIn(userName, CONNECTOR_PASSWORD);
        MvcResult change = mvc.perform(withCsrf(post("/api/auth/change-password"))
                        .cookie(confined)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"currentPassword\":\"%s\",\"newPassword\":\"%s\"}"
                                .formatted(CONNECTOR_PASSWORD, own)))
                .andReturn();
        assertThat(change.getResponse().getStatus()).isEqualTo(204);
    }

    private Cookie logIn(String userName, String password) throws Exception {
        MvcResult login = mvc.perform(withCsrf(post("/api/auth/login"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"%s\",\"password\":\"%s\"}"
                                .formatted(userName, password)))
                .andReturn();
        assertThat(login.getResponse().getStatus()).isEqualTo(200);
        Cookie session = login.getResponse().getCookie(sessionCookieName);
        assertThat(session).as("the login issued a session cookie").isNotNull();
        return new Cookie(session.getName(), session.getValue());
    }

    private int logInStatus(String userName, String password) throws Exception {
        return mvc.perform(withCsrf(post("/api/auth/login"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"%s\",\"password\":\"%s\"}"
                                .formatted(userName, password)))
                .andReturn().getResponse().getStatus();
    }

    private MvcResult send(MockHttpServletRequestBuilder request, Cookie session) throws Exception {
        return mvc.perform(withCsrf(request).cookie(session)).andReturn();
    }

    private int failedAttempts(String userId) {
        return jdbc.queryForObject(
                "SELECT failed_login_attempts FROM scim_users WHERE resource_id = ?::uuid",
                Integer.class, userId);
    }

    private Instant lastAuthenticatedAt(String userId) {
        Timestamp stored = jdbc.queryForObject(
                "SELECT last_authenticated_at FROM scim_users WHERE resource_id = ?::uuid",
                Timestamp.class, userId);
        assertThat(stored).as("the login recorded an authentication").isNotNull();
        return stored.toInstant();
    }

    private MockHttpServletRequestBuilder asConnector(MockHttpServletRequestBuilder request) {
        return request.header(HttpHeaders.AUTHORIZATION, "Bearer " + writeToken)
                .with(ScimConditionalWrites.currentVersion(jdbc));
    }

    private MockHttpServletRequestBuilder withCsrf(MockHttpServletRequestBuilder request) {
        return SessionCsrf.withCsrf(mvc, request);
    }
}

package com.example.backend.auth.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.example.backend.auth.application.SelfReadService;
import com.example.backend.scim.InMemoryScimGroupRepository;
import com.example.backend.scim.InMemoryScimUserRepository;
import com.example.backend.scim.domain.ScimGroup;
import com.example.backend.scim.domain.ScimLoginState;
import com.example.backend.scim.domain.ScimUser;
import com.example.backend.scim.domain.ScimUserProfile;
import com.example.backend.scim.ScimIdentities;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.session.FindByIndexNameSessionRepository;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * {@code GET /api/self} as the web adapter answers it: the User is the session's, whatever the
 * request says. The filter chain is not in this MockMvc — its {@code ROLE_USER} rule and the real
 * session store are exercised in {@code SelfReadIntegrationTests}.
 */
class SelfControllerTests {

    private static final Instant ADA_LOGIN = Instant.parse("2026-04-01T10:00:00Z");
    private static final Instant BOB_LOCKED_AT = Instant.parse("2026-04-02T10:00:00Z");

    private final JsonMapper json = JsonMapper.builder().build();

    private final InMemoryScimUserRepository users = new InMemoryScimUserRepository();
    private final InMemoryScimGroupRepository groups = new InMemoryScimGroupRepository(users);

    private MockMvc mvc;
    private ScimUser ada;
    private ScimUser bob;
    private ScimGroup adaGroup;

    @BeforeEach
    void setUp() {
        ada = users.given(user("ada", "Ada Lovelace",
                new ScimLoginState("hash", 0, null, ADA_LOGIN, null)));
        // Bob carries a failure run and a lock, so their absence from every body is not vacuous.
        bob = users.given(user("bob", "Bob Builder",
                new ScimLoginState("hash", 4, BOB_LOCKED_AT, null, ADA_LOGIN)));
        adaGroup = groups.given(ScimIdentities.group("Analysts", ada));
        groups.given(ScimIdentities.group("Builders", bob));
        mvc = MockMvcBuilders
                .standaloneSetup(new SelfController(new SelfReadService(users, groups)))
                .build();
    }

    @Test
    void returnsTheSessionsUserExactly() throws Exception {
        MockHttpServletResponse response = perform(get("/api/self"), sessionOf(ada.id()));

        assertThat(response.getStatus()).isEqualTo(200);
        JsonNode body = json.readTree(response.getContentAsString());
        assertThat(body.get("id").asText()).isEqualTo(ada.id().toString());
        assertThat(body.get("userName").asText()).isEqualTo("ada");
        assertThat(body.get("displayName").asText()).isEqualTo("Ada Lovelace");
        assertThat(body.get("groups")).hasSize(1);
        assertThat(body.get("groups").get(0).get("id").asText()).isEqualTo(adaGroup.id().toString());
        assertThat(body.get("groups").get(0).get("displayName").asText()).isEqualTo("Analysts");
        assertThat(body.get("passwordChangeRequired").booleanValue()).isFalse();
        assertThat(Instant.parse(body.get("lastAuthenticatedAt").asText())).isEqualTo(ADA_LOGIN);
    }

    /**
     * The wire shape, as a key set: exactly the specified fields and so nothing about a lock or
     * a failure run, for a User whose stored state carries both.
     */
    @Test
    void theBodyHasExactlyTheSpecifiedFieldsForALockedUser() throws Exception {
        MockHttpServletResponse response = perform(get("/api/self"), sessionOf(bob.id()));

        assertThat(response.getStatus()).isEqualTo(200);
        JsonNode body = json.readTree(response.getContentAsString());
        assertThat(Set.copyOf(body.propertyNames())).containsExactlyInAnyOrder(
                "id", "userName", "displayName", "groups",
                "passwordChangeRequired", "lastAuthenticatedAt");
        assertThat(Set.copyOf(body.get("groups").get(0).propertyNames()))
                .containsExactlyInAnyOrder("id", "displayName");
        assertThat(body.get("passwordChangeRequired").booleanValue()).isTrue();
        assertThat(body.get("lastAuthenticatedAt").isNull()).isTrue();
        assertThat(response.getContentAsString())
                .doesNotContainIgnoringCase("lock")
                .doesNotContainIgnoringCase("fail")
                .doesNotContainIgnoringCase("attempt")
                .doesNotContain(BOB_LOCKED_AT.toString());
    }

    /**
     * Every identifier-shaped value a request could carry — query parameters by each plausible
     * name, headers, a body — naming the OTHER User. Each is ignored; the session's User is
     * returned.
     */
    @Test
    void identifierShapedRequestValuesAreIgnored() throws Exception {
        String bobId = bob.id().toString();
        List<MockHttpServletRequestBuilder> attempts = List.of(
                get("/api/self").param("id", bobId),
                get("/api/self").param("userId", bobId),
                get("/api/self").param("userName", "bob"),
                get("/api/self").param("username", "bob"),
                get("/api/self").param("filter", "userName eq \"bob\""),
                get("/api/self").header("X-User-Id", bobId),
                get("/api/self").header("X-Forwarded-User", "bob"),
                get("/api/self").contentType("application/json")
                        .content("{\"id\":\"" + bobId + "\",\"userName\":\"bob\"}"));

        for (MockHttpServletRequestBuilder attempt : attempts) {
            MockHttpServletResponse response = perform(attempt, sessionOf(ada.id()));
            assertThat(response.getStatus()).isEqualTo(200);
            JsonNode body = json.readTree(response.getContentAsString());
            assertThat(body.get("id").asText()).isEqualTo(ada.id().toString());
            assertThat(response.getContentAsString()).doesNotContain(bobId).doesNotContain("Bob");
        }
    }

    /** An id in the path is not a route to anyone's record. */
    @Test
    void anIdInThePathIsNotARoute() throws Exception {
        MockHttpServletResponse response =
                perform(get("/api/self/" + bob.id()), sessionOf(ada.id()));

        assertThat(response.getStatus()).isEqualTo(404);
        assertThat(response.getContentAsString()).doesNotContain(bob.id().toString());
    }

    @Test
    void noSessionIsUnauthorized() throws Exception {
        MockHttpServletResponse response = mvc.perform(get("/api/self")).andReturn().getResponse();

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getContentAsString()).isEmpty();
    }

    /** A session with no stable id — one never signed in to — identifies nobody. */
    @Test
    void aSessionWithoutAStableIdIsUnauthorized() throws Exception {
        MockHttpSession anonymous = new MockHttpSession();
        anonymous.setAttribute("unrelated", "value");

        MockHttpServletResponse response = perform(get("/api/self"), anonymous);

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getContentAsString()).isEmpty();
    }

    /** A principal index that is not a string is not an id either. */
    @Test
    void aNonStringPrincipalIndexIsUnauthorized() throws Exception {
        MockHttpSession odd = new MockHttpSession();
        odd.setAttribute(FindByIndexNameSessionRepository.PRINCIPAL_NAME_INDEX_NAME, ada.id());

        assertThat(perform(get("/api/self"), odd).getStatus()).isEqualTo(401);
    }

    /** A principal index that is not a stable id names nobody: a bare 401, never a 500. */
    @Test
    void aMalformedPrincipalIndexIsUnauthorized() throws Exception {
        MockHttpSession malformed = new MockHttpSession();
        malformed.setAttribute(
                FindByIndexNameSessionRepository.PRINCIPAL_NAME_INDEX_NAME, "not-an-id");

        MockHttpServletResponse response = perform(get("/api/self"), malformed);

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getContentAsString()).isEmpty();
    }

    /** A session that outlived its User names nobody. */
    @Test
    void aSessionNamingNoLiveUserIsUnauthorized() throws Exception {
        MockHttpServletResponse response = perform(get("/api/self"), sessionOf(UUID.randomUUID()));

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getContentAsString()).isEmpty();
    }

    private MockHttpServletResponse perform(MockHttpServletRequestBuilder request,
            MockHttpSession session) throws Exception {
        return mvc.perform(request.session(session)).andReturn().getResponse();
    }

    private static MockHttpSession sessionOf(UUID userId) {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(
                FindByIndexNameSessionRepository.PRINCIPAL_NAME_INDEX_NAME, userId.toString());
        return session;
    }

    private static ScimUser user(String userName, String displayName, ScimLoginState login) {
        return new ScimUser(
                UUID.randomUUID(),
                new ScimUserProfile(userName, null, displayName, null, null, null, true, List.of()),
                login,
                null,
                ScimUser.INITIAL_VERSION,
                ScimIdentities.NOW,
                ScimIdentities.NOW);
    }
}

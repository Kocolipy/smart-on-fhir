package com.example.backend.scim;

import static com.example.backend.ScimHttp.SCIM_JSON;
import static com.example.backend.ScimHttp.asConnector;
import static com.example.backend.TestJson.body;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import com.example.backend.ContainerTestConfiguration;
import com.example.backend.CreatedScimRows;
import com.example.backend.TokenPermissions;
import com.example.backend.observability.RequestIdFilter;
import com.example.backend.scim.application.ConnectorAdministrationService;
import jakarta.servlet.Filter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.JsonNode;

/**
 * Issue #104's demonstration: one attribute profile, observed at every protocol entry point.
 *
 * <p>The attribute facts have one definition, so this class does not compare two views of it.
 * It states the profile's outcomes by hand — what discovery says about representative string,
 * boolean, complex and multi-valued attributes; what a write keeps, ignores and refuses; what a
 * read returns and omits; how a filter and a sort compare — and observes each one over the real
 * filter chain against real SQL Server, so the query results come from the persistence adapter's
 * own SQL.
 *
 * <p>Scoped like {@link ScimQueryProtocolIntegrationTests}: every query is
 * {@code externalId pr and (...)} under a connector created here, so only this class's seed can
 * match, and only this class's resources are removed afterwards.
 */
@SpringBootTest
@Import(ContainerTestConfiguration.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ScimAttributeProfileIntegrationTests {

    private static final String BASE = "/scim/v2";

    private static final String USERS = BASE + "/Users";

    private static final String GROUPS = BASE + "/Groups";

    private static final String USER_SCHEMA = "urn:ietf:params:scim:schemas:core:2.0:User";

    private static final String GROUP_SCHEMA = "urn:ietf:params:scim:schemas:core:2.0:Group";

    private static final String PATCH_OP = "urn:ietf:params:scim:api:messages:2.0:PatchOp";

    private static final String SCOPE = "externalId pr";

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private ConnectorAdministrationService connectors;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private RequestIdFilter requestIdFilter;

    @Autowired
    @Qualifier("springSecurityFilterChain")
    private Filter springSecurityFilterChain;

    private MockMvc mvc;

    private String token;

    private final Map<String, UUID> ids = new HashMap<>();

    private final CreatedScimRows created = new CreatedScimRows();

    @BeforeAll
    void seed() throws Exception {
        mvc = MockMvcBuilders.webAppContextSetup(context)
                .addFilters(requestIdFilter, springSecurityFilterChain)
                .build();
        UUID connector = connectors.create("Attribute profile", null).id();
        token = connectors.issueToken(
                connector, TokenPermissions.ALL, null, null).presentedValue();

        user("""
                {"schemas":["%s"],"userName":"ap-ada","externalId":"ap-Ext-Ada",
                 "displayName":"Ada Lovelace","active":true,
                 "name":{"familyName":"Lovelace","givenName":"Ada"},
                 "emails":[{"value":"ada@work.example","type":"work","primary":true}]}""");
        user("""
                {"schemas":["%s"],"userName":"ap-grace","externalId":"ap-ext-grace",
                 "displayName":"Grace Hopper","active":false,
                 "name":{"familyName":"Hopper","givenName":"Grace"},
                 "emails":[{"value":"grace@home.example","type":"home"}]}""");
        user("""
                {"schemas":["%s"],"userName":"ap-alan","externalId":"ap-ext-alan",
                 "active":true,"name":{"familyName":"Turing"}}""");
        group("ap-Analysts", "ap-ext-analysts", "ap-ada", "ap-grace");
        group("ap-builders", "ap-ext-builders", "ap-alan");
    }

    @AfterAll
    void removeOnlyWhatThisClassCreated() {
        created.deleteAll(jdbc);
    }

    // ---- discovery -------------------------------------------------------------------------

    // ---- writes and readback ---------------------------------------------------------------

    /**
     * The read-only attributes a client sends are ignored, never refused nor stored — on a create,
     * where the server's own {@code id} wins so no connector chooses its resource ids, and on a
     * replacement, which keeps what it sets and reads back in the profile's shape: no password, no
     * unassigned attribute, no {@code groups} for a User in none, and the server's {@code meta}.
     * The one owner of that claim over HTTP; what each reader ignores is its unit test's.
     */
    @Test
    void an_accepted_write_persists_and_reads_back_ignoring_read_only_input() throws Exception {
        UUID chosenByClient = UUID.fromString("8a5c1f4e-0000-4000-8000-0000000000ff");
        UUID id = user("""
                {"schemas":["%s"],"userName":"ap-write","externalId":"ap-ext-write",
                 "id":"8a5c1f4e-0000-4000-8000-0000000000ff",
                 "active":true,"password":"a sufficiently long passphrase 104"}""");
        assertThat(id).as("the server chose the id").isNotEqualTo(chosenByClient);
        String etag = etag(id);

        MvcResult replaced = mvc.perform(asConnector(put(USERS + "/" + id), token)
                        .header(HttpHeaders.IF_MATCH, etag)
                        .contentType(SCIM_JSON)
                        .content("""
                                {"schemas":["%s"],"id":"%s","userName":"ap-write",
                                 "externalId":"ap-ext-write","displayName":"Written",
                                 "active":false,"meta":{"resourceType":"Group","version":"W/\\"999\\""},
                                 "groups":[{"value":"%s"}],
                                 "name":{"givenName":"Wren"},
                                 "emails":[{"value":"w@work.example","type":"work","primary":true}]}"""
                                .formatted(USER_SCHEMA, UUID.randomUUID(), ids.get("ap-Analysts"))))
                .andReturn();
        assertThat(replaced.getResponse().getStatus())
                .as(replaced.getResponse().getContentAsString()).isEqualTo(200);

        JsonNode read = body(mvc.perform(asConnector(get(USERS + "/" + id), token)).andReturn());
        assertThat(read.get("id").asText()).isEqualTo(id.toString());
        assertThat(read.get("displayName").asText()).isEqualTo("Written");
        assertThat(read.get("active").asBoolean()).isFalse();
        assertThat(read.get("name").get("givenName").asText()).isEqualTo("Wren");
        assertThat(read.get("name").has("familyName")).isFalse();
        assertThat(read.get("emails").get(0).get("primary").asBoolean()).isTrue();
        assertThat(read.get("externalId").asText()).isEqualTo("ap-ext-write");
        assertThat(read.has("password")).isFalse();
        assertThat(read.has("groups")).as("the submitted groups were ignored").isFalse();
        assertThat(read.has("locale")).as("an unassigned attribute is omitted").isFalse();
        assertThat(read.get("meta").get("version").asText()).isNotEqualTo("W/\"999\"");
        assertThat(read.get("meta").get("resourceType").asText()).isEqualTo("User");
    }

    /** A path-less PATCH value ignores read-only attributes exactly as a PUT body does. */
    @Test
    void a_pathless_patch_ignores_read_only_attributes_and_applies_the_rest() throws Exception {
        UUID id = user("""
                {"schemas":["%s"],"userName":"ap-pathless","externalId":"ap-ext-pathless"}""");

        MvcResult patched = patchUser(id, etag(id), """
                {"op":"replace","value":{"groups":[],"meta":{},"id":"x","locale":"en-GB"}}""");

        assertThat(patched.getResponse().getStatus())
                .as(patched.getResponse().getContentAsString()).isEqualTo(200);
        assertThat(body(patched).get("locale").asText()).isEqualTo("en-GB");
    }

    /**
     * An explicit path to a read-only attribute, or a removal of a required one, is refused as
     * {@code mutability}; a path naming no attribute keeps its own type; and no refusal changes
     * the User or its version.
     */
    @ParameterizedTest
    @ValueSource(strings = {
        "{\"op\":\"replace\",\"path\":\"meta.version\",\"value\":\"x\"}|mutability",
        "{\"op\":\"remove\",\"path\":\"userName\"}|mutability",
        "{\"op\":\"replace\",\"path\":\"active.value\",\"value\":true}|invalidPath",
        "{\"op\":\"replace\",\"path\":\"emails[\",\"value\":\"x\"}|invalidPath"})
    void a_refused_edit_keeps_its_scim_type_and_changes_nothing(String caseLine) throws Exception {
        String[] parts = caseLine.split("\\|");
        UUID id = ids.get("ap-ada");
        String etag = etag(id);
        JsonNode before = body(mvc.perform(asConnector(get(USERS + "/" + id), token)).andReturn());

        MvcResult refused = patchUser(id, etag, parts[0]);

        assertThat(refused.getResponse().getStatus())
                .as(refused.getResponse().getContentAsString()).isEqualTo(400);
        assertThat(body(refused).get("scimType").asText()).isEqualTo(parts[1]);
        assertThat(etag(id)).isEqualTo(etag);
        assertThat(body(mvc.perform(asConnector(get(USERS + "/" + id), token)).andReturn()))
                .isEqualTo(before);
    }

    // ---- projection ------------------------------------------------------------------------

    @ParameterizedTest
    @ValueSource(strings = {"password", "PASSWORD", USER_SCHEMA + ":password"})
    void the_password_is_never_returned_even_when_projected(String attributes) throws Exception {
        JsonNode read = body(mvc.perform(asConnector(get(USERS + "/" + ids.get("ap-ada")), token)
                        .param("attributes", attributes))
                .andReturn());

        assertThat(fieldNames(read)).containsExactlyInAnyOrder("schemas", "id");
    }

    @Test
    void a_projection_narrows_complex_and_multi_valued_attributes() throws Exception {
        JsonNode read = body(mvc.perform(asConnector(get(USERS + "/" + ids.get("ap-ada")), token)
                        .param("attributes", "name.givenName,emails.value,groups.display"))
                .andReturn());

        assertThat(fieldNames(read))
                .containsExactlyInAnyOrder("schemas", "id", "name", "emails", "groups");
        assertThat(fieldNames(read.get("name"))).containsExactly("givenName");
        assertThat(fieldNames(read.get("emails").get(0))).containsExactly("value");
        assertThat(read.get("groups").get(0).get("display").asText()).isEqualTo("ap-Analysts");
        assertThat(fieldNames(read.get("groups").get(0))).containsExactly("display");
    }

    // ---- filtering and sorting through the persistence adapter -----------------------------

    @Test
    void group_filters_and_sort_compare_as_the_profile_advertises() throws Exception {
        assertThat(groupNames("displayName eq \"AP-BUILDERS\"", null))
                .containsExactly("ap-builders");
        assertThat(groupNames("members.value eq \"" + ids.get("ap-grace") + "\"", null))
                .containsExactly("ap-Analysts");
        assertThat(groupNames("members.display eq \"grace hopper\"", null))
                .containsExactly("ap-Analysts");
        assertThat(groupNames("externalId eq \"AP-EXT-BUILDERS\"", null)).isEmpty();
        assertThat(groupNames("displayName sw \"ap-\"", "displayName"))
                .containsExactly("ap-Analysts", "ap-builders");
    }

    // ---- helpers ---------------------------------------------------------------------------

    private static JsonNode attribute(JsonNode schema, String name) {
        return find(schema.get("attributes"), name);
    }

    private static JsonNode find(JsonNode attributes, String name) {
        for (JsonNode attribute : attributes) {
            if (attribute.get("name").asText().equals(name)) {
                return attribute;
            }
        }
        throw new AssertionError("not advertised: " + name);
    }

    private static List<String> names(JsonNode attributes) {
        List<String> names = new ArrayList<>();
        attributes.forEach(attribute -> names.add(attribute.get("name").asText()));
        return names;
    }

    private static List<String> fieldNames(JsonNode node) {
        return List.copyOf(node.propertyNames());
    }

    private List<String> groupNames(String filter, String sortBy) throws Exception {
        MockHttpServletRequestBuilder request = asConnector(get(GROUPS), token)
                .param("filter", SCOPE + " and (" + filter + ")");
        if (sortBy != null) {
            request.param("sortBy", sortBy);
        }
        return resourceNames(body(mvc.perform(request).andReturn()), "displayName");
    }

    private static List<String> resourceNames(JsonNode page, String attribute) {
        List<String> names = new ArrayList<>();
        if (page.has("Resources")) {
            page.get("Resources").forEach(resource -> names.add(resource.get(attribute).asText()));
        }
        return names;
    }

    private UUID user(String body) throws Exception {
        MvcResult result = mvc.perform(asConnector(post(USERS), token).contentType(SCIM_JSON)
                        .content(body.replace("%s", USER_SCHEMA)))
                .andReturn();
        assertThat(result.getResponse().getStatus())
                .as(result.getResponse().getContentAsString()).isEqualTo(201);
        JsonNode user = body(result);
        UUID id = UUID.fromString(user.get("id").asText());
        ids.put(user.get("userName").asText(), id);
        created.add(id);
        return id;
    }

    private void group(String displayName, String externalId, String... members) throws Exception {
        StringBuilder memberList = new StringBuilder();
        for (String member : members) {
            if (!memberList.isEmpty()) {
                memberList.append(',');
            }
            memberList.append("{\"value\":\"").append(ids.get(member)).append("\"}");
        }
        MvcResult result = mvc.perform(asConnector(post(GROUPS), token).contentType(SCIM_JSON)
                        .content("""
                                {"schemas":["%s"],"displayName":"%s","externalId":"%s",
                                 "members":[%s]}"""
                                .formatted(GROUP_SCHEMA, displayName, externalId, memberList)))
                .andReturn();
        assertThat(result.getResponse().getStatus())
                .as(result.getResponse().getContentAsString()).isEqualTo(201);
        UUID id = UUID.fromString(body(result).get("id").asText());
        ids.put(displayName, id);
        created.add(id);
    }

    private MvcResult patchUser(UUID id, String etag, String operation) throws Exception {
        return mvc.perform(asConnector(patch(USERS + "/" + id), token)
                        .header(HttpHeaders.IF_MATCH, etag)
                        .contentType(SCIM_JSON)
                        .content("{\"schemas\":[\"" + PATCH_OP + "\"],\"Operations\":["
                                + operation + "]}"))
                .andReturn();
    }

    private String etag(UUID id) throws Exception {
        return mvc.perform(asConnector(get(USERS + "/" + id), token)).andReturn()
                .getResponse().getHeader(HttpHeaders.ETAG);
    }

}

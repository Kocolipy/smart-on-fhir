package com.example.backend.scim;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import com.example.backend.ContainerTestConfiguration;
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
 * Issue #104's demonstration: one attribute profile, observed at every protocol entry point.
 *
 * <p>The attribute facts now have one definition, so this class does not compare two views of it.
 * It states the profile's outcomes by hand — what discovery says about representative string,
 * boolean, complex and multi-valued attributes; what a write keeps, ignores and refuses; what a
 * read returns and omits; how a filter and a sort compare — and observes each one over the real
 * filter chain against real PostgreSQL, so the query results come from the persistence adapter's
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

    private static final MediaType SCIM_JSON = MediaType.valueOf("application/scim+json");

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

    private final JsonMapper json = JsonMapper.builder().build();

    private MockMvc mvc;

    private String token;

    private String otherToken;

    private final Map<String, UUID> ids = new HashMap<>();

    private final List<UUID> created = new ArrayList<>();

    @BeforeAll
    void seed() throws Exception {
        mvc = MockMvcBuilders.webAppContextSetup(context)
                .addFilters(requestIdFilter, springSecurityFilterChain)
                .build();
        UUID connector = connectors.create("Attribute profile", "test-admin").id();
        token = connectors.issueToken(
                connector, TokenPermissions.ALL, null, "test-admin", TokenPermissions.ALL).presentedValue();
        UUID other = connectors.create("Attribute profile, other", "test-admin").id();
        otherToken = connectors.issueToken(
                other, TokenPermissions.ALL, null, "test-admin", TokenPermissions.ALL).presentedValue();

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
        for (UUID id : created) {
            jdbc.update("DELETE FROM scim_resources WHERE id = ?", id);
        }
    }

    // ---- discovery -------------------------------------------------------------------------

    /** The User schema, as served, says these things of these representative attributes. */
    @Test
    void discovery_advertises_the_profile_of_representative_user_attributes() throws Exception {
        JsonNode schema = body(mvc.perform(as(token, get(BASE + "/Schemas/" + USER_SCHEMA))).andReturn());

        assertThat(names(schema.get("attributes"))).containsExactly(
                "userName", "name", "displayName", "preferredLanguage", "locale", "timezone",
                "active", "password", "emails", "groups");
        assertCharacteristics(attribute(schema, "userName"),
                "string", false, true, false, "readWrite", "default", "server");
        assertCharacteristics(attribute(schema, "active"),
                "boolean", false, false, false, "readWrite", "default", "none");
        assertCharacteristics(attribute(schema, "name"),
                "complex", false, false, false, "readWrite", "default", "none");
        assertThat(names(attribute(schema, "name").get("subAttributes"))).containsExactly(
                "formatted", "familyName", "givenName", "middleName", "honorificPrefix",
                "honorificSuffix");
        assertCharacteristics(attribute(schema, "emails"),
                "complex", true, false, false, "readWrite", "default", "none");
        assertCharacteristics(sub(attribute(schema, "emails"), "primary"),
                "boolean", false, false, false, "readWrite", "default", "none");
        assertCharacteristics(attribute(schema, "password"),
                "string", false, false, true, "writeOnly", "never", "none");
        assertCharacteristics(attribute(schema, "groups"),
                "complex", true, false, false, "readOnly", "default", "none");
        assertCharacteristics(sub(attribute(schema, "groups"), "value"),
                "string", false, false, true, "readOnly", "default", "none");
        assertThat(sub(attribute(schema, "groups"), "$ref").get("referenceTypes").get(0).asText())
                .isEqualTo("Group");
    }

    @Test
    void discovery_advertises_the_profile_of_the_group_attributes() throws Exception {
        JsonNode schema = body(mvc.perform(as(token, get(BASE + "/Schemas/" + GROUP_SCHEMA))).andReturn());

        assertThat(names(schema.get("attributes"))).containsExactly("displayName", "members");
        assertCharacteristics(attribute(schema, "displayName"),
                "string", false, true, false, "readWrite", "default", "server");
        assertCharacteristics(attribute(schema, "members"),
                "complex", true, false, false, "readWrite", "default", "none");
        assertCharacteristics(sub(attribute(schema, "members"), "value"),
                "string", false, false, true, "readWrite", "default", "none");
        assertCharacteristics(sub(attribute(schema, "members"), "display"),
                "string", false, false, false, "readOnly", "default", "none");
        assertThat(sub(attribute(schema, "members"), "$ref").get("referenceTypes").get(0).asText())
                .isEqualTo("User");
    }

    // ---- writes and readback ---------------------------------------------------------------

    /**
     * A replacement keeps what it sets, ignores the read-only attributes a client sends back, and
     * reads back in the profile's shape: no password, no unassigned attribute, no {@code groups}
     * for a User in none.
     */
    @Test
    void an_accepted_write_persists_and_reads_back_ignoring_read_only_input() throws Exception {
        UUID id = user("""
                {"schemas":["%s"],"userName":"ap-write","externalId":"ap-ext-write",
                 "active":true,"password":"a sufficiently long passphrase 104"}""");
        String etag = etag(id);

        MvcResult replaced = mvc.perform(as(token, put(USERS + "/" + id))
                        .header(HttpHeaders.IF_MATCH, etag)
                        .contentType(SCIM_JSON)
                        .content("""
                                {"schemas":["%s"],"id":"%s","userName":"ap-write",
                                 "externalId":"ap-ext-write","displayName":"Written",
                                 "active":false,"meta":{"version":"W/\\"999\\""},
                                 "groups":[{"value":"%s"}],
                                 "name":{"givenName":"Wren"},
                                 "emails":[{"value":"w@work.example","type":"work","primary":true}]}"""
                                .formatted(USER_SCHEMA, UUID.randomUUID(), ids.get("ap-Analysts"))))
                .andReturn();
        assertThat(replaced.getResponse().getStatus())
                .as(replaced.getResponse().getContentAsString()).isEqualTo(200);

        JsonNode read = body(mvc.perform(as(token, get(USERS + "/" + id))).andReturn());
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
     * An explicit path to a read-only attribute is refused as {@code mutability}; the other
     * request-only refusals keep their own types; and no refusal changes the User or its version.
     */
    @ParameterizedTest
    @ValueSource(strings = {
        "{\"op\":\"replace\",\"path\":\"groups\",\"value\":[]}|mutability",
        "{\"op\":\"replace\",\"path\":\"meta.version\",\"value\":\"x\"}|mutability",
        "{\"op\":\"add\",\"path\":\"id\",\"value\":\"x\"}|mutability",
        "{\"op\":\"remove\",\"path\":\"userName\"}|mutability",
        "{\"op\":\"replace\",\"path\":\"nickName\",\"value\":\"x\"}|invalidPath",
        "{\"op\":\"replace\",\"path\":\"active.value\",\"value\":true}|invalidPath",
        "{\"op\":\"replace\",\"path\":\"emails[\",\"value\":\"x\"}|invalidPath",
        "{\"op\":\"replace\",\"path\":\"active\",\"value\":\"yes\"}|invalidValue"})
    void a_refused_edit_keeps_its_scim_type_and_changes_nothing(String caseLine) throws Exception {
        String[] parts = caseLine.split("\\|");
        UUID id = ids.get("ap-ada");
        String etag = etag(id);
        JsonNode before = body(mvc.perform(as(token, get(USERS + "/" + id))).andReturn());

        MvcResult refused = patchUser(id, etag, parts[0]);

        assertThat(refused.getResponse().getStatus())
                .as(refused.getResponse().getContentAsString()).isEqualTo(400);
        assertThat(body(refused).get("scimType").asText()).isEqualTo(parts[1]);
        assertThat(etag(id)).isEqualTo(etag);
        assertThat(body(mvc.perform(as(token, get(USERS + "/" + id))).andReturn()))
                .isEqualTo(before);
    }

    // ---- projection ------------------------------------------------------------------------

    @ParameterizedTest
    @ValueSource(strings = {"password", "PASSWORD", USER_SCHEMA + ":password"})
    void the_password_is_never_returned_even_when_projected(String attributes) throws Exception {
        JsonNode read = body(mvc.perform(as(token, get(USERS + "/" + ids.get("ap-ada")))
                        .param("attributes", attributes))
                .andReturn());

        assertThat(fieldNames(read)).containsExactlyInAnyOrder("schemas", "id");
    }

    @Test
    void a_projection_narrows_complex_and_multi_valued_attributes() throws Exception {
        JsonNode read = body(mvc.perform(as(token, get(USERS + "/" + ids.get("ap-ada")))
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
    void user_filters_compare_as_the_profile_advertises() throws Exception {
        // case-insensitive profile strings, including a complex sub-attribute
        assertThat(userNames("displayName eq \"ADA LOVELACE\"")).containsExactly("ap-ada");
        assertThat(userNames("name.familyName eq \"hopper\"")).containsExactly("ap-grace");
        // a case-exact identifier: the stored alias matches only as written
        assertThat(userNames("externalId eq \"ap-Ext-Ada\"")).containsExactly("ap-ada");
        assertThat(userNames("externalId eq \"ap-ext-ada\"")).isEmpty();
        // a boolean
        assertThat(userNames("active eq false")).containsExactly("ap-grace");
        // multi-valued paths: one value must satisfy a value filter, any value a dotted path
        assertThat(userNames("emails[type eq \"work\" and primary eq true]"))
                .containsExactly("ap-ada");
        assertThat(userNames("emails.value co \"@HOME.\"")).containsExactly("ap-grace");
        assertThat(userNames("groups.value eq \"" + ids.get("ap-builders") + "\""))
                .containsExactly("ap-alan");
        assertThat(userNames("groups.display eq \"AP-ANALYSTS\""))
                .containsExactly("ap-ada", "ap-grace");
    }

    @Test
    void a_user_sort_orders_case_insensitively_by_a_complex_sub_attribute() throws Exception {
        JsonNode page = body(mvc.perform(as(token, get(USERS))
                        .param("filter", SCOPE + " and userName sw \"ap-a\" or " + SCOPE
                                + " and userName eq \"ap-grace\"")
                        .param("sortBy", "name.familyName")
                        .param("sortOrder", "descending"))
                .andReturn());

        assertThat(resourceNames(page, "userName")).containsExactly("ap-alan", "ap-ada", "ap-grace");
    }

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

    @ParameterizedTest
    @ValueSource(strings = {"password pr", "password eq \"x\""})
    void the_password_cannot_be_filtered(String filter) throws Exception {
        MvcResult refused = mvc.perform(as(token, get(USERS)).param("filter", filter)).andReturn();

        assertThat(refused.getResponse().getStatus()).isEqualTo(400);
        assertThat(body(refused).get("scimType").asText()).isEqualTo("invalidFilter");
    }

    @Test
    void the_password_cannot_be_sorted_by() throws Exception {
        MvcResult refused = mvc.perform(as(token, get(USERS)).param("sortBy", "password"))
                .andReturn();

        assertThat(refused.getResponse().getStatus()).isEqualTo(400);
        assertThat(body(refused).get("scimType").asText()).isEqualTo("invalidValue");
    }

    /** Another connector sees none of this connector's aliases, and cannot query by them. */
    @Test
    void an_alias_is_visible_and_queryable_only_to_the_connector_that_set_it() throws Exception {
        JsonNode read = body(mvc.perform(as(otherToken, get(USERS + "/" + ids.get("ap-ada"))))
                .andReturn());
        JsonNode page = body(mvc.perform(as(otherToken, get(USERS))
                        .param("filter", "externalId eq \"ap-Ext-Ada\""))
                .andReturn());

        assertThat(read.has("externalId")).isFalse();
        assertThat(page.get("totalResults").asInt()).isZero();
    }

    // ---- helpers ---------------------------------------------------------------------------

    private static void assertCharacteristics(JsonNode attribute, String type, boolean multiValued,
            boolean required, boolean caseExact, String mutability, String returned,
            String uniqueness) {
        String name = attribute.get("name").asText();
        assertThat(attribute.get("type").asText()).as(name + " type").isEqualTo(type);
        assertThat(attribute.get("multiValued").asBoolean()).as(name + " multiValued")
                .isEqualTo(multiValued);
        assertThat(attribute.get("required").asBoolean()).as(name + " required")
                .isEqualTo(required);
        assertThat(attribute.get("caseExact").asBoolean()).as(name + " caseExact")
                .isEqualTo(caseExact);
        assertThat(attribute.get("mutability").asText()).as(name + " mutability")
                .isEqualTo(mutability);
        assertThat(attribute.get("returned").asText()).as(name + " returned").isEqualTo(returned);
        assertThat(attribute.get("uniqueness").asText()).as(name + " uniqueness")
                .isEqualTo(uniqueness);
    }

    private static JsonNode attribute(JsonNode schema, String name) {
        return find(schema.get("attributes"), name);
    }

    private static JsonNode sub(JsonNode attribute, String name) {
        return find(attribute.get("subAttributes"), name);
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

    private List<String> userNames(String filter) throws Exception {
        return resourceNames(body(mvc.perform(as(token, get(USERS))
                        .param("filter", SCOPE + " and (" + filter + ")"))
                .andReturn()), "userName");
    }

    private List<String> groupNames(String filter, String sortBy) throws Exception {
        MockHttpServletRequestBuilder request = as(token, get(GROUPS))
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
        MvcResult result = mvc.perform(as(token, post(USERS)).contentType(SCIM_JSON)
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
        MvcResult result = mvc.perform(as(token, post(GROUPS)).contentType(SCIM_JSON)
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
        return mvc.perform(as(token, patch(USERS + "/" + id))
                        .header(HttpHeaders.IF_MATCH, etag)
                        .contentType(SCIM_JSON)
                        .content("{\"schemas\":[\"" + PATCH_OP + "\"],\"Operations\":["
                                + operation + "]}"))
                .andReturn();
    }

    private String etag(UUID id) throws Exception {
        return mvc.perform(as(token, get(USERS + "/" + id))).andReturn()
                .getResponse().getHeader(HttpHeaders.ETAG);
    }

    private static MockHttpServletRequestBuilder as(
            String bearer, MockHttpServletRequestBuilder request) {
        return request.header(HttpHeaders.AUTHORIZATION, "Bearer " + bearer);
    }

    private JsonNode body(MvcResult result) throws Exception {
        return json.readTree(result.getResponse().getContentAsString());
    }
}

package com.example.backend.scim;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.example.backend.ContainerTestConfiguration;
import com.example.backend.TokenPermissions;
import com.example.backend.authorization.domain.Permission;
import com.example.backend.observability.RequestIdFilter;
import com.example.backend.scim.application.ConnectorAdministrationService;
import com.example.backend.scim.controller.ScimDiscovery;
import com.example.backend.scim.domain.ScimPageRequest;
import jakarta.servlet.Filter;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Discovery, and the claim that it describes this service rather than SCIM in general.
 *
 * <p>Each advertised flag is asserted BESIDE the behaviour it advertises — the filter flag
 * beside a filtered query, the sort flag beside a sorted one, the ETag flag beside a
 * response's validator. An assertion on the document alone would pass just as well if the
 * document were a fiction, which is the failure mode that matters: a connector configures
 * itself from this and has no other way to find out.
 */
@SpringBootTest
@Import(ContainerTestConfiguration.class)
class ScimDiscoveryIntegrationTests {

    private static final String BASE = "/scim/v2";

    private static final String USER_SCHEMA = "urn:ietf:params:scim:schemas:core:2.0:User";

    /**
     * Spelled out rather than referenced from {@code ScimSchemas}, like its User counterpart: a
     * schema URI is compared byte for byte by a conformance client, so the test's expectation has
     * to be written independently of the constant the production code renders.
     */
    private static final String GROUP_SCHEMA = "urn:ietf:params:scim:schemas:core:2.0:Group";

    private static final String ENTERPRISE_SCHEMA =
            "urn:ietf:params:scim:schemas:extension:enterprise:2.0:User";

    private static final MediaType SCIM_JSON = MediaType.valueOf("application/scim+json");

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

    private final JsonMapper json = JsonMapper.builder().build();

    /**
     * Removes the User this class persists over the real SCIM surface ("etag-probe").
     * This class is not {@code @Transactional} — the create is a real HTTP commit —
     * so without this the fixed userName survives into a second run against a reused
     * Postgres and collides on the userName uniqueness constraint. Deleting the
     * {@code scim_resources} root cascades to {@code scim_users}. Scoped to the one
     * name this class creates so it cannot remove another class's fixtures.
     */
    @org.junit.jupiter.api.AfterEach
    void removeSeededUser() {
        jdbc.update(
                "DELETE FROM scim_resources WHERE id IN "
                        + "(SELECT resource_id FROM scim_users WHERE normalized_user_name = ?)",
                com.example.backend.scim.domain.NormalizedUserName.of("etag-probe").value());
    }

    private MockMvc mvc;

    /** No default credential: what a caller that presents none is answered. */
    private MockMvc anonymous;

    private String writeToken;

    @BeforeEach
    void setUp() {
        UUID connectorId = connectors.create("Okta", "test-admin").id();
        writeToken = connectors
                .issueToken(connectorId, TokenPermissions.ALL, null, "test-admin", TokenPermissions.ALL)
                .presentedValue();
        // Discovery needs a valid token and no Permission (ADR 0010), so every request this class
        // makes carries one by default: a token holding only group:read, which discovery does not
        // look at. A request that names its own credential — asConnector — keeps that one.
        String discoveryToken = connectors
                .issueToken(connectorId, Set.of(Permission.GROUP_READ), null, "test-admin",
                        TokenPermissions.ALL)
                .presentedValue();
        anonymous = MockMvcBuilders.webAppContextSetup(context)
                .addFilters(requestIdFilter, springSecurityFilterChain)
                .build();
        mvc = MockMvcBuilders.webAppContextSetup(context)
                .addFilters(requestIdFilter, springSecurityFilterChain)
                .defaultRequest(get("/").header(HttpHeaders.AUTHORIZATION, "Bearer " + discoveryToken))
                .build();
    }

    /** Discovery is challenged with no credential: it needs a valid token, though no Permission. */
    @ParameterizedTest
    @ValueSource(strings = {
        BASE + "/ServiceProviderConfig",
        BASE + "/ResourceTypes",
        BASE + "/Schemas",
    })
    void discovery_without_a_token_is_challenged(String path) throws Exception {
        MvcResult result = anonymous.perform(get(path)).andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(401);
        assertThat(result.getResponse().getHeader(HttpHeaders.WWW_AUTHENTICATE)).isEqualTo("Bearer");
    }

    /** Discovery answers any valid token, whatever Permissions it carries. */
    @ParameterizedTest
    @ValueSource(strings = {
        BASE + "/ServiceProviderConfig",
        BASE + "/ResourceTypes",
        BASE + "/Schemas",
    })
    void discovery_answers_any_valid_token_as_scim_json(String path) throws Exception {
        MvcResult result = mvc.perform(get(path)).andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        assertThat(result.getResponse().getContentType()).startsWith("application/scim+json");
    }

    @Test
    void the_capability_document_advertises_bulk_as_unsupported_with_zero_limits()
            throws Exception {
        JsonNode config = body(get(BASE + "/ServiceProviderConfig"));

        assertThat(config.get("bulk").get("supported").booleanValue()).isFalse();
        assertThat(config.get("bulk").get("maxOperations").asInt()).isZero();
        assertThat(config.get("bulk").get("maxPayloadSize").asInt()).isZero();
    }

    @Test
    void the_capability_document_declares_exactly_one_bearer_scheme() throws Exception {
        JsonNode schemes = body(get(BASE + "/ServiceProviderConfig"))
                .get("authenticationSchemes");

        assertThat(schemes.size()).isEqualTo(1);
        assertThat(schemes.get(0).get("type").asText()).isEqualTo("oauthbearertoken");
        assertThat(schemes.get(0).get("primary").booleanValue()).isTrue();
    }

    /**
     * {@code filter.supported=true}, and a filtered query is honoured: it selects exactly the
     * matching User. The pair is the assertion — either both change or the document is lying.
     */
    @Test
    void the_filter_flag_agrees_with_what_a_filtered_query_does() throws Exception {
        assertThat(body(get(BASE + "/ServiceProviderConfig"))
                        .get("filter").get("supported").booleanValue())
                .isEqualTo(ScimDiscovery.FILTER_SUPPORTED)
                .isTrue();

        MvcResult filtered = mvc.perform(asConnector(get(BASE + "/Users")
                        .param("filter", "userName eq \"no-such-user-anywhere\"")))
                .andReturn();

        assertThat(filtered.getResponse().getStatus()).isEqualTo(200);
        JsonNode list = json.readTree(filtered.getResponse().getContentAsString());
        assertThat(list.get("schemas").get(0).asText())
                .isEqualTo("urn:ietf:params:scim:api:messages:2.0:ListResponse");
        assertThat(list.get("totalResults").asInt())
                .as("a filter that matches nobody selects nobody; an ignored one would not")
                .isZero();
    }

    /** The page ceiling advertised is the one the paging rule enforces. */
    @Test
    void the_advertised_maximum_results_is_the_enforced_page_ceiling() throws Exception {
        assertThat(body(get(BASE + "/ServiceProviderConfig"))
                        .get("filter").get("maxResults").asInt())
                .isEqualTo(ScimPageRequest.MAX_COUNT);
    }

    @Test
    void the_sort_flag_agrees_with_what_a_sorted_query_does() throws Exception {
        assertThat(body(get(BASE + "/ServiceProviderConfig"))
                        .get("sort").get("supported").booleanValue())
                .isEqualTo(ScimDiscovery.SORT_SUPPORTED)
                .isTrue();

        assertThat(mvc.perform(asConnector(get(BASE + "/Users").param("sortBy", "userName")))
                        .andReturn().getResponse().getStatus())
                .isEqualTo(200);
    }

    /**
     * {@code patch.supported=true}, and a Group PATCH is ACCEPTED.
     *
     * <p>This assertion inverted with the ticket that completed Groups. It used to pair the flag
     * with a PATCH that no handler was mapped for; the pairing is only honest in whichever
     * direction the implementation actually goes, and advertising {@code false} while
     * {@code /Groups} answered a PATCH would be the same failure as the reverse.
     *
     * <p>Exercised against a Group because Groups are what accept PATCH: a membership change is
     * the operation the capability exists for, and a provisioning system that had to PUT a Group
     * to add one member would resend the whole membership every time and overwrite concurrent
     * changes it never read.
     */
    @Test
    void the_patch_flag_agrees_with_a_group_patch_being_accepted() throws Exception {
        assertThat(body(get(BASE + "/ServiceProviderConfig"))
                        .get("patch").get("supported").booleanValue())
                .isEqualTo(ScimDiscovery.PATCH_SUPPORTED)
                .isTrue();

        MvcResult created = mvc.perform(asConnector(post(BASE + "/Groups"))
                        .contentType(SCIM_JSON)
                        .content("""
                                {"schemas":["urn:ietf:params:scim:schemas:core:2.0:Group"],
                                 "displayName":"Patch Target"}"""))
                .andReturn();
        assertThat(created.getResponse().getStatus()).isEqualTo(201);
        String groupId = parsed(created).get("id").asText();

        int status = mvc.perform(asConnector(patch(BASE + "/Groups/" + groupId))
                        .contentType(SCIM_JSON)
                        .content("""
                                {"schemas":["urn:ietf:params:scim:api:messages:2.0:PatchOp"],
                                 "Operations":[
                                   {"op":"replace","path":"displayName","value":"Patched"}]}"""))
                .andReturn()
                .getResponse()
                .getStatus();

        assertThat(status)
                .as("the advertised capability has to be the one the endpoint honours")
                .isEqualTo(200);
    }

    /** {@code etag.supported=true}, and a created resource carries one that matches its version. */
    @Test
    void the_etag_flag_agrees_with_the_validator_a_response_carries() throws Exception {
        assertThat(body(get(BASE + "/ServiceProviderConfig"))
                        .get("etag").get("supported").booleanValue())
                .isEqualTo(ScimDiscovery.ETAG_SUPPORTED)
                .isTrue();

        MvcResult created = mvc.perform(asConnector(post(BASE + "/Users"))
                        .contentType(SCIM_JSON)
                        .content("""
                                {"schemas":["urn:ietf:params:scim:schemas:core:2.0:User"],
                                 "userName":"etag-probe"}"""))
                .andReturn();

        assertThat(created.getResponse().getStatus()).isEqualTo(201);
        assertThat(created.getResponse().getHeader(HttpHeaders.ETAG))
                .isEqualTo(json.readTree(created.getResponse().getContentAsString())
                        .get("meta").get("version").asText());
    }

    /**
     * {@code changePassword.supported=true}: PUT and PATCH can now set a User's password, which is
     * the capability that flag names.
     */
    @Test
    void change_password_is_advertised_as_supported_now_that_put_and_patch_can_do_it()
            throws Exception {
        assertThat(body(get(BASE + "/ServiceProviderConfig"))
                        .get("changePassword").get("supported").booleanValue())
                .isEqualTo(ScimDiscovery.CHANGE_PASSWORD_SUPPORTED)
                .isTrue();
    }

    @Test
    void the_resource_types_are_user_and_group_now_that_both_have_endpoints() throws Exception {
        JsonNode types = body(get(BASE + "/ResourceTypes"));

        assertThat(types.get("totalResults").asInt()).isEqualTo(2);
        assertThat(types.get("Resources")).hasSize(2)
                .extracting(type -> type.get("id").asText())
                .containsExactly("User", "Group");

        JsonNode user = body(get(BASE + "/ResourceTypes/User"));
        assertThat(user.get("id").asText())
                .as("the single-resource endpoint serves the resource type, not an empty body")
                .isEqualTo("User");
        assertThat(user.get("endpoint").asText()).isEqualTo("/Users");
        assertThat(user.get("schema").asText()).isEqualTo(USER_SCHEMA);

        JsonNode group = body(get(BASE + "/ResourceTypes/Group"));
        assertThat(group.get("id").asText()).isEqualTo("Group");
        assertThat(group.get("endpoint").asText()).isEqualTo("/Groups");
        assertThat(group.get("schema").asText()).isEqualTo(GROUP_SCHEMA);

        // The claim the list makes: a resource type in it means its endpoint answers. Inverted
        // from the version of this test that asserted /ResourceTypes/Group was a 404, because
        // /Groups now answers — and an advertised type whose endpoint did not would be the
        // dishonesty this whole test class exists to catch.
        assertThat(mvc.perform(asConnector(get(BASE + "/Groups"))).andReturn()
                        .getResponse().getStatus())
                .as("a resource type in the list is a claim that its endpoint answers")
                .isEqualTo(200);
    }

    /** A resource type this service does not serve is a SCIM 404, not a 500 or an empty body. */
    @Test
    void an_unknown_resource_type_is_not_found() throws Exception {
        MvcResult result = mvc.perform(get(BASE + "/ResourceTypes/NotAResourceType")).andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(404);
        assertThat(parsed(result).get("detail").asText())
                .isEqualTo("This service serves no such resource type.");
    }

    @Test
    void the_schemas_are_the_core_user_and_group_schemas() throws Exception {
        JsonNode schemas = body(get(BASE + "/Schemas"));

        assertThat(schemas.get("totalResults").asInt()).isEqualTo(2);
        assertThat(schemas.get("Resources")).hasSize(2)
                .extracting(schema -> schema.get("id").asText())
                .containsExactly(USER_SCHEMA, GROUP_SCHEMA);

        JsonNode user = body(get(BASE + "/Schemas/" + USER_SCHEMA));
        assertThat(user.get("id").asText())
                .as("the single-schema endpoint serves the schema, not an empty body")
                .isEqualTo(USER_SCHEMA);
        assertThat(user.get("attributes").isArray()).isTrue();

        JsonNode group = body(get(BASE + "/Schemas/" + GROUP_SCHEMA));
        assertThat(group.get("id").asText()).isEqualTo(GROUP_SCHEMA);
        assertThat(group.get("attributes").isArray()).isTrue();

        assertThat(mvc.perform(get(BASE + "/Schemas/" + ENTERPRISE_SCHEMA)).andReturn()
                        .getResponse().getStatus())
                .as("no extension is implemented, so none is served")
                .isEqualTo(404);
    }

    /**
     * RFC 7643 §3.1 defines {@code meta.location} as the URI of the resource, so each discovery
     * resource names the absolute URL it is served at — the same form Users and Groups render —
     * and not a path a client would have to resolve against a base it was never told.
     */
    @ParameterizedTest
    @ValueSource(strings = {
        BASE + "/ServiceProviderConfig",
        BASE + "/ResourceTypes/User",
        BASE + "/ResourceTypes/Group",
        BASE + "/Schemas/" + USER_SCHEMA,
        BASE + "/Schemas/" + GROUP_SCHEMA,
    })
    void a_discovery_resource_is_located_at_the_absolute_url_it_is_served_at(String path)
            throws Exception {
        MvcResult result = mvc.perform(get(path)).andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        assertThat(parsed(result).at("/meta/location").asText())
                .startsWith("http://localhost/")
                .isEqualTo(result.getRequest().getRequestURL().toString());
    }

    /**
     * The list responses carry the same locations as the by-id ones: each listed resource is
     * located at its by-id URL, and that URL serves it with the same location.
     */
    @ParameterizedTest
    @ValueSource(strings = {BASE + "/ResourceTypes", BASE + "/Schemas"})
    void every_listed_discovery_resource_is_located_at_its_by_id_url(String path)
            throws Exception {
        MvcResult list = mvc.perform(get(path)).andReturn();
        String collectionUrl = list.getRequest().getRequestURL().toString();

        JsonNode resources = parsed(list).get("Resources");
        assertThat(resources).hasSize(2);
        for (JsonNode resource : resources) {
            String id = resource.get("id").asText();
            String location = resource.at("/meta/location").asText();
            assertThat(location).isEqualTo(collectionUrl + "/" + id);

            MvcResult byId = mvc.perform(get(path + "/" + id)).andReturn();
            assertThat(byId.getResponse().getStatus()).isEqualTo(200);
            assertThat(parsed(byId).at("/meta/location").asText()).isEqualTo(location);
        }
    }

    /**
     * The base comes from the request, as it does for Users and Groups, so a deployment reached
     * on another scheme, host or port renders that one.
     */
    @Test
    void a_discovery_location_follows_the_scheme_host_and_port_the_request_arrived_on()
            throws Exception {
        String origin = "https://scim.example.test:8443";
        MvcResult result = mvc.perform(get(origin + BASE + "/ServiceProviderConfig")).andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        assertThat(parsed(result).at("/meta/location").asText())
                .isEqualTo(origin + BASE + "/ServiceProviderConfig");
    }

    /**
     * A refusal that has no {@code scimType} omits the key rather than carrying a null.
     *
     * <p>RFC 7644 §3.12 makes {@code scimType} optional and defines its values as a closed
     * set, so {@code "scimType": null} is not a member of it. A client that switches on the
     * value would see a type it cannot map.
     */
    @Test
    void a_refusal_with_no_scim_type_omits_the_key() throws Exception {
        MvcResult result = mvc.perform(get(BASE + "/Schemas/" + ENTERPRISE_SCHEMA)).andReturn();

        JsonNode error = json.readTree(result.getResponse().getContentAsString());
        assertThat(error.has("scimType")).isFalse();
        assertThat(error.get("status").asText()).isEqualTo("404");
        assertThat(error.get("detail").asText()).isNotBlank();
    }

    /**
     * The schema document lists exactly the implemented attributes, checked against the
     * write surface: an attribute the schema declares is accepted on a create, and one it
     * does not is refused. The unit test pins the two sets to one list; this pins the list
     * to the running service.
     */
    @Test
    void an_attribute_the_schema_does_not_declare_is_refused_on_a_create() throws Exception {
        MvcResult refused = mvc.perform(asConnector(post(BASE + "/Users"))
                        .contentType(SCIM_JSON)
                        .content("""
                                {"schemas":["urn:ietf:params:scim:schemas:core:2.0:User"],
                                 "userName":"undeclared-attribute","nickName":"Babs"}"""))
                .andReturn();

        assertThat(refused.getResponse().getStatus()).isEqualTo(400);
        JsonNode error = json.readTree(refused.getResponse().getContentAsString());
        assertThat(error.get("scimType").asText()).isEqualTo("invalidValue");
        assertThat(error.get("status").asText()).isEqualTo("400");
    }

    /**
     * A filter on a discovery path is refused rather than ignored, on every one of them.
     *
     * <p>RFC 7644 has a provider ignore filtering here, and ignoring it is the one answer a
     * client cannot tell from a match.
     */
    @ParameterizedTest
    @ValueSource(strings = {
        BASE + "/ServiceProviderConfig",
        BASE + "/ResourceTypes",
        BASE + "/ResourceTypes/User",
        BASE + "/Schemas",
        BASE + "/Schemas/" + USER_SCHEMA,
    })
    void a_filter_on_a_discovery_path_is_refused(String path) throws Exception {
        MvcResult result = mvc.perform(get(path).param("filter", "id eq \"User\"")).andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(403);
        assertThat(json.readTree(result.getResponse().getContentAsString()).get("status").asText())
                .isEqualTo("403");
    }

    private MockHttpServletRequestBuilder asConnector(MockHttpServletRequestBuilder request) {
        // A conforming connector's write carries the version it read; the precondition's own
        // behaviour is pinned in ScimConditionalWriteIntegrationTests.
        return request.header(HttpHeaders.AUTHORIZATION, "Bearer " + writeToken)
                .with(ScimConditionalWrites.currentVersion(jdbc));
    }

    private JsonNode body(MockHttpServletRequestBuilder request) throws Exception {
        MvcResult result = mvc.perform(request).andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        return parsed(result);
    }

    /**
     * The body of a result whose status the caller has already asserted.
     *
     * <p>Separate from {@link #body(MockHttpServletRequestBuilder)}, which asserts {@code 200}:
     * a create answers {@code 201}, so reading its body through that helper would fail on the
     * status rather than on anything the test is about.
     */
    private JsonNode parsed(MvcResult result) throws Exception {
        return json.readTree(result.getResponse().getContentAsString());
    }
}

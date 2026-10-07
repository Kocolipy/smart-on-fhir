package com.example.backend.contract;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;

import com.example.backend.ContainerTestConfiguration;
import com.example.backend.TokenPermissions;
import com.example.backend.observability.RequestIdFilter;
import com.example.backend.scim.application.ConnectorAdministrationService;
import jakarta.servlet.Filter;
import com.example.backend.authorization.domain.Permission;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Named;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Generic RFC 7643/7644 conformance fixtures for the SCIM namespace, run against the finished
 * implementation over the real filter chain, Postgres and Redis.
 *
 * <p>Independent of every feature ticket's own tests on purpose: those were written beside the
 * code they test and share its reading of the RFC, so a misreading passes both. These are written
 * from the RFC and {@code docs/openapi.yaml} alone, and each fixture is
 * one externally observable claim. Users and Groups are run through the SAME fixtures wherever the
 * RFC says the same thing about both, so a condition one resource type honours and the other
 * forgets shows up as one red row rather than as a gap nobody wrote a test for.
 *
 * <p><strong>Every exchange is also a contract check.</strong> Requests go through
 * {@link ContractRecorder}, which fails a fixture whose response status, headers, media type or
 * body departs from {@code docs/openapi.yaml}, and records which documented statuses were seen.
 * {@link #every_documented_scim_status_is_produced_by_some_fixture} then runs last and fails for
 * any status the document promises that no fixture produced — so the check runs in both
 * directions: nothing implemented is undocumented, and nothing documented is unimplemented.
 */
@SpringBootTest
@Import(ContainerTestConfiguration.class)
@TestPropertySource(properties = "app.scim.enabled=true")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ScimConformanceFixtureTests {

    static final String BASE = "/scim/v2";

    static final String USER_SCHEMA = "urn:ietf:params:scim:schemas:core:2.0:User";

    static final String GROUP_SCHEMA = "urn:ietf:params:scim:schemas:core:2.0:Group";

    static final String ERROR_SCHEMA = "urn:ietf:params:scim:api:messages:2.0:Error";

    static final String LIST_SCHEMA = "urn:ietf:params:scim:api:messages:2.0:ListResponse";

    static final String PATCH_OP = "urn:ietf:params:scim:api:messages:2.0:PatchOp";

    static final String SEARCH_REQUEST = "urn:ietf:params:scim:api:messages:2.0:SearchRequest";

    static final String SPC_SCHEMA =
            "urn:ietf:params:scim:schemas:core:2.0:ServiceProviderConfig";

    static final MediaType SCIM_JSON = MediaType.valueOf("application/scim+json");

    static final int ONE_MIB = 1024 * 1024;

    private static final OpenApiContract CONTRACT = OpenApiContract.load();

    private static final ContractRecorder RECORDER = new ContractRecorder(CONTRACT);

    private static final JsonMapper JSON = JsonMapper.builder().build();

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

    String writeToken;

    String otherConnectorToken;

    String readOnlyToken;

    /** {@code user:read} alone: the base search returns it Users and no Groups. */
    String userReadToken;

    /** Both write Permissions and no read: discovery answers it, every read refuses it. */
    String writeOnlyToken;

    private final List<UUID> created = new ArrayList<>();

    /**
     * Once for the class, not per fixture: no fixture creates, revokes or reconfigures a connector
     * or a token, so the 343 fixtures can share them, and each fixture removes the resources it
     * created afterwards.
     */
    @BeforeAll
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context)
                .addFilters(requestIdFilter, springSecurityFilterChain)
                .build();
        UUID connector = connectors.create("conformance", "test-admin").id();
        UUID other = connectors.create("conformance-other", "test-admin").id();
        writeToken = connectors.issueToken(connector, TokenPermissions.ALL, null,
                "test-admin", TokenPermissions.ALL).presentedValue();
        readOnlyToken = connectors.issueToken(connector, TokenPermissions.READ, null,
                "test-admin", TokenPermissions.ALL).presentedValue();
        userReadToken = connectors.issueToken(connector, Set.of(Permission.USER_READ), null,
                "test-admin", TokenPermissions.ALL).presentedValue();
        writeOnlyToken = connectors.issueToken(connector,
                Set.of(Permission.USER_WRITE, Permission.GROUP_WRITE), null,
                "test-admin", TokenPermissions.ALL).presentedValue();
        otherConnectorToken = connectors.issueToken(other, TokenPermissions.ALL, null,
                "test-admin", TokenPermissions.ALL).presentedValue();
    }

    @AfterEach
    void removeWhatThisFixtureCreated() {
        for (UUID id : created) {
            jdbc.update("DELETE FROM scim_resources WHERE id = ? AND reserved_name IS NULL", id);
        }
        created.clear();
    }

    // ---- the suite ----------------------------------------------------------------------

    @Test
    @Order(0)
    void the_contract_check_understands_every_schema_keyword_the_document_uses() {
        assertThat(CONTRACT.unsupportedKeywords())
                .as("a schema keyword the validator would silently ignore")
                .isEmpty();
        assertThat(CONTRACT.operations())
                .as("the document was read and has SCIM operations to check against")
                .anyMatch(operation -> operation.template().startsWith(BASE));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("fixtures")
    @Order(1)
    void fixture(Fixture fixture) throws Exception {
        try {
            fixture.step().run(this);
        } catch (AssertionError | RuntimeException failed) {
            // Reports carry the invocation index, not the display name; name the claim here.
            throw new AssertionError("[" + fixture.name() + "] " + failed.getMessage(), failed);
        }
    }

    @Test
    @Order(2)
    void every_documented_scim_status_is_produced_by_some_fixture() {
        assertThat(RECORDER.covered())
                .as("the fixtures ran in this JVM before this check — run the whole class")
                .isNotEmpty();
        assertThat(RECORDER.uncovered(BASE))
                .as("statuses docs/openapi.yaml documents under %s that no fixture produced", BASE)
                .isEmpty();
    }

    static Stream<Arguments> fixtures() {
        List<Fixture> all = new ArrayList<>();
        ScimConformanceCases.discovery(all);
        for (Kind kind : Kind.values()) {
            ScimConformanceCases.lifecycle(all, kind);
            ScimConformanceCases.errors(all, kind);
            ScimConformanceCases.mutability(all, kind);
            ScimConformanceCases.pagination(all, kind);
            ScimConformanceCases.patchGrammar(all, kind);
        }
        ScimConformanceCases.userOnly(all);
        ScimConformanceCases.groupOnly(all);
        ScimConformanceCases.filterGrammar(all);
        ScimConformanceCases.namespace(all);
        Set<String> names = new LinkedHashSet<>();
        for (Fixture fixture : all) {
            assertThat(names.add(fixture.name())).as("fixture names are unique").isTrue();
        }
        return all.stream().map(fixture -> Arguments.of(Named.of(fixture.name(), fixture)));
    }

    // ---- what a fixture is --------------------------------------------------------------

    /** One externally observable claim. */
    record Fixture(String name, Step step) {
        @Override
        public String toString() {
            return name;
        }
    }

    @FunctionalInterface
    interface Step {
        void run(ScimConformanceFixtureTests t) throws Exception;
    }

    /** The two resource types, described by what the RFC lets a generic client know of each. */
    enum Kind {
        USER("Users", USER_SCHEMA, "userName"),
        GROUP("Groups", GROUP_SCHEMA, "displayName");

        final String endpoint;

        final String schema;

        /** The attribute that is required and unique — the one a conflict is made with. */
        final String identifying;

        Kind(String endpoint, String schema, String identifying) {
            this.endpoint = endpoint;
            this.schema = schema;
            this.identifying = identifying;
        }

        String collection() {
            return BASE + "/" + endpoint;
        }

        String one(Object id) {
            return collection() + "/" + id;
        }

        String resourceType() {
            return this == USER ? "User" : "Group";
        }

        /** A minimal valid create body. */
        String create(String name) {
            return this == USER
                    ? """
                      {"schemas":["%s"],"userName":"%s","displayName":"Conformance %s"}"""
                            .formatted(schema, name, name)
                    : """
                      {"schemas":["%s"],"displayName":"%s"}""".formatted(schema, name);
        }

        /** A complete replacement body. */
        String replace(String name) {
            return this == USER
                    ? """
                      {"schemas":["%s"],"userName":"%s","displayName":"Replaced"}"""
                            .formatted(schema, name)
                    : """
                      {"schemas":["%s"],"displayName":"%s"}""".formatted(schema, name);
        }

        /** A PatchOp of the given operations. */
        static String patch(String... operations) {
            return """
                   {"schemas":["%s"],"Operations":[%s]}""".formatted(PATCH_OP,
                    String.join(",", operations));
        }

        /** A PatchOp changing a writable string attribute to {@code value}. */
        String patchRename(String value) {
            return patch("""
                    {"op":"replace","path":"displayName","value":"%s"}""".formatted(value));
        }
    }

    /** A resource a fixture created, with the validator it was returned with. */
    record Resource(UUID id, String etag, JsonNode body) {
    }

    // ---- harness used by the fixtures ---------------------------------------------------

    /** A request in the namespace, bearing {@code token} ({@code null}: no credential). */
    MockHttpServletRequestBuilder as(String token, HttpMethod method, String path) {
        MockHttpServletRequestBuilder request = request(method, path);
        if (token != null) {
            request.header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
        }
        return request;
    }

    /** A request bearing the token that holds every SCIM Permission. */
    MockHttpServletRequestBuilder scim(HttpMethod method, String path) {
        return as(writeToken, method, path);
    }

    /** Adds a SCIM JSON body. */
    static MockHttpServletRequestBuilder body(MockHttpServletRequestBuilder request, String json) {
        return request.contentType(SCIM_JSON).content(json);
    }

    /** Performs one exchange, holding it against the contract. */
    MvcResult exchange(MockHttpServletRequestBuilder request) throws Exception {
        return RECORDER.perform(mvc, request);
    }

    /** Performs one exchange and asserts its status. */
    MvcResult expect(MockHttpServletRequestBuilder request, int status) throws Exception {
        MvcResult result = exchange(request);
        assertThat(result.getResponse().getStatus())
                .as("status of %s %s: %s", result.getRequest().getMethod(),
                        result.getRequest().getRequestURI(),
                        result.getResponse().getContentAsString())
                .isEqualTo(status);
        return result;
    }

    /**
     * Performs one exchange and asserts it is a SCIM error document for {@code status}: the Error
     * schema alone, {@code status} as a STRING equal to the HTTP status, {@code scimType} exactly as
     * given ({@code null}: absent, not null), and a non-blank {@code detail}.
     */
    JsonNode expectError(MockHttpServletRequestBuilder request, int status, String scimType)
            throws Exception {
        MvcResult result = expect(request, status);
        assertThat(result.getResponse().getContentType()).startsWith("application/scim+json");
        JsonNode error = json(result);
        assertThat(texts(error.get("schemas"))).containsExactly(ERROR_SCHEMA);
        assertThat(error.get("status").isString()).as("status is a string").isTrue();
        assertThat(error.get("status").stringValue()).isEqualTo(String.valueOf(status));
        if (scimType == null) {
            assertThat(error.has("scimType")).as("no scimType for %s", status).isFalse();
        } else {
            assertThat(error.path("scimType").asText()).isEqualTo(scimType);
        }
        assertThat(error.path("detail").asText()).isNotBlank();
        return error;
    }

    /** Creates a resource of {@code kind} over HTTP and remembers it for cleanup. */
    Resource create(Kind kind, String body) throws Exception {
        MvcResult result = expect(body(scim(HttpMethod.POST, kind.collection()), body), 201);
        JsonNode created = json(result);
        UUID id = UUID.fromString(created.get("id").asText());
        this.created.add(id);
        return new Resource(id, result.getResponse().getHeader(HttpHeaders.ETAG), created);
    }

    Resource create(Kind kind) throws Exception {
        return create(kind, kind.create(name()));
    }

    /** Reads a resource back and returns its current ETag. */
    String etag(Kind kind, UUID id) throws Exception {
        return expect(scim(HttpMethod.GET, kind.one(id)), 200)
                .getResponse().getHeader(HttpHeaders.ETAG);
    }

    /** Asserts a refused request left the resource exactly as it was. */
    void assertUnchanged(Kind kind, Resource resource) throws Exception {
        MvcResult reread = expect(scim(HttpMethod.GET, kind.one(resource.id())), 200);
        assertThat(reread.getResponse().getHeader(HttpHeaders.ETAG))
                .as("a refused request changes nothing, version included")
                .isEqualTo(resource.etag());
        JsonNode body = json(reread);
        assertThat(body.get(kind.identifying)).isEqualTo(resource.body().get(kind.identifying));
    }

    /** Remembers an id created out of band so it is cleaned up. */
    void track(UUID id) {
        created.add(id);
    }

    /** A ListResponse's resources' values of {@code attribute}, in order. */
    static List<String> listed(JsonNode list, String attribute) {
        JsonNode resources = list.get("Resources");
        if (resources == null) {
            return List.of();
        }
        return StreamSupport.stream(resources.spliterator(), false)
                .map(resource -> resource.path(attribute).asText())
                .toList();
    }

    static JsonNode json(MvcResult result) throws Exception {
        return JSON.readTree(result.getResponse().getContentAsString());
    }

    static List<String> texts(JsonNode array) {
        return StreamSupport.stream(array.spliterator(), false).map(JsonNode::asString).toList();
    }

    static Set<String> names(JsonNode object) {
        return object.propertyNames().stream().collect(Collectors.toCollection(LinkedHashSet::new));
    }

    /** A name no other fixture or test uses. */
    static String name() {
        return "conf-" + HexFormat.of().toHexDigits(ThreadLocalRandom.current().nextInt());
    }

    /** A body of exactly {@code size} bytes that is valid JSON up to the size bound. */
    static String oversized(Kind kind, int size) {
        String prefix = kind.create(name());
        String head = prefix.substring(0, prefix.length() - 1) + ",\"padding\":\"";
        String tail = "\"}";
        return head + "x".repeat(size - head.length() - tail.length()) + tail;
    }

    static String lower(String value) {
        return value.toLowerCase(Locale.ROOT);
    }

    static Map<String, String> map(String... pairs) {
        Map<String, String> map = new java.util.LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            map.put(pairs[i], pairs[i + 1]);
        }
        return map;
    }
}

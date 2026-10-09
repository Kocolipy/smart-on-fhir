package com.example.backend.scim;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.example.backend.ContainerTestConfiguration;
import com.example.backend.TokenPermissions;
import com.example.backend.observability.RequestIdFilter;
import com.example.backend.scim.application.ConnectorAdministrationService;
import jakarta.servlet.Filter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
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
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * The ticket's demo oracle: a parameterized RFC 7644 fixture set run over the real filter chain
 * against real PostgreSQL, seeded with Users and Groups — including a DELETED User — and every
 * fixture's audit event read back with SQL.
 *
 * <p><strong>Scoping on a shared database.</strong> Other test classes leave resources in the
 * same schema, so every fixture is run as {@code externalId pr and (<fixture>)} under a connector
 * created for this class. {@code externalId} is connector-scoped: only the resources this class
 * created carry an alias the connector can see, so the conjunction confines every result to this
 * seed without deleting anything that is not this class's own.
 */
@SpringBootTest
@Import(ContainerTestConfiguration.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ScimQueryProtocolIntegrationTests {

    private static final String BASE = "/scim/v2";

    private static final String BASE_URI = "http://localhost" + BASE;

    private static final String USERS = BASE + "/Users";

    private static final String GROUPS = BASE + "/Groups";

    private static final String USER_SCHEMA = "urn:ietf:params:scim:schemas:core:2.0:User";

    private static final String GROUP_SCHEMA = "urn:ietf:params:scim:schemas:core:2.0:Group";

    private static final String SEARCH_REQUEST =
            "urn:ietf:params:scim:api:messages:2.0:SearchRequest";

    private static final MediaType SCIM_JSON = MediaType.valueOf("application/scim+json");

    /** Confines a fixture to this class's seed; see the class comment. */
    private static final String SCOPE = "externalId pr";

    private static final String LATEST_EVENT = """
            SELECT operation, resource_type, result_count, filter_shape, outcome, subject_id,
                   http_method, http_path
              FROM audit_events
             WHERE actor_id = ?
             ORDER BY occurred_at DESC
             LIMIT 1""";

    /** The latest event's columns a bulk read must leave empty. */
    private static final String LATEST_EVENT_REST = """
            SELECT resource_id, changed_paths, error_code, outcome
              FROM audit_events
             WHERE actor_id = ?
             ORDER BY occurred_at DESC
             LIMIT 1""";

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

    private UUID connectorId;

    private String writeToken;

    private String readToken;

    /** Seeded resource name -> id; users by userName, groups by displayName. */
    private final Map<String, UUID> ids = new HashMap<>();

    /** Every resource this class created, for the cleanup; nothing else is deleted. */
    private final List<UUID> created = new ArrayList<>();

    @BeforeAll
    void seed() throws Exception {
        mvc = MockMvcBuilders.webAppContextSetup(context)
                .addFilters(requestIdFilter, springSecurityFilterChain)
                .build();
        connectorId = connectors.create("Query protocol", "test-admin").id();
        writeToken = connectors.issueToken(
                connectorId, TokenPermissions.ALL, null, "test-admin", TokenPermissions.ALL).presentedValue();
        readToken = connectors.issueToken(
                connectorId, TokenPermissions.READ, null, "test-admin", TokenPermissions.ALL).presentedValue();

        user("""
                {"schemas":["%s"],"userName":"qp-alice","externalId":"ext-alice",
                 "displayName":"Alice Smith","locale":"en-GB","active":true,
                 "name":{"familyName":"Smith","givenName":"Alice"},
                 "emails":[{"value":"alice@work.example","type":"work","primary":true},
                           {"value":"alice@home.example","type":"home"}]}""");
        user("""
                {"schemas":["%s"],"userName":"qp-bob","externalId":"ext-bob",
                 "displayName":"bob jones","locale":"en-US","active":false,
                 "name":{"familyName":"Jones","givenName":"Bob"},
                 "emails":[{"value":"bob@work.example","type":"work"}]}""");
        user("""
                {"schemas":["%s"],"userName":"qp-Carol","externalId":"ext-carol",
                 "active":true,"name":{"familyName":"O'Brien","givenName":"Carol"}}""");
        user("""
                {"schemas":["%s"],"userName":"qp-dave","externalId":"ext-dave",
                 "displayName":"Dave 50%_off","active":true,"name":{"givenName":"Dave"},
                 "emails":[{"value":"dave@home.example","type":"home","primary":true}]}""");
        user("""
                {"schemas":["%s"],"userName":"qp-deleted","externalId":"ext-deleted",
                 "displayName":"Deleted Person","active":true}""");
        group("qp-Engineering", "ext-eng", "qp-alice", "qp-bob", "qp-deleted");
        group("qp-support", "ext-sup", "qp-Carol");
        group("qp-empty", "ext-empty");

        // Deleted AFTER joining a Group, so the membership is a real one the delete removed.
        MvcResult deleted = mvc.perform(asWriter(delete(USERS + "/" + ids.get("qp-deleted")))
                        .header(HttpHeaders.IF_MATCH, "*"))
                .andReturn();
        if (deleted.getResponse().getStatus() != 204) {
            String etag = mvc.perform(asWriter(get(USERS + "/" + ids.get("qp-deleted"))))
                    .andReturn().getResponse().getHeader(HttpHeaders.ETAG);
            MvcResult retry = mvc.perform(asWriter(delete(USERS + "/" + ids.get("qp-deleted")))
                            .header(HttpHeaders.IF_MATCH, etag))
                    .andReturn();
            assertThat(retry.getResponse().getStatus()).isEqualTo(204);
        }
    }

    @AfterAll
    void removeOnlyWhatThisClassCreated() {
        for (UUID id : created) {
            jdbc.update("DELETE FROM scim_resources WHERE id = ?", id);
        }
    }

    // ---- the RFC fixture set -------------------------------------------------------------------

    /**
     * {@code (endpoint, filter, expected names)}: the grammar fixture set. Expected names are
     * in the default order — Users by normalized {@code userName}, Groups by normalized
     * {@code displayName} — so the assertion checks order as well as membership.
     */
    Stream<Arguments> fixtures() {
        return Stream.of(
                // eq, and case rules: attribute names and operators are case-insensitive; a
                // caseExact=false attribute compares case-insensitively — every profile string,
                // as RFC 7643 declares them — and a caseExact one (an id) does not
                users("userName eq \"qp-alice\"", "qp-alice"),
                users("USERNAME EQ \"QP-ALICE\"", "qp-alice"),
                users("userName eq \"qp-carol\"", "qp-Carol"),
                users("displayName eq \"bob jones\"", "qp-bob"),
                users("displayName eq \"BOB JONES\"", "qp-bob"),
                users("emails.value eq \"ALICE@WORK.EXAMPLE\"", "qp-alice"),
                users("emails[type eq \"WORK\" and value sw \"BOB\"]", "qp-bob"),
                users("name.familyName eq \"o'brien\"", "qp-Carol"),
                users("externalId eq \"EXT-ALICE\""),
                // substring and ordering operators
                users("userName sw \"qp-a\"", "qp-alice"),
                users("userName ew \"OB\"", "qp-bob"),
                users("userName co \"aro\"", "qp-Carol"),
                users("userName gt \"qp-c\"", "qp-Carol", "qp-dave"),
                users("userName le \"qp-bob\"", "qp-alice", "qp-bob"),
                users("locale sw \"en\"", "qp-alice", "qp-bob"),
                // ne is not-eq, so a User with no value is not equal
                users("userName ne \"qp-alice\"", "qp-bob", "qp-Carol", "qp-dave"),
                users("displayName ne \"bob jones\"", "qp-alice", "qp-Carol", "qp-dave"),
                // complex sub-attributes, unqualified and schema-qualified
                users("name.familyName eq \"O'Brien\"", "qp-Carol"),
                users("urn:ietf:params:scim:schemas:core:2.0:User:name.givenName eq \"Dave\"",
                        "qp-dave"),
                users("urn:ietf:params:scim:schemas:core:2.0:User:userName sw \"qp-b\"", "qp-bob"),
                // booleans
                users("active eq false", "qp-bob"),
                users("active eq true", "qp-alice", "qp-Carol", "qp-dave"),
                users("active ne false", "qp-alice", "qp-Carol", "qp-dave"),
                // presence and null
                users("displayName pr", "qp-alice", "qp-bob", "qp-dave"),
                users("name.familyName pr", "qp-alice", "qp-bob", "qp-Carol"),
                users("name pr", "qp-alice", "qp-bob", "qp-Carol", "qp-dave"),
                users("displayName eq null", "qp-Carol"),
                users("displayName ne null", "qp-alice", "qp-bob", "qp-dave"),
                // multi-valued: a bare multi-valued complex attribute compares its value
                users("emails co \"@work.example\"", "qp-alice", "qp-bob"),
                users("emails.primary eq true", "qp-alice", "qp-dave"),
                users("emails pr", "qp-alice", "qp-bob", "qp-dave"),
                users("not (emails pr)", "qp-Carol"),
                users("emails.value ne \"bob@work.example\"", "qp-alice", "qp-Carol", "qp-dave"),
                // value paths bind every condition to ONE value; dotted paths do not
                users("emails[type eq \"home\" and value co \"alice\"]", "qp-alice"),
                users("emails[type eq \"work\" and value co \"home\"]"),
                users("emails.type eq \"work\" and emails.value co \"home\"", "qp-alice"),
                users("emails[type eq \"work\" or primary eq true]", "qp-alice", "qp-bob", "qp-dave"),
                users("emails[not (type eq \"work\")]", "qp-alice", "qp-dave"),
                // the read-only reverse membership view; a deleted member left no trace. The
                // label is case-insensitive like a Group's displayName; the id and $ref are not
                users("groups.display eq \"qp-Engineering\"", "qp-alice", "qp-bob"),
                users("groups[display sw \"QP-SUP\"]", "qp-Carol"),
                users("groups[display sw \"qp-sup\"]", "qp-Carol"),
                users("groups.value eq \"{qp-support}\"", "qp-Carol"),
                users("groups.type eq \"direct\"", "qp-alice", "qp-bob", "qp-Carol"),
                users("groups.$ref eq \"" + BASE_URI + "/Groups/{qp-support}\"", "qp-Carol"),
                // precedence: not > and > or, and parentheses override it
                users("userName eq \"qp-bob\" or userName eq \"qp-alice\" and active eq true",
                        "qp-alice", "qp-bob"),
                users("(userName eq \"qp-bob\" or userName eq \"qp-alice\") and active eq true",
                        "qp-alice"),
                users("not (userName eq \"qp-alice\") and not (userName eq \"qp-bob\")",
                        "qp-Carol", "qp-dave"),
                users("not (userName eq \"qp-alice\" or userName eq \"qp-bob\")",
                        "qp-Carol", "qp-dave"),
                // common attributes and meta
                users("id eq \"{qp-alice}\"", "qp-alice"),
                users("meta.resourceType eq \"User\"", "qp-alice", "qp-bob", "qp-Carol", "qp-dave"),
                users("meta.created gt \"2000-01-01T00:00:00Z\"",
                        "qp-alice", "qp-bob", "qp-Carol", "qp-dave"),
                users("meta.lastModified lt \"2000-01-01T00:00:00+08:00\""),
                users("meta.location eq \"" + BASE_URI + "/Users/{qp-dave}\"", "qp-dave"),
                users("meta pr", "qp-alice", "qp-bob", "qp-Carol", "qp-dave"),
                users("active pr", "qp-alice", "qp-bob", "qp-Carol", "qp-dave"),
                users("emails.type pr", "qp-alice", "qp-bob", "qp-dave"),
                users("emails[type pr]", "qp-alice", "qp-bob", "qp-dave"),
                users("externalId eq \"ext-bob\"", "qp-bob"),
                users("externalId eq \"EXT-BOB\""),
                // a deleted User is gone from every query
                users("userName eq \"qp-deleted\""),
                users("id eq \"{qp-deleted}\""),

                groups("displayName eq \"QP-ENGINEERING\"", "qp-Engineering"),
                groups("displayName sw \"qp-e\"", "qp-empty", "qp-Engineering"),
                groups("members pr", "qp-Engineering", "qp-support"),
                groups("not (members pr)", "qp-empty"),
                groups("members.value eq \"{qp-Carol}\"", "qp-support"),
                groups("members.value eq \"{qp-deleted}\""),
                groups("members[display eq \"Alice Smith\"]", "qp-Engineering"),
                groups("members.display eq \"qp-Carol\"", "qp-support"),
                groups("members.type eq \"User\"", "qp-Engineering", "qp-support"),
                groups("members.$ref co \"/Users/{qp-bob}\"", "qp-Engineering"),
                groups("urn:ietf:params:scim:schemas:core:2.0:Group:displayName ew \"PORT\"",
                        "qp-support"),
                groups("meta.resourceType eq \"Group\"", "qp-empty", "qp-Engineering", "qp-support"),
                groups("meta pr", "qp-empty", "qp-Engineering", "qp-support"),
                groups("meta.location eq \"" + BASE_URI + "/Groups/{qp-support}\"", "qp-support"),
                groups("displayName pr and not (displayName eq \"qp-empty\")",
                        "qp-Engineering", "qp-support"));
    }

    @ParameterizedTest(name = "{0} {1}")
    @MethodSource("fixtures")
    void a_fixture_selects_exactly_its_expected_resources_in_order(
            String endpoint, String filter, List<String> expected) throws Exception {
        JsonNode page = body(mvc.perform(asWriter(get(endpoint)
                        .param("filter", scoped(filter))))
                .andReturn());

        assertThat(names(page)).containsExactlyElementsOf(expected);
        assertThat(page.get("totalResults").asInt()).isEqualTo(expected.size());
    }

    /**
     * The criterion's audit half, over the same fixtures: exactly one event per query, carrying
     * how many resources came back and the filter's shape — and not one literal from it.
     */
    @ParameterizedTest(name = "{0} {1}")
    @MethodSource("fixtures")
    void every_fixture_query_is_audited_once_with_its_count_and_shape(
            String endpoint, String filter, List<String> expected) throws Exception {
        long before = eventCount();

        mvc.perform(asWriter(get(endpoint).param("filter", scoped(filter)))).andReturn();

        assertThat(eventCount()).isEqualTo(before + 1);
        Map<String, Object> event = latestEvent();
        assertThat(event.get("operation"))
                .isEqualTo(endpoint.equals(USERS) ? "SCIM_USER_LIST" : "SCIM_GROUP_LIST");
        assertThat(event.get("resource_type")).isEqualTo(endpoint.equals(USERS) ? "User" : "Group");
        assertThat(event.get("result_count")).isEqualTo(expected.size());
        assertThat(event.get("outcome")).isEqualTo("SUCCESS");
        assertThat(event.get("subject_id")).isNull();
        String shape = (String) event.get("filter_shape");
        assertThat(shape).startsWith("(externalId pr and ");
        for (String literal : literals(resolve(filter))) {
            assertThat(shape).as("the shape of %s", filter).doesNotContain(literal);
        }
        assertThat(shape).doesNotContain("\"");
    }

    /** A handful of exact shapes, so the rendering itself is pinned, not just its redaction. */
    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "userName eq \"qp-alice\"                               | (externalId pr and userName eq ?)",
            "USERNAME EQ \"QP-ALICE\"                               | (externalId pr and userName eq ?)",
            "emails[type eq \"home\" and value co \"x\"]            | (externalId pr and emails[(type eq ? and value co ?)])",
            "not (active eq true) or name.familyName pr             | (externalId pr and (not (active eq ?) or name.familyName pr))",
            "urn:ietf:params:scim:schemas:core:2.0:User:userName sw \"a\" | (externalId pr and userName sw ?)",
            "emails co \"x\"                                        | (externalId pr and emails.value co ?)",
            "displayName eq null                                    | (externalId pr and displayName eq ?)"})
    void the_recorded_shape_is_canonical_and_value_free(String filter, String shape) throws Exception {
        mvc.perform(asWriter(get(USERS).param("filter", scoped(filter.trim())))).andReturn();

        assertThat(latestEvent().get("filter_shape")).isEqualTo(shape.trim());
    }

    // ---- refusals --------------------------------------------------------------------------------

    /**
     * Every one of these is a {@code 400 invalidFilter}, and none of them ran: the trail gains no
     * bulk-read event, because nothing was read.
     */
    @ParameterizedTest
    @ValueSource(strings = {
            "password eq \"secret\"",
            "password pr",
            "PassWord ne \"x\"",
            "urn:ietf:params:scim:schemas:core:2.0:User:password eq \"x\"",
            "userName eq",
            "userName",
            "userName xx \"a\"",
            "(userName eq \"a\"",
            "userName eq \"a\")",
            "userName eq \"unterminated",
            "userName eq bjensen",
            "nickName eq \"a\"",
            "members pr",
            "name eq \"x\"",
            "meta eq \"x\"",
            "active gt true",
            "active eq \"true\"",
            "userName eq true",
            "userName eq 5",
            "meta.created co \"2020\"",
            "meta.created gt \"yesterday\"",
            "emails[type eq \"work\"",
            "emails[nope eq \"x\"]",
            "userName[value eq \"x\"]",
            "emails[type eq \"work\"][value eq \"x\"]",
            "not userName eq \"a\"",
            "userName eq \"a\" and",
            "urn:ietf:params:scim:schemas:core:2.0:Group:displayName eq \"x\"",
            "urn:example:custom:attr eq \"x\"",
            "userName lt null",
            "userName co \"\\u0000\"",
            ""})
    void a_filter_this_service_does_not_evaluate_is_refused_without_running(String filter)
            throws Exception {
        long before = eventCount();

        MvcResult refused = mvc.perform(asWriter(get(USERS).param("filter", filter))).andReturn();

        assertRefusal(refused, 400, "invalidFilter");
        assertThat(eventCount()).as("a refused query read nothing and records nothing")
                .isEqualTo(before);
        if (filter.contains("\"")) {
            // Only distinctive literals: a one-letter value could occur in any sentence.
            for (String literal : literals(filter)) {
                if (literal.length() > 3) {
                    assertThat(body(refused).get("detail").asText()).doesNotContain(literal);
                }
            }
        }
    }

    static Stream<Arguments> oversizedFilters() {
        String deep = "(".repeat(21) + "userName eq \"a\"" + ")".repeat(21);
        StringBuilder wide = new StringBuilder("userName eq \"a0\"");
        for (int i = 1; i <= 50; i++) {
            wide.append(" or userName eq \"a").append(i).append('"');
        }
        String longText = "userName eq \"" + "x".repeat(8 * 1024) + "\"";
        return Stream.of(
                Arguments.of("too deep (21 levels)", deep),
                Arguments.of("too wide (101 nodes)", wide.toString()),
                Arguments.of("too long (over 8 KiB)", longText));
    }

    /** The bounded-parsing criterion: each limit refuses, and refuses before any read. */
    @ParameterizedTest(name = "{0}")
    @MethodSource("oversizedFilters")
    void an_oversized_filter_is_refused_without_running(String why, String filter)
            throws Exception {
        long before = eventCount();

        MvcResult refused = mvc.perform(asWriter(post(USERS + "/.search")
                        .contentType(SCIM_JSON)
                        .content(searchBody(Map.of("filter", filter)))))
                .andReturn();

        assertRefusal(refused, 400, "invalidFilter");
        assertThat(eventCount()).isEqualTo(before);
    }

    /**
     * Just inside the depth and width limits is accepted, so the refusals above are the limits
     * and not luck. Unscoped, because the scoping conjunction would itself spend a level and two
     * nodes; the exact boundaries (20 levels, 100 nodes) are pinned by {@code ScimFilterParserTests}.
     */
    @Test
    void a_filter_at_each_limit_is_accepted() throws Exception {
        String deepest = "(".repeat(19) + "userName eq \"qp-alice\"" + ")".repeat(19);
        StringBuilder widest = new StringBuilder("userName eq \"qp-alice\"");
        for (int i = 1; i < 50; i++) {
            widest.append(" or userName eq \"qp-none-").append(i).append('"');
        }
        for (String filter : List.of(deepest, widest.toString())) {
            JsonNode page = body(mvc.perform(asWriter(get(USERS).param("filter", filter)))
                    .andReturn());
            assertThat(names(page)).containsExactly("qp-alice");
        }
    }

    /**
     * The fuzz criterion: values carrying SQL metacharacters are compared as the literal text
     * they are. Each matches exactly the resources whose value contains that text, nothing is
     * injected, and the tables are intact afterwards.
     */
    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "displayName co \"50%_\"                          | qp-dave",
            "displayName co \"%\"                             | qp-dave",
            "displayName co \"_\"                             | qp-dave",
            "displayName sw \"%\"                             | ",
            "name.familyName eq \"O'Brien\"                   | qp-Carol",
            "name.familyName eq \"O''Brien\"                  | ",
            "userName eq \"x' OR '1'='1\"                     | ",
            "userName eq \"qp-alice' --\"                     | ",
            "userName co \"'; DROP TABLE scim_users; --\"     | ",
            "userName eq \"\\\\\"                             | ",
            "displayName co \"\\\\%\"                         | ",
            "userName eq \":p0\"                              | ",
            "userName eq \"$1\"                               | ",
            "userName eq \"?\"                                | "})
    void sql_metacharacters_in_a_value_are_matched_as_literal_text(String filter, String expected)
            throws Exception {
        long users = jdbc.queryForObject("SELECT count(*) FROM scim_users", Long.class);

        MvcResult result = mvc.perform(asWriter(post(USERS + "/.search")
                        .contentType(SCIM_JSON)
                        .content(searchBody(Map.of("filter", scoped(filter.trim()))))))
                .andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        assertThat(names(body(result)))
                .containsExactlyElementsOf(expected == null ? List.of() : List.of(expected.trim()));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM scim_users", Long.class))
                .isEqualTo(users);
    }

    // ---- sorting -------------------------------------------------------------------------------

    @ParameterizedTest(name = "sortBy={0} sortOrder={1}")
    @CsvSource(delimiter = '|', nullValues = "-", value = {
            // caseExact=false sorts case-insensitively: Carol between bob and dave
            "userName          | -          | qp-alice,qp-bob,qp-Carol,qp-dave",
            "userName          | descending | qp-dave,qp-Carol,qp-bob,qp-alice",
            "USERNAME          | DESCENDING | qp-dave,qp-Carol,qp-bob,qp-alice",
            // displayName too, now case-insensitive ("bob jones" between Alice and Dave), and
            // Carol has no displayName: last ascending...
            "displayName       | ascending  | qp-alice,qp-bob,qp-dave,qp-Carol",
            // ...and first descending
            "displayName       | descending | qp-Carol,qp-dave,qp-bob,qp-alice",
            // multi-valued: the primary value, else the first; none sorts as missing
            "emails.value      | -          | qp-alice,qp-bob,qp-dave,qp-Carol",
            "emails.value      | descending | qp-Carol,qp-dave,qp-bob,qp-alice",
            "name.familyName   | -          | qp-bob,qp-Carol,qp-alice,qp-dave",
            "urn:ietf:params:scim:schemas:core:2.0:User:name.givenName | descending | qp-dave,qp-Carol,qp-bob,qp-alice"})
    void a_sort_orders_the_page_with_missing_values_last_ascending_and_first_descending(
            String sortBy, String sortOrder, String expected) throws Exception {
        MockHttpServletRequestBuilder request = get(USERS)
                .param("filter", SCOPE)
                .param("sortBy", sortBy);
        if (sortOrder != null) {
            request.param("sortOrder", sortOrder);
        }
        JsonNode page = body(mvc.perform(asWriter(request)).andReturn());

        assertThat(names(page)).containsExactly(expected.split(","));
    }

    /**
     * A multi-valued sort key is the PRIMARY value where one is marked, and the first value only
     * when none is: each of the first two Users' primary email is NOT its first array element,
     * and the third's first email is not its smallest, so a first-element, a minimum or a
     * last-element rule each produces a different order from the one asserted. The Users are
     * this test's own and are removed after.
     */
    @Test
    void a_multi_valued_sort_prefers_the_primary_value_then_the_first() throws Exception {
        List<UUID> mine = new ArrayList<>();
        try {
            for (String emails : List.of(
                    // key a1 (primary), though z1 comes first
                    "[{\"value\":\"z1@first.example\"},{\"value\":\"a1@primary.example\",\"primary\":true}]",
                    // key z2 (primary), though a2 comes first
                    "[{\"value\":\"a2@first.example\"},{\"value\":\"z2@primary.example\",\"primary\":true}]",
                    // no primary: key m3 (first), though 03 is smaller
                    "[{\"value\":\"m3@first.example\"},{\"value\":\"03@second.example\"}]")) {
                String userName = "qp-sortkey-" + (mine.size() + 1);
                MvcResult result = mvc.perform(asWriter(post(USERS)).contentType(SCIM_JSON)
                                .content("""
                                        {"schemas":["%s"],"userName":"%s","externalId":"sortkey",
                                         "emails":%s}""".formatted(USER_SCHEMA, userName, emails)))
                        .andReturn();
                assertThat(result.getResponse().getStatus()).as(userName).isEqualTo(201);
                mine.add(UUID.fromString(body(result).get("id").asText()));
            }

            for (String order : List.of("ascending", "descending")) {
                JsonNode page = body(mvc.perform(asWriter(get(USERS)
                                .param("filter", "externalId eq \"sortkey\"")
                                .param("sortBy", "emails.value")
                                .param("sortOrder", order)))
                        .andReturn());

                List<String> expected = List.of("qp-sortkey-1", "qp-sortkey-3", "qp-sortkey-2");
                assertThat(names(page)).as(order).containsExactlyElementsOf(
                        order.equals("ascending") ? expected : expected.reversed());
            }
        } finally {
            for (UUID id : mine) {
                jdbc.update("DELETE FROM scim_resources WHERE id = ?", id);
            }
        }
    }

    /**
     * A multi-valued sub-attribute's presence is a question about ANY value at the top level, and
     * about THE value in scope inside a value path. Seeding an email with no type beside one
     * with a type is what tells the two apart. The Users are this test's own and are removed
     * after.
     */
    @Test
    void a_sub_attributes_presence_is_per_value_inside_a_value_path() throws Exception {
        List<UUID> mine = new ArrayList<>();
        try {
            for (String emails : List.of(
                    "[{\"value\":\"a@work.example\",\"type\":\"work\"},{\"value\":\"b@untyped.example\"}]",
                    "[{\"value\":\"c@untyped.example\"}]")) {
                String userName = "qp-presence-" + (mine.size() + 1);
                MvcResult result = mvc.perform(asWriter(post(USERS)).contentType(SCIM_JSON)
                                .content("""
                                        {"schemas":["%s"],"userName":"%s","externalId":"presence",
                                         "emails":%s}""".formatted(USER_SCHEMA, userName, emails)))
                        .andReturn();
                assertThat(result.getResponse().getStatus()).as(userName).isEqualTo(201);
                mine.add(UUID.fromString(body(result).get("id").asText()));
            }
            Map<String, List<String>> expected = new LinkedHashMap<>();
            expected.put("emails.type pr", List.of("qp-presence-1"));
            expected.put("emails[type pr and value co \"untyped\"]", List.of());
            expected.put("emails[value co \"untyped\" and not (type pr)]",
                    List.of("qp-presence-1", "qp-presence-2"));
            expected.put("emails[type pr]", List.of("qp-presence-1"));

            for (Map.Entry<String, List<String>> query : expected.entrySet()) {
                JsonNode page = body(mvc.perform(asWriter(get(USERS)
                                .param("filter", "externalId eq \"presence\" and " + query.getKey())))
                        .andReturn());

                assertThat(names(page)).as(query.getKey()).containsExactlyElementsOf(query.getValue());
            }
        } finally {
            for (UUID id : mine) {
                jdbc.update("DELETE FROM scim_resources WHERE id = ?", id);
            }
        }
    }

    /**
     * The base search's own ListResponse envelope, which no per-type endpoint renders for it: the
     * message schema, the page it answers, and no Resources member on an empty page.
     */
    @Test
    void the_base_search_answers_with_a_complete_list_response() throws Exception {
        JsonNode page = body(mvc.perform(asWriter(post(BASE + "/.search").contentType(SCIM_JSON)
                        .content(searchBody(Map.of("filter", SCOPE, "startIndex", 2, "count", 3)))))
                .andReturn());
        JsonNode empty = body(mvc.perform(asWriter(post(BASE + "/.search").contentType(SCIM_JSON)
                        .content(searchBody(Map.of("filter", SCOPE, "count", 0)))))
                .andReturn());

        assertThat(page.get("schemas").toString())
                .isEqualTo("[\"urn:ietf:params:scim:api:messages:2.0:ListResponse\"]");
        assertThat(page.get("totalResults").asInt()).isEqualTo(7);
        assertThat(page.get("startIndex").asInt()).isEqualTo(2);
        assertThat(page.get("itemsPerPage").asInt()).isEqualTo(3);
        assertThat(page.get("Resources").size()).isEqualTo(3);
        assertThat(empty.get("totalResults").asInt()).isEqualTo(7);
        assertThat(empty.get("itemsPerPage").asInt()).isZero();
        assertThat(empty.has("Resources")).isFalse();
    }

    /**
     * The tie-breaker: resources with equal sort keys come back in id order, both ways, so
     * stateless paging over a tie returns each resource exactly once. {@code active} ties three
     * Users; {@code emails.type} ties alice and bob, whose primary-or-first email is a work one.
     */
    @ParameterizedTest
    @CsvSource({"active,ascending", "active,descending", "emails.type,ascending"})
    void resources_with_equal_sort_keys_are_ordered_by_id(String sortBy, String sortOrder)
            throws Exception {
        JsonNode page = body(mvc.perform(asWriter(get(USERS)
                        .param("filter", SCOPE)
                        .param("sortBy", sortBy)
                        .param("sortOrder", sortOrder)))
                .andReturn());

        List<String> names = names(page);
        List<String> ties = sortBy.equals("active")
                ? List.of("qp-alice", "qp-Carol", "qp-dave")
                : List.of("qp-alice", "qp-bob");
        List<String> tiedInResponse = names.stream().filter(ties::contains).toList();
        List<String> byId = ties.stream()
                .sorted((a, b) -> ids.get(a).toString().compareTo(ids.get(b).toString()))
                .toList();
        assertThat(tiedInResponse).containsExactlyElementsOf(byId);
        if (sortBy.equals("active")) {
            assertThat(names.getFirst())
                    .isEqualTo(sortOrder.equals("ascending") ? "qp-bob" : byId.getFirst());
        } else {
            assertThat(names).first().isEqualTo("qp-dave");
            assertThat(names).last().isEqualTo("qp-Carol");
        }
    }

    /** Stateless paging over a tied sort key visits every resource exactly once. */
    @Test
    void paging_one_at_a_time_over_a_tied_sort_visits_each_resource_once() throws Exception {
        List<String> visited = new ArrayList<>();
        for (int start = 1; start <= 4; start++) {
            JsonNode page = body(mvc.perform(asWriter(get(USERS)
                            .param("filter", SCOPE)
                            .param("sortBy", "active")
                            .param("startIndex", String.valueOf(start))
                            .param("count", "1")))
                    .andReturn());
            visited.addAll(names(page));
        }
        assertThat(visited).containsExactlyInAnyOrder("qp-alice", "qp-bob", "qp-Carol", "qp-dave");
    }

    @Test
    void groups_sort_by_their_case_insensitive_display_name() throws Exception {
        JsonNode page = body(mvc.perform(asWriter(get(GROUPS)
                        .param("filter", SCOPE)
                        .param("sortBy", "displayName")
                        .param("sortOrder", "descending")))
                .andReturn());

        assertThat(names(page)).containsExactly("qp-support", "qp-Engineering", "qp-empty");
    }

    /** A complex attribute needs a sub-attribute; the password and unknown paths are not sortable. */
    @ParameterizedTest
    @CsvSource({
            "sortBy,name", "sortBy,emails", "sortBy,meta", "sortBy,groups", "sortBy,password",
            "sortBy,nickName", "sortBy,members.value", "sortOrder,upwards"})
    void an_unsortable_request_is_refused_as_an_invalid_value(String parameter, String value)
            throws Exception {
        long before = eventCount();

        MockHttpServletRequestBuilder request = get(USERS).param(parameter, value);
        if (parameter.equals("sortOrder")) {
            request.param("sortBy", "userName");
        }
        MvcResult refused = mvc.perform(asWriter(request)).andReturn();

        assertRefusal(refused, 400, "invalidValue");
        assertThat(eventCount()).isEqualTo(before);
    }

    // ---- pagination ----------------------------------------------------------------------------

    @ParameterizedTest(name = "startIndex={0} count={1}")
    @CsvSource(nullValues = "-", value = {
            // out-of-range start clamps to the minimum
            "0,   -,  1, qp-alice;qp-bob;qp-Carol;qp-dave",
            "-7,  2,  1, qp-alice;qp-bob",
            "3,   -,  3, qp-Carol;qp-dave",
            "99999999999, 5, 2147483647, ''",
            // a negative count clamps to zero, and zero still reports the total
            "-, -1,   1, ''",
            "-, 0,    1, ''",
            "2, 1,    2, qp-bob",
            "-, 99999999999, 1, qp-alice;qp-bob;qp-Carol;qp-dave"})
    void paging_parameters_are_coerced_and_the_total_is_always_reported(
            String startIndex, String count, int expectedStart, String expected) throws Exception {
        MockHttpServletRequestBuilder request = get(USERS).param("filter", SCOPE);
        if (startIndex != null) {
            request.param("startIndex", startIndex);
        }
        if (count != null) {
            request.param("count", count);
        }
        JsonNode page = body(mvc.perform(asWriter(request)).andReturn());

        List<String> names = expected.isEmpty() ? List.of() : List.of(expected.split(";"));
        assertThat(page.get("totalResults").asInt()).isEqualTo(4);
        assertThat(page.get("startIndex").asLong()).isEqualTo(expectedStart);
        assertThat(page.get("itemsPerPage").asInt()).isEqualTo(names.size());
        assertThat(names(page)).containsExactlyElementsOf(names);
        if (names.isEmpty()) {
            assertThat(page.has("Resources")).as("an empty page carries no Resources").isFalse();
        }
    }

    /**
     * Over-maximum count clamps to the configured maximum: with 201 matching Users a count of a
     * thousand returns 200 and reports 201. The 201 are this test's own and are removed after.
     */
    @Test
    void a_count_over_the_maximum_is_capped_at_the_maximum() throws Exception {
        List<UUID> mine = new ArrayList<>();
        try {
            for (int i = 0; i < 201; i++) {
                MvcResult result = mvc.perform(asWriter(post(USERS)).contentType(SCIM_JSON)
                                .content("""
                                        {"schemas":["%s"],"userName":"qp-bulk-%03d","externalId":"bulk"}"""
                                        .formatted(USER_SCHEMA, i)))
                        .andReturn();
                mine.add(UUID.fromString(body(result).get("id").asText()));
            }

            JsonNode page = body(mvc.perform(asWriter(get(USERS)
                            .param("filter", "externalId eq \"bulk\"")
                            .param("count", "1000")))
                    .andReturn());

            assertThat(page.get("totalResults").asInt()).isEqualTo(201);
            assertThat(page.get("itemsPerPage").asInt()).isEqualTo(200);
            assertThat(page.get("Resources").size()).isEqualTo(200);
            assertThat(latestEvent().get("result_count")).isEqualTo(200);
        } finally {
            for (UUID id : mine) {
                jdbc.update("DELETE FROM scim_resources WHERE id = ?", id);
            }
        }
    }

    // ---- projection ----------------------------------------------------------------------------

    @Test
    void inclusion_and_exclusion_together_are_refused_on_every_query_path() throws Exception {
        assertRefusal(mvc.perform(asWriter(get(USERS)
                        .param("attributes", "userName")
                        .param("excludedAttributes", "emails")))
                .andReturn(), 400, "invalidValue");
        for (String endpoint : List.of(USERS + "/.search", GROUPS + "/.search", BASE + "/.search")) {
            assertRefusal(mvc.perform(asWriter(post(endpoint)).contentType(SCIM_JSON)
                            .content(searchBody(Map.of(
                                    "attributes", List.of("displayName"),
                                    "excludedAttributes", List.of("meta")))))
                    .andReturn(), 400, "invalidValue");
        }
    }

    /**
     * The password is never returned, however it is asked for, on every query path. The seed
     * gives alice no password; bob is given one here so there is a credential to leak.
     */
    @ParameterizedTest
    @ValueSource(strings = {"password", "userName,password", "PASSWORD",
            "urn:ietf:params:scim:schemas:core:2.0:User:password"})
    void the_password_is_never_returned_even_when_requested(String attributes) throws Exception {
        String etag = mvc.perform(asWriter(get(USERS + "/" + ids.get("qp-bob"))))
                .andReturn().getResponse().getHeader(HttpHeaders.ETAG);
        MvcResult changed = mvc.perform(asWriter(
                        org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                                .patch(USERS + "/" + ids.get("qp-bob")))
                .header(HttpHeaders.IF_MATCH, etag)
                .contentType(SCIM_JSON)
                .content("""
                        {"schemas":["urn:ietf:params:scim:api:messages:2.0:PatchOp"],
                         "Operations":[{"op":"replace","path":"password",
                                        "value":"a sufficiently long passphrase %d"}]}"""
                        .formatted(System.nanoTime())))
                .andReturn();
        assertThat(changed.getResponse().getStatus())
                .as(changed.getResponse().getContentAsString())
                .isEqualTo(200);

        List<String> responses = List.of(
                mvc.perform(asWriter(get(USERS).param("filter", SCOPE)
                        .param("attributes", attributes))).andReturn().getResponse()
                        .getContentAsString(),
                mvc.perform(asWriter(post(USERS + "/.search")).contentType(SCIM_JSON)
                        .content(searchBody(Map.of("filter", SCOPE, "attributes",
                                List.of(attributes.split(","))))))
                        .andReturn().getResponse().getContentAsString(),
                mvc.perform(asWriter(post(BASE + "/.search")).contentType(SCIM_JSON)
                        .content(searchBody(Map.of("filter", SCOPE, "attributes",
                                List.of(attributes.split(","))))))
                        .andReturn().getResponse().getContentAsString());
        for (String response : responses) {
            JsonNode page = json.readTree(response);
            assertThat(page.get("totalResults").asInt()).isPositive();
            assertThat(response.toLowerCase()).doesNotContain("password").doesNotContain("passphrase");
            for (JsonNode resource : page.get("Resources")) {
                assertThat(resource.has("id")).as("always-returned attributes remain").isTrue();
            }
        }
    }

    // ---- search by request body ----------------------------------------------------------------

    static Stream<Arguments> equivalentQueries() {
        return Stream.of(
                Arguments.of(Map.of()),
                Arguments.of(Map.of("sortBy", "displayName", "sortOrder", "descending")),
                Arguments.of(Map.of("startIndex", 2, "count", 2)),
                Arguments.of(Map.of("count", 0)),
                Arguments.of(Map.of("filterSuffix", "displayName co \"e\"")),
                Arguments.of(Map.of("filterSuffix", "meta.resourceType pr",
                        "sortBy", "meta.created")),
                Arguments.of(Map.of("attributes", List.of("displayName", "meta"))),
                Arguments.of(Map.of("excludedAttributes", List.of("meta", "externalId"))));
    }

    /**
     * The three search endpoints return what the equivalent query-parameter requests return. The
     * base search is compared with the Users and Groups answers combined: Users first in the
     * default order, and every page total the sum of the two.
     */
    @ParameterizedTest
    @MethodSource("equivalentQueries")
    void a_search_body_returns_what_the_equivalent_query_parameters_return(Map<String, Object> query)
            throws Exception {
        Map<String, Object> body = new LinkedHashMap<>(query);
        String suffix = (String) body.remove("filterSuffix");
        String filter = suffix == null ? SCOPE : SCOPE + " and (" + suffix + ")";
        body.put("filter", filter);

        for (String endpoint : List.of(USERS, GROUPS)) {
            JsonNode viaGet = body(mvc.perform(asWriter(asParameters(get(endpoint), body)))
                    .andReturn());
            JsonNode viaSearch = body(mvc.perform(asWriter(post(endpoint + "/.search"))
                            .contentType(SCIM_JSON)
                            .content(searchBody(body)))
                    .andReturn());
            assertThat(viaGet.get("totalResults").asInt()).as(endpoint).isPositive();
            assertThat(viaSearch).as(endpoint).isEqualTo(viaGet);
        }
        if (body.containsKey("startIndex") || body.containsKey("sortBy")) {
            return;
        }
        JsonNode users = body(mvc.perform(asWriter(asParameters(get(USERS), body))).andReturn());
        JsonNode groups = body(mvc.perform(asWriter(asParameters(get(GROUPS), body))).andReturn());
        JsonNode base = body(mvc.perform(asWriter(post(BASE + "/.search"))
                        .contentType(SCIM_JSON)
                        .content(searchBody(body)))
                .andReturn());
        List<JsonNode> expected = new ArrayList<>();
        int total = 0;
        for (JsonNode list : new JsonNode[] {users, groups}) {
            total += list.get("totalResults").asInt();
            if (list.has("Resources")) {
                list.get("Resources").forEach(expected::add);
            }
        }
        assertThat(base.get("totalResults").asInt()).isEqualTo(total);
        List<JsonNode> actual = new ArrayList<>();
        if (base.has("Resources")) {
            base.get("Resources").forEach(actual::add);
        }
        assertThat(actual).containsExactlyElementsOf(expected);
    }

    /** A read-only token may call all three search endpoints: they are reads. */
    @ParameterizedTest
    @ValueSource(strings = {USERS + "/.search", GROUPS + "/.search", BASE + "/.search"})
    void a_read_only_token_can_call_every_search_endpoint(String endpoint) throws Exception {
        MvcResult result = mvc.perform(post(endpoint)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + readToken)
                        .contentType(SCIM_JSON)
                        .content(searchBody(Map.of("filter", SCOPE))))
                .andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        assertThat(body(result).get("totalResults").asInt()).isPositive();
    }

    /** ...and still may not write, so the exemption is the {@code .search} suffix alone. */
    @Test
    void a_read_only_token_still_cannot_create() throws Exception {
        MvcResult refused = mvc.perform(post(USERS)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + readToken)
                        .contentType(SCIM_JSON)
                        .content("{\"schemas\":[\"" + USER_SCHEMA + "\"],\"userName\":\"qp-ro\"}"))
                .andReturn();

        assertThat(refused.getResponse().getStatus()).isEqualTo(403);
    }

    /**
     * The base search: an attribute one type lacks has no value there. {@code userName} is a
     * User attribute, so it selects no Group; {@code not (userName pr)} selects every Group and
     * no User; {@code ne} on it is true for every Group.
     */
    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "userName sw \"qp-\"                  | qp-alice,qp-bob,qp-Carol,qp-dave",
            "not (userName pr)                    | qp-empty,qp-Engineering,qp-support",
            "members pr                           | qp-Engineering,qp-support",
            "userName ne \"qp-alice\"             | qp-bob,qp-Carol,qp-dave,qp-empty,qp-Engineering,qp-support",
            "displayName co \"e\"                 | qp-alice,qp-bob,qp-dave,qp-empty,qp-Engineering",
            "urn:ietf:params:scim:schemas:core:2.0:Group:displayName pr | qp-empty,qp-Engineering,qp-support",
            "urn:ietf:params:scim:schemas:core:2.0:User:displayName pr  | qp-alice,qp-bob,qp-dave",
            "emails[type eq \"home\"] or members[value eq \"{qp-Carol}\"] | qp-alice,qp-dave,qp-support",
            "meta.resourceType eq \"Group\" and displayName ew \"T\"      | qp-support"})
    void the_base_search_treats_an_attribute_absent_from_a_type_as_no_value(
            String filter, String expected) throws Exception {
        JsonNode page = body(mvc.perform(asWriter(post(BASE + "/.search"))
                        .contentType(SCIM_JSON)
                        .content(searchBody(Map.of("filter", scoped(filter.trim())))))
                .andReturn());

        assertThat(names(page)).containsExactly(expected.trim().split(","));
        assertThat(page.get("totalResults").asInt()).isEqualTo(expected.trim().split(",").length);
    }

    /** A base-search sort by an attribute only Users have puts every Group among the missing. */
    @Test
    void the_base_search_sorts_across_both_types_with_absent_values_as_missing() throws Exception {
        JsonNode ascending = body(mvc.perform(asWriter(post(BASE + "/.search"))
                        .contentType(SCIM_JSON)
                        .content(searchBody(Map.of("filter", SCOPE, "sortBy", "userName"))))
                .andReturn());
        JsonNode byDisplay = body(mvc.perform(asWriter(post(BASE + "/.search"))
                        .contentType(SCIM_JSON)
                        .content(searchBody(Map.of(
                                "filter", SCOPE, "sortBy", "meta.resourceType",
                                "sortOrder", "descending"))))
                .andReturn());

        assertThat(names(ascending).subList(0, 4))
                .containsExactly("qp-alice", "qp-bob", "qp-Carol", "qp-dave");
        assertThat(names(ascending).subList(4, 7))
                .containsExactlyInAnyOrder("qp-empty", "qp-Engineering", "qp-support");
        assertThat(names(byDisplay).subList(0, 4))
                .containsExactlyInAnyOrder("qp-alice", "qp-bob", "qp-Carol", "qp-dave");
    }

    /** A base-search projection applies each path to the type that has it. */
    @Test
    void the_base_search_projects_each_type_by_its_own_attributes() throws Exception {
        JsonNode page = body(mvc.perform(asWriter(post(BASE + "/.search"))
                        .contentType(SCIM_JSON)
                        .content(searchBody(Map.of("filter", SCOPE, "attributes",
                                List.of("userName", "members.value")))))
                .andReturn());

        for (JsonNode resource : page.get("Resources")) {
            boolean user = resource.get("schemas").get(0).asText().equals(USER_SCHEMA);
            List<String> keys = new ArrayList<>(resource.propertyNames());
            if (user) {
                assertThat(keys).containsExactlyInAnyOrder("schemas", "id", "userName");
            } else if (resource.has("members")) {
                assertThat(keys).containsExactlyInAnyOrder("schemas", "id", "members");
                assertThat(new ArrayList<>(resource.get("members").get(0).propertyNames()))
                        .containsExactly("value");
            } else {
                assertThat(keys).containsExactlyInAnyOrder("schemas", "id");
            }
        }
        assertRefusal(mvc.perform(asWriter(post(BASE + "/.search"))
                        .contentType(SCIM_JSON)
                        .content(searchBody(Map.of("attributes", List.of("nickName")))))
                .andReturn(), 400, "invalidValue");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"filter\":\"userName pr\"}",
            "{\"schemas\":[\"urn:ietf:params:scim:api:messages:2.0:ListResponse\"]}",
            "{\"schemas\":[\"" + SEARCH_REQUEST + "\"],\"filtr\":\"userName pr\"}",
            "[]"})
    void a_body_that_is_not_a_search_request_is_refused(String body) throws Exception {
        assertRefusal(mvc.perform(asWriter(post(USERS + "/.search"))
                        .contentType(SCIM_JSON).content(body))
                .andReturn(), 400, "invalidSyntax");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"schemas\":[\"" + SEARCH_REQUEST + "\"],\"count\":\"ten\"}",
            "{\"schemas\":[\"" + SEARCH_REQUEST + "\"],\"startIndex\":1.5}",
            "{\"schemas\":[\"" + SEARCH_REQUEST + "\"],\"filter\":5}",
            "{\"schemas\":[\"" + SEARCH_REQUEST + "\"],\"attributes\":[5]}"})
    void a_search_request_member_of_the_wrong_type_is_refused(String body) throws Exception {
        assertRefusal(mvc.perform(asWriter(post(USERS + "/.search"))
                        .contentType(SCIM_JSON).content(body))
                .andReturn(), 400, "invalidValue");
    }

    // ---- bulk-read audit -----------------------------------------------------------------------

    /**
     * Every collection query and search call, both types and the base search, produces exactly
     * one event with its result count and filter shape — including an empty result and a
     * single-result page — and single-resource retrieval produces none.
     */
    @Test
    void every_query_path_is_one_audit_event_and_a_single_read_is_none() throws Exception {
        record Call(MockHttpServletRequestBuilder request, String operation, String type,
                    String path, int count, String shape) {
        }
        String userSearch = searchBody(Map.of("filter", "userName eq \"qp-nobody\"", "count", 5));
        List<Call> calls = List.of(
                new Call(get(USERS).param("filter", SCOPE).param("count", "1"),
                        "SCIM_USER_LIST", "User", USERS, 1, "externalId pr"),
                new Call(get(USERS).param("filter", "userName eq \"qp-nobody\""),
                        "SCIM_USER_LIST", "User", USERS, 0, "userName eq ?"),
                new Call(get(USERS).param("count", "0"),
                        "SCIM_USER_LIST", "User", USERS, 0, null),
                new Call(post(USERS + "/.search").contentType(SCIM_JSON).content(userSearch),
                        "SCIM_USER_LIST", "User", USERS + "/.search", 0, "userName eq ?"),
                new Call(get(GROUPS).param("filter", "displayName eq \"qp-support\""),
                        "SCIM_GROUP_LIST", "Group", GROUPS, 1, "displayName eq ?"),
                new Call(post(GROUPS + "/.search").contentType(SCIM_JSON)
                        .content(searchBody(Map.of("filter", "members pr and " + SCOPE))),
                        "SCIM_GROUP_LIST", "Group", GROUPS + "/.search", 2,
                        "(members pr and externalId pr)"),
                new Call(post(BASE + "/.search").contentType(SCIM_JSON)
                        .content(searchBody(Map.of("filter", SCOPE))),
                        "SCIM_RESOURCE_LIST", "User,Group", BASE + "/.search", 7, "externalId pr"),
                new Call(post(BASE + "/.search").contentType(SCIM_JSON)
                        .content(searchBody(Map.of("filter", SCOPE, "count", 0))),
                        "SCIM_RESOURCE_LIST", "User,Group", BASE + "/.search", 0, "externalId pr"));
        for (Call call : calls) {
            long before = eventCount();

            MvcResult result = mvc.perform(asWriter(call.request())).andReturn();

            assertThat(result.getResponse().getStatus()).isEqualTo(200);
            assertThat(eventCount()).as(call.path()).isEqualTo(before + 1);
            Map<String, Object> event = latestEvent();
            assertThat(event.get("operation")).isEqualTo(call.operation());
            assertThat(event.get("resource_type")).isEqualTo(call.type());
            assertThat(event.get("result_count")).isEqualTo(call.count());
            assertThat(event.get("filter_shape")).isEqualTo(call.shape());
            assertThat(event.get("http_path")).isEqualTo(call.path());
            assertThat(event.get("subject_id")).isNull();
            assertThat(event.get("result_count"))
                    .isEqualTo(body(result).get("itemsPerPage").asInt());
            // "result count and filter shape only": the event names no resource, no changed
            // attribute and no error beside them.
            Map<String, Object> rest = jdbc.queryForMap(LATEST_EVENT_REST, connectorId);
            assertThat(rest.get("resource_id")).as(call.path()).isNull();
            assertThat((String) rest.get("changed_paths")).as(call.path()).isNullOrEmpty();
            assertThat(rest.get("error_code")).as(call.path()).isNull();
        }

        long before = eventCount();
        mvc.perform(asWriter(get(USERS + "/" + ids.get("qp-alice")))).andReturn();
        mvc.perform(asWriter(get(GROUPS + "/" + ids.get("qp-support")))).andReturn();
        assertThat(eventCount()).as("a single-resource retrieval is not audited").isEqualTo(before);
    }

    // ---- helpers -------------------------------------------------------------------------------

    private static Arguments users(String filter, String... expected) {
        return Arguments.of(USERS, filter, List.of(expected));
    }

    private static Arguments groups(String filter, String... expected) {
        return Arguments.of(GROUPS, filter, List.of(expected));
    }

    private String scoped(String filter) {
        return SCOPE + " and (" + resolve(filter) + ")";
    }

    /** Replaces each {@code {name}} with the seeded resource's id. */
    private String resolve(String filter) {
        Matcher placeholder = Pattern.compile("\\{([A-Za-z-]+)}").matcher(filter);
        StringBuilder resolved = new StringBuilder();
        while (placeholder.find()) {
            placeholder.appendReplacement(resolved, ids.get(placeholder.group(1)).toString());
        }
        return placeholder.appendTail(resolved).toString();
    }

    /** Every quoted literal in a filter, unescaped enough for a containment check. */
    private static List<String> literals(String filter) {
        List<String> literals = new ArrayList<>();
        Matcher quoted = Pattern.compile("\"((?:[^\"\\\\]|\\\\.)*)\"").matcher(filter);
        while (quoted.find()) {
            if (!quoted.group(1).isEmpty() && !quoted.group(1).startsWith("urn:")) {
                literals.add(quoted.group(1));
            }
        }
        return literals;
    }

    private void user(String body) throws Exception {
        MvcResult result = mvc.perform(asWriter(post(USERS)).contentType(SCIM_JSON)
                        .content(body.replace("%s", USER_SCHEMA)))
                .andReturn();
        assertThat(result.getResponse().getStatus()).as(body).isEqualTo(201);
        JsonNode user = body(result);
        UUID id = UUID.fromString(user.get("id").asText());
        ids.put(user.get("userName").asText(), id);
        created.add(id);
    }

    private void group(String displayName, String externalId, String... members) throws Exception {
        ObjectNode body = json.createObjectNode();
        body.putArray("schemas").add(GROUP_SCHEMA);
        body.put("displayName", displayName);
        body.put("externalId", externalId);
        ArrayNode memberList = body.putArray("members");
        for (String member : members) {
            memberList.addObject().put("value", ids.get(member).toString());
        }
        MvcResult result = mvc.perform(asWriter(post(GROUPS)).contentType(SCIM_JSON)
                        .content(body.toString()))
                .andReturn();
        assertThat(result.getResponse().getStatus()).as(displayName).isEqualTo(201);
        UUID id = UUID.fromString(body(result).get("id").asText());
        ids.put(displayName, id);
        created.add(id);
    }

    private String searchBody(Map<String, Object> members) {
        ObjectNode body = json.createObjectNode();
        body.putArray("schemas").add(SEARCH_REQUEST);
        members.forEach((name, value) -> body.set(name, json.valueToTree(value)));
        return body.toString();
    }

    /** The same members as query parameters, lists comma-joined. */
    private static MockHttpServletRequestBuilder asParameters(
            MockHttpServletRequestBuilder request, Map<String, Object> members) {
        members.forEach((name, value) -> request.param(name, value instanceof List<?> list
                ? String.join(",", list.stream().map(String::valueOf).toList())
                : String.valueOf(value)));
        return request;
    }

    /** The page's resources by name: userName for a User, displayName for a Group. */
    private static List<String> names(JsonNode page) {
        List<String> names = new ArrayList<>();
        if (page.has("Resources")) {
            for (JsonNode resource : page.get("Resources")) {
                names.add(resource.has("userName")
                        ? resource.get("userName").asText()
                        : resource.get("displayName").asText());
            }
        }
        return names;
    }

    private long eventCount() {
        return jdbc.queryForObject(
                "SELECT count(*) FROM audit_events WHERE actor_id = ?", Long.class, connectorId);
    }

    private Map<String, Object> latestEvent() {
        return jdbc.queryForMap(LATEST_EVENT, connectorId);
    }

    private void assertRefusal(MvcResult result, int status, String scimType) throws Exception {
        assertThat(result.getResponse().getStatus())
                .as(result.getResponse().getContentAsString())
                .isEqualTo(status);
        JsonNode error = body(result);
        assertThat(error.get("schemas").get(0).asText())
                .isEqualTo("urn:ietf:params:scim:api:messages:2.0:Error");
        assertThat(error.get("scimType").asText()).isEqualTo(scimType);
        assertThat(error.get("detail").asText()).isNotBlank();
    }

    private MockHttpServletRequestBuilder asWriter(MockHttpServletRequestBuilder request) {
        return request.header(HttpHeaders.AUTHORIZATION, "Bearer " + writeToken);
    }

    private JsonNode body(MvcResult result) throws Exception {
        return json.readTree(result.getResponse().getContentAsString());
    }
}

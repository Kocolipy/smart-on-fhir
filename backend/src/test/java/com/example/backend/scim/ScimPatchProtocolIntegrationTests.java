package com.example.backend.scim;

import static com.example.backend.ScimHttp.SCIM_JSON;
import static com.example.backend.ScimHttp.asConnector;
import static com.example.backend.TestJson.body;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;

import com.example.backend.ContainerTestConfiguration;
import com.example.backend.CreatedScimRows;
import com.example.backend.ScimTestRows;
import com.example.backend.TokenPermissions;
import com.example.backend.observability.RequestIdFilter;
import com.example.backend.scim.application.ConnectorAdministrationService;
import jakarta.servlet.Filter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.JsonNode;

/**
 * The PATCH conformance table: every rule of the practical SCIM protocol profile's PATCH path
 * grammar ({@code docs/domain/scim-protocol.md}, "PATCH paths"), run against a User and a Group
 * over the real filter chain and SQL Server.
 *
 * <p>Each row patches a resource created for it alone, then re-reads it: a refused row must leave
 * it exactly as it was, and an accepted one must show the change it names. Rows come in pairs, a
 * User row and a Group row per rule, so a rule that holds for one resource and not the other is a
 * red row rather than an untested gap. The exceptions are the rules that are resource-specific by
 * decision: a path-less {@code add} or {@code replace} is a merge on a User and refused on a Group,
 * and removing by a {@code value} array is a {@code members} rule, which a User has no attribute
 * for.
 *
 * <p>In an operation's text, {@code {A}} and {@code {B}} are the Group's two members, and
 * {@code {N}} a live User that is a member of nothing; {@code {NAME}} is a display name no other
 * row uses, since a Group's is unique.
 */
@SpringBootTest
@Import(ContainerTestConfiguration.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ScimPatchProtocolIntegrationTests {

    private static final String USERS = "/scim/v2/Users";

    private static final String GROUPS = "/scim/v2/Groups";

    private static final String USER_URN = "urn:ietf:params:scim:schemas:core:2.0:User";

    private static final String GROUP_URN = "urn:ietf:params:scim:schemas:core:2.0:Group";

    private static final String PATCH_OP = "urn:ietf:params:scim:api:messages:2.0:PatchOp";

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

    private UUID memberA;

    private UUID memberB;

    private UUID nonMember;

    private final CreatedScimRows created = new CreatedScimRows();

    @BeforeAll
    void connectorAndUsers() throws Exception {
        mvc = MockMvcBuilders.webAppContextSetup(context)
                .addFilters(requestIdFilter, springSecurityFilterChain)
                .build();
        UUID connectorId = connectors.create(ScimTestRows.unique("Patch protocol"), null).id();
        token = connectors.issueToken(connectorId, TokenPermissions.ALL, null, null)
                .presentedValue();
        memberA = user();
        memberB = user();
        nonMember = user();
    }

    @AfterAll
    void removeOnlyWhatThisClassCreated() {
        created.deleteAll(jdbc);
    }

    /** Which resource a row patches. */
    enum Resource { USER, GROUP }

    /** What the re-read resource must show, given the one read before the PATCH. */
    @FunctionalInterface
    interface Expectation {

        void check(JsonNode before, JsonNode after, Members members);
    }

    /** The ids and the fresh name a row's text and expectation name. */
    record Members(UUID a, UUID b, UUID n, String name) {

        String resolve(String text) {
            return text.replace("{A}", a.toString())
                    .replace("{B}", b.toString())
                    .replace("{N}", n.toString())
                    .replace("{NAME}", name);
        }
    }

    Stream<Arguments> table() {
        return Stream.of(
                // 1. Grammar: the resource's core schema URN prefixes any path, value paths
                //    included; the prefix and the attribute names match regardless of case.
                accepted("1 schema URN prefix", Resource.USER,
                        op("replace", USER_URN + ":displayName", "\"{NAME}\""),
                        displayName("{NAME}")),
                accepted("1 schema URN prefix", Resource.GROUP,
                        op("replace", GROUP_URN + ":displayName", "\"{NAME}\""),
                        displayName("{NAME}")),
                accepted("1 prefix and name in any case", Resource.USER,
                        op("replace", USER_URN.toUpperCase(Locale.ROOT) + ":DISPLAYNAME",
                                "\"{NAME}\""),
                        displayName("{NAME}")),
                accepted("1 prefix and name in any case", Resource.GROUP,
                        op("replace", GROUP_URN.toUpperCase(Locale.ROOT) + ":DISPLAYNAME",
                                "\"{NAME}\""),
                        displayName("{NAME}")),
                accepted("1 schema URN prefix on a value path", Resource.USER,
                        op("remove", USER_URN + ":emails[type eq \\\"home\\\"]", null),
                        emails("work@before.example")),
                accepted("1 schema URN prefix on a value path", Resource.GROUP,
                        op("remove", GROUP_URN + ":members[value eq \\\"{A}\\\"]", null),
                        members("{B}")),
                refused("1 another resource's schema URN", Resource.USER,
                        op("replace", GROUP_URN + ":displayName", "\"{NAME}\""), "invalidPath"),
                refused("1 another resource's schema URN", Resource.GROUP,
                        op("replace", USER_URN + ":displayName", "\"{NAME}\""), "invalidPath"),

                // 2. Whitespace: none before or after a path.
                refused("2 leading whitespace", Resource.USER,
                        op("replace", " displayName", "\"{NAME}\""), "invalidPath"),
                refused("2 leading whitespace", Resource.GROUP,
                        op("replace", " displayName", "\"{NAME}\""), "invalidPath"),
                refused("2 trailing whitespace", Resource.USER,
                        op("replace", "displayName\\t", "\"{NAME}\""), "invalidPath"),
                refused("2 trailing whitespace", Resource.GROUP,
                        op("replace", "displayName\\t", "\"{NAME}\""), "invalidPath"),

                // 3. A path that is not a string is malformed.
                refused("3 a number as a path", Resource.USER,
                        "{\"op\":\"replace\",\"path\":7,\"value\":\"{NAME}\"}", "invalidPath"),
                refused("3 a number as a path", Resource.GROUP,
                        "{\"op\":\"replace\",\"path\":7,\"value\":\"{NAME}\"}", "invalidPath"),
                refused("3 an object as a path", Resource.USER,
                        "{\"op\":\"remove\",\"path\":{\"attribute\":\"displayName\"}}",
                        "invalidPath"),
                refused("3 an object as a path", Resource.GROUP,
                        "{\"op\":\"remove\",\"path\":{\"attribute\":\"members\"}}",
                        "invalidPath"),

                // 4. op in any case.
                accepted("4 op in mixed case", Resource.USER,
                        op("rEpLaCe", "displayName", "\"{NAME}\""), displayName("{NAME}")),
                accepted("4 op in mixed case", Resource.GROUP,
                        op("rEpLaCe", "displayName", "\"{NAME}\""), displayName("{NAME}")),

                // 5. Path-less operations.
                refused("5 a path-less remove", Resource.USER,
                        "{\"op\":\"remove\"}", "noTarget"),
                refused("5 a path-less remove", Resource.GROUP,
                        "{\"op\":\"remove\"}", "noTarget"),
                accepted("5 a path-less add merges", Resource.USER,
                        op("add", null, "{\"displayName\":\"{NAME}\"}"), displayName("{NAME}")),
                accepted("5 a path-less replace merges", Resource.USER,
                        op("replace", null, "{\"displayName\":\"{NAME}\"}"),
                        displayName("{NAME}")),
                refused("5 a path-less add is refused", Resource.GROUP,
                        op("add", null, "{\"displayName\":\"{NAME}\"}"), "invalidSyntax"),
                refused("5 a path-less replace is refused", Resource.GROUP,
                        op("replace", null, "{\"displayName\":\"{NAME}\"}"), "invalidSyntax"),

                // 6. Filters: eq and and only; a filtered remove matching nothing succeeds.
                refused("6 an operator other than eq", Resource.USER,
                        op("remove", "emails[type ne \\\"home\\\"]", null), "invalidFilter"),
                refused("6 an operator other than eq", Resource.GROUP,
                        op("remove", "members[value ne \\\"{A}\\\"]", null), "invalidFilter"),
                refused("6 or", Resource.USER,
                        op("remove", "emails[type eq \\\"home\\\" or type eq \\\"work\\\"]",
                                null), "invalidFilter"),
                refused("6 or", Resource.GROUP,
                        op("remove", "members[value eq \\\"{A}\\\" or value eq \\\"{B}\\\"]",
                                null), "invalidFilter"),
                accepted("6 eq joined by and", Resource.USER,
                        op("remove", "emails[type eq \\\"home\\\" and value eq"
                                + " \\\"home@before.example\\\"]", null),
                        emails("work@before.example")),
                accepted("6 eq joined by and", Resource.GROUP,
                        op("remove", "members[value eq \\\"{A}\\\" and value eq \\\"{A}\\\"]",
                                null),
                        members("{B}")),
                accepted("6 a filtered remove matching nothing", Resource.USER,
                        op("remove", "emails[type eq \\\"pager\\\"]", null), unchanged()),
                accepted("6 a filtered remove matching nothing", Resource.GROUP,
                        op("remove", "members[value eq \\\"{N}\\\"]", null), unchanged()),

                // 7. remove of members with a value array removes only the listed members.
                accepted("7 remove members by value", Resource.GROUP,
                        op("remove", "members", "[{\"value\":\"{A}\"}]"), members("{B}")));
    }

    @ParameterizedTest(name = "{0} — {1}")
    @MethodSource("table")
    void each_patch_rule_holds(String rule, Resource resource, String operation, int status,
            String scimType, Expectation expectation) throws Exception {
        Members ids = new Members(
                memberA, memberB, nonMember, ScimTestRows.unique("patch-protocol-renamed"));
        String location = resource == Resource.USER
                ? USERS + "/" + user()
                : GROUPS + "/" + group();
        JsonNode before = read(location);

        MvcResult result = mvc.perform(asConnector(patch(location), token, jdbc)
                        .contentType(SCIM_JSON)
                        .content("{\"schemas\":[\"" + PATCH_OP + "\"],\"Operations\":["
                                + ids.resolve(operation) + "]}"))
                .andReturn();

        assertThat(result.getResponse().getStatus())
                .as(result.getResponse().getContentAsString())
                .isEqualTo(status);
        if (scimType != null) {
            assertThat(body(result).get("scimType").asText()).isEqualTo(scimType);
        }
        expectation.check(before, read(location), ids);
    }

    // ---- rows -----------------------------------------------------------------------------

    private static Arguments accepted(String rule, Resource resource, String operation,
            Expectation expectation) {
        return Arguments.of(rule, resource, operation, 200, null, expectation);
    }

    /** A refusal, which leaves the resource exactly as it was. */
    private static Arguments refused(String rule, Resource resource, String operation,
            String scimType) {
        return Arguments.of(rule, resource, operation, 400, scimType, unchanged());
    }

    /** One operation's JSON; {@code path} and {@code value} are omitted when {@code null}. */
    private static String op(String op, String path, String value) {
        return "{\"op\":\"" + op + "\""
                + (path == null ? "" : ",\"path\":\"" + path + "\"")
                + (value == null ? "" : ",\"value\":" + value)
                + "}";
    }

    private static Expectation unchanged() {
        return (before, after, ids) -> assertThat(after).isEqualTo(before);
    }

    private static Expectation displayName(String expected) {
        return (before, after, ids) ->
                assertThat(after.get("displayName").asText())
                        .isEqualTo(ids.resolve(expected));
    }

    private static Expectation emails(String... expected) {
        return (before, after, ids) -> assertThat(values(after.get("emails")))
                .containsExactlyInAnyOrder(expected);
    }

    private static Expectation members(String... expected) {
        return (before, after, ids) -> assertThat(values(after.get("members")))
                .containsExactlyInAnyOrder(
                        Stream.of(expected).map(ids::resolve).toArray(String[]::new));
    }

    private static List<String> values(JsonNode multiValued) {
        List<String> values = new ArrayList<>();
        if (multiValued != null) {
            multiValued.forEach(value -> values.add(value.get("value").asText()));
        }
        return values;
    }

    // ---- resources ------------------------------------------------------------------------

    /** A User with a work and a home email, displayed as "Before". */
    private UUID user() throws Exception {
        return created.create(mvc, token, USERS, """
                {"schemas":["%s"],"userName":"%s","displayName":"Before",
                 "emails":[{"value":"work@before.example","type":"work","primary":true},
                           {"value":"home@before.example","type":"home"}]}"""
                .formatted(USER_URN, ScimTestRows.unique("patch-protocol")));
    }

    /** A Group whose members are {@code {A}} and {@code {B}}. */
    private UUID group() throws Exception {
        return created.create(mvc, token, GROUPS, """
                {"schemas":["%s"],"displayName":"%s",
                 "members":[{"value":"%s"},{"value":"%s"}]}"""
                .formatted(GROUP_URN, ScimTestRows.unique("patch-protocol"), memberA, memberB));
    }

    private JsonNode read(String location) throws Exception {
        MvcResult result = mvc.perform(asConnector(get(location), token)).andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        return body(result);
    }
}

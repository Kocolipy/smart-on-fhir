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
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.UnaryOperator;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
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
 * A value too long for its column, and any other integrity violation, against the real Postgres
 * schema — the bug report's table, observed end to end.
 *
 * <p>Every refusal here used to reach the database, whose integrity violation the persistence
 * adapters translated wholesale into "the name is taken": a 40-character email {@code type} came
 * back as {@code 409 uniqueness} naming {@code userName}, and an over-length {@code externalId}
 * as the servlet container's own {@code 500} body. Postgres is what decides those outcomes, so
 * they are asserted here rather than against the in-memory fakes, which have no columns.
 *
 * <p>A U+0000 in any stored string is the same kind of refusal: PostgreSQL cannot store it, so
 * it is refused as {@code 400 invalidValue} before the statement rather than failing at it.
 */
@SpringBootTest
@ExtendWith(OutputCaptureExtension.class)
@Import(ContainerTestConfiguration.class)
class ScimStoredLengthIntegrationTests {

    private static final String USERS = "/scim/v2/Users";

    private static final String GROUPS = "/scim/v2/Groups";

    private static final String USER_SCHEMA = "urn:ietf:params:scim:schemas:core:2.0:User";

    private static final String GROUP_SCHEMA = "urn:ietf:params:scim:schemas:core:2.0:Group";

    private static final String PATCH_OP = "urn:ietf:params:scim:api:messages:2.0:PatchOp";

    private static final MediaType SCIM_JSON = MediaType.valueOf("application/scim+json");

    /** One code point outside the Basic Multilingual Plane: two UTF-16 units. */
    private static final String SUPPLEMENTARY = "\uD83D\uDE00";

    /** The value a forced CHECK constraint refuses, so the database alone turns the write down. */
    private static final String FORCED = "forced-violation";

    /** How a refusal names the characters a {@code userName} or {@code displayName} refuses. */
    private static final String CONTROL_CHARACTERS =
            "control characters (U+0000 to U+001F, U+007F)";

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

    private final List<UUID> created = new ArrayList<>();

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context)
                .addFilters(requestIdFilter, springSecurityFilterChain)
                .build();
        connectorId = connectors.create("Length limits", "test-admin").id();
        writeToken = connectors.issueToken(connectorId, TokenPermissions.ALL, null,
                "test-admin", TokenPermissions.ALL).presentedValue();
    }

    @AfterEach
    void removeOnlyWhatThisTestCreated() {
        for (UUID id : created) {
            jdbc.update("DELETE FROM scim_resources WHERE id = ? AND reserved_name IS NULL", id);
        }
        created.clear();
    }

    // ---- the limits, per attribute and per verb ------------------------------------------

    /**
     * One stored User attribute: the path a refusal names, its column's limit, the body fragment
     * carrying a value for it, and the PatchOp operation setting it.
     *
     * <p>{@code patchPath} is {@code null} for an attribute no PATCH path writes; none now —
     * {@code externalId} became PATCH-writable with issue #54.
     */
    record UserAttribute(
            String attribute,
            int limit,
            UnaryOperator<String> fragment,
            String patchPath,
            UnaryOperator<String> patchValue) {

        @Override
        public String toString() {
            return attribute;
        }

        /** A value as a JSON string. */
        static String quoted(String value) {
            return "\"" + value + "\"";
        }
    }

    static Stream<UserAttribute> userAttributes() {
        return Stream.of(
                new UserAttribute("userName", 256, v -> "\"userName\":\"" + v + "\"",
                        "userName", UserAttribute::quoted),
                text("displayName", 256),
                text("preferredLanguage", 64),
                text("locale", 64),
                text("timezone", 64),
                new UserAttribute("externalId", 256, v -> "\"externalId\":\"" + v + "\"",
                        "externalId", UserAttribute::quoted),
                new UserAttribute("name.givenName", 256,
                        v -> "\"name\":{\"givenName\":\"" + v + "\"}",
                        "name.givenName", UserAttribute::quoted),
                new UserAttribute("emails.value", 256,
                        v -> "\"emails\":[{\"value\":\"" + v + "\"}]",
                        "emails", v -> "[{\"value\":\"" + v + "\"}]"),
                new UserAttribute("emails.type", 32,
                        v -> "\"emails\":[{\"value\":\"ada@work.example\",\"type\":\"" + v + "\"}]",
                        "emails", v -> "[{\"value\":\"ada@work.example\",\"type\":\"" + v + "\"}]"));
    }

    static Stream<Arguments> userAttributesByVerb() {
        return userAttributes().flatMap(attribute -> Stream.of("POST", "PUT", "PATCH")
                .filter(verb -> !verb.equals("PATCH") || attribute.patchPath() != null)
                .map(verb -> Arguments.of(attribute, verb)));
    }

    /**
     * Each stored User attribute, one character past its column, is {@code 400 invalidValue} on
     * every verb that can write it, naming the attribute and the limit but not the value — and the
     * refusal changes nothing and is audited as an invalid value, not as a conflict.
     */
    @ParameterizedTest(name = "{1} {0}")
    @MethodSource("userAttributesByVerb")
    void an_over_length_user_value_is_an_invalid_value_on_every_verb(
            UserAttribute attribute, String verb) throws Exception {
        String tooLong = "q".repeat(attribute.limit() + 1);
        String userName = "len-" + UUID.randomUUID();

        MvcResult refused;
        UUID target = null;
        String etagBefore = null;
        if (verb.equals("POST")) {
            refused = createUser(userBody(userName, attribute, tooLong));
        } else {
            MvcResult base = createUser(userBody(userName, null, null));
            assertThat(base.getResponse().getStatus()).isEqualTo(201);
            target = id(base);
            etagBefore = base.getResponse().getHeader(HttpHeaders.ETAG);
            refused = verb.equals("PUT")
                    ? perform(put(USERS + "/" + target), etagBefore,
                            userBody(userName, attribute, tooLong))
                    : perform(patch(USERS + "/" + target), etagBefore,
                            patchOp(attribute.patchPath(), attribute.patchValue().apply(tooLong)));
        }

        assertTooLong(refused, attribute.attribute(), attribute.limit(), tooLong);
        if (target == null) {
            assertThat(jdbc.queryForObject(
                    "SELECT count(*) FROM scim_users WHERE user_name IN (?, ?)", Long.class,
                    userName, tooLong)).as("the refused create stored nothing").isZero();
            assertThat(refusalCodes("SCIM_USER_CREATE")).containsExactly("INVALID_VALUE");
        } else {
            assertThat(etagOf(USERS, target)).as("the refused write changed nothing")
                    .isEqualTo(etagBefore);
            assertThat(refusalCodes("SCIM_USER_REPLACE")).containsExactly("INVALID_VALUE");
        }
    }

    /**
     * The other side of every limit: a value exactly as long as its column is accepted, counted
     * in code points as Postgres counts them — a {@code userName} of 256 supplementary characters
     * is 512 UTF-16 units and the column stores it.
     */
    @Test
    void every_user_value_at_its_limit_is_stored_counting_code_points() throws Exception {
        String userName = SUPPLEMENTARY.repeat(256);
        MvcResult stored = createUser("""
                {"schemas":["%s"],"userName":"%s","externalId":"%s","displayName":"%s",
                 "name":{"givenName":"%s","familyName":"%s"},
                 "preferredLanguage":"%s","locale":"%s","timezone":"%s",
                 "emails":[{"value":"%s","type":"%s","primary":true}]}"""
                .formatted(USER_SCHEMA, userName, "x".repeat(256), "d".repeat(256),
                        "g".repeat(256), "f".repeat(256), "l".repeat(64), "c".repeat(64),
                        "z".repeat(64), "e".repeat(256), "t".repeat(32)));

        assertThat(stored.getResponse().getStatus())
                .as(stored.getResponse().getContentAsString()).isEqualTo(201);
        assertThat(jdbc.queryForObject("SELECT user_name FROM scim_users WHERE resource_id = ?",
                String.class, id(stored))).isEqualTo(userName);
    }

    /** The bug report's Group rows: {@code displayName} on all three verbs, and {@code externalId}. */
    @Test
    void an_over_length_group_value_is_an_invalid_value_on_every_verb() throws Exception {
        String tooLong = "q".repeat(257);
        assertTooLong(createGroup("""
                {"schemas":["%s"],"displayName":"%s"}""".formatted(GROUP_SCHEMA, tooLong)),
                "displayName", 256, tooLong);
        assertTooLong(createGroup("""
                {"schemas":["%s"],"displayName":"len-group-ext","externalId":"%s"}"""
                .formatted(GROUP_SCHEMA, tooLong)), "externalId", 256, tooLong);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM scim_groups WHERE display_name = 'len-group-ext'",
                Long.class)).isZero();
        assertThat(refusalCodes("SCIM_GROUP_CREATE"))
                .containsExactly("INVALID_VALUE", "INVALID_VALUE");

        MvcResult base = createGroup("""
                {"schemas":["%s"],"displayName":"len-group"}""".formatted(GROUP_SCHEMA));
        UUID group = id(base);
        String etag = base.getResponse().getHeader(HttpHeaders.ETAG);

        assertTooLong(perform(put(GROUPS + "/" + group), etag, """
                {"schemas":["%s"],"displayName":"%s"}""".formatted(GROUP_SCHEMA, tooLong)),
                "displayName", 256, tooLong);
        assertTooLong(perform(patch(GROUPS + "/" + group), etag,
                        patchOp("displayName", "\"" + tooLong + "\"")),
                "displayName", 256, tooLong);
        // externalId is PUT- and PATCH-writable since issue #54, so both are bounded too.
        assertTooLong(perform(put(GROUPS + "/" + group), etag, """
                {"schemas":["%s"],"displayName":"len-group","externalId":"%s"}"""
                .formatted(GROUP_SCHEMA, tooLong)), "externalId", 256, tooLong);
        assertTooLong(perform(patch(GROUPS + "/" + group), etag,
                        patchOp("externalId", "\"" + tooLong + "\"")),
                "externalId", 256, tooLong);
        assertThat(etagOf(GROUPS, group)).isEqualTo(etag);
        assertThat(refusalCodes("SCIM_GROUP_REPLACE"))
                .containsExactly("INVALID_VALUE", "INVALID_VALUE", "INVALID_VALUE", "INVALID_VALUE");

        MvcResult longest = createGroup("""
                {"schemas":["%s"],"displayName":"%s"}"""
                .formatted(GROUP_SCHEMA, SUPPLEMENTARY.repeat(256)));
        assertThat(longest.getResponse().getStatus()).isEqualTo(201);
    }

    // ---- uniqueness keeps its meaning ----------------------------------------------------

    /**
     * A genuine live-name collision is still {@code 409 uniqueness}, decided by the constraint the
     * adapter now matches by name — including when two connectors create the same name at the same
     * moment, which only the database can arbitrate. Repeated, because a race that happens to
     * serialize once proves little.
     */
    @Test
    void two_creates_racing_for_one_user_name_produce_one_201_and_one_409() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            for (int round = 0; round < 5; round++) {
                String body = userBody("race-create-" + round, null, null);
                CountDownLatch start = new CountDownLatch(1);
                Future<MvcResult> first = pool.submit(() -> {
                    start.await();
                    return createUser(body);
                });
                Future<MvcResult> second = pool.submit(() -> {
                    start.await();
                    return createUser(body);
                });
                start.countDown();

                MvcResult a = first.get(30, TimeUnit.SECONDS);
                MvcResult b = second.get(30, TimeUnit.SECONDS);
                assertThat(List.of(a.getResponse().getStatus(), b.getResponse().getStatus()))
                        .as("round " + round)
                        .containsExactlyInAnyOrder(201, 409);
                MvcResult conflict = a.getResponse().getStatus() == 409 ? a : b;
                assertThat(body(conflict).get("scimType").asText()).isEqualTo("uniqueness");
            }
        } finally {
            pool.shutdownNow();
        }
        assertThat(refusalCodes("SCIM_USER_CREATE")).hasSize(5).containsOnly("UNIQUENESS");
    }

    @Test
    void a_group_display_name_collision_is_still_a_uniqueness_conflict() throws Exception {
        createGroup("""
                {"schemas":["%s"],"displayName":"len-taken"}""".formatted(GROUP_SCHEMA));

        MvcResult conflict = createGroup("""
                {"schemas":["%s"],"displayName":"LEN-TAKEN"}""".formatted(GROUP_SCHEMA));

        assertThat(conflict.getResponse().getStatus()).isEqualTo(409);
        assertThat(body(conflict).get("scimType").asText()).isEqualTo("uniqueness");
        assertThat(refusalCodes("SCIM_GROUP_CREATE")).containsExactly("UNIQUENESS");
    }

    // ---- characters a column or a name refuses ---------------------------------------------

    /** U+0000 as a JSON escape, which is how a connector sends it: JSON allows it in a string. */
    private static final String NUL = "\\" + "u0000";

    /**
     * Each stored User attribute — one or more per table: {@code scim_users}, its emails and
     * {@code scim_external_ids} — holding U+0000 is {@code 400 invalidValue} on every verb that
     * can write it, naming the attribute and not the value, and the refusal changes nothing and
     * is audited as an invalid value. PostgreSQL cannot store the character at all, so before
     * this check the write failed at the statement and surfaced as a fault.
     */
    @ParameterizedTest(name = "{1} {0}")
    @MethodSource("userAttributesByVerb")
    void a_user_value_holding_a_nul_is_an_invalid_value_on_every_verb(
            UserAttribute attribute, String verb) throws Exception {
        String holding = "nul-" + NUL + "-value";
        String userName = "nul-" + UUID.randomUUID();

        MvcResult refused;
        UUID target = null;
        String etagBefore = null;
        if (verb.equals("POST")) {
            refused = createUser(userBody(userName, attribute, holding));
        } else {
            MvcResult base = createUser(userBody(userName, null, null));
            assertThat(base.getResponse().getStatus()).isEqualTo(201);
            target = id(base);
            etagBefore = base.getResponse().getHeader(HttpHeaders.ETAG);
            refused = verb.equals("PUT")
                    ? perform(put(USERS + "/" + target), etagBefore,
                            userBody(userName, attribute, holding))
                    : perform(patch(USERS + "/" + target), etagBefore,
                            patchOp(attribute.patchPath(), attribute.patchValue().apply(holding)));
        }

        String forbidden = Set.of("userName", "displayName").contains(attribute.attribute())
                ? CONTROL_CHARACTERS : "the NUL character (U+0000)";
        assertRefusedCharacter(refused, attribute.attribute(), forbidden);
        if (target == null) {
            assertThat(jdbc.queryForObject(
                    "SELECT count(*) FROM scim_users WHERE user_name = ?", Long.class, userName))
                    .as("the refused create stored nothing").isZero();
            assertThat(refusalCodes("SCIM_USER_CREATE")).containsExactly("INVALID_VALUE");
        } else {
            assertThat(etagOf(USERS, target)).as("the refused write changed nothing")
                    .isEqualTo(etagBefore);
            assertThat(refusalCodes("SCIM_USER_REPLACE")).containsExactly("INVALID_VALUE");
        }
    }

    /** The Group table and its alias: {@code displayName} and {@code externalId}, every verb. */
    @Test
    void a_group_value_holding_a_nul_is_an_invalid_value_on_every_verb() throws Exception {
        String holding = "nul-" + NUL + "-group";
        assertRefusedCharacter(createGroup("""
                {"schemas":["%s"],"displayName":"%s"}""".formatted(GROUP_SCHEMA, holding)),
                "displayName", CONTROL_CHARACTERS);
        assertRefusedCharacter(createGroup("""
                {"schemas":["%s"],"displayName":"nul-group-ext","externalId":"%s"}"""
                .formatted(GROUP_SCHEMA, holding)), "externalId", "the NUL character (U+0000)");
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM scim_groups WHERE display_name = 'nul-group-ext'",
                Long.class)).isZero();
        assertThat(refusalCodes("SCIM_GROUP_CREATE"))
                .containsExactly("INVALID_VALUE", "INVALID_VALUE");

        MvcResult base = createGroup("""
                {"schemas":["%s"],"displayName":"nul-group"}""".formatted(GROUP_SCHEMA));
        UUID group = id(base);
        String etag = base.getResponse().getHeader(HttpHeaders.ETAG);

        assertRefusedCharacter(perform(put(GROUPS + "/" + group), etag, """
                {"schemas":["%s"],"displayName":"%s"}""".formatted(GROUP_SCHEMA, holding)),
                "displayName", CONTROL_CHARACTERS);
        assertRefusedCharacter(perform(patch(GROUPS + "/" + group), etag,
                        patchOp("displayName", "\"" + holding + "\"")),
                "displayName", CONTROL_CHARACTERS);
        assertRefusedCharacter(perform(put(GROUPS + "/" + group), etag, """
                {"schemas":["%s"],"displayName":"nul-group","externalId":"%s"}"""
                .formatted(GROUP_SCHEMA, holding)), "externalId", "the NUL character (U+0000)");
        assertRefusedCharacter(perform(patch(GROUPS + "/" + group), etag,
                        patchOp("externalId", "\"" + holding + "\"")),
                "externalId", "the NUL character (U+0000)");
        assertThat(etagOf(GROUPS, group)).isEqualTo(etag);
        assertThat(refusalCodes("SCIM_GROUP_REPLACE"))
                .containsExactly("INVALID_VALUE", "INVALID_VALUE", "INVALID_VALUE", "INVALID_VALUE");
    }

    /**
     * The names refuse every C0 control, not only NUL, because they are what an administrator
     * reads and what the logs carry; elsewhere PostgreSQL stores the other controls and so does
     * this service — a tab in {@code name.formatted} round-trips.
     */
    @Test
    void a_name_refuses_other_controls_that_another_attribute_stores() throws Exception {
        for (String control : List.of("\\n", "\\t", "\\" + "u001b", "\\" + "u007f")) {
            assertRefusedCharacter(createUser(userBody("ctl" + control + "name", null, null)),
                    "userName", CONTROL_CHARACTERS);
            assertRefusedCharacter(createUser("""
                    {"schemas":["%s"],"userName":"ctl-%s","displayName":"a%sb"}"""
                    .formatted(USER_SCHEMA, UUID.randomUUID(), control)),
                    "displayName", CONTROL_CHARACTERS);
            assertRefusedCharacter(createGroup("""
                    {"schemas":["%s"],"displayName":"ctl%sgroup"}"""
                    .formatted(GROUP_SCHEMA, control)), "displayName", CONTROL_CHARACTERS);
        }
        assertThat(refusalCodes("SCIM_USER_CREATE")).hasSize(8).containsOnly("INVALID_VALUE");
        assertThat(refusalCodes("SCIM_GROUP_CREATE")).hasSize(4).containsOnly("INVALID_VALUE");

        MvcResult stored = createUser("""
                {"schemas":["%s"],"userName":"ctl-kept-%s","name":{"formatted":"Ada\\tKing"}}"""
                .formatted(USER_SCHEMA, UUID.randomUUID()));
        assertThat(stored.getResponse().getStatus())
                .as(stored.getResponse().getContentAsString()).isEqualTo(201);
        assertThat(body(stored).get("name").get("formatted").asText()).isEqualTo("Ada\tKing");
    }

    // ---- an integrity violation the checks above the database did not catch -----------------

    /**
     * Forces a violation of a constraint that is NOT the live-name uniqueness — a CHECK added for
     * this test alone — on each of the four statements the adapters translate: the User INSERT and
     * UPDATE, the Group INSERT and rename. Each is a {@code 500} in the SCIM error document rather
     * than a {@code 409} or the container's error body, nothing is audited as a conflict, and the
     * fault is logged without the violation's message.
     */
    @Test
    void a_non_uniqueness_violation_is_a_scim_500_on_every_translated_statement(
            CapturedOutput output) throws Exception {
        MvcResult user = createUser(userBody("len-forced-user", null, null));
        MvcResult group = createGroup("""
                {"schemas":["%s"],"displayName":"len-forced-group"}""".formatted(GROUP_SCHEMA));
        jdbc.execute("ALTER TABLE scim_users ADD CONSTRAINT ck_test_forced_locale"
                + " CHECK (locale IS DISTINCT FROM '" + FORCED + "')");
        jdbc.execute("ALTER TABLE scim_groups ADD CONSTRAINT ck_test_forced_display_name"
                + " CHECK (display_name <> '" + FORCED + "')");
        try {
            assertServerError(createUser("""
                    {"schemas":["%s"],"userName":"len-forced-new","locale":"%s"}"""
                    .formatted(USER_SCHEMA, FORCED)));
            assertServerError(perform(put(USERS + "/" + id(user)),
                    user.getResponse().getHeader(HttpHeaders.ETAG), """
                    {"schemas":["%s"],"userName":"len-forced-user","locale":"%s"}"""
                    .formatted(USER_SCHEMA, FORCED)));
            assertServerError(createGroup("""
                    {"schemas":["%s"],"displayName":"%s"}""".formatted(GROUP_SCHEMA, FORCED)));
            assertServerError(perform(patch(GROUPS + "/" + id(group)),
                    group.getResponse().getHeader(HttpHeaders.ETAG),
                    patchOp("displayName", "\"" + FORCED + "\"")));
        } finally {
            jdbc.execute("ALTER TABLE scim_users DROP CONSTRAINT ck_test_forced_locale");
            jdbc.execute("ALTER TABLE scim_groups DROP CONSTRAINT ck_test_forced_display_name");
        }

        assertThat(etagOf(USERS, id(user))).isEqualTo(user.getResponse().getHeader(HttpHeaders.ETAG));
        assertThat(etagOf(GROUPS, id(group)))
                .isEqualTo(group.getResponse().getHeader(HttpHeaders.ETAG));
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM scim_users WHERE user_name = 'len-forced-new'", Long.class))
                .isZero();
        assertThat(jdbc.queryForList(
                "SELECT error_code FROM audit_events WHERE actor_id = ? AND outcome = 'FAILURE'",
                String.class, connectorId))
                .as("a fault is not recorded as a refusal of the caller's request")
                .isEmpty();

        // The test profile logs in the pattern layout, so the record's fields are asserted by
        // ScimExceptionHandlerBodyTests; here, that one record per fault was written and that
        // none of them carries the violation's message.
        List<String> faultRecords = output.getAll().lines()
                .filter(line -> line.contains("SCIM write refused by an unmapped integrity violation"))
                .toList();
        assertThat(faultRecords).hasSize(4).allSatisfy(line -> assertThat(line)
                .contains("ERROR")
                .doesNotContain(FORCED)
                .doesNotContain("ck_test_forced"));
    }

    // ---- harness ---------------------------------------------------------------------------

    private static String userBody(String userName, UserAttribute attribute, String value) {
        if (attribute == null) {
            return """
                    {"schemas":["%s"],"userName":"%s"}""".formatted(USER_SCHEMA, userName);
        }
        String fragment = attribute.fragment().apply(value);
        return attribute.attribute().equals("userName")
                ? "{\"schemas\":[\"" + USER_SCHEMA + "\"]," + fragment + "}"
                : "{\"schemas\":[\"" + USER_SCHEMA + "\"],\"userName\":\"" + userName + "\","
                        + fragment + "}";
    }

    private static UserAttribute text(String attribute, int limit) {
        return new UserAttribute(attribute, limit,
                v -> "\"" + attribute + "\":\"" + v + "\"", attribute, UserAttribute::quoted);
    }

    private static String patchOp(String path, String valueJson) {
        return """
                {"schemas":["%s"],"Operations":[{"op":"replace","path":"%s","value":%s}]}"""
                .formatted(PATCH_OP, path, valueJson);
    }

    private void assertTooLong(MvcResult result, String attribute, int limit, String value)
            throws Exception {
        String raw = result.getResponse().getContentAsString();
        assertThat(result.getResponse().getStatus()).as(raw).isEqualTo(400);
        JsonNode error = json.readTree(raw);
        assertThat(error.get("schemas").get(0).asText())
                .isEqualTo("urn:ietf:params:scim:api:messages:2.0:Error");
        assertThat(error.get("status").asText()).isEqualTo("400");
        assertThat(error.get("scimType").asText()).isEqualTo("invalidValue");
        assertThat(error.get("detail").asText())
                .isEqualTo(attribute + " must be at most " + limit + " characters long.");
        assertThat(raw).doesNotContain(value);
    }

    private void assertRefusedCharacter(MvcResult result, String attribute, String forbidden)
            throws Exception {
        String raw = result.getResponse().getContentAsString();
        assertThat(result.getResponse().getStatus()).as(raw).isEqualTo(400);
        JsonNode error = json.readTree(raw);
        assertThat(error.get("schemas").get(0).asText())
                .isEqualTo("urn:ietf:params:scim:api:messages:2.0:Error");
        assertThat(error.get("status").asText()).isEqualTo("400");
        assertThat(error.get("scimType").asText()).isEqualTo("invalidValue");
        assertThat(error.get("detail").asText())
                .isEqualTo(attribute + " must not contain " + forbidden + ".");
        // Neither the value around the character nor the character itself is echoed.
        assertThat(raw).doesNotContain("nul-").doesNotContain("ctl").doesNotContain(NUL)
                .doesNotContain(String.valueOf((char) 0));
    }

    private void assertServerError(MvcResult result) throws Exception {
        String raw = result.getResponse().getContentAsString();
        assertThat(result.getResponse().getStatus()).as(raw).isEqualTo(500);
        assertThat(result.getResponse().getContentType()).startsWith("application/scim+json");
        JsonNode error = json.readTree(raw);
        assertThat(error.get("schemas").get(0).asText())
                .isEqualTo("urn:ietf:params:scim:api:messages:2.0:Error");
        assertThat(error.get("status").asText()).isEqualTo("500");
        assertThat(error.has("scimType")).isFalse();
        assertThat(error.get("detail").asText()).isNotBlank();
        assertThat(raw).doesNotContain(FORCED).doesNotContain("ck_test_forced");
    }

    private MvcResult createUser(String body) throws Exception {
        return track(mvc.perform(asConnector(post(USERS)).contentType(SCIM_JSON).content(body))
                .andReturn());
    }

    private MvcResult createGroup(String body) throws Exception {
        return track(mvc.perform(asConnector(post(GROUPS)).contentType(SCIM_JSON).content(body))
                .andReturn());
    }

    private synchronized MvcResult track(MvcResult result) throws Exception {
        if (result.getResponse().getStatus() == 201) {
            created.add(id(result));
        }
        return result;
    }

    private MvcResult perform(MockHttpServletRequestBuilder request, String etag, String body)
            throws Exception {
        return mvc.perform(asConnector(request)
                        .header(HttpHeaders.IF_MATCH, etag)
                        .contentType(SCIM_JSON)
                        .content(body))
                .andReturn();
    }

    private String etagOf(String collection, UUID id) throws Exception {
        MvcResult read = mvc.perform(asConnector(get(collection + "/" + id))).andReturn();
        assertThat(read.getResponse().getStatus()).isEqualTo(200);
        return read.getResponse().getHeader(HttpHeaders.ETAG);
    }

    /** The error codes of this connector's refused events of one operation, oldest first. */
    private List<String> refusalCodes(String operation) {
        return jdbc.queryForList("""
                        SELECT error_code FROM audit_events
                        WHERE operation = ? AND actor_id = ? AND outcome = 'FAILURE'
                        ORDER BY occurred_at""",
                String.class, operation, connectorId);
    }

    private MockHttpServletRequestBuilder asConnector(MockHttpServletRequestBuilder request) {
        return request.header(HttpHeaders.AUTHORIZATION, "Bearer " + writeToken);
    }

    private UUID id(MvcResult result) throws Exception {
        return UUID.fromString(body(result).get("id").asText());
    }

    private JsonNode body(MvcResult result) throws Exception {
        return json.readTree(result.getResponse().getContentAsString());
    }
}

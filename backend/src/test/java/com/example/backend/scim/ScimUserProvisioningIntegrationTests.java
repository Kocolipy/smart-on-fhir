package com.example.backend.scim;

import static com.example.backend.ScimHttp.SCIM_JSON;
import static com.example.backend.ScimHttp.asConnector;
import static com.example.backend.TestJson.JSON;
import static com.example.backend.TestJson.body;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import com.example.backend.CreatedScimRows;
import com.example.backend.ContainerTestConfiguration;
import com.example.backend.ScimTestRows;
import com.example.backend.TokenPermissions;
import com.example.backend.UuidColumnRows;
import com.example.backend.audit.domain.AuditOperation;
import com.example.backend.observability.RequestIdFilter;
import com.example.backend.scim.application.ConnectorAdministrationService;
import jakarta.servlet.Filter;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.JsonNode;

/**
 * The ticket's demo oracle: a connector drives creation and retrieval over the real filter
 * chain against a real SQL Server, and the stored rows and audit rows are read back with SQL.
 *
 * <p>SQL rather than a repository for the state assertions, for the reason the connector
 * lifecycle test gives: what is claimed about a password is a claim about the BYTES that
 * landed, and a mapping's opinion of them is not the same evidence.
 */
@SpringBootTest
@ExtendWith(OutputCaptureExtension.class)
@Import(ContainerTestConfiguration.class)
class ScimUserProvisioningIntegrationTests {

    private static final String USERS = "/scim/v2/Users";

    private static final String GROUPS = "/scim/v2/Groups";

    private static final String USER_SCHEMA = "urn:ietf:params:scim:schemas:core:2.0:User";

    private static final String GROUP_SCHEMA = "urn:ietf:params:scim:schemas:core:2.0:Group";

    private static final String PASSWORD = "correct horse battery staple";

    private static final String USER_EVENTS_BY_ACTOR =
            "SELECT * FROM audit_events WHERE operation = ? AND actor_id = ? ORDER BY occurred_at";

    private static final String HASH_OF =
            "SELECT password_hash FROM scim_users WHERE resource_id = ?";

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private ConnectorAdministrationService connectors;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private RequestIdFilter requestIdFilter;

    @Autowired
    @Qualifier("springSecurityFilterChain")
    private Filter springSecurityFilterChain;

    private MockMvc mvc;

    private UUID connectorId;

    private String writeToken;

    /** Every resource this test created, so teardown removes exactly those and nothing else. */
    private final CreatedScimRows createdResources = new CreatedScimRows();

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context)
                .addFilters(requestIdFilter, springSecurityFilterChain)
                .build();
        connectorId = connectors.create(ScimTestRows.unique("Okta"), null).id();
        writeToken = issueTokenFor(connectorId, TokenPermissions.ALL);
    }

    /**
     * Removes the resources THIS test created, and only those.
     *
     * <p>This class shares one Spring context and one database with every other integration
     * test, and {@code userName} and {@code displayName} are server-unique. Without teardown a
     * name created here (e.g. "member") leaks into a later class that creates the same name,
     * which then gets a {@code 409} depending only on Surefire's class ordering. Scoped to
     * tracked ids for the same reason ScimGroupProvisioningIntegrationTests gives: a broader
     * delete would remove other classes' fixtures.
     *
     * <p>Deleting the {@code scim_resources} row cascades to the type-specific table and to any
     * membership, so this is one statement per resource regardless of its kind.
     */
    @AfterEach
    void removeOnlyWhatThisTestCreated() {
        createdResources.deleteAll(jdbc);
    }

    /**
     * A create with a password: the canonical resource, its location and its validator, and
     * no trace of the credential anywhere in the response.
     */
    @Test
    void a_user_is_created_with_a_password_that_never_appears_in_the_response()
            throws Exception {
        MvcResult created = create("""
                {"schemas":["%s"],
                 "userName":"bjensen",
                 "externalId":"701984",
                 "name":{"givenName":"Barbara","familyName":"Jensen"},
                 "displayName":"Babs Jensen",
                 "emails":[{"value":"bjensen@example.com","type":"work","primary":true}],
                 "password":"%s"}""".formatted(USER_SCHEMA, PASSWORD));

        assertThat(created.getResponse().getStatus()).isEqualTo(201);
        JsonNode body = body(created);
        UUID id = UUID.fromString(body.get("id").asText());

        assertThat(created.getResponse().getHeader(HttpHeaders.LOCATION))
                .endsWith(USERS + "/" + id);
        assertThat(created.getResponse().getHeader(HttpHeaders.ETAG))
                .isEqualTo("\"1\"")
                .isEqualTo(body.get("meta").get("version").asText());
        assertThat(body.get("schemas").get(0).asText()).isEqualTo(USER_SCHEMA);
        assertThat(body.get("userName").asText()).isEqualTo("bjensen");
        assertThat(body.get("externalId").asText()).isEqualTo("701984");
        assertThat(body.get("active").booleanValue())
                .as("active defaults to true on create")
                .isTrue();
        assertThat(body.get("meta").get("resourceType").asText()).isEqualTo("User");
        assertThat(body.get("meta").get("created").asText()).endsWith("Z");

        // The whole response body, not only the attributes looked at above: neither the
        // plaintext nor the stored hash is anywhere in it.
        String rendered = created.getResponse().getContentAsString();
        assertThat(rendered).doesNotContain(PASSWORD);
        assertThat(rendered).doesNotContain("password");
        assertThat(rendered).doesNotContain(storedHash(id));

        // Hashed immediately: the stored value verifies the password and is not it.
        String hash = storedHash(id);
        assertThat(hash).isNotEqualTo(PASSWORD).startsWith("{argon2id}");
        assertThat(passwordEncoder.matches(PASSWORD, hash)).isTrue();
    }

    /**
     * {@code primary} is rendered on every email, false included, so a client comparing what it
     * wrote with what it reads back sees the flag it sent — including one it left out, which
     * RFC 7643 §2.4 makes false.
     */
    @Test
    void every_email_renders_its_primary_flag_false_included() throws Exception {
        MvcResult created = create("""
                {"schemas":["%s"],"userName":"primary-flags",
                 "emails":[{"value":"a@work.example","type":"work","primary":true},
                           {"value":"b@home.example","type":"home","primary":false},
                           {"value":"c@other.example","type":"other"}]}"""
                .formatted(USER_SCHEMA));

        assertThat(created.getResponse().getStatus()).isEqualTo(201);
        JsonNode emails = body(created).get("emails");
        assertThat(emails).hasSize(3);
        assertThat(emails.get(0).get("primary").isBoolean()).isTrue();
        assertThat(emails.get(0).get("primary").booleanValue()).isTrue();
        for (int i = 1; i < 3; i++) {
            assertThat(emails.get(i).get("primary")).as("email %d", i).isNotNull();
            assertThat(emails.get(i).get("primary").isBoolean()).isTrue();
            assertThat(emails.get(i).get("primary").booleanValue()).isFalse();
        }
    }

    /** A credentialless User is a supported state, not a rejected one. */
    @Test
    void a_user_is_created_without_a_password_and_stores_no_hash() throws Exception {
        MvcResult created = create("""
                {"schemas":["%s"],"userName":"credentialless"}""".formatted(USER_SCHEMA));

        assertThat(created.getResponse().getStatus()).isEqualTo(201);
        UUID id = UUID.fromString(body(created).get("id").asText());
        assertThat(storedHash(id)).isNull();
    }

    /**
     * Nothing but an explicit {@code active=true} reactivates a User: a PUT that leaves
     * {@code active} out keeps it deactivated, and a PATCH that removes it is refused rather
     * than read as the create default. Neither request carries {@code If-Match}, which is the
     * shape a provisioning client that does not track ETags sends.
     */
    @Test
    void a_deactivated_user_is_not_reactivated_by_omitting_or_removing_active() throws Exception {
        MvcResult created = create("""
                {"schemas":["%s"],"userName":"stays-inactive","active":false}"""
                .formatted(USER_SCHEMA));
        UUID id = UUID.fromString(body(created).get("id").asText());

        MvcResult replaced = mvc.perform(asConnector(put(USERS + "/" + id), writeToken).contentType(SCIM_JSON)
                        .content("""
                                {"schemas":["%s"],"userName":"stays-inactive",
                                 "displayName":"Renamed"}""".formatted(USER_SCHEMA)))
                .andReturn();
        assertThat(replaced.getResponse().getStatus()).isEqualTo(200);
        assertThat(body(replaced).get("active").asBoolean(true)).as("PUT without active").isFalse();
        assertThat(body(replaced).get("displayName").asText()).isEqualTo("Renamed");

        MvcResult removed = mvc.perform(asConnector(patch(USERS + "/" + id), writeToken).contentType(SCIM_JSON)
                        .content("""
                                {"schemas":["urn:ietf:params:scim:api:messages:2.0:PatchOp"],
                                 "Operations":[{"op":"remove","path":"active"}]}"""))
                .andReturn();
        assertRefusal(removed, 400, "mutability");
        assertThat(readAs(writeToken, id).get("active").asBoolean(true)).isFalse();

        MvcResult reactivated = mvc.perform(asConnector(put(USERS + "/" + id), writeToken).contentType(SCIM_JSON)
                        .content("""
                                {"schemas":["%s"],"userName":"stays-inactive","active":true}"""
                                .formatted(USER_SCHEMA)))
                .andReturn();
        assertThat(body(reactivated).get("active").asBoolean(false)).as("explicit true").isTrue();
    }

    /**
     * The reverse membership view, end to end: a User added to a Group renders that
     * membership, with the label and the {@code $ref} derived rather than submitted.
     *
     * <p>{@code type} is {@code direct} for every entry because this directory has no nested
     * Groups, so there is no indirect membership it could report.
     */
    @Test
    void a_user_added_to_a_group_renders_that_membership_as_a_read_only_view()
            throws Exception {
        UUID id = UUID.fromString(body(create("""
                {"schemas":["%s"],"userName":"member"}""".formatted(USER_SCHEMA)))
                .get("id").asText());
        String versionBeforeMembership =
                readAs(writeToken, id).get("meta").get("version").asText();

        MvcResult group = mvc.perform(asConnector(post(GROUPS), writeToken)
                        .contentType(SCIM_JSON)
                        .content("""
                                {"schemas":["%s"],
                                 "displayName":"Engineering",
                                 "members":[{"value":"%s"}]}"""
                                .formatted(GROUP_SCHEMA, id)))
                .andReturn();
        assertThat(group.getResponse().getStatus()).isEqualTo(201);
        UUID groupId = UUID.fromString(body(group).get("id").asText());
        createdResources.add(groupId);

        JsonNode reread = readAs(writeToken, id);
        assertThat(reread.get("groups")).hasSize(1);
        JsonNode membership = reread.get("groups").get(0);
        assertThat(membership.get("value").asText()).isEqualTo(groupId.toString());
        assertThat(membership.get("display").asText()).isEqualTo("Engineering");
        assertThat(membership.get("$ref").asText()).endsWith("/scim/v2/Groups/" + groupId);
        assertThat(membership.get("type").asText()).isEqualTo("direct");

        // The membership advanced the User's OWN version too, because its representation changed:
        // the `groups` attribute above was not there a moment ago. This assertion is the reason
        // the defect behind it was found — ScimGroupRepository#create made no version-bump promise
        // while replace and deleteById did, so a create with members left every member behind an
        // ETag saying nothing had happened. Reading the version before and after rather than
        // asserting a literal, so the claim is "it moved" rather than "it is 2".
        assertThat(reread.get("meta").get("version").asText())
                .as("a User whose rendered groups changed must not sit behind an unmoved ETag")
                .isNotEqualTo(versionBeforeMembership);
    }

    /** A live {@code userName} is taken, case-insensitively, and the refusal is a conflict. */
    @Test
    void a_live_user_name_conflict_is_refused_as_a_uniqueness_conflict() throws Exception {
        create("""
                {"schemas":["%s"],"userName":"taken"}""".formatted(USER_SCHEMA));
        long before = users();

        MvcResult refused = create("""
                {"schemas":["%s"],"userName":"TAKEN"}""".formatted(USER_SCHEMA));

        assertThat(refused.getResponse().getStatus()).isEqualTo(409);
        JsonNode error = body(refused);
        assertThat(error.get("schemas").get(0).asText())
                .isEqualTo("urn:ietf:params:scim:api:messages:2.0:Error");
        assertThat(error.get("status").asText()).isEqualTo("409");
        assertThat(error.get("scimType").asText()).isEqualTo("uniqueness");
        assertThat(users()).as("the refused create stored nothing").isEqualTo(before);

        // The refusal is recorded, so a connector retrying against a name it will never get
        // is visible in the trail rather than silent.
        assertThat(eventsOf(AuditOperation.SCIM_USER_CREATE))
                .anySatisfy(event -> {
                    assertThat(event.get("outcome")).isEqualTo("FAILURE");
                    assertThat(event.get("error_code")).isEqualTo("UNIQUENESS");
                    assertThat(event.get("subject_id")).isNull();
                });
    }

    /**
     * The read-only-token scope check, deferred from the connector ticket, now against a real
     * mutating endpoint: refused before the handler, and nothing created.
     */
    @Test
    void a_read_only_token_cannot_create_and_creates_nothing() throws Exception {
        String readOnly = issueTokenFor(connectorId, TokenPermissions.READ);
        long before = users();

        MvcResult refused = mvc.perform(post(USERS)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + readOnly)
                        .contentType(SCIM_JSON)
                        .content("""
                                {"schemas":["%s"],"userName":"by-read-only"}"""
                                .formatted(USER_SCHEMA)))
                .andReturn();

        assertThat(refused.getResponse().getStatus()).isEqualTo(403);
        assertThat(refused.getResponse().getHeader(HttpHeaders.WWW_AUTHENTICATE))
                .isEqualTo("Bearer error=\"insufficient_scope\"");
        assertThat(users()).isEqualTo(before);

        // And the same token reads perfectly well, so the refusal was about the mutation and
        // not about the credential.
        assertThat(mvc.perform(get(USERS).header(HttpHeaders.AUTHORIZATION, "Bearer " + readOnly))
                        .andReturn().getResponse().getStatus())
                .isEqualTo(200);
    }

    /**
     * Excluding the password is uneventful. That asking for it omits it silently, on both reads,
     * is the projection tests' and the conformance sweep's.
     */
    @Test
    void excluding_the_password_is_uneventful() throws Exception {
        UUID id = UUID.fromString(body(create("""
                {"schemas":["%s"],"userName":"projected","password":"%s"}"""
                .formatted(USER_SCHEMA, PASSWORD)))
                .get("id").asText());

        assertThat(mvc.perform(asConnector(
                        get(USERS + "/" + id).param("excludedAttributes", "password"), writeToken))
                        .andReturn().getResponse().getStatus())
                .isEqualTo(200);
    }

    /**
     * A schema extension this service does not implement, and a sub-attribute its schema does
     * not declare: both SCIM errors. The rest of the refusals this endpoint produces are the
     * conformance sweep's.
     */
    @Test
    void every_refusal_this_endpoint_produces_is_a_scim_error_document() throws Exception {
        assertRefusal(create("""
                {"schemas":["%s","urn:ietf:params:scim:schemas:extension:enterprise:2.0:User"],
                 "userName":"extension"}""".formatted(USER_SCHEMA)), 400, "invalidValue");
        assertRefusal(create("""
                {"schemas":["%s"],"userName":"bad-sub","name":{"nickName":"Babs"}}"""
                .formatted(USER_SCHEMA)), 400, "invalidValue");
    }

    /**
     * A paging parameter that is not an integer is a {@code 400 invalidValue} SCIM error document
     * naming the parameter, not a container's own type-conversion failure: the parameters are read
     * as strings and parsed by the service precisely so the refusal is one. Which values are
     * refused, coerced or defaulted is {@code ScimQueryRequestTests}'; this is the one case that
     * the refusal reaches a connector as SCIM.
     */
    @Test
    void a_paging_parameter_that_is_not_an_integer_is_a_scim_refusal() throws Exception {
        MvcResult result = mvc.perform(asConnector(get(USERS).param("count", "many"), writeToken))
                .andReturn();

        assertRefusal(result, 400, "invalidValue");
        assertThat(body(result).get("detail").asText()).contains("count");
    }

    /** Audit: a create leaves one event, naming what it created and the request. */
    @Test
    void a_create_is_audited() throws Exception {
        UUID id = UUID.fromString(body(create("""
                {"schemas":["%s"],"userName":"audited"}""".formatted(USER_SCHEMA)))
                .get("id").asText());

        assertThat(eventsOf(AuditOperation.SCIM_USER_CREATE))
                .singleElement()
                .satisfies(event -> {
                    assertThat(event.get("subject_id")).hasToString(id.toString());
                    assertThat(event.get("resource_id")).hasToString(id.toString());
                    assertThat(event.get("resource_type")).isEqualTo("User");
                    assertThat(event.get("outcome")).isEqualTo("SUCCESS");
                    assertThat(event.get("status_class")).isEqualTo("ok");
                    assertThat(event.get("http_method")).isEqualTo("POST");
                    assertThat(event.get("request_id")).isNotNull();
                });
    }

    /**
     * A refusal is audited by what it is, not by which module raised it: a PATCH naming a read-only
     * attribute is refused by the request reader, one removing {@code userName} by the directory,
     * and both are {@code mutability} — so each is recorded once, and alike, naming the User the
     * write was aimed at.
     */
    @Test
    void a_reader_refusal_is_audited_once_like_the_same_refusal_from_the_directory()
            throws Exception {
        UUID id = UUID.fromString(body(create("""
                {"schemas":["%s"],"userName":"refused-alike"}""".formatted(USER_SCHEMA)))
                .get("id").asText());
        for (String operation : List.of(
                "{\"op\":\"replace\",\"path\":\"id\",\"value\":\"another\"}",
                "{\"op\":\"remove\",\"path\":\"userName\"}")) {
            mvc.perform(asConnector(patch(USERS + "/" + id), writeToken).contentType(SCIM_JSON).content("""
                    {"schemas":["urn:ietf:params:scim:api:messages:2.0:PatchOp"],
                     "Operations":[%s]}""".formatted(operation))).andReturn();
        }

        assertThat(eventsOf(AuditOperation.SCIM_USER_REPLACE).stream()
                        .map(event -> event.get("outcome") + " " + event.get("error_code") + " "
                                + event.get("subject_id"))
                        .toList())
                .containsExactly("FAILURE MUTABILITY " + id, "FAILURE MUTABILITY " + id);
    }

    /**
     * A write refused while its request is read is recorded naming the id it addressed, whether or
     * not a User holds it: the reader refuses before the User is looked up, and the attempt is the
     * act on record. A well-formed write to that id is a {@code 404}, which is not recorded.
     */
    @Test
    void a_reader_refusal_on_an_id_naming_no_user_is_recorded_and_its_not_found_is_not()
            throws Exception {
        UUID nobody = UUID.fromString("5f0c2b8e-1d4a-4e6b-9c3f-2a7d8e9b0c1d");
        for (String operation : List.of(
                "{\"op\":\"replace\",\"path\":\"id\",\"value\":\"another\"}",
                "{\"op\":\"replace\",\"path\":\"displayName\",\"value\":\"Nobody\"}")) {
            mvc.perform(asConnector(patch(USERS + "/" + nobody), writeToken).contentType(SCIM_JSON).content("""
                    {"schemas":["urn:ietf:params:scim:api:messages:2.0:PatchOp"],
                     "Operations":[%s]}""".formatted(operation))).andReturn();
        }

        assertThat(eventsOf(AuditOperation.SCIM_USER_REPLACE).stream()
                        .map(event -> event.get("outcome") + " " + event.get("error_code") + " "
                                + event.get("subject_id"))
                        .toList())
                .containsExactly("FAILURE MUTABILITY " + nobody);
    }

    /**
     * One password policy on every SCIM path that sets a password: a sub-policy value is refused on
     * POST, PUT and PATCH as {@code 400 invalidValue} naming the rule, the refusal is audited, and
     * the value reaches no response, no audit row and no log line.
     */
    @ParameterizedTest
    @CsvSource({"short-pw-1,TOO_SHORT", "i-am-POLICY-USER-truly,CONTAINS_USER_NAME"})
    void a_sub_policy_password_is_refused_on_every_setting_path(
            String candidate, String rule, CapturedOutput output) throws Exception {
        MvcResult refusedCreate = create("""
                {"schemas":["%s"],"userName":"policy-user","password":"%s"}"""
                .formatted(USER_SCHEMA, candidate));
        assertPolicyRefusal(refusedCreate, candidate, rule);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM scim_users WHERE user_name = 'policy-user'", Long.class))
                .isZero();
        assertThat(eventsOf(AuditOperation.SCIM_USER_CREATE)).singleElement()
                .satisfies(event -> assertThat(event.get("error_code")).isEqualTo("INVALID_VALUE"));

        MvcResult created = create("""
                {"schemas":["%s"],"userName":"policy-user","password":"%s"}"""
                .formatted(USER_SCHEMA, PASSWORD));
        UUID id = UUID.fromString(body(created).get("id").asText());
        String etag = created.getResponse().getHeader(HttpHeaders.ETAG);
        String hash = storedHash(id);

        assertPolicyRefusal(mvc.perform(asConnector(put(USERS + "/" + id), writeToken)
                .header(HttpHeaders.IF_MATCH, etag).contentType(SCIM_JSON).content("""
                        {"schemas":["%s"],"userName":"policy-user","password":"%s"}"""
                        .formatted(USER_SCHEMA, candidate))).andReturn(), candidate, rule);
        assertPolicyRefusal(mvc.perform(asConnector(patch(USERS + "/" + id), writeToken)
                .header(HttpHeaders.IF_MATCH, etag).contentType(SCIM_JSON).content("""
                        {"schemas":["urn:ietf:params:scim:api:messages:2.0:PatchOp"],
                         "Operations":[{"op":"replace","path":"password","value":"%s"}]}"""
                        .formatted(candidate))).andReturn(), candidate, rule);

        assertThat(storedHash(id)).as("nothing was written").isEqualTo(hash);
        assertThat(eventsOf(AuditOperation.SCIM_USER_REPLACE))
                .extracting(event -> event.get("error_code"))
                .containsExactly("INVALID_VALUE", "INVALID_VALUE");
        assertThat(jdbc.queryForList("SELECT * FROM audit_events").toString())
                .doesNotContain(candidate);
        assertThat(output.getAll()).doesNotContain(candidate);
    }

    private void assertPolicyRefusal(MvcResult result, String candidate, String rule)
            throws Exception {
        assertRefusal(result, 400, "invalidValue");
        assertThat(body(result).get("detail").asText()).contains(rule);
        assertThat(result.getResponse().getContentAsString()).doesNotContain(candidate);
    }

    /** No audit event anywhere carries the password or a hash. */
    @Test
    void no_recorded_event_carries_a_credential() throws Exception {
        create("""
                {"schemas":["%s"],"userName":"never-logged","password":"%s"}"""
                .formatted(USER_SCHEMA, PASSWORD));

        assertThat(jdbc.queryForList("SELECT * FROM audit_events").toString())
                .doesNotContain(PASSWORD)
                .doesNotContain("argon2id")
                .doesNotContain("never-logged");
    }

    /** Every User create goes through here, so every created User is tracked for teardown. */
    private MvcResult create(String body) throws Exception {
        return createdResources.track(mvc.perform(asConnector(post(USERS), writeToken).contentType(SCIM_JSON).content(body))
                .andReturn());
    }

    private JsonNode readAs(String token, UUID id) throws Exception {
        return JSON.readTree(rawAs(token, id));
    }

    private String rawAs(String token, UUID id) throws Exception {
        MvcResult read = mvc.perform(get(USERS + "/" + id)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andReturn();
        assertThat(read.getResponse().getStatus()).isEqualTo(200);
        return read.getResponse().getContentAsString();
    }

    private void assertRefusal(MvcResult result, int status, String scimType) throws Exception {
        assertThat(result.getResponse().getStatus()).isEqualTo(status);
        JsonNode error = body(result);
        assertThat(error.get("schemas").get(0).asText())
                .isEqualTo("urn:ietf:params:scim:api:messages:2.0:Error");
        assertThat(error.get("status").asText()).isEqualTo(String.valueOf(status));
        assertThat(error.get("scimType").asText()).isEqualTo(scimType);
        assertThat(error.get("detail").asText()).isNotBlank();
    }

    private String issueTokenFor(UUID connector, java.util.Set<com.example.backend.authorization.domain.Permission> scope) {
        return connectors.issueToken(connector, scope, null, null).presentedValue();
    }

    private List<Map<String, Object>> eventsOf(AuditOperation operation) {
        return jdbc.query(USER_EVENTS_BY_ACTOR, UuidColumnRows.ROWS, operation.name(), connectorId);
    }

    private String storedHash(UUID id) {
        return jdbc.queryForObject(HASH_OF, String.class, id);
    }

    private long users() {
        return jdbc.queryForObject("SELECT count(*) FROM scim_users", Long.class);
    }
}

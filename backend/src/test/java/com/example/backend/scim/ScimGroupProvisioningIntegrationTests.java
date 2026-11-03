package com.example.backend.scim;

import static com.example.backend.ScimHttp.SCIM_JSON;
import static com.example.backend.ScimHttp.asConnector;
import static com.example.backend.TestJson.body;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import com.example.backend.ContainerTestConfiguration;
import com.example.backend.CreatedScimRows;
import com.example.backend.TestJson;
import com.example.backend.TokenPermissions;
import com.example.backend.observability.RequestIdFilter;
import com.example.backend.scim.application.ConnectorAdministrationService;
import jakarta.servlet.Filter;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.JsonNode;

/**
 * The Group endpoints driven over the real filter chain against a real SQL Server, with the stored
 * rows read back in SQL.
 *
 * <p>The counterpart of {@link ScimUserProvisioningIntegrationTests}, and it exists because
 * mutation testing found it missing: the whole Group HTTP surface — the controller, the request
 * reader, and the persistence adapter's delete — was reached by no test at all, and the Group
 * behaviour was verified only at service level against an in-memory fake. The fake and the
 * adapter can disagree — about whether a create advances its members' versions, say — while every
 * unit test passes; only a test against the database catches it.
 *
 * <p>So the assertions here are deliberately about the things only this layer decides: which
 * status code each refusal becomes, what the {@code Location} and {@code ETag} carry, which
 * request bodies the reader refuses before the use case sees them, and what remains in the
 * database afterwards.
 */
@SpringBootTest
@Import(ContainerTestConfiguration.class)
class ScimGroupProvisioningIntegrationTests {

    private static final String GROUPS = "/scim/v2/Groups";

    private static final String USERS = "/scim/v2/Users";

    private static final String GROUP_SCHEMA = "urn:ietf:params:scim:schemas:core:2.0:Group";

    private static final String USER_SCHEMA = "urn:ietf:params:scim:schemas:core:2.0:User";

    private static final String PATCH_OP = "urn:ietf:params:scim:api:messages:2.0:PatchOp";

    private static final String ERROR_SCHEMA = "urn:ietf:params:scim:api:messages:2.0:Error";

    private static final String MEMBERS_OF =
            "SELECT count(*) FROM scim_group_members WHERE group_id = ?";

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

    private String writeToken;

    /** Every resource this test created, so teardown removes exactly those and nothing else. */
    private final CreatedScimRows createdResources = new CreatedScimRows();

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context)
                .addFilters(requestIdFilter, springSecurityFilterChain)
                .build();
        UUID connectorId = connectors.create("Okta", null).id();
        writeToken = connectors
                .issueToken(connectorId, TokenPermissions.ALL, null, null)
                .presentedValue();
    }

    /**
     * Removes the resources THIS test created, and only those.
     *
     * <p>Needed because this class shares one Spring context and one database across its methods
     * and with every other integration test, while {@code displayName} and {@code userName} are
     * server-unique: without teardown the second method to create "Engineering-grpit" gets a
     * {@code 409} and the failure reads as a defect in uniqueness rather than test bleed.
     *
     * <p>Scoped to tracked ids rather than "everything not reserved", which is what this first
     * did. That version passed the ordinary gate and then broke 39 tests in OTHER classes the
     * moment the suite ran in a single JVM, because it deleted their fixtures too — a test that
     * empties a shared directory is not isolating itself, it is corrupting its neighbours, and
     * Surefire's ordering was the only thing hiding it.
     *
     * <p>Deleting the {@code scim_resources} row cascades to the type-specific table and to any
     * membership, so this is one statement per resource regardless of its kind.
     */
    @AfterEach
    void removeOnlyWhatThisTestCreated() {
        createdResources.deleteAll(jdbc);
    }

    // ---- retrieval ----------------------------------------------------------------------------

    /**
     * An id that names nothing and an id that is not a UUID answer the SAME {@code 404}, on the
     * Users as on the Groups.
     *
     * <p>Indistinguishable on purpose: telling a caller its id was the wrong SHAPE discloses what
     * shape the real ones have, and "this id names nothing" is the truthful answer to both.
     */
    /** The same on the delete path as on the read path. */
    @ParameterizedTest(name = "{0} {1}/{2}")
    @CsvSource({
        "GET, Users, 6f9c1f66-0000-4000-8000-00000000dead, No User has that id.",
        "GET, Users, not-a-uuid, No User has that id.",
        "GET, Users, 12345, No User has that id.",
        "GET, Groups, 6f9c1f66-0000-4000-8000-00000000dead, No Group has that id.",
        "GET, Groups, not-a-uuid, No Group has that id.",
        "GET, Groups, 12345, No Group has that id.",
        "DELETE, Users, 6f9c1f66-0000-4000-8000-00000000dead, No User has that id.",
        "DELETE, Users, not-a-uuid, No User has that id.",
        "DELETE, Users, 12345, No User has that id.",
        "DELETE, Groups, 6f9c1f66-0000-4000-8000-00000000dead, No Group has that id.",
        "DELETE, Groups, not-a-uuid, No Group has that id.",
        "DELETE, Groups, 12345, No Group has that id."})
    void an_unknown_or_malformed_id_is_the_same_not_found(
            String method, String collection, String id, String detail) throws Exception {
        String path = "/scim/v2/" + collection + "/" + id;
        MvcResult answered = mvc.perform(asConnector(
                "GET".equals(method) ? get(path) : delete(path), writeToken, jdbc)).andReturn();

        assertRefusal(answered, 404, null);
        assertThat(body(answered).get("detail").asText()).isEqualTo(detail);
    }

    /** A User's id is not a Group's id, and asking for one as the other reveals neither. */
    @Test
    void a_user_id_is_not_found_as_a_group() throws Exception {
        UUID userId = createUser("solo");

        assertRefusal(mvc.perform(asConnector(get(GROUPS + "/" + userId), writeToken, jdbc)).andReturn(), 404, null);
    }

    // ---- the collection -----------------------------------------------------------------------

    /**
     * A query parameter naming something a Group does not have is refused, never ignored: an
     * ignored filter would return every Group to a caller that asked for some. {@code userName}
     * is a User attribute, so it is unknown here.
     */
    @ParameterizedTest
    @CsvSource({
            "filter,    userName eq \"x\",  invalidFilter",
            "sortBy,    userName,           invalidValue",
            "sortOrder, sideways,           invalidValue"})
    void a_query_parameter_this_resource_type_cannot_honour_is_refused(
            String parameter, String value, String scimType) throws Exception {
        MvcResult refused = mvc.perform(asConnector(get(GROUPS).param(parameter, value), writeToken, jdbc))
                .andReturn();

        assertRefusal(refused, 400, scimType);
    }

    // ---- replace ------------------------------------------------------------------------------

    /**
     * A PUT states the resource's whole value, so a {@code members} the document does not mention
     * is set to nothing rather than left alone. That is the difference between PUT and PATCH, and
     * this asserts it against the database rather than against the response.
     */
    @Test
    void a_put_replaces_the_whole_resource_including_an_omitted_membership() throws Exception {
        UUID member = createUser("member");
        UUID id = idOf(createGroup("""
                {"schemas":["%s"],"displayName":"Engineering-grpit","members":[{"value":"%s"}]}"""
                .formatted(GROUP_SCHEMA, member)));
        assertThat(memberCount(id)).isEqualTo(1);

        MvcResult replaced = mvc.perform(asConnector(put(GROUPS + "/" + id), writeToken, jdbc)
                        .contentType(SCIM_JSON)
                        .content("""
                                {"schemas":["%s"],"displayName":"Platform-grpit"}"""
                                .formatted(GROUP_SCHEMA)))
                .andReturn();

        assertThat(replaced.getResponse().getStatus()).isEqualTo(200);
        assertThat(body(replaced).get("displayName").asText()).isEqualTo("Platform-grpit");
        assertThat(memberCount(id))
                .as("a PUT with no members states a membership of nothing")
                .isZero();
    }

    /**
     * Re-PUTting identical state changes no version and no ETag.
     *
     * <p>A provisioning system converging on a desired state re-sends constantly, so this is the
     * common case rather than an edge one. Advancing the version anyway would invalidate the cached
     * copy a connector's idempotent re-send is trying to confirm, and contradict the audit event
     * for the same write, which records no changed attribute.
     */
    @Test
    void re_putting_identical_state_moves_neither_the_version_nor_the_etag() throws Exception {
        UUID member = createUser("steady");
        UUID id = idOf(createGroup("""
                {"schemas":["%s"],"displayName":"Engineering-grpit","members":[{"value":"%s"}]}"""
                .formatted(GROUP_SCHEMA, member)));
        MvcResult before = mvc.perform(asConnector(get(GROUPS + "/" + id), writeToken, jdbc)).andReturn();
        String versionBefore = body(before).get("meta").get("version").asText();
        String etagBefore = before.getResponse().getHeader(HttpHeaders.ETAG);

        MvcResult replayed = mvc.perform(asConnector(put(GROUPS + "/" + id), writeToken, jdbc)
                        .contentType(SCIM_JSON)
                        .content("""
                                {"schemas":["%s"],
                                 "displayName":"Engineering-grpit",
                                 "members":[{"value":"%s"}]}"""
                                .formatted(GROUP_SCHEMA, member)))
                .andReturn();

        assertThat(replayed.getResponse().getStatus()).isEqualTo(200);
        assertThat(body(replayed).get("meta").get("version").asText())
                .as("an idempotent re-send must not invalidate every connector's cached copy")
                .isEqualTo(versionBefore);
        assertThat(replayed.getResponse().getHeader(HttpHeaders.ETAG)).isEqualTo(etagBefore);

        JsonNode memberAfter = body(mvc.perform(asConnector(get(USERS + "/" + member), writeToken, jdbc))
                .andReturn());
        assertThat(memberAfter.get("groups")).hasSize(1);
    }

    // ---- the request reader's refusals ---------------------------------------------------------

    /** An attribute this service does not implement is refused, not silently dropped. */
    @Test
    void an_unimplemented_group_attribute_is_refused_naming_it() throws Exception {
        MvcResult refused = createGroup("""
                {"schemas":["%s"],"displayName":"Engineering-grpit","nickName":"eng"}"""
                .formatted(GROUP_SCHEMA));

        assertRefusal(refused, 400, "invalidValue");
        assertThat(body(refused).get("detail").asText()).contains("nickname");
    }

    /** A body declaring an extension schema is asserting attributes this service does not have. */
    @Test
    void a_body_declaring_more_than_one_schema_is_refused() throws Exception {
        MvcResult refused = createGroup("""
                {"schemas":["%s","urn:example:extension"],"displayName":"Engineering-grpit"}"""
                .formatted(GROUP_SCHEMA));

        assertRefusal(refused, 400, "invalidValue");
    }

    /** A members entry must be a complex value, and every sub-attribute must be a declared one. */
    @Test
    void an_unknown_members_sub_attribute_is_refused_naming_it() throws Exception {
        UUID member = createUser("sub");

        MvcResult refused = createGroup("""
                {"schemas":["%s"],
                 "displayName":"Engineering-grpit",
                 "members":[{"value":"%s","primary":true}]}"""
                .formatted(GROUP_SCHEMA, member));

        assertRefusal(refused, 400, "invalidValue");
        assertThat(body(refused).get("detail").asText()).contains("primary");
    }

    /** A member reference that is not a resource id is a malformed request, not an unknown member. */
    @Test
    void a_members_value_that_is_not_a_resource_id_is_refused() throws Exception {
        MvcResult refused = createGroup("""
                {"schemas":["%s"],"displayName":"Engineering-grpit","members":[{"value":"nope"}]}"""
                .formatted(GROUP_SCHEMA));

        assertRefusal(refused, 400, "invalidValue");
        assertThat(body(refused).get("detail").asText()).contains("resource id");
    }

    /** A member that is not a live User is one message for every cause. */
    @Test
    void a_member_that_is_not_a_live_user_is_refused_without_naming_it() throws Exception {
        MvcResult refused = createGroup("""
                {"schemas":["%s"],"displayName":"Engineering-grpit","members":[{"value":"%s"}]}"""
                .formatted(GROUP_SCHEMA, UUID.randomUUID()));

        assertRefusal(refused, 400, "invalidValue");
        assertThat(body(refused).get("detail").asText())
                .isEqualTo("Every Group member must reference a live User.");
    }

    // ---- PATCH --------------------------------------------------------------------------------

    /** Adding, replacing and clearing a membership, each through its own operation. */
    @Test
    void a_patch_adds_replaces_and_clears_the_membership() throws Exception {
        UUID first = createUser("first");
        UUID second = createUser("second");
        UUID id = idOf(createGroup("""
                {"schemas":["%s"],"displayName":"Engineering-grpit"}""".formatted(GROUP_SCHEMA)));

        patchGroup(id, """
                {"schemas":["%s"],
                 "Operations":[{"op":"add","path":"members","value":[{"value":"%s"}]}]}"""
                .formatted(PATCH_OP, first));
        assertThat(memberCount(id)).isEqualTo(1);

        patchGroup(id, """
                {"schemas":["%s"],
                 "Operations":[{"op":"replace","path":"members","value":[{"value":"%s"}]}]}"""
                .formatted(PATCH_OP, second));
        assertThat(memberCount(id)).isEqualTo(1);

        patchGroup(id, """
                {"schemas":["%s"],"Operations":[{"op":"remove","path":"members"}]}"""
                .formatted(PATCH_OP));
        assertThat(memberCount(id))
                .as("a remove with no value clears the whole membership")
                .isZero();
    }

    /**
     * A {@code members} value path removes exactly the member it names from the stored
     * membership — the form every provisioning system uses to revoke one membership. The answer
     * it renders is the conformance sweep's.
     */
    @Test
    void a_patch_removes_one_member_by_a_value_path() throws Exception {
        UUID kept = createUser("kept");
        UUID removed = createUser("removed");
        UUID id = idOf(createGroup("""
                {"schemas":["%s"],
                 "displayName":"Engineering-grpit",
                 "members":[{"value":"%s"},{"value":"%s"}]}"""
                .formatted(GROUP_SCHEMA, kept, removed)));

        patchGroup(id, """
                {"schemas":["%s"],
                 "Operations":[{"op":"remove","path":"members[value eq \\"%s\\"]"}]}"""
                .formatted(PATCH_OP, removed));

        assertThat(memberCount(id)).isEqualTo(1);
    }

    /**
     * A path this service does not implement is refused while it is still a request, and the echo
     * is lower-cased so it cannot be mistaken for a canonical spelling this service recognises.
     */
    @Test
    void an_unimplemented_patch_path_is_refused_with_the_path_echoed_sanitized() throws Exception {
        UUID id = idOf(createGroup("""
                {"schemas":["%s"],"displayName":"Engineering-grpit"}""".formatted(GROUP_SCHEMA)));

        MvcResult refused = patchGroup(id, """
                {"schemas":["%s"],
                 "Operations":[{"op":"replace","path":"NickName","value":"x"}]}"""
                .formatted(PATCH_OP));

        assertRefusal(refused, 400, "invalidPath");
        assertThat(body(refused).get("detail").asText()).contains("nickname");
    }

    /**
     * A control character in the echoed path is stripped, not echoed: it is what a crafted value
     * would carry to forge a log line out of the error detail.
     */
    @Test
    void a_control_character_in_a_refused_path_is_not_echoed() throws Exception {
        UUID id = idOf(createGroup("""
                {"schemas":["%s"],"displayName":"Engineering-grpit"}""".formatted(GROUP_SCHEMA)));

        MvcResult refused = patchGroup(id, """
                {"schemas":["%s"],
                 "Operations":[{"op":"replace","path":"Nick\\u0007Name","value":"x"}]}"""
                .formatted(PATCH_OP));

        assertRefusal(refused, 400, "invalidPath");
        assertThat(body(refused).get("detail").asText())
                .contains("nickname")
                .doesNotContain("\u0007");
    }

    /**
     * {@code remove} on {@code members} WITH a value removes exactly those members — the form a
     * client that cannot build a value path uses — rather than clearing the membership.
     */
    @Test
    void a_members_remove_with_a_value_removes_only_the_members_it_names() throws Exception {
        UUID kept = createUser("kept-by-value");
        UUID removed = createUser("removed-by-value");
        UUID id = idOf(createGroup("""
                {"schemas":["%s"],
                 "displayName":"Engineering-grpit",
                 "members":[{"value":"%s"},{"value":"%s"}]}"""
                .formatted(GROUP_SCHEMA, kept, removed)));

        JsonNode patched = body(patchGroup(id, """
                {"schemas":["%s"],
                 "Operations":[{"op":"remove","path":"members","value":[{"value":"%s"}]}]}"""
                .formatted(PATCH_OP, removed)));

        assertThat(patched.get("members")).hasSize(1);
        assertThat(patched.get("members").get(0).get("value").asText()).isEqualTo(kept.toString());
        assertThat(memberCount(id)).isEqualTo(1);
    }

    /**
     * An operation that is no object is refused as a SCIM error. Which PatchOp bodies are
     * malformed is {@code ScimGroupRequestReaderTests}' table.
     */
    @Test
    void a_malformed_patch_op_is_refused() throws Exception {
        UUID id = idOf(createGroup("""
                {"schemas":["%s"],"displayName":"Engineering-grpit"}""".formatted(GROUP_SCHEMA)));

        MvcResult refused = patchGroup(id,
                "{\"schemas\":[\"urn:ietf:params:scim:api:messages:2.0:PatchOp\"],\"Operations\":[1]}");

        assertThat(refused.getResponse().getStatus()).isEqualTo(400);
        assertThat(body(refused).get("schemas").get(0).asText()).isEqualTo(ERROR_SCHEMA);
    }

    // ---- delete -------------------------------------------------------------------------------

    /**
     * A delete is a {@code 204} with no body, and it removes the memberships with the Group.
     *
     * <p>The membership count is read in SQL after the fact because this is the adapter's
     * {@code deleteById} — the one place the real delete's row arithmetic happens, and the method
     * the in-memory fake was standing in for everywhere else.
     */
    @Test
    void a_delete_removes_the_group_and_its_memberships() throws Exception {
        UUID member = createUser("doomed");
        UUID id = idOf(createGroup("""
                {"schemas":["%s"],"displayName":"Engineering-grpit","members":[{"value":"%s"}]}"""
                .formatted(GROUP_SCHEMA, member)));
        assertThat(memberCount(id)).isEqualTo(1);
        String memberVersionBefore = userVersion(member);

        MvcResult deleted = mvc.perform(asConnector(delete(GROUPS + "/" + id), writeToken, jdbc)).andReturn();

        assertThat(deleted.getResponse().getStatus()).isEqualTo(204);
        assertThat(deleted.getResponse().getContentAsString()).isEmpty();
        assertThat(memberCount(id)).isZero();
        assertThat(userVersion(member))
                .as("the former member's rendered groups lost an entry, so its version moved")
                .isNotEqualTo(memberVersionBefore);
        assertRefusal(mvc.perform(asConnector(get(GROUPS + "/" + id), writeToken, jdbc)).andReturn(), 404, null);
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM scim_users WHERE resource_id = ?", Long.class, member))
                .as("deleting a Group does not delete its members")
                .isEqualTo(1);
    }

    // ---- the reserved Admin group --------------------------------------------------------------

    /**
     * The seeded Admin group cannot be renamed or deleted, and the refusal is a {@code 400} with
     * {@code mutability} — deliberately NOT a {@code 403}, because the token is valid and it is the
     * TARGET that is refused. A 403 would send an integrator to re-check a token scope that is fine.
     */
    @Test
    void the_admin_group_cannot_be_renamed_or_deleted() throws Exception {
        UUID adminGroupId = seededAdminGroupId();

        MvcResult renamed = mvc.perform(asConnector(put(GROUPS + "/" + adminGroupId), writeToken, jdbc)
                        .contentType(SCIM_JSON)
                        .content("""
                                {"schemas":["%s"],"displayName":"Not Admins"}"""
                                .formatted(GROUP_SCHEMA)))
                .andReturn();
        assertRefusal(renamed, 400, "mutability");
        assertThat(body(renamed).get("detail").asText()).contains("reserved for deployment recovery");

        assertRefusal(mvc.perform(asConnector(delete(GROUPS + "/" + adminGroupId), writeToken, jdbc)).andReturn(),
                400, "mutability");

        assertThat(body(mvc.perform(asConnector(get(GROUPS + "/" + adminGroupId), writeToken, jdbc)).andReturn())
                        .get("displayName").asText())
                .as("a refused rename leaves the Group unchanged on re-read")
                .isNotEqualTo("Not Admins");
    }

    /** The Bootstrap Admin's membership is frozen in both directions. */
    @Test
    void the_bootstrap_admin_cannot_be_added_to_an_ordinary_group() throws Exception {
        UUID bootstrapAdminId = jdbc.queryForObject(
                "SELECT id FROM scim_resources WHERE reserved_name = 'bootstrap-admin'",
                UUID.class);

        MvcResult refused = createGroup("""
                {"schemas":["%s"],"displayName":"Engineering-grpit","members":[{"value":"%s"}]}"""
                .formatted(GROUP_SCHEMA, bootstrapAdminId));

        assertRefusal(refused, 400, "mutability");
    }

    // ---- acceptance criteria, observed against the database -----------------------------------
    //
    // The service tests prove these rules against the in-memory fake. They are repeated here
    // because the fake can disagree with the adapter, and each rule
    // below is decided, in production, by something the fake does not have: a foreign key, a
    // transaction rollback, a bulk version UPDATE, or a fail-open audit append in its own
    // transaction.

    /**
     * A Group is not a User, so naming one as a member is refused as an invalid value — on create
     * and on a PATCH add alike, and the PATCH target is left without the member.
     */
    @Test
    void a_group_named_as_a_member_is_refused_as_an_invalid_value() throws Exception {
        UUID nested = idOf(createGroup("""
                {"schemas":["%s"],"displayName":"Nested-grpit"}""".formatted(GROUP_SCHEMA)));

        assertRefusal(createGroup("""
                {"schemas":["%s"],"displayName":"Engineering-grpit","members":[{"value":"%s"}]}"""
                .formatted(GROUP_SCHEMA, nested)), 400, "invalidValue");

        UUID target = idOf(createGroup("""
                {"schemas":["%s"],"displayName":"Platform-grpit"}""".formatted(GROUP_SCHEMA)));
        assertRefusal(patchGroup(target, """
                {"schemas":["%s"],
                 "Operations":[{"op":"add","path":"members","value":[{"value":"%s"}]}]}"""
                .formatted(PATCH_OP, nested)), 400, "invalidValue");
        assertThat(memberCount(target)).isZero();
    }

    /**
     * A PATCH is all-or-nothing: when a later operation fails, the earlier ones that already
     * succeeded in memory — here a rename and a membership clear — are not stored either, and
     * neither the Group's nor its member's version moves.
     */
    @Test
    void a_patch_whose_last_operation_fails_changes_nothing() throws Exception {
        UUID member = createUser("atomic");
        UUID id = idOf(createGroup("""
                {"schemas":["%s"],"displayName":"Engineering-grpit","members":[{"value":"%s"}]}"""
                .formatted(GROUP_SCHEMA, member)));
        JsonNode before = groupAsRead(id);
        String memberVersionBefore = userVersion(member);

        assertRefusal(patchGroup(id, """
                {"schemas":["%s"],
                 "Operations":[
                   {"op":"replace","path":"displayName","value":"Renamed-grpit"},
                   {"op":"remove","path":"members"},
                   {"op":"add","path":"members","value":[{"value":"%s"}]}]}"""
                .formatted(PATCH_OP, UUID.randomUUID())), 400, "invalidValue");

        JsonNode after = groupAsRead(id);
        assertThat(after.get("displayName").asText()).isEqualTo("Engineering-grpit");
        assertThat(after.get("members")).hasSize(1);
        assertThat(after.get("members").get(0).get("value").asText()).isEqualTo(member.toString());
        assertThat(after.get("meta").get("version")).isEqualTo(before.get("meta").get("version"));
        assertThat(memberCount(id)).isEqualTo(1);
        assertThat(userVersion(member))
                .as("the member's rendered groups did not change, so neither did its version")
                .isEqualTo(memberVersionBefore);
    }

    /**
     * A membership change advances the Group's version and the affected User's; a rename
     * advances every CURRENT member's version, and nobody else's. Read over HTTP, so the version
     * asserted is the one a connector's ETag would carry.
     */
    @Test
    void membership_and_rename_advance_exactly_the_affected_versions() throws Exception {
        UUID member = createUser("versioned");
        UUID bystander = createUser("bystander");
        UUID id = idOf(createGroup("""
                {"schemas":["%s"],"displayName":"Engineering-grpit"}""".formatted(GROUP_SCHEMA)));

        String groupV0 = groupAsRead(id).get("meta").get("version").asText();
        String memberV0 = userVersion(member);
        patchGroup(id, """
                {"schemas":["%s"],
                 "Operations":[{"op":"add","path":"members","value":[{"value":"%s"}]}]}"""
                .formatted(PATCH_OP, member));
        String groupV1 = groupAsRead(id).get("meta").get("version").asText();
        String memberV1 = userVersion(member);
        assertThat(groupV1).as("adding a member moves the Group").isNotEqualTo(groupV0);
        assertThat(memberV1).as("adding a member moves that User").isNotEqualTo(memberV0);

        String bystanderBefore = userVersion(bystander);
        patchGroup(id, """
                {"schemas":["%s"],
                 "Operations":[{"op":"replace","path":"displayName","value":"Platform-grpit"}]}"""
                .formatted(PATCH_OP));
        String memberV2 = userVersion(member);
        assertThat(memberV2).as("a rename moves every current member").isNotEqualTo(memberV1);
        assertThat(userVersion(bystander))
                .as("a rename leaves a non-member alone")
                .isEqualTo(bystanderBefore);

        patchGroup(id, """
                {"schemas":["%s"],
                 "Operations":[{"op":"remove","path":"members[value eq \\"%s\\"]"}]}"""
                .formatted(PATCH_OP, member));
        assertThat(userVersion(member)).as("removal moves the removed User").isNotEqualTo(memberV2);
    }

    /**
     * Every route to removing the Bootstrap Admin from the Admin group — a value-path remove, a
     * whole-membership clear, and a PUT omitting it — is refused as {@code mutability}, leaves the
     * Group byte-for-byte as it was on re-read, and is recorded as its own refusal event.
     */
    @Test
    void the_bootstrap_admins_admin_membership_cannot_be_removed_by_any_route() throws Exception {
        UUID adminGroupId = seededAdminGroupId();
        UUID bootstrapAdminId = jdbc.queryForObject(
                "SELECT id FROM scim_resources WHERE reserved_name = 'bootstrap-admin'", UUID.class);
        JsonNode before = groupAsRead(adminGroupId);
        long refusalsBefore = mutabilityRefusalsOf(adminGroupId);

        assertRefusal(patchGroup(adminGroupId, """
                {"schemas":["%s"],
                 "Operations":[{"op":"remove","path":"members[value eq \\"%s\\"]"}]}"""
                .formatted(PATCH_OP, bootstrapAdminId)), 400, "mutability");
        assertRefusal(patchGroup(adminGroupId, """
                {"schemas":["%s"],"Operations":[{"op":"remove","path":"members"}]}"""
                .formatted(PATCH_OP)), 400, "mutability");
        assertRefusal(mvc.perform(asConnector(put(GROUPS + "/" + adminGroupId), writeToken, jdbc)
                        .contentType(SCIM_JSON)
                        .content("""
                                {"schemas":["%s"],"displayName":"%s"}"""
                                .formatted(GROUP_SCHEMA, before.get("displayName").asText())))
                .andReturn(), 400, "mutability");

        JsonNode after = groupAsRead(adminGroupId);
        assertThat(after).as("every refusal left the Admin group unchanged on re-read").isEqualTo(before);
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM scim_group_members WHERE group_id = ? AND user_id = ?",
                        Long.class, adminGroupId, bootstrapAdminId))
                .isEqualTo(1);
        assertThat(mutabilityRefusalsOf(adminGroupId) - refusalsBefore)
                .as("each refusal is its own audit event, committed despite the rolled-back write")
                .isEqualTo(3);
    }

    /**
     * Each successful Group write lands in the audit table as its own event naming the Group:
     * the create, the PUT, the PATCH, and the delete.
     */
    @Test
    void every_group_write_is_its_own_audit_event() throws Exception {
        UUID id = idOf(createGroup("""
                {"schemas":["%s"],"displayName":"Engineering-grpit"}""".formatted(GROUP_SCHEMA)));
        mvc.perform(asConnector(put(GROUPS + "/" + id), writeToken, jdbc)
                .contentType(SCIM_JSON)
                .content("""
                        {"schemas":["%s"],"displayName":"Platform-grpit"}""".formatted(GROUP_SCHEMA)));
        patchGroup(id, """
                {"schemas":["%s"],
                 "Operations":[{"op":"replace","path":"displayName","value":"Infra-grpit"}]}"""
                .formatted(PATCH_OP));
        assertThat(mvc.perform(asConnector(delete(GROUPS + "/" + id), writeToken, jdbc)).andReturn()
                .getResponse().getStatus()).isEqualTo(204);

        assertThat(jdbc.queryForList("""
                        SELECT operation FROM audit_events
                        WHERE resource_id = ? AND outcome = 'SUCCESS'
                        ORDER BY occurred_at""", String.class, id))
                .containsExactly(
                        "SCIM_GROUP_CREATE", "SCIM_GROUP_REPLACE", "SCIM_GROUP_REPLACE",
                        "SCIM_GROUP_DELETE");
    }

    // ---- projection ---------------------------------------------------------------------------

    /**
     * {@code attributes} narrows a multi-valued complex attribute to the sub-attributes asked for,
     * which is the list-valued branch of the projection rather than the single-object one.
     */
    @Test
    void attributes_narrows_the_members_sub_attributes() throws Exception {
        UUID member = createUser("narrowed");
        UUID id = idOf(createGroup("""
                {"schemas":["%s"],"displayName":"Engineering-grpit","members":[{"value":"%s"}]}"""
                .formatted(GROUP_SCHEMA, member)));

        JsonNode projected = body(mvc.perform(asConnector(
                        get(GROUPS + "/" + id).param("attributes", "members.value"), writeToken, jdbc))
                .andReturn());

        assertThat(projected.get("members")).hasSize(1);
        JsonNode rendered = projected.get("members").get(0);
        assertThat(rendered.get("value").asText()).isEqualTo(member.toString());
        assertThat(rendered.has("display"))
                .as("a sub-attribute that was not asked for is not rendered")
                .isFalse();
        assertThat(projected.has("displayName")).isFalse();
    }

    /** {@code excludedAttributes} removes a sub-attribute while keeping the rest of the value. */
    @Test
    void excluded_attributes_removes_a_members_sub_attribute() throws Exception {
        UUID member = createUser("excluded");
        UUID id = idOf(createGroup("""
                {"schemas":["%s"],"displayName":"Engineering-grpit","members":[{"value":"%s"}]}"""
                .formatted(GROUP_SCHEMA, member)));

        JsonNode projected = body(mvc.perform(asConnector(
                        get(GROUPS + "/" + id).param("excludedAttributes", "members.display"), writeToken, jdbc))
                .andReturn());

        JsonNode rendered = projected.get("members").get(0);
        assertThat(rendered.has("display")).isFalse();
        assertThat(rendered.get("value").asText()).isEqualTo(member.toString());
        assertThat(projected.has("displayName")).isTrue();
    }

    // ---- helpers ------------------------------------------------------------------------------

    /**
     * Creates a Group and, when the create succeeded, tracks it for teardown — including the
     * callers that only want the first create as a precondition and never read its id, which would
     * otherwise leak a server-unique displayName into every later method.
     */
    private MvcResult createGroup(String body) throws Exception {
        return createdResources.post(mvc, writeToken, GROUPS, body);
    }

    private MvcResult patchGroup(UUID id, String body) throws Exception {
        return mvc.perform(asConnector(patch(GROUPS + "/" + id), writeToken, jdbc)
                        .contentType(SCIM_JSON)
                        .content(body))
                .andReturn();
    }

    private UUID createUser(String userName) throws Exception {
        return createdResources.create(mvc, writeToken, USERS, """
                {"schemas":["%s"],"userName":"%s"}""".formatted(USER_SCHEMA, userName));
    }

    private UUID seededAdminGroupId() {
        return jdbc.queryForObject(
                "SELECT id FROM scim_resources WHERE reserved_name = 'admin-group'", UUID.class);
    }

    private JsonNode groupAsRead(UUID id) throws Exception {
        MvcResult read = mvc.perform(asConnector(get(GROUPS + "/" + id), writeToken, jdbc)).andReturn();
        assertThat(read.getResponse().getStatus()).isEqualTo(200);
        return body(read);
    }

    private String userVersion(UUID id) throws Exception {
        MvcResult read = mvc.perform(asConnector(get(USERS + "/" + id), writeToken, jdbc)).andReturn();
        assertThat(read.getResponse().getStatus()).isEqualTo(200);
        return body(read).get("meta").get("version").asText();
    }

    private long mutabilityRefusalsOf(UUID groupId) {
        return jdbc.queryForObject("""
                SELECT count(*) FROM audit_events
                WHERE operation = 'SCIM_GROUP_REPLACE' AND outcome = 'FAILURE'
                  AND error_code = 'MUTABILITY' AND subject_id = ?""", Long.class, groupId);
    }

    private long memberCount(UUID groupId) {
        return jdbc.queryForObject(MEMBERS_OF, Long.class, groupId);
    }

    /** The id of the Group a create made, which {@link #createGroup} has already registered. */
    private UUID idOf(MvcResult result) throws Exception {
        assertThat(result.getResponse().getStatus()).isEqualTo(201);
        return TestJson.id(result);
    }

    private void assertRefusal(MvcResult result, int status, String scimType) throws Exception {
        assertThat(result.getResponse().getStatus()).isEqualTo(status);
        JsonNode error = body(result);
        assertThat(error.get("schemas").get(0).asText()).isEqualTo(ERROR_SCHEMA);
        assertThat(error.get("status").asText()).isEqualTo(String.valueOf(status));
        if (scimType == null) {
            assertThat(error.has("scimType")).isFalse();
        } else {
            assertThat(error.get("scimType").asText()).isEqualTo(scimType);
        }
        assertThat(error.get("detail").asText()).isNotBlank();
    }
}

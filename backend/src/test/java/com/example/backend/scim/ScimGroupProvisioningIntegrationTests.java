package com.example.backend.scim;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import com.example.backend.ContainerTestConfiguration;
import com.example.backend.TokenPermissions;
import com.example.backend.observability.RequestIdFilter;
import com.example.backend.scim.application.ConnectorAdministrationService;
import com.example.backend.scim.domain.ReservedResourceName;
import com.example.backend.scim.domain.ScimGroup;
import com.example.backend.scim.domain.ScimGroupMember;
import com.example.backend.scim.domain.ScimGroupRepository;
import jakarta.servlet.Filter;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
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
 * The Group endpoints driven over the real filter chain against a real Postgres, with the stored
 * rows read back in SQL.
 *
 * <p>The counterpart of {@link ScimUserProvisioningIntegrationTests}, and it exists because
 * mutation testing found it missing: the whole Group HTTP surface — the controller, the request
 * reader, and the persistence adapter's delete — was reached by no test at all, and the Group
 * behaviour was verified only at service level against an in-memory fake. That is not a
 * theoretical gap on this ticket. The fake and the adapter had already DISAGREED once about
 * whether a create advances its members' versions, and every unit test passed while the adapter
 * was wrong; only a Postgres test caught it. {@code deleteById} was the same shape of hole.
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

    private static final MediaType SCIM_JSON = MediaType.valueOf("application/scim+json");

    private static final String MEMBERS_OF =
            "SELECT count(*) FROM scim_group_members WHERE group_id = ?";

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private ConnectorAdministrationService connectors;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ScimGroupRepository groupRepository;

    @Autowired
    private RequestIdFilter requestIdFilter;

    @Autowired
    @Qualifier("springSecurityFilterChain")
    private Filter springSecurityFilterChain;

    private final JsonMapper json = JsonMapper.builder().build();

    private MockMvc mvc;

    private String writeToken;

    /** Every resource this test created, so teardown removes exactly those and nothing else. */
    private final List<UUID> createdResources = new ArrayList<>();

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context)
                .addFilters(requestIdFilter, springSecurityFilterChain)
                .build();
        UUID connectorId = connectors.create("Okta", "test-admin").id();
        writeToken = connectors
                .issueToken(connectorId, TokenPermissions.ALL, null, "test-admin", TokenPermissions.ALL)
                .presentedValue();
    }

    /**
     * Removes the resources THIS test created, and only those.
     *
     * <p>Needed because this class shares one Spring context and one Postgres across its methods
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
        for (UUID id : createdResources) {
            jdbc.update("DELETE FROM scim_resources WHERE id = ? AND reserved_name IS NULL", id);
        }
        createdResources.clear();
    }

    // ---- retrieval ----------------------------------------------------------------------------

    /**
     * The port the Accounts page's Groups projection and each User's direct Groups are read
     * through, against the real adapter: every live Group in normalized display-name order —
     * not insertion order, which is why the two are created backwards — each carrying its own
     * memberships and no one else's, from a single membership read.
     */
    @Test
    void every_group_is_listed_in_display_name_order_with_its_own_members() throws Exception {
        UUID ada = createUser("ada-directory-listing");
        UUID bob = createUser("bob-directory-listing");
        UUID zulu = idOf(createGroup("""
                {"schemas":["%s"],"displayName":"zz-listing-Zulu","members":[{"value":"%s"}]}"""
                .formatted(GROUP_SCHEMA, ada)));
        UUID alpha = idOf(createGroup("""
                {"schemas":["%s"],"displayName":"zz-listing-alpha",
                 "members":[{"value":"%s"},{"value":"%s"}]}"""
                .formatted(GROUP_SCHEMA, ada, bob)));

        List<ScimGroup> listed = groupRepository.findAllOrderedByNormalizedDisplayName().stream()
                .filter(group -> group.displayName().startsWith("zz-listing-"))
                .toList();

        assertThat(listed).extracting(ScimGroup::id).containsExactly(alpha, zulu);
        assertThat(listed.get(0).members()).extracting(ScimGroupMember::userId)
                .containsExactlyInAnyOrder(ada, bob);
        assertThat(listed.get(1).members()).extracting(ScimGroupMember::userId)
                .containsExactly(ada);
        assertThat(groupRepository.findAllOrderedByNormalizedDisplayName())
                .as("the seeded Admin group is listed too, with its reservation")
                .anySatisfy(group -> assertThat(group.reservedName())
                        .isEqualTo(ReservedResourceName.ADMIN_GROUP));
    }

    /** A create, then the same Group read back by its id with its validator and its location. */
    @Test
    void a_group_is_retrievable_by_its_id_with_an_etag_and_a_location() throws Exception {
        UUID id = idOf(createGroup("""
                {"schemas":["%s"],"displayName":"Engineering-grpit"}""".formatted(GROUP_SCHEMA)));

        MvcResult read = mvc.perform(asConnector(get(GROUPS + "/" + id))).andReturn();

        assertThat(read.getResponse().getStatus()).isEqualTo(200);
        assertThat(read.getResponse().getHeader(HttpHeaders.ETAG)).isNotBlank().startsWith("\"");
        assertThat(read.getResponse().getHeader(HttpHeaders.LOCATION))
                .endsWith(GROUPS + "/" + id);
        JsonNode group = body(read);
        assertThat(group.get("id").asText()).isEqualTo(id.toString());
        assertThat(group.get("displayName").asText()).isEqualTo("Engineering-grpit");
        assertThat(group.get("meta").get("resourceType").asText()).isEqualTo("Group");
    }

    /**
     * An id that names nothing and an id that is not a UUID answer the SAME {@code 404}.
     *
     * <p>Indistinguishable on purpose: telling a caller its id was the wrong SHAPE discloses what
     * shape the real ones have, and "this id names nothing" is the truthful answer to both.
     */
    @ParameterizedTest
    @ValueSource(strings = {"6f9c1f66-0000-4000-8000-00000000dead", "not-a-uuid", "12345"})
    void an_unknown_or_malformed_id_is_the_same_not_found(String id) throws Exception {
        MvcResult read = mvc.perform(asConnector(get(GROUPS + "/" + id))).andReturn();

        assertRefusal(read, 404, null);
        assertThat(body(read).get("detail").asText()).isEqualTo("No Group has that id.");
    }

    /** A User's id is not a Group's id, and asking for one as the other reveals neither. */
    @Test
    void a_user_id_is_not_found_as_a_group() throws Exception {
        UUID userId = idOf(mvc.perform(asConnector(post(USERS))
                        .contentType(SCIM_JSON)
                        .content("""
                                {"schemas":["%s"],"userName":"solo"}""".formatted(USER_SCHEMA)))
                .andReturn());

        assertRefusal(mvc.perform(asConnector(get(GROUPS + "/" + userId))).andReturn(), 404, null);
    }

    // ---- the collection -----------------------------------------------------------------------

    /**
     * Paging is by {@code startIndex} and {@code count}, both one-based on the wire, and the
     * envelope reports the total independently of the page.
     *
     * <p>The seeded Admin group is in the directory too, so the total is asserted as "at least",
     * not as a literal: a test that hard-coded it would break the moment seeding changes and would
     * be asserting the fixture rather than the paging.
     */
    @Test
    void a_page_is_selected_by_start_index_and_count() throws Exception {
        createGroup("""
                {"schemas":["%s"],"displayName":"Alpha"}""".formatted(GROUP_SCHEMA));
        createGroup("""
                {"schemas":["%s"],"displayName":"Beta"}""".formatted(GROUP_SCHEMA));

        JsonNode page = body(mvc.perform(asConnector(
                        get(GROUPS).param("startIndex", "1").param("count", "2")))
                .andReturn());

        assertThat(page.get("schemas").get(0).asText())
                .isEqualTo("urn:ietf:params:scim:api:messages:2.0:ListResponse");
        assertThat(page.get("startIndex").asInt()).isEqualTo(1);
        assertThat(page.get("itemsPerPage").asInt()).isEqualTo(2);
        assertThat(page.get("Resources")).hasSize(2);
        assertThat(page.get("totalResults").asInt()).isGreaterThanOrEqualTo(3);
    }

    /**
     * A paging parameter that is not an integer is a {@code 400} naming the parameter.
     *
     * <p>Refused rather than defaulted: a connector that sent {@code count=many} and received the
     * default page believes it received the page it asked for.
     */
    @ParameterizedTest
    @ValueSource(strings = {"startIndex", "count"})
    void a_non_numeric_paging_parameter_is_refused_naming_it(String parameter) throws Exception {
        MvcResult refused = mvc.perform(asConnector(get(GROUPS).param(parameter, "many")))
                .andReturn();

        assertRefusal(refused, 400, "invalidValue");
        assertThat(body(refused).get("detail").asText()).contains(parameter);
    }

    /**
     * A blank paging parameter is absent rather than malformed, so the default applies.
     *
     * <p>Asserted by returning MORE resources than a small explicit count would have, rather than
     * by reading {@code itemsPerPage}: RFC 7644 defines that as the number of resources actually
     * returned in this page, so on a directory smaller than one page it reports the directory's
     * size and would say nothing about which count was applied.
     */
    @Test
    void a_blank_paging_parameter_falls_back_to_the_default() throws Exception {
        createGroup("""
                {"schemas":["%s"],"displayName":"Alpha"}""".formatted(GROUP_SCHEMA));
        createGroup("""
                {"schemas":["%s"],"displayName":"Beta"}""".formatted(GROUP_SCHEMA));

        MvcResult page = mvc.perform(asConnector(get(GROUPS).param("count", " "))).andReturn();

        assertThat(page.getResponse().getStatus()).isEqualTo(200);
        JsonNode body = body(page);
        assertThat(body.get("Resources").size())
                .as("a blank count is an absent one, so the default page holds everything here")
                .isEqualTo(body.get("totalResults").asInt());
        assertThat(body.get("startIndex").asInt()).isEqualTo(1);
    }

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
        MvcResult refused = mvc.perform(asConnector(get(GROUPS).param(parameter, value)))
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

        MvcResult replaced = mvc.perform(asConnector(put(GROUPS + "/" + id))
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
     * common case rather than an edge one. The adapter previously advanced the Group's version
     * unconditionally, which meant a connector's own idempotent re-send invalidated the cached
     * copy it was trying to confirm — and it contradicted the audit event for the same write,
     * which correctly recorded no changed attribute.
     */
    @Test
    void re_putting_identical_state_moves_neither_the_version_nor_the_etag() throws Exception {
        UUID member = createUser("steady");
        UUID id = idOf(createGroup("""
                {"schemas":["%s"],"displayName":"Engineering-grpit","members":[{"value":"%s"}]}"""
                .formatted(GROUP_SCHEMA, member)));
        MvcResult before = mvc.perform(asConnector(get(GROUPS + "/" + id))).andReturn();
        String versionBefore = body(before).get("meta").get("version").asText();
        String etagBefore = before.getResponse().getHeader(HttpHeaders.ETAG);

        MvcResult replayed = mvc.perform(asConnector(put(GROUPS + "/" + id))
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

        JsonNode memberAfter = body(mvc.perform(asConnector(get(USERS + "/" + member)))
                .andReturn());
        assertThat(memberAfter.get("groups")).hasSize(1);
    }

    /** A PUT at an id that names nothing is a 404, not a create. */
    @Test
    void a_put_at_an_unknown_id_is_not_found() throws Exception {        MvcResult refused = mvc.perform(asConnector(put(GROUPS + "/" + UUID.randomUUID()))
                        .contentType(SCIM_JSON)
                        .content("""
                                {"schemas":["%s"],"displayName":"Ghost"}"""
                                .formatted(GROUP_SCHEMA)))
                .andReturn();

        assertRefusal(refused, 404, null);
    }

    /** {@code displayName} is required on a replacement, as it is on a create. */
    @Test
    void a_put_without_a_display_name_is_refused() throws Exception {
        UUID id = idOf(createGroup("""
                {"schemas":["%s"],"displayName":"Engineering-grpit"}""".formatted(GROUP_SCHEMA)));

        MvcResult refused = mvc.perform(asConnector(put(GROUPS + "/" + id))
                        .contentType(SCIM_JSON)
                        .content("""
                                {"schemas":["%s"],"members":[]}""".formatted(GROUP_SCHEMA)))
                .andReturn();

        assertRefusal(refused, 400, "invalidSyntax");
        assertThat(body(refused).get("detail").asText()).contains("displayName");
    }

    /**
     * An {@code externalId} on a PUT is stored, not discarded: a different value re-keys the
     * caller's alias and the response shows the value that was stored. Before issue #54 this
     * answered 200 with the OLD value, so an IdP believed it had stored one it had not. The full
     * read-write contract is in {@code ScimExternalIdReadWriteIntegrationTests}.
     */
    @Test
    void a_put_with_a_different_external_id_re_keys_the_alias() throws Exception {
        UUID id = idOf(createGroup("""
                {"schemas":["%s"],"displayName":"Engineering-grpit","externalId":"grp-1"}"""
                .formatted(GROUP_SCHEMA)));

        MvcResult replaced = mvc.perform(asConnector(put(GROUPS + "/" + id))
                        .contentType(SCIM_JSON)
                        .content("""
                                {"schemas":["%s"],"displayName":"Engineering-grpit","externalId":"other"}"""
                                .formatted(GROUP_SCHEMA)))
                .andReturn();

        assertThat(replaced.getResponse().getStatus()).isEqualTo(200);
        assertThat(body(replaced).get("externalId").asText()).isEqualTo("other");
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

    /** {@code id} and {@code meta} are read-only, so a client may send back what it read. */
    @Test
    void the_read_only_attributes_are_ignored_on_a_write() throws Exception {
        MvcResult created = createGroup("""
                {"schemas":["%s"],
                 "id":"%s",
                 "displayName":"Engineering-grpit",
                 "meta":{"resourceType":"Group"}}"""
                .formatted(GROUP_SCHEMA, UUID.randomUUID()));

        assertThat(created.getResponse().getStatus()).isEqualTo(201);
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

    /** A duplicate displayName is a conflict, translated at the boundary without echoing a value. */
    @Test
    void a_display_name_already_held_is_a_conflict() throws Exception {
        createGroup("""
                {"schemas":["%s"],"displayName":"Engineering-grpit"}""".formatted(GROUP_SCHEMA));

        MvcResult refused = createGroup("""
                {"schemas":["%s"],"displayName":"ENGINEERING-GRPIT"}""".formatted(GROUP_SCHEMA));

        assertRefusal(refused, 409, "uniqueness");
        assertThat(body(refused).get("detail").asText()).doesNotContain("ENGINEERING-GRPIT");
    }

    // ---- PATCH --------------------------------------------------------------------------------

    /** {@code add} on a single-valued attribute replaces it, as RFC 7644 §3.5.2.1 defines. */
    @Test
    void a_patch_sets_the_display_name() throws Exception {
        UUID id = idOf(createGroup("""
                {"schemas":["%s"],"displayName":"Engineering-grpit"}""".formatted(GROUP_SCHEMA)));

        JsonNode patched = body(patchGroup(id, """
                {"schemas":["%s"],
                 "Operations":[{"op":"add","path":"displayName","value":"Platform-grpit"}]}"""
                .formatted(PATCH_OP)));

        assertThat(patched.get("displayName").asText()).isEqualTo("Platform-grpit");
    }

    /** {@code op} is matched case-insensitively, as the RFC requires. */
    @Test
    void a_patch_op_is_read_case_insensitively() throws Exception {
        UUID id = idOf(createGroup("""
                {"schemas":["%s"],"displayName":"Engineering-grpit"}""".formatted(GROUP_SCHEMA)));

        JsonNode patched = body(patchGroup(id, """
                {"schemas":["%s"],
                 "Operations":[{"op":"REPLACE","path":"DisplayName","value":"Platform-grpit"}]}"""
                .formatted(PATCH_OP)));

        assertThat(patched.get("displayName").asText()).isEqualTo("Platform-grpit");
    }

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
     * A {@code members} value path removes exactly the member it names — the form every
     * provisioning system uses to revoke one membership.
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

        JsonNode patched = body(patchGroup(id, """
                {"schemas":["%s"],
                 "Operations":[{"op":"remove","path":"members[value eq \\"%s\\"]"}]}"""
                .formatted(PATCH_OP, removed)));

        assertThat(patched.get("members")).hasSize(1);
        assertThat(patched.get("members").get(0).get("value").asText()).isEqualTo(kept.toString());
        assertThat(memberCount(id)).isEqualTo(1);
    }

    /** A value path is supported for remove only; anything else would select loosely. */
    @Test
    void a_members_value_path_on_anything_but_remove_is_refused() throws Exception {
        UUID id = idOf(createGroup("""
                {"schemas":["%s"],"displayName":"Engineering-grpit"}""".formatted(GROUP_SCHEMA)));

        MvcResult refused = patchGroup(id, """
                {"schemas":["%s"],
                 "Operations":[{"op":"add","path":"members[value eq \\"%s\\"]"}]}"""
                .formatted(PATCH_OP, UUID.randomUUID()));

        assertRefusal(refused, 400, "invalidPath");
        assertThat(body(refused).get("detail").asText()).contains("remove only");
    }

    /** {@code displayName} is required, so removing it would leave a Group the schema forbids. */
    @Test
    void removing_the_display_name_is_refused() throws Exception {
        UUID id = idOf(createGroup("""
                {"schemas":["%s"],"displayName":"Engineering-grpit"}""".formatted(GROUP_SCHEMA)));

        MvcResult refused = patchGroup(id, """
                {"schemas":["%s"],"Operations":[{"op":"remove","path":"displayName"}]}"""
                .formatted(PATCH_OP));

        assertRefusal(refused, 400, "mutability");
        assertThat(body(refused).get("detail").asText()).contains("cannot be removed");
    }

    /** An op this service does not implement is refused, with the submitted op echoed sanitized. */
    @Test
    void an_unsupported_patch_op_is_refused() throws Exception {
        UUID id = idOf(createGroup("""
                {"schemas":["%s"],"displayName":"Engineering-grpit"}""".formatted(GROUP_SCHEMA)));

        MvcResult refused = patchGroup(id, """
                {"schemas":["%s"],
                 "Operations":[{"op":"move","path":"members","value":[{"value":"%s"}]}]}"""
                .formatted(PATCH_OP, UUID.randomUUID()));

        assertRefusal(refused, 400, "invalidValue");
        assertThat(body(refused).get("detail").asText()).contains("move");
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

    /** A path-less operation would be an attribute merge whose semantics the RFC leaves open. */
    @Test
    void a_patch_operation_without_a_path_is_refused() throws Exception {
        UUID id = idOf(createGroup("""
                {"schemas":["%s"],"displayName":"Engineering-grpit"}""".formatted(GROUP_SCHEMA)));

        MvcResult refused = patchGroup(id, """
                {"schemas":["%s"],
                 "Operations":[{"op":"replace","value":{"displayName":"Platform-grpit"}}]}"""
                .formatted(PATCH_OP));

        assertRefusal(refused, 400, "invalidValue");
        assertThat(body(refused).get("detail").asText()).contains("path");
    }

    /** A members add or replace must name at least one member. */
    @ParameterizedTest
    @ValueSource(strings = {"add", "replace"})
    void a_members_operation_with_no_value_is_refused(String op) throws Exception {
        UUID id = idOf(createGroup("""
                {"schemas":["%s"],"displayName":"Engineering-grpit"}""".formatted(GROUP_SCHEMA)));

        assertRefusal(patchGroup(id, """
                {"schemas":["%s"],"Operations":[{"op":"%s","path":"members"}]}"""
                .formatted(PATCH_OP, op)), 400, "invalidSyntax");
    }

    /** An empty members array names no member, which is not the same as clearing the membership. */
    @Test
    void a_members_operation_with_an_empty_value_is_refused() throws Exception {
        UUID id = idOf(createGroup("""
                {"schemas":["%s"],"displayName":"Engineering-grpit"}""".formatted(GROUP_SCHEMA)));

        MvcResult refused = patchGroup(id, """
                {"schemas":["%s"],
                 "Operations":[{"op":"add","path":"members","value":[]}]}"""
                .formatted(PATCH_OP));

        assertRefusal(refused, 400, "invalidValue");
        assertThat(body(refused).get("detail").asText()).contains("at least one");
    }

    /** A PatchOp must declare its schema and carry a non-empty Operations array. */
    @ParameterizedTest
    @ValueSource(strings = {
            "{\"Operations\":[{\"op\":\"replace\",\"path\":\"displayName\",\"value\":\"x\"}]}",
            "{\"schemas\":[\"urn:ietf:params:scim:api:messages:2.0:PatchOp\"],\"Operations\":[]}",
            "{\"schemas\":[\"urn:ietf:params:scim:api:messages:2.0:PatchOp\"]}",
            "{\"schemas\":[\"urn:ietf:params:scim:api:messages:2.0:PatchOp\"],\"Operations\":[1]}"})
    void a_malformed_patch_op_is_refused(String malformed) throws Exception {
        UUID id = idOf(createGroup("""
                {"schemas":["%s"],"displayName":"Engineering-grpit"}""".formatted(GROUP_SCHEMA)));

        MvcResult refused = patchGroup(id, malformed);

        assertThat(refused.getResponse().getStatus()).isEqualTo(400);
        assertThat(body(refused).get("schemas").get(0).asText()).isEqualTo(ERROR_SCHEMA);
    }

    /** A PATCH at an id that names nothing is a 404. */
    @Test
    void a_patch_at_an_unknown_id_is_not_found() throws Exception {
        assertRefusal(patchGroup(UUID.randomUUID(), """
                {"schemas":["%s"],
                 "Operations":[{"op":"replace","path":"displayName","value":"Ghost"}]}"""
                .formatted(PATCH_OP)), 404, null);
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

        MvcResult deleted = mvc.perform(asConnector(delete(GROUPS + "/" + id))).andReturn();

        assertThat(deleted.getResponse().getStatus()).isEqualTo(204);
        assertThat(deleted.getResponse().getContentAsString()).isEmpty();
        assertThat(memberCount(id)).isZero();
        assertThat(userVersion(member))
                .as("the former member's rendered groups lost an entry, so its version moved")
                .isNotEqualTo(memberVersionBefore);
        assertRefusal(mvc.perform(asConnector(get(GROUPS + "/" + id))).andReturn(), 404, null);
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM scim_users WHERE resource_id = ?", Long.class, member))
                .as("deleting a Group does not delete its members")
                .isEqualTo(1);
    }

    /**
     * A delete of a Group that is not there is a {@code 404}, not a silent success: a connector
     * converging on a desired state needs to know whether the id it holds was ever real.
     */
    @Test
    void deleting_a_group_twice_is_not_found_the_second_time() throws Exception {
        UUID id = idOf(createGroup("""
                {"schemas":["%s"],"displayName":"Engineering-grpit"}""".formatted(GROUP_SCHEMA)));

        assertThat(mvc.perform(asConnector(delete(GROUPS + "/" + id)))
                .andReturn().getResponse().getStatus()).isEqualTo(204);

        assertRefusal(mvc.perform(asConnector(delete(GROUPS + "/" + id))).andReturn(), 404, null);
    }

    /** A malformed id on the delete path is the same 404 as on the read path. */
    @Test
    void deleting_a_malformed_id_is_not_found() throws Exception {
        assertRefusal(mvc.perform(asConnector(delete(GROUPS + "/not-a-uuid"))).andReturn(),
                404, null);
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

        MvcResult renamed = mvc.perform(asConnector(put(GROUPS + "/" + adminGroupId))
                        .contentType(SCIM_JSON)
                        .content("""
                                {"schemas":["%s"],"displayName":"Not Admins"}"""
                                .formatted(GROUP_SCHEMA)))
                .andReturn();
        assertRefusal(renamed, 400, "mutability");
        assertThat(body(renamed).get("detail").asText()).contains("reserved for deployment recovery");

        assertRefusal(mvc.perform(asConnector(delete(GROUPS + "/" + adminGroupId))).andReturn(),
                400, "mutability");

        assertThat(body(mvc.perform(asConnector(get(GROUPS + "/" + adminGroupId))).andReturn())
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

    /**
     * The adapter's own answer when the Group is already gone. The service checks existence first,
     * so over HTTP this branch is reached only by a concurrent delete landing between the two; the
     * port is driven directly here because that race cannot be staged through the endpoint.
     */
    @Test
    void the_adapter_reports_a_missing_group_as_not_deleted() {
        assertThat(groupRepository.deleteById(UUID.randomUUID(), java.time.Instant.now())).isFalse();
    }

    // ---- acceptance criteria, observed against the database -----------------------------------
    //
    // The service tests prove these rules against the in-memory fake. They are repeated here
    // because the fake has already disagreed with the adapter once on this ticket, and each rule
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
        assertRefusal(mvc.perform(asConnector(put(GROUPS + "/" + adminGroupId))
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

    /** A refused rename and a refused delete of the Admin group move nothing, not just the name. */
    @Test
    void a_refused_rename_or_delete_of_the_admin_group_changes_nothing() throws Exception {
        UUID adminGroupId = seededAdminGroupId();
        JsonNode before = groupAsRead(adminGroupId);

        mvc.perform(asConnector(patch(GROUPS + "/" + adminGroupId))
                .contentType(SCIM_JSON)
                .content("""
                        {"schemas":["%s"],
                         "Operations":[{"op":"replace","path":"displayName","value":"Not Admins"}]}"""
                        .formatted(PATCH_OP)));
        mvc.perform(asConnector(delete(GROUPS + "/" + adminGroupId)));

        assertThat(groupAsRead(adminGroupId)).isEqualTo(before);
    }

    /**
     * Each successful Group write lands in the audit table as its own event naming the Group:
     * the create, the PUT, the PATCH, and the delete.
     */
    @Test
    void every_group_write_is_its_own_audit_event() throws Exception {
        UUID id = idOf(createGroup("""
                {"schemas":["%s"],"displayName":"Engineering-grpit"}""".formatted(GROUP_SCHEMA)));
        mvc.perform(asConnector(put(GROUPS + "/" + id))
                .contentType(SCIM_JSON)
                .content("""
                        {"schemas":["%s"],"displayName":"Platform-grpit"}""".formatted(GROUP_SCHEMA)));
        patchGroup(id, """
                {"schemas":["%s"],
                 "Operations":[{"op":"replace","path":"displayName","value":"Infra-grpit"}]}"""
                .formatted(PATCH_OP));
        assertThat(mvc.perform(asConnector(delete(GROUPS + "/" + id))).andReturn()
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
                        get(GROUPS + "/" + id).param("attributes", "members.value")))
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
                        get(GROUPS + "/" + id).param("excludedAttributes", "members.display")))
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
        MvcResult result = mvc.perform(asConnector(post(GROUPS)).contentType(SCIM_JSON).content(body))
                .andReturn();
        if (result.getResponse().getStatus() == 201) {
            createdResources.add(UUID.fromString(body(result).get("id").asText()));
        }
        return result;
    }

    private MvcResult patchGroup(UUID id, String body) throws Exception {
        return mvc.perform(asConnector(patch(GROUPS + "/" + id))
                        .contentType(SCIM_JSON)
                        .content(body))
                .andReturn();
    }

    private UUID createUser(String userName) throws Exception {
        MvcResult created = mvc.perform(asConnector(post(USERS))
                        .contentType(SCIM_JSON)
                        .content("""
                                {"schemas":["%s"],"userName":"%s"}"""
                                .formatted(USER_SCHEMA, userName)))
                .andReturn();
        assertThat(created.getResponse().getStatus()).isEqualTo(201);
        return idOf(created);
    }

    private UUID seededAdminGroupId() {
        return jdbc.queryForObject(
                "SELECT id FROM scim_resources WHERE reserved_name = 'admin-group'", UUID.class);
    }

    private JsonNode groupAsRead(UUID id) throws Exception {
        MvcResult read = mvc.perform(asConnector(get(GROUPS + "/" + id))).andReturn();
        assertThat(read.getResponse().getStatus()).isEqualTo(200);
        return body(read);
    }

    private String userVersion(UUID id) throws Exception {
        MvcResult read = mvc.perform(asConnector(get(USERS + "/" + id))).andReturn();
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

    private UUID idOf(MvcResult result) throws Exception {
        assertThat(result.getResponse().getStatus()).isEqualTo(201);
        UUID id = UUID.fromString(body(result).get("id").asText());
        createdResources.add(id);
        return id;
    }

    private MockHttpServletRequestBuilder asConnector(MockHttpServletRequestBuilder request) {
        // A conforming connector's write carries the version it read; the precondition's own
        // behaviour is pinned in ScimConditionalWriteIntegrationTests.
        return request.header(HttpHeaders.AUTHORIZATION, "Bearer " + writeToken)
                .with(ScimConditionalWrites.currentVersion(jdbc));
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

    private JsonNode body(MvcResult result) throws Exception {
        return json.readTree(result.getResponse().getContentAsString());
    }
}

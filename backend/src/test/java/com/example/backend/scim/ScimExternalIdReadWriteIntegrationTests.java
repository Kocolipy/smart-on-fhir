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
import com.example.backend.scim.domain.ScimExternalIdRepository;
import jakarta.servlet.Filter;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
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
 * {@code externalId} is read-write on Users and Groups (issue #54), over the real filter chain
 * against a real Postgres.
 *
 * <p>Every test runs as two connectors, A and B, each holding its own alias for the same resource,
 * and asserts the STORED alias of both through the connector-scoped port after each write: a PUT
 * or PATCH by A sets, re-keys or removes A's alias and leaves B's alias exactly as it was and
 * invisible to A. Stored rather than rendered, because a write that rendered the new value but
 * stored the old one is the defect this replaces — a Group PUT that answered 200 and discarded the
 * value.
 */
@SpringBootTest
@ExtendWith(OutputCaptureExtension.class)
@Import(ContainerTestConfiguration.class)
class ScimExternalIdReadWriteIntegrationTests {

    private static final MediaType SCIM_JSON = MediaType.valueOf("application/scim+json");

    private static final String PATCH_OP = "urn:ietf:params:scim:api:messages:2.0:PatchOp";

    /** The two resource types, with what a minimal body of each needs. */
    enum Kind {
        USER("/scim/v2/Users", "urn:ietf:params:scim:schemas:core:2.0:User", "userName",
                "SCIM_USER_REPLACE"),
        GROUP("/scim/v2/Groups", "urn:ietf:params:scim:schemas:core:2.0:Group", "displayName",
                "SCIM_GROUP_REPLACE");

        final String collection;
        final String schema;
        final String identifying;
        final String replaceOperation;

        Kind(String collection, String schema, String identifying, String replaceOperation) {
            this.collection = collection;
            this.schema = schema;
            this.identifying = identifying;
            this.replaceOperation = replaceOperation;
        }

        String one(UUID id) {
            return collection + "/" + id;
        }

        /** A complete body naming the resource, with {@code externalId} only when non-null. */
        String body(String name, String externalId) {
            return "{\"schemas\":[\"" + schema + "\"],\"" + identifying + "\":\"" + name + "\""
                    + (externalId == null ? "" : ",\"externalId\":\"" + externalId + "\"") + "}";
        }
    }

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private ConnectorAdministrationService connectors;

    @Autowired
    private ScimExternalIdRepository aliases;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private RequestIdFilter requestIdFilter;

    @Autowired
    @Qualifier("springSecurityFilterChain")
    private Filter springSecurityFilterChain;

    private final JsonMapper json = JsonMapper.builder().build();

    private MockMvc mvc;

    private UUID connectorA;

    private String tokenA;

    private UUID connectorB;

    private String tokenB;

    private final List<UUID> createdResources = new ArrayList<>();

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context)
                .addFilters(requestIdFilter, springSecurityFilterChain)
                .build();
        connectorA = connectors.create("Entra-extid", "test-admin").id();
        tokenA = connectors.issueToken(connectorA, TokenPermissions.ALL, null,
                "test-admin", TokenPermissions.ALL).presentedValue();
        connectorB = connectors.create("Okta-extid", "test-admin").id();
        tokenB = connectors.issueToken(connectorB, TokenPermissions.ALL, null,
                "test-admin", TokenPermissions.ALL).presentedValue();
    }

    /**
     * Removes what this test created: its resources, and the aliases its two connectors hold —
     * the reserved resources survive, but an alias a test set on them must not.
     */
    @AfterEach
    void removeOnlyWhatThisTestCreated() {
        jdbc.update("DELETE FROM scim_external_ids WHERE connector_id IN (?, ?)",
                connectorA, connectorB);
        for (UUID id : createdResources) {
            jdbc.update("DELETE FROM scim_resources WHERE id = ? AND reserved_name IS NULL", id);
        }
        createdResources.clear();
    }

    // ---- PUT ------------------------------------------------------------------------------------

    /**
     * PUT changes, restates and omits {@code externalId}: a different value re-keys A's alias and
     * advances the version and {@code lastModified}; the same value is a no-op that advances
     * nothing; an omitted one removes A's alias (RFC 7644 §3.5.1). B's alias is unchanged
     * throughout.
     */
    @ParameterizedTest
    @EnumSource(Kind.class)
    void put_changes_restates_and_omits_only_the_callers_alias(Kind kind) throws Exception {
        String name = name(kind);
        Written created = create(kind, name, "a-1");
        setAliasAsB(kind, created.id(), "b-1");
        Written before = read(kind, created.id(), tokenA);
        Thread.sleep(5);

        Written changed = write(put(kind.one(created.id())), tokenA, before.etag(),
                kind.body(name, "a-2"));
        assertThat(changed.body().get("externalId").asText()).isEqualTo("a-2");
        assertThat(changed.etag()).isNotEqualTo(before.etag())
                .isEqualTo(changed.body().at("/meta/version").asText());
        assertThat(lastModified(changed)).isAfter(lastModified(before));
        assertStored(created.id(), "a-2", "b-1");

        Written restated = write(put(kind.one(created.id())), tokenA, changed.etag(),
                kind.body(name, "a-2"));
        assertThat(restated.etag()).as("a restated alias moves no version")
                .isEqualTo(changed.etag());
        assertThat(restated.body().at("/meta/lastModified"))
                .isEqualTo(changed.body().at("/meta/lastModified"));
        assertStored(created.id(), "a-2", "b-1");

        Written omitted = write(put(kind.one(created.id())), tokenA, restated.etag(),
                kind.body(name, null));
        assertThat(omitted.body().has("externalId")).isFalse();
        assertThat(omitted.etag()).isNotEqualTo(restated.etag());
        assertStored(created.id(), null, "b-1");
        assertThat(read(kind, created.id(), tokenB).body().get("externalId").asText())
                .isEqualTo("b-1");
    }

    // ---- PATCH ----------------------------------------------------------------------------------

    static List<Object[]> kindsAndPaths() {
        List<Object[]> cases = new ArrayList<>();
        for (Kind kind : Kind.values()) {
            cases.add(new Object[] {kind, "externalId"});
            cases.add(new Object[] {kind, kind.schema + ":externalId"});
        }
        return cases;
    }

    /**
     * PATCH {@code add} sets A's alias on a resource A created without one, {@code replace}
     * re-keys it and {@code remove} clears it — by the bare path and the schema-qualified one —
     * each advancing the version, and none touching B's.
     */
    @ParameterizedTest
    @MethodSource("kindsAndPaths")
    void patch_adds_replaces_and_removes_only_the_callers_alias(Kind kind, String path)
            throws Exception {
        Written created = create(kind, name(kind), null);
        setAliasAsB(kind, created.id(), "b-1");
        Written current = read(kind, created.id(), tokenA);
        assertStored(created.id(), null, "b-1");

        Written added = patchAs(kind, created.id(), tokenA, current.etag(),
                "{\"op\":\"add\",\"path\":\"" + path + "\",\"value\":\"a-1\"}");
        assertThat(added.body().get("externalId").asText()).isEqualTo("a-1");
        assertThat(added.etag()).isNotEqualTo(current.etag());
        assertStored(created.id(), "a-1", "b-1");

        Written replaced = patchAs(kind, created.id(), tokenA, added.etag(),
                "{\"op\":\"replace\",\"path\":\"" + path + "\",\"value\":\"a-2\"}");
        assertThat(replaced.body().get("externalId").asText()).isEqualTo("a-2");
        assertThat(replaced.etag()).isNotEqualTo(added.etag());
        assertStored(created.id(), "a-2", "b-1");

        Written removed = patchAs(kind, created.id(), tokenA, replaced.etag(),
                "{\"op\":\"remove\",\"path\":\"" + path + "\"}");
        assertThat(removed.body().has("externalId")).isFalse();
        assertThat(removed.etag()).isNotEqualTo(replaced.etag());
        assertStored(created.id(), null, "b-1");
    }

    // ---- two connectors -------------------------------------------------------------------------

    /**
     * One connector's change leaves the other's alias unchanged and invisible to it: after A
     * re-keys and B re-keys, each reads only its own value and neither response carries the
     * other's at all.
     */
    @ParameterizedTest
    @EnumSource(Kind.class)
    void one_connectors_change_leaves_the_others_alias_unchanged_and_invisible(Kind kind)
            throws Exception {
        // Distinctive values, so "not in the response" cannot be satisfied or broken by a
        // substring of an id or a timestamp.
        String a1 = "alias-of-a-one";
        String a2 = "alias-of-a-two";
        String b1 = "alias-of-b-one";
        String b2 = "alias-of-b-two";
        String name = name(kind);
        Written created = create(kind, name, a1);
        setAliasAsB(kind, created.id(), b1);

        Written current = read(kind, created.id(), tokenA);
        write(put(kind.one(created.id())), tokenA, current.etag(), kind.body(name, a2));
        assertStored(created.id(), a2, b1);

        current = read(kind, created.id(), tokenB);
        patchAs(kind, created.id(), tokenB, current.etag(),
                "{\"op\":\"replace\",\"path\":\"externalId\",\"value\":\"" + b2 + "\"}");
        assertStored(created.id(), a2, b2);

        String seenByA = raw(kind, created.id(), tokenA);
        String seenByB = raw(kind, created.id(), tokenB);
        assertThat(json.readTree(seenByA).get("externalId").asText()).isEqualTo(a2);
        assertThat(json.readTree(seenByB).get("externalId").asText()).isEqualTo(b2);
        assertThat(seenByA).doesNotContain("alias-of-b");
        assertThat(seenByB).doesNotContain("alias-of-a");
    }

    // ---- filter ---------------------------------------------------------------------------------

    /**
     * After a re-key, {@code externalId eq "<new>"} finds the resource and {@code eq "<old>"} does
     * not; and the other connector's filter on A's new value finds nothing, because it filters
     * its own namespace.
     */
    @ParameterizedTest
    @EnumSource(Kind.class)
    void a_filter_finds_the_new_alias_and_not_the_old(Kind kind) throws Exception {
        String name = name(kind);
        String oldAlias = "old-" + UUID.randomUUID();
        String newAlias = "new-" + UUID.randomUUID();
        Written created = create(kind, name, oldAlias);
        assertThat(filtered(kind, tokenA, oldAlias)).as("the old value matched before the change")
                .containsExactly(created.id().toString());

        Written current = read(kind, created.id(), tokenA);
        write(put(kind.one(created.id())), tokenA, current.etag(), kind.body(name, newAlias));

        assertThat(filtered(kind, tokenA, newAlias)).containsExactly(created.id().toString());
        assertThat(filtered(kind, tokenA, oldAlias)).isEmpty();
        assertThat(filtered(kind, tokenB, newAlias)).isEmpty();
    }

    // ---- audit ----------------------------------------------------------------------------------

    /**
     * An alias change is audited as a normal successful update naming the path
     * {@code externalId}, and the alias value appears in no column of the row and in no log
     * line — while it does appear in the response, so the search is for a value that was really
     * in flight.
     */
    @ParameterizedTest
    @EnumSource(Kind.class)
    void an_alias_change_is_audited_as_a_successful_update_with_no_alias_value(
            Kind kind, CapturedOutput output) throws Exception {
        String name = name(kind);
        Written created = create(kind, name, null);
        String alias = "secret-alias-" + UUID.randomUUID();

        Written current = read(kind, created.id(), tokenA);
        Written changed = write(put(kind.one(created.id())), tokenA, current.etag(),
                kind.body(name, alias));
        assertThat(changed.body().get("externalId").asText()).isEqualTo(alias);

        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT * FROM audit_events WHERE operation = ? AND actor_id = ?"
                        + " AND resource_id = ?",
                kind.replaceOperation, connectorA, created.id());
        assertThat(rows).singleElement().satisfies(row -> {
            assertThat(row.get("outcome")).isEqualTo("SUCCESS");
            assertThat(row.get("changed_paths")).isEqualTo("externalId");
            assertThat(row.values()).noneMatch(value -> String.valueOf(value).contains(alias));
        });
        assertThat(output.getAll()).isNotEmpty().doesNotContain(alias);
    }

    // ---- reserved resources ---------------------------------------------------------------------

    /**
     * The Bootstrap Admin keeps its protection: no SCIM write may change it, and an alias write
     * advances its version, so it is refused as {@code mutability} and nothing is stored.
     */
    @Test
    void the_bootstrap_admins_alias_is_not_writable() throws Exception {
        UUID bootstrapAdmin = reserved("bootstrap-admin");
        Written current = read(Kind.USER, bootstrapAdmin, tokenA);

        MvcResult refused = mvc.perform(patch(Kind.USER.one(bootstrapAdmin))
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenA)
                        .header(HttpHeaders.IF_MATCH, current.etag())
                        .contentType(SCIM_JSON)
                        .content(patchBody(
                                "{\"op\":\"add\",\"path\":\"externalId\",\"value\":\"root\"}")))
                .andReturn();

        assertThat(refused.getResponse().getStatus()).isEqualTo(400);
        assertThat(json.readTree(refused.getResponse().getContentAsString())
                .get("scimType").asText()).isEqualTo("mutability");
        assertThat(aliases.find(connectorA, bootstrapAdmin)).isEmpty();
        assertThat(read(Kind.USER, bootstrapAdmin, tokenA).etag()).isEqualTo(current.etag());
    }

    /**
     * The Admin group may not be renamed, but the caller's own alias for it is writable — it is
     * that connector's name for the Group, invisible to every other — and setting it leaves the
     * label as it was.
     */
    @Test
    void the_callers_alias_on_the_admin_group_is_writable() throws Exception {
        UUID adminGroup = reserved("admin-group");
        Written current = read(Kind.GROUP, adminGroup, tokenA);

        Written set = patchAs(Kind.GROUP, adminGroup, tokenA, current.etag(),
                "{\"op\":\"add\",\"path\":\"externalId\",\"value\":\"admins-a\"}");

        assertThat(set.body().get("externalId").asText()).isEqualTo("admins-a");
        assertThat(set.body().get("displayName")).isEqualTo(current.body().get("displayName"));
        assertStored(adminGroup, "admins-a", null);
        assertThat(read(Kind.GROUP, adminGroup, tokenB).body().has("externalId")).isFalse();
    }

    // ---- fixtures -------------------------------------------------------------------------------

    /** A response's body and its ETag. */
    private record Written(UUID id, JsonNode body, String etag) {
    }

    private static String name(Kind kind) {
        return kind.name().toLowerCase() + "-extid-" + UUID.randomUUID();
    }

    private Written create(Kind kind, String name, String externalId) throws Exception {
        MvcResult result = mvc.perform(post(kind.collection)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenA)
                        .contentType(SCIM_JSON)
                        .content(kind.body(name, externalId)))
                .andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(201);
        Written created = written(result);
        createdResources.add(created.id());
        return created;
    }

    /** B sets its own alias over HTTP, so B's namespace is written the way A's is. */
    private void setAliasAsB(Kind kind, UUID id, String externalId) throws Exception {
        Written current = read(kind, id, tokenB);
        patchAs(kind, id, tokenB, current.etag(),
                "{\"op\":\"add\",\"path\":\"externalId\",\"value\":\"" + externalId + "\"}");
        assertThat(aliases.find(connectorB, id)).contains(externalId);
    }

    private Written read(Kind kind, UUID id, String token) throws Exception {
        MvcResult result = mvc.perform(get(kind.one(id))
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        return written(result);
    }

    private String raw(Kind kind, UUID id, String token) throws Exception {
        return mvc.perform(get(kind.one(id)).header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andReturn().getResponse().getContentAsString();
    }

    private Written patchAs(Kind kind, UUID id, String token, String etag, String operation)
            throws Exception {
        return write(patch(kind.one(id)), token, etag, patchBody(operation));
    }

    private Written write(MockHttpServletRequestBuilder request, String token, String etag,
            String body) throws Exception {
        MvcResult result = mvc.perform(request
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .header(HttpHeaders.IF_MATCH, etag)
                        .contentType(SCIM_JSON)
                        .content(body))
                .andReturn();
        assertThat(result.getResponse().getStatus())
                .as(result.getResponse().getContentAsString()).isEqualTo(200);
        return written(result);
    }

    private static String patchBody(String operation) {
        return "{\"schemas\":[\"" + PATCH_OP + "\"],\"Operations\":[" + operation + "]}";
    }

    private List<String> filtered(Kind kind, String token, String externalId) throws Exception {
        MvcResult result = mvc.perform(get(kind.collection)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .param("filter", "externalId eq \"" + externalId + "\""))
                .andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        List<String> ids = new ArrayList<>();
        JsonNode resources = json.readTree(result.getResponse().getContentAsString())
                .get("Resources");
        if (resources != null) {
            resources.forEach(resource -> ids.add(resource.get("id").asText()));
        }
        return ids;
    }

    /** The stored alias of each connector, read through the connector-scoped port. */
    private void assertStored(UUID id, String ofA, String ofB) {
        assertThat(aliases.find(connectorA, id)).as("connector A's stored alias")
                .isEqualTo(java.util.Optional.ofNullable(ofA));
        assertThat(aliases.find(connectorB, id)).as("connector B's stored alias")
                .isEqualTo(java.util.Optional.ofNullable(ofB));
    }

    private UUID reserved(String reservedName) {
        return jdbc.queryForObject(
                "SELECT id FROM scim_resources WHERE reserved_name = ?", UUID.class, reservedName);
    }

    private Written written(MvcResult result) throws Exception {
        JsonNode body = json.readTree(result.getResponse().getContentAsString());
        return new Written(UUID.fromString(body.get("id").asText()), body,
                result.getResponse().getHeader(HttpHeaders.ETAG));
    }

    private static Instant lastModified(Written written) {
        return Instant.parse(written.body().at("/meta/lastModified").asText());
    }
}

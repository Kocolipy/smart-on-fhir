package com.example.backend.scim.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.assertj.core.api.Assertions.fail;

import com.example.backend.TokenPermissions;
import com.example.backend.scim.InMemoryScimUserRepository;
import com.example.backend.scim.domain.AuthenticatedConnector;
import com.example.backend.scim.domain.ScimAttribute;
import com.example.backend.scim.domain.ScimRequestRefusedException;
import com.example.backend.scim.domain.ScimResourceSchema;
import com.example.backend.scim.domain.ScimResourceType;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.BooleanNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Every writable attribute the User schema advertises survives the whole write and read path: a
 * connector's value is read from the request, stored, and rendered back — so the schema cannot
 * advertise an attribute this service accepts and then silently discards.
 *
 * <p><strong>The schema is the list.</strong> Every parameterized case below starts from
 * {@link ScimResourceSchema#writableNames()} and, for a complex attribute, from its declared
 * writable sub-attributes; the tables here hold only test DATA — a representative value and the
 * semantics expected of it — keyed by those names. An attribute the schema gains without a row
 * fails every case it reaches, by name, and a row the schema no longer advertises fails
 * {@link #every_case_is_for_an_advertised_writable_attribute}. Nothing here is a second production
 * registry: the rows say what a test sends and expects, never what the service implements.
 *
 * <p>Driven through {@link ScimUserController}, the SCIM resource interface a connector calls:
 * the request reader, the PATCH reader, the use case and the renderer all run for real, over the
 * in-memory adapters. Each write is read back with a {@code GET}, so a value the store did not
 * keep is a failure, not just a value the response echoed; a query, in either form, lists a User
 * exactly as that read renders it. The SQL Server adapter's fidelity for
 * every profile component is {@code ScimUserRepositoryContract}'s
 * ({@code a_created_user_is_read_back_as_given}, {@code a_replacement_stores_the_given_profile}),
 * run over both adapters; it builds a {@code ScimUserProfile} through its canonical constructor,
 * so a component the profile gains cannot be left out of it.
 *
 * <p>Three kinds of writable attribute, each held to its own semantics rather than excluded:
 *
 * <ul>
 *   <li><strong>Ordinary</strong>: read-write and returned by default — what is written is
 *       what is read back.
 *   <li><strong>The connector's alias</strong> ({@code externalId}): read back by the connector
 *       that wrote it and by no other. How an alias is re-keyed, restated and kept apart from
 *       another connector's on every verb is {@code ScimAliasWriteContract}'s, and which connector
 *       reads which alias over HTTP is {@code ScimExternalIdReadWriteIntegrationTests}'.
 *   <li><strong>The write-only credential</strong> ({@code password}): never rendered, and what
 *       is stored is the credential the login path reads through the directory port — the only
 *       place a write-only value can be observed. The history, the policy and the sessions a
 *       credential change ends are {@code ScimUserServiceTests}'
 *       ({@code a_new_password_is_hashed_stored_remembered_and_never_kept_in_plaintext},
 *       {@code a_put_replaces_the_profile_clears_omitted_optionals_and_keeps_an_omitted_password},
 *       {@code a_password_removal_is_written_and_ends_the_users_sessions}).
 * </ul>
 */
class ScimUserAttributeContractTests {

    private static final ScimResourceSchema SCHEMA = ScimResourceSchema.of(ScimResourceType.USER);

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private static final String PATCH_OP = "urn:ietf:params:scim:api:messages:2.0:PatchOp";

    /** The userName every case's base body carries, so a case varies one attribute alone. */
    private static final String BASE_USER_NAME = "contract-user";

    /**
     * How each kind of writable attribute is observed: what a write that set it leaves behind,
     * and what a write that left it unassigned does.
     */
    enum Kind {
        /** What is written is what the write answers with and what is read back. */
        ORDINARY {
            @Override
            void assertHeld(ScimUserAttributeContractTests contract, String name,
                    WriteResult written, JsonNode value) {
                assertRendered(written.document(), name, value, "the write's response");
                assertRendered(contract.get(CONNECTOR, written.id()), name, value, "read back");
            }

            @Override
            void assertUnassigned(ScimUserAttributeContractTests contract, String name,
                    WriteResult written) {
                assertNotRendered(written.document(), name, "the write's response");
                assertNotRendered(contract.get(CONNECTOR, written.id()), name, "read back");
            }
        },
        /** Read back by the connector that wrote it, as an ordinary value, and by no other. */
        ALIAS {
            @Override
            void assertHeld(ScimUserAttributeContractTests contract, String name,
                    WriteResult written, JsonNode value) {
                ORDINARY.assertHeld(contract, name, written, value);
                assertNotRendered(contract.get(OTHER_CONNECTOR, written.id()), name,
                        "read back by another connector");
            }

            @Override
            void assertUnassigned(ScimUserAttributeContractTests contract, String name,
                    WriteResult written) {
                ORDINARY.assertUnassigned(contract, name, written);
            }
        },
        /** Never rendered; observed as the credential the login path reads, or none. */
        CREDENTIAL {
            @Override
            void assertHeld(ScimUserAttributeContractTests contract, String name,
                    WriteResult written, JsonNode value) {
                ORDINARY.assertUnassigned(contract, name, written);
                assertThat(contract.storedCredential(written.id())).as("stored")
                        .isEqualTo("{contract}" + value.asString());
            }

            @Override
            void assertUnassigned(ScimUserAttributeContractTests contract, String name,
                    WriteResult written) {
                ORDINARY.assertUnassigned(contract, name, written);
                assertThat(contract.storedCredential(written.id())).as("stored").isNull();
            }
        };

        /** The write set the attribute to {@code value}. */
        abstract void assertHeld(ScimUserAttributeContractTests contract, String name,
                WriteResult written, JsonNode value);

        /** The write left the attribute with no value. */
        abstract void assertUnassigned(ScimUserAttributeContractTests contract, String name,
                WriteResult written);
    }

    /** The two ways a body leaves an attribute out, which RFC 7644 §3.5.1 makes the same. */
    enum Spelling {
        OMITTED,
        NULL
    }

    /** A PATCH operation's {@code op}. */
    enum Op {
        ADD,
        REPLACE,
        REMOVE;

        String wire() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    /** The two forms of one query of the User collection, RFC 7644 §3.4.2 and §3.4.3. */
    enum QueryForm {
        GET,
        SEARCH
    }

    /**
     * What a write does to an attribute it leaves out — omitted or {@code null}, which RFC 7644
     * §3.5.1 makes the same thing — or takes away with a PATCH {@code remove}.
     */
    enum Absence {
        /** The attribute is unassigned: not rendered, or no credential stored. */
        UNASSIGNED,
        /** The stored value stays as it was. */
        KEPT,
        /** A boolean with no unassigned state, which reads back as {@code true}. */
        READS_TRUE,
        /** A boolean with no unassigned state, which reads back as {@code false}. */
        READS_FALSE,
        /** Refused {@code 400 invalidSyntax}: the attribute is required. */
        REFUSED_AS_MISSING,
        /** Refused {@code 400 mutability}: the attribute cannot be removed. */
        REFUSED_AS_IMMUTABLE
    }

    /**
     * One writable attribute's test data.
     *
     * @param value             a representative valid value, which reads back exactly as sent,
     *                          and differs from what a create leaving the attribute out stores
     * @param omittedFromCreate what a create leaving the attribute out stores
     * @param omittedFromPut    what a PUT leaving the attribute out does to a stored value
     * @param removedByPatch    what a PATCH {@code remove} of it does to a stored value
     */
    record Case(Kind kind, String value, Absence omittedFromCreate, Absence omittedFromPut,
            Absence removedByPatch) {

        JsonNode json() {
            return JSON.readTree(value);
        }
    }

    /** One row per writable attribute the schema advertises: common, declared and credential. */
    private static final Map<String, Case> CASES = Map.ofEntries(
            Map.entry("externalId", new Case(Kind.ALIAS, "\"ext-ada-lovelace\"",
                    Absence.UNASSIGNED, Absence.UNASSIGNED, Absence.UNASSIGNED)),
            Map.entry("userName", new Case(Kind.ORDINARY, "\"ada.lovelace\"",
                    Absence.REFUSED_AS_MISSING, Absence.REFUSED_AS_MISSING,
                    Absence.REFUSED_AS_IMMUTABLE)),
            Map.entry("name", new Case(Kind.ORDINARY, """
                    {"formatted":"Dr. Ada M. Lovelace","familyName":"Lovelace","givenName":"Ada",
                     "middleName":"M.","honorificPrefix":"Dr.","honorificSuffix":"PhD"}""",
                    Absence.UNASSIGNED, Absence.UNASSIGNED, Absence.UNASSIGNED)),
            Map.entry("displayName", new Case(Kind.ORDINARY, "\"Ada Lovelace\"",
                    Absence.UNASSIGNED, Absence.UNASSIGNED, Absence.UNASSIGNED)),
            Map.entry("preferredLanguage", new Case(Kind.ORDINARY, "\"fr-CA\"",
                    Absence.UNASSIGNED, Absence.UNASSIGNED, Absence.UNASSIGNED)),
            Map.entry("locale", new Case(Kind.ORDINARY, "\"en-GB\"",
                    Absence.UNASSIGNED, Absence.UNASSIGNED, Absence.UNASSIGNED)),
            Map.entry("timezone", new Case(Kind.ORDINARY, "\"Europe/London\"",
                    Absence.UNASSIGNED, Absence.UNASSIGNED, Absence.UNASSIGNED)),
            // false, because a create that leaves `active` out makes the User active: a value the
            // reader dropped would read back as true and be caught.
            Map.entry("active", new Case(Kind.ORDINARY, "false",
                    Absence.READS_TRUE, Absence.KEPT, Absence.REFUSED_AS_IMMUTABLE)),
            Map.entry("password", new Case(Kind.CREDENTIAL, "\"a-representative-password-1\"",
                    Absence.UNASSIGNED, Absence.KEPT, Absence.UNASSIGNED)),
            Map.entry("emails", new Case(Kind.ORDINARY, """
                    [{"value":"ada@work.example","type":"work","primary":true}]""",
                    Absence.UNASSIGNED, Absence.UNASSIGNED, Absence.UNASSIGNED)));

    /**
     * What a PATCH {@code remove} of each writable sub-attribute does, keyed
     * {@code attribute.subAttribute}. The value a sub-attribute is set to is its part of the
     * attribute's own representative value, so the two tables cannot disagree.
     */
    private static final Map<String, Absence> SUB_ATTRIBUTE_REMOVALS = Map.ofEntries(
            Map.entry("name.formatted", Absence.UNASSIGNED),
            Map.entry("name.familyName", Absence.UNASSIGNED),
            Map.entry("name.givenName", Absence.UNASSIGNED),
            Map.entry("name.middleName", Absence.UNASSIGNED),
            Map.entry("name.honorificPrefix", Absence.UNASSIGNED),
            Map.entry("name.honorificSuffix", Absence.UNASSIGNED),
            Map.entry("emails.value", Absence.REFUSED_AS_IMMUTABLE),
            Map.entry("emails.type", Absence.UNASSIGNED),
            Map.entry("emails.primary", Absence.READS_FALSE));

    /**
     * How a sub-attribute path of a multi-valued attribute selects one stored value, by attribute:
     * a value to store when the case needs one, the same value as rendered, and the sub-attribute
     * whose value a filter selects on.
     *
     * @param written  a stored value for a case that sets a sub-attribute
     * @param rendered {@code written} as rendered back
     * @param key      the sub-attribute the selecting filter compares
     */
    record Selection(String written, String rendered, String key) {

        /** The path to {@code sub} of exactly the stored {@code element} of {@code parent}. */
        String path(String parent, JsonNode element, String sub) {
            return parent + "[" + key + " eq \"" + element.get(key).asString() + "\"]." + sub;
        }
    }

    private static final Map<String, Selection> SELECTIONS = Map.of(
            "emails", new Selection(
                    "{\"value\":\"seed@example.test\"}",
                    "{\"value\":\"seed@example.test\",\"primary\":false}",
                    "value"));

    private static final AuthenticatedConnector CONNECTOR = new AuthenticatedConnector(
            UUID.randomUUID(), UUID.randomUUID(), TokenPermissions.of(TokenPermissions.ALL));

    private static final AuthenticatedConnector OTHER_CONNECTOR = new AuthenticatedConnector(
            UUID.randomUUID(), UUID.randomUUID(), TokenPermissions.of(TokenPermissions.ALL));

    private final InMemoryScimUserRepository users = new InMemoryScimUserRepository();

    private final ScimUserController controller = ScimUserControllers.over(users);

    /** The renderer builds {@code meta.location} from the current request. */
    @BeforeEach
    void bindARequest() {
        RequestContextHolder.setRequestAttributes(
                new ServletRequestAttributes(new MockHttpServletRequest()));
    }

    @AfterEach
    void unbindTheRequest() {
        RequestContextHolder.resetRequestAttributes();
    }

    // ---- the sources: the schema's names --------------------------------------------------

    static Stream<String> writableAttributes() {
        return SCHEMA.writableNames().stream().sorted();
    }

    /** Every writable attribute with both ways a body leaves it out. */
    static Stream<Arguments> omissions() {
        return writableAttributes().flatMap(name -> Stream.of(Spelling.values())
                .map(spelling -> Arguments.of(name, spelling)));
    }

    /** Every writable attribute with every way a PATCH sets one: add or replace, path or none. */
    static Stream<Arguments> patchSettings() {
        return writableAttributes().flatMap(name -> Stream.of(Op.ADD, Op.REPLACE)
                .flatMap(op -> Stream.of(Arguments.of(name, op, true),
                        Arguments.of(name, op, false))));
    }

    /** Every writable sub-attribute of every writable complex attribute, as attribute.sub. */
    static Stream<String> writableSubAttributes() {
        return SCHEMA.allAttributes().stream()
                .filter(ScimAttribute::isWritable)
                .flatMap(attribute -> attribute.subAttributes().stream()
                        .filter(ScimAttribute::isWritable)
                        .map(sub -> attribute.name() + "." + sub.name()))
                .sorted();
    }

    static Stream<Arguments> subAttributeSettings() {
        return writableSubAttributes().flatMap(path -> Stream.of(
                Arguments.of(path, Op.ADD), Arguments.of(path, Op.REPLACE)));
    }

    // ---- the tables cover the schema exactly ----------------------------------------------

    /** A row for a name the schema stopped advertising would test nothing a connector can send. */
    @Test
    void every_case_is_for_an_advertised_writable_attribute() {
        assertThat(CASES.keySet()).isEqualTo(SCHEMA.writableNames());
        assertThat(SUB_ATTRIBUTE_REMOVALS.keySet())
                .isEqualTo(writableSubAttributes().collect(Collectors.toSet()));
    }

    // ---- create ---------------------------------------------------------------------------

    @ParameterizedTest
    @MethodSource("writableAttributes")
    void a_created_value_is_stored_and_rendered_back(String name) {
        Case given = caseOf(name);

        WriteResult created = create(with(name, given.json()));

        assertHolds(name, created, given.json());
    }

    /**
     * Attribute names match regardless of case (RFC 7643 §2.1, RFC 7644 §3.10): a value written
     * under its name upper-cased, and its sub-attributes' names with it, is the value stored.
     */
    @ParameterizedTest
    @MethodSource("writableAttributes")
    void a_created_value_named_in_another_case_is_stored_and_rendered_back(String name) {
        Case given = caseOf(name);

        WriteResult created = create(withUpperCasedName(name, given.json()));

        assertHolds(name, created, given.json());
    }

    @ParameterizedTest(name = "{0} {1}")
    @MethodSource("omissions")
    void a_create_leaving_the_value_out_does_what_its_semantics_say(
            String name, Spelling spelling) {
        Case given = caseOf(name);

        assertAbsence(name, given.omittedFromCreate(), given.json(),
                () -> create(leftOut(name, spelling)));
    }

    /**
     * The whole document a User with every writable attribute renders as: each attribute as
     * written, the credential withheld, and the common attributes the service derives.
     */
    @Test
    void a_user_with_every_writable_attribute_renders_as_exactly_this() {
        UUID id = create(everyWritableAttribute()).id();

        assertThat(get(CONNECTOR, id)).isEqualTo(JSON.readTree("""
                {"schemas":["urn:ietf:params:scim:schemas:core:2.0:User"],
                 "id":"%1$s",
                 "externalId":"ext-ada-lovelace",
                 "userName":"ada.lovelace",
                 "name":{"formatted":"Dr. Ada M. Lovelace","familyName":"Lovelace",
                         "givenName":"Ada","middleName":"M.","honorificPrefix":"Dr.",
                         "honorificSuffix":"PhD"},
                 "displayName":"Ada Lovelace",
                 "preferredLanguage":"fr-CA",
                 "locale":"en-GB",
                 "timezone":"Europe/London",
                 "active":false,
                 "emails":[{"value":"ada@work.example","type":"work","primary":true}],
                 "meta":{"resourceType":"User",
                         "created":"2026-01-01T00:00:00Z",
                         "lastModified":"2026-01-01T00:00:00Z",
                         "location":"http://localhost/scim/v2/Users/%1$s",
                         "version":"\\"1\\""}}""".formatted(id)));
    }

    /**
     * A query lists each User it finds exactly as that User's own read renders it, to each
     * connector: the alias for the connector that wrote it, and for no other.
     */
    @ParameterizedTest
    @EnumSource(QueryForm.class)
    void a_user_with_every_writable_attribute_is_listed_as_its_read_renders_it(QueryForm form) {
        UUID id = create(everyWritableAttribute()).id();

        assertThat(List.of(listed(CONNECTOR, form), listed(OTHER_CONNECTOR, form)))
                .isEqualTo(List.of(
                        JSON.createArrayNode().add(get(CONNECTOR, id)),
                        JSON.createArrayNode().add(get(OTHER_CONNECTOR, id))));
    }

    // ---- PUT ------------------------------------------------------------------------------

    @ParameterizedTest
    @MethodSource("writableAttributes")
    void a_replacement_carrying_the_value_stores_it(String name) {
        Case given = caseOf(name);
        UUID id = create(base()).id();

        WriteResult replaced = replace(id, with(name, given.json()));

        assertHolds(name, replaced, given.json());
    }

    @ParameterizedTest
    @MethodSource("writableAttributes")
    void a_replacement_carrying_the_value_named_in_another_case_stores_it(String name) {
        Case given = caseOf(name);
        UUID id = create(base()).id();

        WriteResult replaced = replace(id, withUpperCasedName(name, given.json()));

        assertHolds(name, replaced, given.json());
    }

    @ParameterizedTest(name = "{0} {1}")
    @MethodSource("omissions")
    void a_replacement_leaving_the_value_out_does_what_its_semantics_say(
            String name, Spelling spelling) {
        Case given = caseOf(name);
        UUID id = create(with(name, given.json())).id();

        assertAbsence(name, given.omittedFromPut(), given.json(),
                () -> replace(id, leftOut(name, spelling)));
    }

    // ---- PATCH ----------------------------------------------------------------------------

    @ParameterizedTest(name = "{0} by {1}, with a path: {2}")
    @MethodSource("patchSettings")
    void a_patch_setting_the_value_stores_it(String name, Op op, boolean withPath) {
        Case given = caseOf(name);
        UUID id = create(base()).id();

        WriteResult patched = patch(id, withPath
                ? operation(op, name, given.json())
                : operation(op, null, JSON.createObjectNode().set(name, given.json())));

        assertHolds(name, patched, given.json());
    }

    @ParameterizedTest
    @MethodSource("writableAttributes")
    void a_patch_removing_the_value_does_what_its_semantics_say(String name) {
        Case given = caseOf(name);
        UUID id = create(with(name, given.json())).id();

        assertAbsence(name, given.removedByPatch(), given.json(),
                () -> patch(id, operation(Op.REMOVE, name, null)));
    }

    // ---- PATCH on one sub-attribute -------------------------------------------------------

    /**
     * A sub-attribute path is the PATCH reader's own mapping, apart from the one a create reads
     * a whole complex value with: setting each sub-attribute alone reaches the store.
     */
    @ParameterizedTest(name = "{0} by {1}")
    @MethodSource("subAttributeSettings")
    void a_patch_setting_one_sub_attribute_stores_it(String path, Op op) {
        String parent = parentOf(path);
        String sub = subOf(path);
        JsonNode value = representativePart(parent, sub);
        Selection selection = SELECTIONS.get(parent);

        UUID id;
        String target;
        JsonNode expected;
        if (selection == null) {
            id = create(base()).id();
            target = path;
            expected = JSON.createObjectNode().set(sub, value);
        } else {
            JsonNode stored = JSON.readTree(selection.written());
            id = create(with(parent, JSON.createArrayNode().add(stored))).id();
            target = selection.path(parent, stored, sub);
            expected = JSON.createArrayNode().add(
                    ((ObjectNode) JSON.readTree(selection.rendered())).set(sub, value));
        }

        WriteResult patched = patch(id, operation(op, target, value));

        assertHolds(parent, patched, expected);
    }

    @ParameterizedTest
    @MethodSource("writableSubAttributes")
    void a_patch_removing_one_sub_attribute_does_what_its_semantics_say(String path) {
        String parent = parentOf(path);
        String sub = subOf(path);
        Absence removal = SUB_ATTRIBUTE_REMOVALS.get(path);
        if (removal == null) {
            fail("No removal expectation for the advertised sub-attribute " + path);
        }
        JsonNode stored = caseOf(parent).json();
        UUID id = create(with(parent, stored)).id();
        Selection selection = SELECTIONS.get(parent);
        String target = selection == null ? path : selection.path(parent, stored.get(0), sub);

        Attempt attempt = () -> patch(id, operation(Op.REMOVE, target, null));

        switch (removal) {
            case REFUSED_AS_MISSING, REFUSED_AS_IMMUTABLE ->
                    assertAbsence(parent, removal, stored, attempt);
            default -> assertHolds(parent, attempt.run(),
                    withoutPart(stored, selection != null, sub, removal));
        }
    }

    // ---- the expectations -----------------------------------------------------------------

    /** The value as its kind of attribute is observed after a write that set it. */
    private void assertHolds(String name, WriteResult written, JsonNode value) {
        caseOf(name).kind().assertHeld(this, name, written, value);
    }

    /**
     * What a write leaving out or removing a value did, as {@code absence} says.
     *
     * @param remaining what the attribute holds when the write keeps it, or keeps the rest of it
     */
    private void assertAbsence(String name, Absence absence, JsonNode remaining, Attempt attempt) {
        switch (absence) {
            case REFUSED_AS_MISSING ->
                    assertThat(refusal(attempt).scimType()).isEqualTo("invalidSyntax");
            case REFUSED_AS_IMMUTABLE ->
                    assertThat(refusal(attempt).scimType()).isEqualTo("mutability");
            case KEPT -> assertHolds(name, attempt.run(), remaining);
            case READS_TRUE -> assertHolds(name, attempt.run(), BooleanNode.TRUE);
            case READS_FALSE -> assertHolds(name, attempt.run(), BooleanNode.FALSE);
            case UNASSIGNED -> caseOf(name).kind().assertUnassigned(this, name, attempt.run());
        }
    }

    /**
     * What the attribute holds once a PATCH {@code remove} has taken one sub-attribute away:
     * the stored value without it, or, for a boolean with no unassigned state, with it false.
     */
    private static JsonNode withoutPart(
            JsonNode stored, boolean multiValued, String sub, Absence removal) {
        JsonNode remaining = stored.deepCopy();
        ObjectNode part = (ObjectNode) (multiValued ? remaining.get(0) : remaining);
        if (removal == Absence.READS_FALSE) {
            part.put(sub, false);
        } else {
            part.remove(sub);
        }
        return remaining;
    }

    private static void assertRendered(
            JsonNode document, String name, JsonNode value, String description) {
        assertThat(document.get(name)).as(description).isEqualTo(value);
    }

    private static void assertNotRendered(JsonNode document, String name, String description) {
        assertThat(document.has(name)).as(description).isFalse();
    }

    private static Case caseOf(String name) {
        Case given = CASES.get(name);
        if (given == null) {
            fail("No contract case for the advertised writable User attribute " + name
                    + ": give it a representative value and its semantics");
        }
        return given;
    }

    /** A sub-attribute's part of its attribute's representative value. */
    private static JsonNode representativePart(String parent, String sub) {
        JsonNode whole = caseOf(parent).json();
        JsonNode part = (whole.isArray() ? whole.get(0) : whole).get(sub);
        if (part == null) {
            fail("The representative " + parent + " has no " + sub + ": add one");
        }
        return part;
    }

    private static String parentOf(String path) {
        return path.substring(0, path.indexOf('.'));
    }

    private static String subOf(String path) {
        return path.substring(path.indexOf('.') + 1);
    }

    // ---- the interface --------------------------------------------------------------------

    /** What a write answered: the id it wrote and the document it rendered. */
    private record WriteResult(UUID id, JsonNode document) {
    }

    /** One write, made through the interface when the test calls for it. */
    @FunctionalInterface
    private interface Attempt {
        WriteResult run();
    }

    private static ObjectNode base() {
        ObjectNode body = JSON.createObjectNode();
        body.set("schemas", JSON.createArrayNode().add("urn:ietf:params:scim:schemas:core:2.0:User"));
        body.put("userName", BASE_USER_NAME);
        return body;
    }

    private static ObjectNode with(String name, JsonNode value) {
        ObjectNode body = base();
        body.set(name, value);
        return body;
    }

    /** The base body carrying {@code value} under {@code name} upper-cased, in place of it. */
    private static ObjectNode withUpperCasedName(String name, JsonNode value) {
        ObjectNode body = base();
        body.remove(name);
        body.set(name.toUpperCase(Locale.ROOT), upperCasedNames(value));
        return body;
    }

    /** {@code value} with every object member's name upper-cased, at every depth. */
    private static JsonNode upperCasedNames(JsonNode value) {
        if (value.isArray()) {
            ArrayNode recased = JSON.createArrayNode();
            value.forEach(element -> recased.add(upperCasedNames(element)));
            return recased;
        }
        if (value.isObject()) {
            ObjectNode recased = JSON.createObjectNode();
            value.propertyNames().forEach(member -> recased.set(
                    member.toUpperCase(Locale.ROOT), upperCasedNames(value.get(member))));
            return recased;
        }
        return value;
    }

    /** A body carrying every writable attribute's representative value. */
    private static ObjectNode everyWritableAttribute() {
        ObjectNode body = base();
        CASES.forEach((name, given) -> body.set(name, given.json()));
        return body;
    }

    /** The base body with the attribute left out as {@code spelling} says. */
    private static ObjectNode leftOut(String name, Spelling spelling) {
        ObjectNode body = base();
        switch (spelling) {
            case NULL -> body.putNull(name);
            case OMITTED -> body.remove(name);
        }
        return body;
    }

    private static ObjectNode operation(Op op, String path, JsonNode value) {
        ObjectNode operation = JSON.createObjectNode().put("op", op.wire());
        if (path != null) {
            operation.put("path", path);
        }
        if (value != null) {
            operation.set("value", value);
        }
        ObjectNode body = JSON.createObjectNode();
        body.set("schemas", JSON.createArrayNode().add(PATCH_OP));
        ArrayNode operations = JSON.createArrayNode().add(operation);
        body.set("Operations", operations);
        return body;
    }

    private WriteResult create(JsonNode body) {
        return written(controller.create(CONNECTOR, body, null, null));
    }

    private WriteResult replace(UUID id, JsonNode body) {
        return written(controller.replace(CONNECTOR, id.toString(), null, body, null, null));
    }

    private WriteResult patch(UUID id, JsonNode body) {
        return written(controller.patch(CONNECTOR, id.toString(), null, body, null, null));
    }

    private static WriteResult written(ResponseEntity<Map<String, Object>> response) {
        JsonNode document = JSON.valueToTree(response.getBody());
        return new WriteResult(UUID.fromString(document.get("id").asString()), document);
    }

    private JsonNode get(AuthenticatedConnector caller, UUID id) {
        return JSON.valueToTree(controller.byId(caller, id.toString(), null, null).getBody());
    }

    /** The resources one unfiltered query of the collection lists, to this caller. */
    private JsonNode listed(AuthenticatedConnector caller, QueryForm form) {
        ResponseEntity<Map<String, Object>> response = switch (form) {
            case GET -> controller.page(caller, null, null, null, null, null, null, null);
            case SEARCH -> controller.search(caller, JSON.readTree(
                    "{\"schemas\":[\"urn:ietf:params:scim:api:messages:2.0:SearchRequest\"]}"));
        };
        return JSON.valueToTree(response.getBody()).get("Resources");
    }

    /** The credential the login path reads through the directory port, or null for none. */
    private String storedCredential(UUID id) {
        return users.findById(id).orElseThrow().login().passwordHash();
    }

    /** A refused write as the SCIM edge answers it. */
    private static ScimErrorException refusal(Attempt attempt) {
        Throwable thrown = catchThrowable(attempt::run);
        if (thrown instanceof ScimRequestRefusedException refused) {
            return ScimRefusals.answer(refused);
        }
        assertThat(thrown).isInstanceOf(ScimErrorException.class);
        return (ScimErrorException) thrown;
    }
}

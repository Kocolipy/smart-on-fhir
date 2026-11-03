package com.example.backend.scim.controller;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.backend.scim.domain.ScimAttributeType;
import com.example.backend.scim.domain.ScimFilterPath;
import com.example.backend.scim.domain.ScimQueryVocabulary;
import com.example.backend.scim.domain.ScimQueryVocabulary.Attribute;
import com.example.backend.scim.domain.ScimResourceType;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * What a filter compares by is what {@code /Schemas} advertises.
 *
 * <p>A connector builds its filters from discovery — whether {@code displayName} is case-exact,
 * whether {@code active} is a boolean — so the query vocabulary and the schema documents must be
 * the same claim. Both are now derived from one definition, so two kinds of check live here:
 *
 * <ul>
 *   <li>the agreement check reads the RENDERED schema document — the wire values a connector
 *       sees — and holds every advertised attribute queryable with the advertised type, case
 *       sensitivity and cardinality, and nothing queryable that is not advertised apart from the
 *       RFC 7643 §3.1 common attributes;
 *   <li>the profile check is independent of the definition: the whole vocabulary of each type,
 *       written out here by hand, so a definition that drifted consistently in both views at once
 *       still fails.
 * </ul>
 */
class ScimQueryVocabularyTests {

    private static final String BASE_URI = ScimTestUris.BASE_URI;

    /** Queryable although no schema document declares them: RFC 7643 §3.1 common attributes. */
    private static final Set<ScimFilterPath> COMMON = Set.of(
            ScimFilterPath.ID, ScimFilterPath.EXTERNAL_ID, ScimFilterPath.META,
            ScimFilterPath.META_RESOURCE_TYPE, ScimFilterPath.META_CREATED,
            ScimFilterPath.META_LAST_MODIFIED, ScimFilterPath.META_LOCATION,
            ScimFilterPath.META_VERSION);

    @ParameterizedTest
    @EnumSource(ScimResourceType.class)
    void every_advertised_attribute_is_queryable_as_advertised_and_nothing_else_is(
            ScimResourceType type) {
        ScimQueryVocabulary vocabulary = ScimQueryVocabulary.of(type);
        Set<ScimFilterPath> covered = new HashSet<>(COMMON);

        for (Map<String, Object> attribute : attributes(ScimSchemaDocuments.of(type, BASE_URI))) {
            String name = (String) attribute.get("name");
            ScimFilterPath path = ScimFilterPath.of(name, null).orElseThrow();
            if (path == ScimFilterPath.PASSWORD) {
                assertThat(vocabulary.find(path)).as("the password is advertised, never queryable")
                        .isEmpty();
                continue;
            }
            assertAgrees(vocabulary.find(path), attribute, false);
            covered.add(path);
            for (Map<String, Object> sub : subAttributes(attribute)) {
                ScimFilterPath subPath = path.subAttribute((String) sub.get("name")).orElseThrow();
                assertAgrees(vocabulary.find(subPath), sub, (Boolean) attribute.get("multiValued"));
                covered.add(subPath);
            }
        }
        assertThat(vocabulary.paths()).containsExactlyInAnyOrderElementsOf(covered);
    }

    private static void assertAgrees(
            Optional<Attribute> queryable, Map<String, Object> advertised, boolean inMulti) {
        String name = (String) advertised.get("name");
        assertThat(queryable).as(name).isPresent();
        Attribute attribute = queryable.get();
        assertThat(kind(attribute.kind())).as(name).isEqualTo(advertised.get("type"));
        assertThat(attribute.multiValued()).as(name)
                .isEqualTo((Boolean) advertised.get("multiValued") || inMulti);
        if (attribute.isTextual()) {
            assertThat(attribute.caseExact()).as(name + " caseExact")
                    .isEqualTo(advertised.get("caseExact"));
        }
    }

    private static String kind(ScimAttributeType kind) {
        return Map.of(
                ScimAttributeType.STRING, "string", ScimAttributeType.BOOLEAN, "boolean",
                ScimAttributeType.DATE_TIME, "dateTime", ScimAttributeType.REFERENCE, "reference",
                ScimAttributeType.COMPLEX, "complex").get(kind);
    }

    // --- the profile, independently -----------------------------------------------------------

    /**
     * Every queryable User path with its comparison, as this directory's profile has it. Profile
     * strings case-insensitive; identifiers, the {@code meta} values and membership ids and
     * references case-exact; {@code active} and {@code emails.primary} boolean; {@code name} the
     * one single-valued complex attribute; {@code emails} and {@code groups} multi-valued, with
     * their sub-attributes inheriting it. {@code password} is absent.
     */
    @Test
    void the_user_vocabulary_is_exactly_this() {
        assertThat(rows(ScimResourceType.USER)).containsExactlyInAnyOrderElementsOf(Stream.of(
                common(),
                List.of(
                        row(ScimFilterPath.USER_NAME, ScimAttributeType.STRING, false, false),
                        row(ScimFilterPath.NAME, ScimAttributeType.COMPLEX, false, false),
                        row(ScimFilterPath.NAME_FORMATTED, ScimAttributeType.STRING, false, false),
                        row(ScimFilterPath.NAME_FAMILY_NAME, ScimAttributeType.STRING, false, false),
                        row(ScimFilterPath.NAME_GIVEN_NAME, ScimAttributeType.STRING, false, false),
                        row(ScimFilterPath.NAME_MIDDLE_NAME, ScimAttributeType.STRING, false, false),
                        row(ScimFilterPath.NAME_HONORIFIC_PREFIX, ScimAttributeType.STRING, false,
                                false),
                        row(ScimFilterPath.NAME_HONORIFIC_SUFFIX, ScimAttributeType.STRING, false,
                                false),
                        row(ScimFilterPath.DISPLAY_NAME, ScimAttributeType.STRING, false, false),
                        row(ScimFilterPath.PREFERRED_LANGUAGE, ScimAttributeType.STRING, false,
                                false),
                        row(ScimFilterPath.LOCALE, ScimAttributeType.STRING, false, false),
                        row(ScimFilterPath.TIMEZONE, ScimAttributeType.STRING, false, false),
                        row(ScimFilterPath.ACTIVE, ScimAttributeType.BOOLEAN, false, false),
                        row(ScimFilterPath.EMAILS, ScimAttributeType.COMPLEX, false, true),
                        row(ScimFilterPath.EMAILS_VALUE, ScimAttributeType.STRING, false, true),
                        row(ScimFilterPath.EMAILS_TYPE, ScimAttributeType.STRING, false, true),
                        row(ScimFilterPath.EMAILS_PRIMARY, ScimAttributeType.BOOLEAN, false, true),
                        row(ScimFilterPath.GROUPS, ScimAttributeType.COMPLEX, false, true),
                        row(ScimFilterPath.GROUPS_VALUE, ScimAttributeType.STRING, true, true),
                        row(ScimFilterPath.GROUPS_DISPLAY, ScimAttributeType.STRING, false, true),
                        row(ScimFilterPath.GROUPS_REF, ScimAttributeType.REFERENCE, true, true),
                        row(ScimFilterPath.GROUPS_TYPE, ScimAttributeType.STRING, false, true)))
                .flatMap(List::stream)
                .toList());
    }

    /** Every queryable Group path with its comparison: a case-insensitive name and a membership. */
    @Test
    void the_group_vocabulary_is_exactly_this() {
        assertThat(rows(ScimResourceType.GROUP)).containsExactlyInAnyOrderElementsOf(Stream.of(
                common(),
                List.of(
                        row(ScimFilterPath.DISPLAY_NAME, ScimAttributeType.STRING, false, false),
                        row(ScimFilterPath.MEMBERS, ScimAttributeType.COMPLEX, false, true),
                        row(ScimFilterPath.MEMBERS_VALUE, ScimAttributeType.STRING, true, true),
                        row(ScimFilterPath.MEMBERS_DISPLAY, ScimAttributeType.STRING, false, true),
                        row(ScimFilterPath.MEMBERS_REF, ScimAttributeType.REFERENCE, true, true),
                        row(ScimFilterPath.MEMBERS_TYPE, ScimAttributeType.STRING, false, true)))
                .flatMap(List::stream)
                .toList());
    }

    /** The common attributes, identical on both types, and case-exact throughout. */
    private static List<Attribute> common() {
        return List.of(
                row(ScimFilterPath.ID, ScimAttributeType.STRING, true, false),
                row(ScimFilterPath.EXTERNAL_ID, ScimAttributeType.STRING, true, false),
                row(ScimFilterPath.META, ScimAttributeType.COMPLEX, true, false),
                row(ScimFilterPath.META_RESOURCE_TYPE, ScimAttributeType.STRING, true, false),
                row(ScimFilterPath.META_CREATED, ScimAttributeType.DATE_TIME, true, false),
                row(ScimFilterPath.META_LAST_MODIFIED, ScimAttributeType.DATE_TIME, true, false),
                row(ScimFilterPath.META_LOCATION, ScimAttributeType.REFERENCE, true, false),
                row(ScimFilterPath.META_VERSION, ScimAttributeType.STRING, true, false));
    }

    private static Attribute row(
            ScimFilterPath path, ScimAttributeType kind, boolean caseExact, boolean multiValued) {
        return new Attribute(path, kind, caseExact, multiValued);
    }

    private static Set<Attribute> rows(ScimResourceType type) {
        ScimQueryVocabulary vocabulary = ScimQueryVocabulary.of(type);
        return vocabulary.paths().stream()
                .map(path -> vocabulary.find(path).orElseThrow())
                .collect(Collectors.toSet());
    }

    /** Strings and references compare as text; only a complex attribute is complex. */
    @ParameterizedTest
    @EnumSource(ScimAttributeType.class)
    void only_strings_and_references_are_textual_and_only_complex_is_complex(
            ScimAttributeType kind) {
        Attribute attribute = new Attribute(ScimFilterPath.ID, kind, false, false);

        assertThat(attribute.isTextual()).isEqualTo(
                kind == ScimAttributeType.STRING || kind == ScimAttributeType.REFERENCE);
        assertThat(attribute.isComplex()).isEqualTo(kind == ScimAttributeType.COMPLEX);
    }

    /** A path both types have compares the same way on both, so a base search has one meaning. */
    @Test
    void a_path_both_types_have_is_the_same_kind_on_both() {
        ScimQueryVocabulary users = ScimQueryVocabulary.of(ScimResourceType.USER);
        ScimQueryVocabulary groups = ScimQueryVocabulary.of(ScimResourceType.GROUP);
        Set<ScimFilterPath> shared = new HashSet<>(users.paths());
        shared.retainAll(groups.paths());

        assertThat(shared).containsExactlyInAnyOrderElementsOf(
                union(COMMON, Set.of(ScimFilterPath.DISPLAY_NAME)));
        for (ScimFilterPath path : shared) {
            assertThat(users.find(path).orElseThrow().kind()).as(path.canonical())
                    .isEqualTo(groups.find(path).orElseThrow().kind());
        }
    }

    /** The schema URI a filter path is qualified with is the one the adapter renders. */
    @Test
    void each_types_schema_uri_is_the_rendered_schema() {
        assertThat(ScimResourceType.USER.schemaUri()).isEqualTo(ScimSchemas.USER);
        assertThat(ScimResourceType.GROUP.schemaUri()).isEqualTo(ScimSchemas.GROUP);
    }

    private static Set<ScimFilterPath> union(Set<ScimFilterPath> a, Set<ScimFilterPath> b) {
        Set<ScimFilterPath> union = new HashSet<>(a);
        union.addAll(b);
        return union;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> attributes(Map<String, Object> document) {
        return (List<Map<String, Object>>) document.get("attributes");
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> subAttributes(Map<String, Object> attribute) {
        Object subs = attribute.get("subAttributes");
        return subs == null ? List.of() : (List<Map<String, Object>>) subs;
    }
}

package com.example.backend.scim.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.backend.paging.domain.PageRequest;
import com.example.backend.scim.domain.ScimFilter;
import com.example.backend.scim.domain.ScimFilterParser;
import com.example.backend.scim.domain.ScimQuery;
import com.example.backend.scim.domain.ScimResourceType;
import com.example.backend.scim.domain.ScimSort;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Locale;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The parameterization criterion, held as a property of the translator's OUTPUT: whatever a
 * filter's literal contains, it never appears in the statement text, and it does appear —
 * verbatim, or as the escaped {@code LIKE} pattern built from it — among the bound values.
 *
 * <p>The integration fixtures prove the statements return the right rows; this proves how they
 * got there. A translator that concatenated a quoted value would pass most row-level fixtures and
 * fail here on the first one.
 */
class ScimQuerySqlTests {

    private static final Set<ScimResourceType> USERS = Set.of(ScimResourceType.USER);

    private static final Set<ScimResourceType> BOTH =
            Set.of(ScimResourceType.USER, ScimResourceType.GROUP);

    private static final UUID CONNECTOR = UUID.fromString("00000000-0000-4000-8000-000000000001");

    private static final String BASE_URI = "https://scim.example/scim/v2";

    private static ScimQuery query(String filter, Set<ScimResourceType> types) {
        return new ScimQuery(types, ScimFilterParser.parse(filter, types), null,
                PageRequest.FIRST_PAGE);
    }

    /** The count statement a fresh translator renders for {@code filter} over {@code types}. */
    private static String count(String filter, Set<ScimResourceType> types) {
        return new ScimQuerySql(CONNECTOR, BASE_URI).count(query(filter, types));
    }

    /**
     * Literals built to look like the statement's own syntax. None is a fragment of the fixed SQL
     * this class emits, so "does not contain" is a test of where the literal went rather than of
     * whether the translator happens to use the same characters.
     */
    @ParameterizedTest
    @ValueSource(strings = {
            "x' OR '1'='1",
            "'; DROP TABLE scim_users; --",
            "qp-alice' --",
            "O'Brien",
            ":connectorIdX",
            ":p99x",
            "?",
            "$1",
            "a\\b",
            "50%_off",
            "\"quoted\"",
            "a) OR (1=1",
            "*/ SELECT 1 /*",
            "é\u2028\u00a0"})
    void a_literal_never_becomes_statement_text(String literal) {
        String json = literal.replace("\\", "\\\\").replace("\"", "\\\"");
        for (String operator : new String[] {"eq", "ne", "co", "sw", "ew", "gt", "le"}) {
            for (String path : new String[] {"userName", "displayName", "emails.value", "externalId"}) {
                String filter = path + " " + operator + " \"" + json + "\" or emails[type "
                        + operator + " \"" + json + "\"]";
                ScimQuery query = query(filter, BOTH);

                ScimQuerySql count = new ScimQuerySql(CONNECTOR, BASE_URI);
                String countSql = count.count(query);
                ScimQuerySql page = new ScimQuerySql(CONNECTOR, BASE_URI);
                String pageSql = page.page(query);

                assertThat(countSql).as(filter).doesNotContain(literal);
                assertThat(pageSql).as(filter).doesNotContain(literal);
                String bound = operator.equals("co") ? "%" + ScimQuerySql.escapeLike(literal) + "%"
                        : operator.equals("sw") ? ScimQuerySql.escapeLike(literal) + "%"
                        : operator.equals("ew") ? "%" + ScimQuerySql.escapeLike(literal)
                        : literal;
                assertThat(count.parameters()).as(filter).containsValue(bound);
                assertThat(page.parameters()).as(filter).containsValue(bound);
            }
        }
    }

    /** The same property over random printable text, seeded so a failure reproduces. */
    @Test
    void no_random_literal_becomes_statement_text() {
        Random random = new Random(17L);
        for (int round = 0; round < 2_000; round++) {
            // A leading § cannot occur in the statement's fixed text, so any match is the literal.
            StringBuilder literal = new StringBuilder("§");
            int length = 1 + random.nextInt(12);
            for (int i = 0; i < length; i++) {
                literal.append((char) (0x21 + random.nextInt(0x5e)));
            }
            String value = literal.toString();
            String json = value.replace("\\", "\\\\").replace("\"", "\\\"");
            ScimQuery query = query("userName eq \"" + json + "\"", USERS);
            ScimQuerySql sql = new ScimQuerySql(CONNECTOR, BASE_URI);

            assertThat(sql.page(query)).doesNotContain(value);
            assertThat(sql.parameters()).containsValue(value);
        }
    }

    @Test
    void a_like_pattern_escapes_its_own_metacharacters() {
        assertThat(ScimQuerySql.escapeLike("50%_off\\")).isEqualTo("50\\%\\_off\\\\");
        assertThat(ScimQuerySql.escapeLike("plain")).isEqualTo("plain");
    }

    /** SQL Server's LIKE reads {@code [} as a character class; escaped, it is the character. */
    @Test
    void a_like_pattern_escapes_an_opening_bracket() {
        assertThat(ScimQuerySql.escapeLike("a[b-c]")).isEqualTo("a\\[b-c]");
    }

    /** Case sensitivity follows the attribute: lower() on both sides only where caseExact=false. */
    @Test
    void case_insensitive_attributes_lower_both_sides_and_case_exact_ones_neither() {
        ScimQuerySql insensitive = new ScimQuerySql(CONNECTOR, BASE_URI);
        String userName = insensitive.count(query("userName eq \"A\"", USERS));
        ScimQuerySql exact = new ScimQuerySql(CONNECTOR, BASE_URI);
        String externalId = exact.count(query("externalId eq \"A\"", USERS));

        assertThat(userName).contains("lower(u.user_name) = lower(:p0)");
        assertThat(externalId).doesNotContain("lower(");
    }

    /**
     * Every resource id compared or rendered as text is lower-cased, as the API renders it, so the
     * answer does not depend on the case an engine's driver gives a UUID.
     */
    @Test
    void an_id_compared_as_text_is_lower_cased() {
        assertThat(count("id eq \"a\"", USERS))
                .contains("lower(CAST(r.id AS VARCHAR(36))) = :p0");
        assertThat(count("groups.value eq \"a\"", USERS))
                .contains("lower(CAST(gg.resource_id AS VARCHAR(36))) = :p0");
        assertThat(count("members[value eq \"a\"]", Set.of(ScimResourceType.GROUP)))
                .contains("lower(CAST(mu.resource_id AS VARCHAR(36))) = :p0");
    }

    /** A rendered URI or ETag is built with CONCAT, the id in it lower-cased. */
    @Test
    void a_rendered_reference_concatenates_the_base_uri_and_the_lower_cased_id() {
        assertThat(count("meta.location eq \"a\"", USERS))
                .contains("CONCAT(:baseUri, '/Users/', lower(CAST(r.id AS VARCHAR(36))))");
        assertThat(count("meta.location eq \"a\"", Set.of(ScimResourceType.GROUP)))
                .contains("CONCAT(:baseUri, '/Groups/', lower(CAST(r.id AS VARCHAR(36))))");
        assertThat(count("groups.$ref eq \"a\"", USERS))
                .contains("CONCAT(:baseUri, '/Groups/', lower(CAST(gg.resource_id AS VARCHAR(36))))");
        assertThat(count("members.$ref eq \"a\"", Set.of(ScimResourceType.GROUP)))
                .contains("CONCAT(:baseUri, '/Users/', lower(CAST(mu.resource_id AS VARCHAR(36))))");
        assertThat(count("meta.version eq \"a\"", USERS))
                .contains("CONCAT('\"', CAST(r.version AS VARCHAR(20)), '\"')");
    }

    /**
     * Two-valued logic: each comparison first requires a value, so a missing one makes it FALSE
     * rather than unknown, and {@code not} of it is TRUE.
     */
    @Test
    void a_comparison_requires_a_value_so_it_is_never_unknown_even_under_not() {
        assertThat(count("not (name.givenName eq \"a\")", USERS))
                .endsWith("WHERE (NOT (u.given_name IS NOT NULL"
                        + " AND lower(u.given_name) = lower(:p0))))");
        assertThat(count("name.givenName co \"a\"", USERS))
                .endsWith("WHERE (u.given_name IS NOT NULL"
                        + " AND lower(u.given_name) LIKE lower(:p0) ESCAPE '\\'))");
        assertThat(count("name.givenName gt \"a\"", USERS))
                .endsWith("WHERE (u.given_name IS NOT NULL"
                        + " AND (lower(u.given_name)) COLLATE Latin1_General_100_BIN2_UTF8 > lower(:p0)))");
        assertThat(count("meta.created gt \"2011-05-13T04:42:34Z\"", USERS))
                .endsWith("WHERE (r.created_at IS NOT NULL AND r.created_at > :p0))");
    }

    /**
     * T-SQL throughout: across filters and sorts reaching every fragment kind, no statement uses
     * syntax T-SQL rejects or reads otherwise, and the engine words — the binary UTF-8 collation, the types
     * a NULL is cast to and {@code TOP 1} for a first value — appear only in their positions.
     */
    @Test
    void statements_are_t_sql_and_engine_words_appear_only_in_their_positions() {
        String[] filters = {
                "userName eq \"a\" and not (name.givenName gt \"b\")",
                "id eq \"a\" or externalId sw \"b\" or meta.location ew \"c\"",
                "meta.version eq \"a\" or meta.created ge \"2011-05-13T04:42:34Z\" or active eq true",
                "emails[type eq \"work\" and primary eq true] or emails.value co \"[x]\"",
                "groups.value eq \"a\" or groups.$ref pr or groups.display lt \"b\"",
                "members.value eq \"a\" or members.$ref eq \"b\" or members.display le \"c\"",
                "name pr and meta pr and displayName ne \"a\""};
        String[] sorts = {"userName", "active", "meta.created", "emails.value", "members.display",
                "groups.$ref", "meta.location", "meta.version", "id"};
        StringBuilder all = new StringBuilder();
        for (String filter : filters) {
            ScimQuery query = query(filter, BOTH);
            all.append(new ScimQuerySql(CONNECTOR, BASE_URI).count(query)).append('\n');
            for (String sort : sorts) {
                for (String order : new String[] {"ascending", "descending"}) {
                    all.append(new ScimQuerySql(CONNECTOR, BASE_URI).page(new ScimQuery(BOTH,
                            query.filter(), ScimSort.of(sort, order, BOTH),
                            PageRequest.FIRST_PAGE))).append('\n');
                }
            }
            all.append(new ScimQuerySql(CONNECTOR, BASE_URI).page(query)).append('\n');
        }
        String statements = all.toString();
        String upper = statements.toUpperCase(Locale.ROOT);

        for (String notTSql : new String[] {
                " LIMIT ", "NULLS FIRST", "NULLS LAST", "||", "TRUE", "FALSE", "::", "ILIKE",
                "AS TEXT", "AS VARCHAR)", "COALESCE(LOWER", ", FALSE)", "\"C\"", "BOOLEAN",
                "TIMESTAMPTZ", "OFFSET 0 ROWS"}) {
            assertThat(upper).as(notTSql).doesNotContain(notTSql);
        }
        String withoutEngineWords = statements.replace("COLLATE Latin1_General_100_BIN2_UTF8", "")
                .replace("CAST(NULL AS BIT)", "")
                .replace("SELECT TOP 1 ", "");
        assertThat(statements).contains("COLLATE Latin1_General_100_BIN2_UTF8")
                .contains("CAST(NULL AS BIT)").contains("SELECT TOP 1 ");
        assertThat(withoutEngineWords).doesNotContain("COLLATE").doesNotContain("Latin1")
                .doesNotContain("BIT").doesNotContain("TOP");
    }

    /** A boolean literal is bound as a boolean, which the driver sends as a {@code BIT}. */
    @Test
    void a_boolean_literal_is_bound_as_a_boolean_against_the_bit_column() {
        ScimQuerySql sql = new ScimQuerySql(CONNECTOR, BASE_URI);

        assertThat(sql.count(query("active eq false", USERS)))
                .endsWith("WHERE (u.active IS NOT NULL AND u.active = :p0))");
        assertThat(sql.parameters().get("p0")).isEqualTo(Boolean.FALSE);
    }

    @Test
    void a_datetime_literal_is_bound_as_a_utc_timestamp() {
        ScimQuerySql sql = new ScimQuerySql(CONNECTOR, BASE_URI);
        sql.count(query("meta.created ge \"2011-05-13T04:42:34+08:00\"", USERS));

        assertThat(sql.parameters().get("p0"))
                .isEqualTo(OffsetDateTime.of(2011, 5, 12, 20, 42, 34, 0, ZoneOffset.UTC));
    }

    @Test
    void the_connector_and_base_uri_are_bound_values_too() {
        ScimQuerySql sql = new ScimQuerySql(CONNECTOR, BASE_URI);
        String statement = sql.count(query("externalId eq \"e\" and meta.location pr", USERS));

        assertThat(statement).contains(":connectorId").contains(":baseUri")
                .doesNotContain(CONNECTOR.toString()).doesNotContain(BASE_URI);
        assertThat(sql.parameters()).containsEntry("connectorId", CONNECTOR)
                .containsEntry("baseUri", BASE_URI);
    }

    /** An attribute a type lacks renders as the constant its absence implies, in that type's branch. */
    @Test
    void an_attribute_a_type_lacks_is_a_constant_in_that_branch() {
        ScimQuerySql sql = new ScimQuerySql(CONNECTOR, BASE_URI);
        String statement = sql.count(query(
                "userName eq \"a\" or userName ne \"b\" or members pr or emails[type pr]", BOTH));

        String userBranch = statement.substring(0, statement.indexOf(" + "));
        String groupBranch = statement.substring(statement.indexOf(" + "));
        assertThat(groupBranch).isEqualTo(" + (SELECT count(*) FROM scim_resources r"
                + " JOIN scim_groups g ON g.resource_id = r.id WHERE ((((1=0) OR (NOT (1=0)))"
                + " OR EXISTS (SELECT 1 FROM scim_group_members mm JOIN scim_users mu"
                + " ON mu.resource_id = mm.user_id WHERE mm.group_id = r.id)) OR (1=0)))");
        assertThat(userBranch).doesNotContain("mm.group_id").contains("(1=0)");
    }

    @Test
    void a_descending_page_puts_missing_first_orders_by_the_sort_key_then_id_and_binds_its_window() {
        ScimQuery sorted = new ScimQuery(
                USERS, null,
                ScimSort.of("displayName", "descending", USERS),
                new PageRequest(11, 5));
        ScimQuerySql sql = new ScimQuerySql(CONNECTOR, BASE_URI);

        String statement = sql.page(sorted);

        assertThat(statement).endsWith(
                "ORDER BY CASE WHEN q.sort_key IS NULL THEN 1 ELSE 0 END DESC,"
                        + " q.sort_key COLLATE Latin1_General_100_BIN2_UTF8 DESC, q.id"
                        + " OFFSET :offset ROWS FETCH NEXT :limit ROWS ONLY");
        assertThat(sql.parameters()).containsEntry("limit", 5).containsEntry("offset", 10);
    }

    @Test
    void an_unsorted_page_uses_the_default_order() {
        ScimQuerySql sql = new ScimQuerySql(CONNECTOR, BASE_URI);

        String statement = sql.page(new ScimQuery(BOTH, null, null, PageRequest.FIRST_PAGE));

        assertThat(statement).isEqualTo("SELECT q.id, q.kind FROM (SELECT r.id AS id,"
                + " r.resource_type AS kind, CAST(NULL AS VARCHAR(1024)) AS sort_key,"
                + " 0 AS type_order, u.normalized_user_name AS default_key"
                + " FROM scim_resources r JOIN scim_users u ON u.resource_id = r.id WHERE (1=1)"
                + " UNION ALL SELECT r.id AS id, r.resource_type AS kind,"
                + " CAST(NULL AS VARCHAR(1024)) AS sort_key, 1 AS type_order,"
                + " g.normalized_display_name AS default_key"
                + " FROM scim_resources r JOIN scim_groups g ON g.resource_id = r.id WHERE (1=1)) q"
                + " ORDER BY q.type_order, q.default_key, q.id"
                + " OFFSET :offset ROWS FETCH NEXT :limit ROWS ONLY");
    }

    @Test
    void a_non_text_sort_key_has_no_collation_and_ascending_puts_missing_last() {
        ScimQuery sorted = new ScimQuery(USERS, null, ScimSort.of("meta.created", null, USERS),
                PageRequest.FIRST_PAGE);
        ScimQuerySql sql = new ScimQuerySql(CONNECTOR, BASE_URI);

        assertThat(sql.page(sorted)).contains("r.created_at AS sort_key")
                .contains("ORDER BY CASE WHEN q.sort_key IS NULL THEN 1 ELSE 0 END ASC,"
                        + " q.sort_key ASC, q.id");
    }

    /**
     * Presence by kind: text is present only when non-empty (SCIM treats "" as unassigned), any
     * other kind when non-null, and meta always. Text-level because an empty string cannot be
     * stored through the API to show the difference end to end.
     */
    @Test
    void presence_renders_by_the_attributes_kind() {
        assertThat(count("displayName pr", USERS))
                .contains("WHERE (u.display_name IS NOT NULL AND u.display_name <> ''))");
        assertThat(count("active pr", USERS))
                .contains("WHERE (u.active IS NOT NULL))");
        assertThat(count("meta pr", USERS))
                .contains("WHERE (1=1))");
    }

    /** In a base search the branch lacking the sort attribute projects a NULL of the other's type. */
    @Test
    void a_branch_lacking_the_sort_attribute_projects_a_typed_null() {
        ScimQuerySql text = new ScimQuerySql(CONNECTOR, BASE_URI);
        ScimQuerySql bool = new ScimQuerySql(CONNECTOR, BASE_URI);
        ScimQuerySql members = new ScimQuerySql(CONNECTOR, BASE_URI);

        assertThat(text.page(new ScimQuery(BOTH, null, ScimSort.of("userName", null, BOTH), null)))
                .contains("CAST(NULL AS VARCHAR(1024)) AS sort_key");
        assertThat(bool.page(new ScimQuery(BOTH, null, ScimSort.of("active", null, BOTH), null)))
                .contains("CAST(NULL AS BIT) AS sort_key")
                .doesNotContain("COLLATE");
        assertThat(members.page(new ScimQuery(
                        BOTH, null, ScimSort.of("members.display", null, BOTH), null)))
                .contains("CAST(NULL AS VARCHAR(1024)) AS sort_key")
                .contains("lower(NULLIF((SELECT TOP 1 coalesce(mu.display_name, mu.user_name)"
                        + " FROM scim_group_members mm JOIN scim_users mu"
                        + " ON mu.resource_id = mm.user_id WHERE mm.group_id = r.id"
                        + " ORDER BY mu.normalized_user_name, mu.resource_id), ''))");
    }

    /**
     * A filter tree with a missing operand is refused rather than rendered: the parser never
     * builds one, so a null operand is a programming error and fails fast as one.
     */
    @Test
    void a_filter_tree_with_a_missing_operand_is_refused() {
        ScimFilter missingOperand = new ScimFilter.And(
                null, ScimFilterParser.parse("userName pr", USERS));
        ScimQuery query = new ScimQuery(USERS, missingOperand, null, PageRequest.FIRST_PAGE);

        assertThatThrownBy(() -> new ScimQuerySql(CONNECTOR, BASE_URI).count(query))
                .isExactlyInstanceOf(NullPointerException.class);
    }

    // --- whole statements: each fragment in the place the protocol puts it -----------------

    private static final String USER_COUNT =
            "SELECT (SELECT count(*) FROM scim_resources r JOIN scim_users u ON u.resource_id = r.id WHERE ";

    /**
     * {@code pr} by shape: a single-valued complex attribute is present when any sub-attribute is,
     * a multi-valued one when it has a row, and a sub-attribute of one when some row has it.
     */
    @Test
    void presence_reaches_sub_attributes_and_rows() {
        String statement = count("name pr or emails pr or emails.value pr or emails[value pr]", USERS);

        String emailValue = "EXISTS (SELECT 1 FROM scim_user_emails e WHERE e.resource_id = r.id"
                + " AND (e.value IS NOT NULL AND e.value <> ''))";
        assertThat(statement).isEqualTo(USER_COUNT + "((("
                + "((u.formatted_name IS NOT NULL AND u.formatted_name <> '')"
                + " OR (u.family_name IS NOT NULL AND u.family_name <> '')"
                + " OR (u.given_name IS NOT NULL AND u.given_name <> '')"
                + " OR (u.middle_name IS NOT NULL AND u.middle_name <> '')"
                + " OR (u.honorific_prefix IS NOT NULL AND u.honorific_prefix <> '')"
                + " OR (u.honorific_suffix IS NOT NULL AND u.honorific_suffix <> ''))"
                + " OR EXISTS (SELECT 1 FROM scim_user_emails e WHERE e.resource_id = r.id))"
                + " OR " + emailValue + ") OR " + emailValue + "))");
    }

    /**
     * A multi-valued comparison is an EXISTS over the attribute's rows, at the top level and in a
     * value path alike, and each literal is bound under its own name.
     */
    @Test
    void a_multi_valued_comparison_is_an_exists_and_each_literal_has_its_own_name() {
        ScimQuerySql sql = new ScimQuerySql(CONNECTOR, BASE_URI);

        String statement = sql.count(query(
                "emails.value eq \"a\" or emails[type eq \"w\"] or externalId co \"x\"", USERS));

        String externalId = "(SELECT x.external_id FROM scim_external_ids x"
                + " WHERE x.resource_id = r.id AND x.connector_id = :connectorId)";
        assertThat(statement).isEqualTo(USER_COUNT + "((EXISTS (SELECT 1 FROM scim_user_emails e"
                + " WHERE e.resource_id = r.id AND (e.value IS NOT NULL AND lower(e.value) = lower(:p0)))"
                + " OR EXISTS (SELECT 1 FROM scim_user_emails e WHERE e.resource_id = r.id"
                + " AND (e.type IS NOT NULL AND lower(e.type) = lower(:p1))))"
                + " OR (" + externalId + " IS NOT NULL AND " + externalId + " LIKE :p2 ESCAPE '\\')))");
        assertThat(sql.parameters()).containsEntry("p0", "a").containsEntry("p1", "w")
                .containsEntry("p2", "%x%").hasSize(5);
    }

    /** {@code eq null} asks for no value and {@code ne null} for some: presence, by another name. */
    @Test
    void eq_null_is_absence_and_ne_null_is_presence() {
        ScimQuerySql sql = new ScimQuerySql(CONNECTOR, BASE_URI);

        assertThat(sql.count(query("displayName eq null and displayName ne null", USERS)))
                .isEqualTo(USER_COUNT + "((NOT (u.display_name IS NOT NULL AND u.display_name <> ''))"
                        + " AND (u.display_name IS NOT NULL AND u.display_name <> '')))");
        assertThat(sql.parameters()).hasSize(2);
    }

    /** A path qualified with the other type's schema has no value in this type's branch. */
    @Test
    void a_path_qualified_with_the_other_types_schema_is_absent_from_this_type() {
        assertThat(count("urn:ietf:params:scim:schemas:core:2.0:Group:displayName eq \"a\"", BOTH))
                .isEqualTo(USER_COUNT + "(1=0)) + (SELECT count(*) FROM scim_resources r"
                        + " JOIN scim_groups g ON g.resource_id = r.id"
                        + " WHERE (g.display_name IS NOT NULL AND lower(g.display_name) = lower(:p0)))");
    }

    private static String sortedPage(String sortBy, Set<ScimResourceType> types) {
        return new ScimQuerySql(CONNECTOR, BASE_URI).page(new ScimQuery(
                types, null, ScimSort.of(sortBy, null, types), PageRequest.FIRST_PAGE));
    }

    private static final String ASCENDING_TEXT_ORDER =
            " q ORDER BY CASE WHEN q.sort_key IS NULL THEN 1 ELSE 0 END ASC,"
                    + " q.sort_key COLLATE Latin1_General_100_BIN2_UTF8 ASC, q.id OFFSET :offset ROWS FETCH NEXT :limit ROWS ONLY";

    /**
     * A multi-valued sort key is its first value, lower-cased when case-insensitive and empty as
     * missing; the branch lacking it projects a text NULL, and the types keep their order.
     */
    @Test
    void a_multi_valued_sort_key_is_the_first_value_and_each_type_has_its_branch() {
        assertThat(sortedPage("emails.value", BOTH)).isEqualTo(
                "SELECT q.id, q.kind FROM (SELECT r.id AS id, r.resource_type AS kind,"
                        + " lower(NULLIF((SELECT TOP 1 e.value FROM scim_user_emails e WHERE e.resource_id = r.id"
                        + " ORDER BY e.is_primary DESC, e.ordinal), ''))"
                        + " AS sort_key, 0 AS type_order, u.normalized_user_name AS default_key"
                        + " FROM scim_resources r JOIN scim_users u ON u.resource_id = r.id WHERE (1=1)"
                        + " UNION ALL SELECT r.id AS id, r.resource_type AS kind,"
                        + " CAST(NULL AS VARCHAR(1024)) AS sort_key, 1 AS type_order,"
                        + " g.normalized_display_name AS default_key"
                        + " FROM scim_resources r JOIN scim_groups g ON g.resource_id = r.id WHERE (1=1))"
                        + ASCENDING_TEXT_ORDER);
    }

    /** A case-exact text sort key is not lower-cased; a boolean one is the column as stored. */
    @Test
    void a_case_exact_sort_key_keeps_its_case_and_a_boolean_one_is_the_column() {
        String user = "SELECT q.id, q.kind FROM (SELECT r.id AS id, r.resource_type AS kind, ";
        String rest = " AS sort_key, 0 AS type_order, u.normalized_user_name AS default_key"
                + " FROM scim_resources r JOIN scim_users u ON u.resource_id = r.id WHERE (1=1))";

        assertThat(sortedPage("externalId", USERS)).isEqualTo(user
                + "NULLIF((SELECT x.external_id FROM scim_external_ids x"
                + " WHERE x.resource_id = r.id AND x.connector_id = :connectorId), '')"
                + rest + ASCENDING_TEXT_ORDER);
        assertThat(sortedPage("active", USERS)).isEqualTo(user + "u.active" + rest
                + " q ORDER BY CASE WHEN q.sort_key IS NULL THEN 1 ELSE 0 END ASC, q.sort_key ASC,"
                + " q.id OFFSET :offset ROWS FETCH NEXT :limit ROWS ONLY");
    }
}

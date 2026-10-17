package com.example.backend.scim.infrastructure.persistence;

import com.example.backend.scim.domain.ScimFilter;
import com.example.backend.scim.domain.ScimFilter.And;
import com.example.backend.scim.domain.ScimFilter.AttributeRef;
import com.example.backend.scim.domain.ScimFilter.Comparison;
import com.example.backend.scim.domain.ScimFilter.Not;
import com.example.backend.scim.domain.ScimFilter.Operator;
import com.example.backend.scim.domain.ScimFilter.Or;
import com.example.backend.scim.domain.ScimFilter.Presence;
import com.example.backend.scim.domain.ScimFilter.ValuePath;
import com.example.backend.scim.domain.ScimFilterPath;
import com.example.backend.scim.domain.ScimQuery;
import com.example.backend.scim.domain.ScimQueryVocabulary;
import com.example.backend.scim.domain.ScimQueryVocabulary.Attribute;
import com.example.backend.scim.domain.ScimResourceType;
import com.example.backend.scim.domain.ScimSort;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Translates a {@link ScimQuery} into T-SQL for SQL Server 2022 (compatibility level 160), with
 * every literal bound (ADR 0013).
 *
 * <p><strong>The engine words in one place.</strong> Four constants at the top of the class are
 * the only SQL Server-specific text: the binary UTF-8 collation text sorts and range comparisons
 * use ({@link #BYTE_ORDER_COLLATION}), the types a NULL is cast to where the union's other branch
 * has a boolean ({@link #NULL_BOOLEAN_TYPE}) or a timestamp ({@link #NULL_TIMESTAMP_TYPE}), and
 * {@link #FIRST_ROW} for a multi-valued attribute's first value. The rest reads the same on most
 * engines: {@code OFFSET … ROWS FETCH NEXT … ROWS ONLY} for the page; a {@code CASE} flag for
 * where a missing value sorts, since SQL Server has no {@code NULLS FIRST/LAST} and sorts a bare
 * {@code NULL} first; {@code CONCAT} for string building; {@code (1=1)} and {@code (1=0)} for
 * constant predicates, since SQL Server has no boolean literal; and casts to {@code VARCHAR} with
 * an explicit length. A resource id rendered as text is lower-cased, because SQL Server renders a
 * {@code UNIQUEIDENTIFIER} in upper case and the API renders ids in lower case. A boolean
 * attribute is a {@code BIT} column, compared with a bound Java boolean, which the driver sends as
 * a {@code BIT}. A {@code LIKE} pattern escapes {@code [} as well as {@code %} and {@code _},
 * since SQL Server reads {@code [} as the start of a character class.
 *
 * <p><strong>What SQL Server changes</strong> (ADR 0013, "Observable changes"). It pads a text
 * comparison with spaces, so {@code eq}, {@code ne}, {@code gt}, {@code ge}, {@code lt} and
 * {@code le} on free text ignore trailing spaces, and a whitespace-only value equals {@code ''}
 * and so counts as absent for {@code pr} and sorting. {@code co}, {@code sw} and {@code ew} keep
 * trailing spaces significant: a {@code LIKE} against the bound Unicode pattern is not padded. The {@code id} tie-break follows SQL Server's
 * {@code UNIQUEIDENTIFIER} order. Case-insensitive comparison is {@code LOWER} under the binary
 * collation, which differs from Java's {@code toLowerCase(Locale.ROOT)} for the characters spike
 * V4 lists.
 *
 * <p><strong>No client text becomes statement text.</strong> The only strings concatenated
 * into a statement are the fixed fragments in this class, selected by exhaustive switches over
 * {@link ScimFilterPath}; every value a filter carries is added to {@link #parameters()} under a
 * generated name and referenced as {@code :pN}. {@code ScimQuerySqlTests} holds that as a
 * property: a literal never appears in the statement, whatever it contains.
 *
 * <p><strong>Two-valued logic.</strong> SQL Server has no boolean expressions to fall back on, so
 * every predicate this renders is TRUE or FALSE, never NULL, by construction: each comparison is guarded as {@code (x IS NOT NULL AND x <op> :p)}, and the bound operand
 * is never NULL, so an attribute with no value fails a comparison instead of making it unknown,
 * and SQL's {@code NOT NULL = NULL} never leaks into {@code not (...)}. {@code ne} is rendered as
 * {@code NOT (eq)}, which is the domain's definition of it.
 *
 * <p><strong>An attribute a type lacks has no value there.</strong> In a base search the same
 * filter is rendered once per resource type, and a path the type does not have — or one
 * qualified with the other type's schema — becomes the constant its absence implies:
 * {@code (1=0)} for a comparison or {@code pr}, {@code NOT (1=0)} for {@code ne}.
 *
 * <p>One instance per statement pair: it accumulates the parameters as it renders.
 */
final class ScimQuerySql {

    // --- engine words: the only SQL Server-specific text in this class ---------------------

    /**
     * The binary (code-point order) collation text sorts and range comparisons use: the
     * database's own collation, named so the order never depends on a column's or a server's.
     */
    private static final String BYTE_ORDER_COLLATION = "Latin1_General_100_BIN2_UTF8";

    /** The SQL type a branch's NULL takes where the union's other branch has a boolean. */
    private static final String NULL_BOOLEAN_TYPE = "BIT";

    /** The SQL type a branch's NULL takes where the union's other branch has a timestamp. */
    private static final String NULL_TIMESTAMP_TYPE = "DATETIMEOFFSET(6)";

    /** Selects a correlated subquery's first row, in the subquery's own ORDER BY. */
    private static final String FIRST_ROW = "SELECT TOP 1 ";

    // --- portable constants -----------------------------------------------------------------

    /**
     * The type a branch's text NULL takes: a {@code VARCHAR} with an explicit length (SQL Server
     * reads a bare {@code VARCHAR} in a cast as {@code VARCHAR(30)}), as wide as the widest text
     * column a sort key reads — 256 code points, which SQL Server's UTF-8 {@code VARCHAR} stores
     * in up to 1024 bytes (ADR 0013's column inventory). A union's column takes its wider branch,
     * so the NULL is never what narrows a value; widening a sort column means widening this.
     */
    private static final String NULL_TEXT_TYPE = "VARCHAR(1024)";

    /** A predicate that holds: SQL Server has no boolean literal, so a tautology stands in. */
    private static final String ALWAYS = "(1=1)";

    /** A predicate that never holds. */
    private static final String NEVER = "(1=0)";

    private final Map<String, Object> parameters = new LinkedHashMap<>();

    private int next;

    ScimQuerySql(UUID connectorId, String baseUri) {
        parameters.put("connectorId", connectorId);
        parameters.put("baseUri", baseUri);
    }

    /** Every bound value, by name. */
    Map<String, Object> parameters() {
        return parameters;
    }

    /** {@code SELECT count(*)} over the query's filter, for one type or summed over both. */
    String count(ScimQuery query) {
        List<String> counts = new ArrayList<>();
        for (ScimResourceType type : ordered(query)) {
            counts.add("(SELECT count(*) FROM " + source(type) + " WHERE "
                    + where(query.filter(), type) + ")");
        }
        return "SELECT " + String.join(" + ", counts);
    }

    /**
     * The ids and types of the query's page, in order.
     *
     * <p>A UNION ALL of one branch per type, each projecting the same five columns, ordered
     * once over the union. {@code sort_key} is the requested sort attribute ({@code NULL} of
     * the right type where a branch lacks it), and a requested sort is broken by {@code id}
     * alone. Without one, {@code type_order} and {@code default_key} give the default order —
     * Users by normalized {@code userName}, then Groups by normalized {@code displayName} —
     * and {@code id} is still the last key.
     *
     * <p>For a page of at least one row: SQL Server rejects {@code FETCH NEXT 0 ROWS}, so a
     * {@code count=0} request is answered without a page query.
     */
    String page(ScimQuery query) {
        ScimSort sort = query.sort();
        List<String> branches = new ArrayList<>();
        int typeOrder = 0;
        for (ScimResourceType type : ordered(query)) {
            branches.add("SELECT r.id AS id, r.resource_type AS kind, "
                    + sortKey(sort, type) + " AS sort_key, "
                    + typeOrder++ + " AS type_order, "
                    + defaultKey(type) + " AS default_key FROM " + source(type)
                    + " WHERE " + where(query.filter(), type));
        }
        // A requested sort is broken by id alone, as the spec states; the default order is Users
        // then Groups, each by its normalized unique name, with id last for completeness.
        String order = sort == null ? "q.type_order, q.default_key, " : sortOrder(sort);
        parameters.put("limit", query.page().count());
        parameters.put("offset", query.page().offset());
        return "SELECT q.id, q.kind FROM (" + String.join(" UNION ALL ", branches) + ") q"
                + " ORDER BY " + order + "q.id"
                + " OFFSET :offset ROWS FETCH NEXT :limit ROWS ONLY";
    }

    /**
     * The requested sort's ORDER BY keys. A missing value sorts as if greater than every present
     * one — last ascending, first descending — through a leading flag key in the sort's own
     * direction, because SQL Server sorts a bare {@code NULL} first and has no
     * {@code NULLS FIRST/LAST}.
     */
    private static String sortOrder(ScimSort sort) {
        String direction = sort.descending() ? " DESC" : " ASC";
        return "CASE WHEN q.sort_key IS NULL THEN 1 ELSE 0 END" + direction + ", "
                + "q.sort_key" + (isText(sort) ? " COLLATE " + BYTE_ORDER_COLLATION : "")
                + direction + ", ";
    }

    /** Users before Groups, whatever order the query's set iterates in. */
    private static List<ScimResourceType> ordered(ScimQuery query) {
        List<ScimResourceType> types = new ArrayList<>();
        for (ScimResourceType type : ScimResourceType.values()) {
            if (query.types().contains(type)) {
                types.add(type);
            }
        }
        return types;
    }

    private static String source(ScimResourceType type) {
        return switch (type) {
            case USER -> "scim_resources r JOIN scim_users u ON u.resource_id = r.id";
            case GROUP -> "scim_resources r JOIN scim_groups g ON g.resource_id = r.id";
        };
    }

    private static String defaultKey(ScimResourceType type) {
        return switch (type) {
            case USER -> "u.normalized_user_name";
            case GROUP -> "g.normalized_display_name";
        };
    }

    // --- predicates -------------------------------------------------------------------------

    private String where(ScimFilter filter, ScimResourceType type) {
        return filter == null ? ALWAYS : predicate(filter, type, null);
    }

    /**
     * @param row the multi-valued attribute whose single value is in scope, inside a value path;
     *            {@code null} at the top level
     */
    private String predicate(ScimFilter filter, ScimResourceType type, ScimFilterPath row) {
        return switch (filter) {
            case And and -> "(" + predicate(and.left(), type, row) + " AND "
                    + predicate(and.right(), type, row) + ")";
            case Or or -> "(" + predicate(or.left(), type, row) + " OR "
                    + predicate(or.right(), type, row) + ")";
            case Not not -> "(NOT " + predicate(not.inner(), type, row) + ")";
            case Presence presence -> presence(presence.attribute(), type, row);
            case Comparison comparison -> comparison(comparison, type, row);
            case ValuePath valuePath -> valuePath(valuePath, type);
        };
    }

    private String valuePath(ValuePath valuePath, ScimResourceType type) {
        Optional<Attribute> attribute = attribute(valuePath.attribute(), type);
        if (attribute.isEmpty()) {
            return NEVER;
        }
        ScimFilterPath parent = attribute.get().path();
        return "EXISTS (SELECT 1 FROM " + rows(parent) + " AND "
                + predicate(valuePath.inner(), type, parent) + ")";
    }

    private String presence(AttributeRef reference, ScimResourceType type, ScimFilterPath row) {
        Optional<Attribute> found = attribute(reference, type);
        if (found.isEmpty()) {
            return NEVER;
        }
        Attribute attribute = found.get();
        ScimFilterPath path = attribute.path();
        if (path == ScimFilterPath.META) {
            // Every resource has meta; its sub-attributes are all rendered unconditionally.
            return ALWAYS;
        }
        if (attribute.isComplex() && attribute.multiValued()) {
            return "EXISTS (SELECT 1 FROM " + rows(path) + ")";
        }
        if (attribute.isComplex()) {
            // A single-valued complex attribute (only User's name) is present when any of its
            // sub-attributes is; its sub-attributes live in the same vocabulary as it does.
            List<String> anySub = new ArrayList<>();
            ScimQueryVocabulary vocabulary = ScimQueryVocabulary.of(type);
            for (ScimFilterPath sub : path.subAttributes()) {
                vocabulary.find(sub)
                        .ifPresent(subAttribute -> anySub.add(present(column(sub, type), subAttribute)));
            }
            return "(" + String.join(" OR ", anySub) + ")";
        }
        if (attribute.multiValued() && row == null) {
            return "EXISTS (SELECT 1 FROM " + rows(path.parent()) + " AND "
                    + present(rowColumn(path), attribute) + ")";
        }
        return present(attribute.multiValued() ? rowColumn(path) : column(path, type), attribute);
    }

    private static String present(String expression, Attribute attribute) {
        return attribute.isTextual()
                ? "(" + expression + " IS NOT NULL AND " + expression + " <> '')"
                : "(" + expression + " IS NOT NULL)";
    }

    private String comparison(Comparison comparison, ScimResourceType type, ScimFilterPath row) {
        if (comparison.value() == null) {
            // `eq null` asks for no value and `ne null` for some: presence, by another name.
            String present = presence(comparison.attribute(), type, row);
            return comparison.operator() == Operator.EQ ? "(NOT " + present + ")" : present;
        }
        if (comparison.operator() == Operator.NE) {
            return "(NOT " + comparison(
                    new Comparison(comparison.attribute(), Operator.EQ, comparison.value()),
                    type,
                    row) + ")";
        }
        Optional<Attribute> found = attribute(comparison.attribute(), type);
        if (found.isEmpty()) {
            return NEVER;
        }
        Attribute attribute = found.get();
        ScimFilterPath path = attribute.path();
        if (attribute.multiValued() && row == null) {
            return "EXISTS (SELECT 1 FROM " + rows(path.parent()) + " AND "
                    + compare(rowColumn(path), attribute, comparison) + ")";
        }
        String expression = attribute.multiValued() ? rowColumn(path) : column(path, type);
        return compare(expression, attribute, comparison);
    }

    /** One comparison against one value; never {@code ne}, which is rendered as {@code NOT eq}. */
    private String compare(String expression, Attribute attribute, Comparison comparison) {
        Operator operator = comparison.operator();
        Object value = comparison.value();
        if (!attribute.isTextual()) {
            Object bound = value instanceof Instant instant
                    ? OffsetDateTime.ofInstant(instant, ZoneOffset.UTC)
                    : value;
            return valued(expression, expression + " " + symbol(operator) + " " + bind(bound));
        }
        String text = (String) value;
        String left = attribute.caseExact() ? expression : "lower(" + expression + ")";
        if (operator.isSubstring()) {
            String pattern = switch (operator) {
                case CO -> "%" + escapeLike(text) + "%";
                case SW -> escapeLike(text) + "%";
                default -> "%" + escapeLike(text);
            };
            String right = attribute.caseExact() ? bind(pattern) : "lower(" + bind(pattern) + ")";
            return valued(expression, left + " LIKE " + right + " ESCAPE '\\'");
        }
        String right = attribute.caseExact() ? bind(text) : "lower(" + bind(text) + ")";
        if (operator.isOrdering()) {
            // A byte-order collation, so the answer does not depend on the database's locale.
            return valued(expression, "(" + left + ") COLLATE " + BYTE_ORDER_COLLATION + " "
                    + symbol(operator) + " " + right);
        }
        return valued(expression, left + " = " + right);
    }

    /**
     * A comparison that is FALSE, never unknown, when the attribute has no value: the bound
     * operand is never NULL, so once {@code expression} is known to be non-NULL the comparison is
     * TRUE or FALSE, and {@code FALSE AND unknown} is FALSE. {@code NOT} of it is therefore TRUE.
     */
    private static String valued(String expression, String comparison) {
        return "(" + expression + " IS NOT NULL AND " + comparison + ")";
    }

    private static String symbol(Operator operator) {
        return switch (operator) {
            case EQ -> "=";
            case GT -> ">";
            case GE -> ">=";
            case LT -> "<";
            case LE -> "<=";
            case NE, CO, SW, EW -> throw new IllegalArgumentException(
                    "not a relational operator: " + operator);
        };
    }

    /**
     * A {@code LIKE} pattern's special characters escaped, so a filter value of {@code 50%}
     * matches the text {@code 50%} rather than everything beginning with {@code 50}.
     */
    static String escapeLike(String value) {
        return value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
                .replace("[", "\\[");
    }

    private String bind(Object value) {
        String name = "p" + next++;
        parameters.put(name, value);
        return ":" + name;
    }

    private static Optional<Attribute> attribute(AttributeRef reference, ScimResourceType type) {
        if (!reference.appliesTo(type)) {
            return Optional.empty();
        }
        return ScimQueryVocabulary.of(type).find(reference.path());
    }

    // --- sorting ----------------------------------------------------------------------------

    /**
     * The sort attribute's value for one row, typed so the union's branches agree.
     *
     * <p>Text sorts lower-cased first when the attribute is case-insensitive, and
     * an empty string sorts as a missing value — the same rule {@code pr} applies. A multi-valued
     * attribute contributes its primary value, or its first in rendered order when none is
     * primary.
     */
    private String sortKey(ScimSort sort, ScimResourceType type) {
        if (sort == null) {
            return "CAST(NULL AS " + NULL_TEXT_TYPE + ")";
        }
        Optional<Attribute> found = attribute(sort.attribute(), type);
        if (found.isEmpty()) {
            return "CAST(NULL AS " + sqlType(sort.attribute().path(), type) + ")";
        }
        Attribute attribute = found.get();
        ScimFilterPath path = attribute.path();
        String value = attribute.multiValued()
                ? "(" + FIRST_ROW + rowColumn(path) + " FROM " + rows(path.parent())
                        + " ORDER BY " + firstValueOrder(path.parent()) + ")"
                : column(path, type);
        if (!attribute.isTextual()) {
            return value;
        }
        String text = "NULLIF(" + value + ", '')";
        return attribute.caseExact() ? text : "lower(" + text + ")";
    }

    /**
     * Whether the sort attribute compares as text, in which case the outer ORDER BY applies a
     * byte-order collation — there rather than in each branch, so the union's column has one
     * collation whichever branch a row came from.
     */
    private static boolean isText(ScimSort sort) {
        // The parser resolved the sort path against a queried type, so some type has it.
        return Arrays.stream(ScimResourceType.values())
                .flatMap(type -> attribute(sort.attribute(), type).stream())
                .findFirst()
                .orElseThrow()
                .isTextual();
    }

    /**
     * The SQL type a branch lacking the sort attribute projects its NULL as — the type the
     * other branch's value has, so UNION ALL accepts the pair.
     */
    private static String sqlType(ScimFilterPath path, ScimResourceType lacking) {
        // Only a base search has a lacking branch, and the sort path came from the other type.
        ScimResourceType other = lacking == ScimResourceType.USER
                ? ScimResourceType.GROUP
                : ScimResourceType.USER;
        return switch (ScimQueryVocabulary.of(other).find(path).orElseThrow().kind()) {
            case BOOLEAN -> NULL_BOOLEAN_TYPE;
            case DATE_TIME -> NULL_TIMESTAMP_TYPE;
            case STRING, REFERENCE, COMPLEX -> NULL_TEXT_TYPE;
        };
    }

    // --- storage mapping --------------------------------------------------------------------

    /** A single-valued attribute of one resource row. */
    private static String column(ScimFilterPath path, ScimResourceType type) {
        String resourcePath = type == ScimResourceType.USER ? "/Users/" : "/Groups/";
        return switch (path) {
            case ID -> idText("r.id");
            case EXTERNAL_ID -> "(SELECT x.external_id FROM scim_external_ids x"
                    + " WHERE x.resource_id = r.id AND x.connector_id = :connectorId)";
            case USER_NAME -> "u.user_name";
            case NAME_FORMATTED -> "u.formatted_name";
            case NAME_FAMILY_NAME -> "u.family_name";
            case NAME_GIVEN_NAME -> "u.given_name";
            case NAME_MIDDLE_NAME -> "u.middle_name";
            case NAME_HONORIFIC_PREFIX -> "u.honorific_prefix";
            case NAME_HONORIFIC_SUFFIX -> "u.honorific_suffix";
            case DISPLAY_NAME -> type == ScimResourceType.USER ? "u.display_name" : "g.display_name";
            case PREFERRED_LANGUAGE -> "u.preferred_language";
            case LOCALE -> "u.locale";
            case TIMEZONE -> "u.timezone";
            case ACTIVE -> "u.active";
            case META_RESOURCE_TYPE -> "r.resource_type";
            case META_CREATED -> "r.created_at";
            case META_LAST_MODIFIED -> "r.last_modified_at";
            case META_LOCATION -> "CONCAT(:baseUri, '" + resourcePath + "', " + idText("r.id") + ")";
            // The strong ETag as rendered: the version in double quotes.
            case META_VERSION -> "CONCAT('\"', CAST(r.version AS VARCHAR(20)), '\"')";
            case NAME, EMAILS, EMAILS_VALUE, EMAILS_TYPE, EMAILS_PRIMARY, GROUPS, GROUPS_VALUE,
                    GROUPS_DISPLAY, GROUPS_REF, GROUPS_TYPE, MEMBERS, MEMBERS_VALUE,
                    MEMBERS_DISPLAY, MEMBERS_REF, MEMBERS_TYPE, META, PASSWORD ->
                    throw new IllegalArgumentException("not a single-valued column: " + path);
        };
    }

    /**
     * A resource id as the API renders it: the canonical lower-case text of the UUID. Lower-cased
     * explicitly because a driver may hand a UUID's text over in upper case, and an id compared in
     * that case would match no filter value.
     */
    private static String idText(String column) {
        return "lower(CAST(" + column + " AS VARCHAR(36)))";
    }

    /**
     * The rows of a multi-valued attribute, as {@code <tables> WHERE <link to r>} so a caller can
     * append further conditions with {@code AND}.
     */
    private static String rows(ScimFilterPath multiValued) {
        return switch (multiValued) {
            case EMAILS -> "scim_user_emails e WHERE e.resource_id = r.id";
            case GROUPS -> "scim_group_members gm JOIN scim_groups gg ON gg.resource_id = gm.group_id"
                    + " WHERE gm.user_id = r.id";
            case MEMBERS -> "scim_group_members mm JOIN scim_users mu ON mu.resource_id = mm.user_id"
                    + " WHERE mm.group_id = r.id";
            default -> throw new IllegalArgumentException("not a multi-valued attribute: " + multiValued);
        };
    }

    /**
     * Which value of a multi-valued attribute is its "first": the primary email, else the first in
     * the order they were written; a User's Groups and a Group's members in the order they render.
     */
    private static String firstValueOrder(ScimFilterPath multiValued) {
        return switch (multiValued) {
            case EMAILS -> "e.is_primary DESC, e.ordinal";
            case GROUPS -> "gg.normalized_display_name, gg.resource_id";
            case MEMBERS -> "mu.normalized_user_name, mu.resource_id";
            default -> throw new IllegalArgumentException("not a multi-valued attribute: " + multiValued);
        };
    }

    /** A sub-attribute of the multi-valued row in scope. */
    private static String rowColumn(ScimFilterPath sub) {
        return switch (sub) {
            case EMAILS_VALUE -> "e.value";
            case EMAILS_TYPE -> "e.type";
            case EMAILS_PRIMARY -> "e.is_primary";
            case GROUPS_VALUE -> idText("gg.resource_id");
            case GROUPS_DISPLAY -> "gg.display_name";
            case GROUPS_REF -> "CONCAT(:baseUri, '/Groups/', " + idText("gg.resource_id") + ")";
            // No nested Groups, so every membership is direct.
            case GROUPS_TYPE -> "'direct'";
            case MEMBERS_VALUE -> idText("mu.resource_id");
            // The label rendered for a member: its displayName, else its userName.
            case MEMBERS_DISPLAY -> "coalesce(mu.display_name, mu.user_name)";
            case MEMBERS_REF -> "CONCAT(:baseUri, '/Users/', " + idText("mu.resource_id") + ")";
            // Every member is a User.
            case MEMBERS_TYPE -> "'User'";
            default -> throw new IllegalArgumentException("not a multi-valued sub-attribute: " + sub);
        };
    }
}

package com.example.backend.scim.domain;

import com.example.backend.scim.domain.ScimFilter.And;
import com.example.backend.scim.domain.ScimFilter.AttributeRef;
import com.example.backend.scim.domain.ScimFilter.Comparison;
import com.example.backend.scim.domain.ScimFilter.Not;
import com.example.backend.scim.domain.ScimFilter.Operator;
import com.example.backend.scim.domain.ScimFilter.Or;
import com.example.backend.scim.domain.ScimFilter.Presence;
import com.example.backend.scim.domain.ScimFilter.ValuePath;
import com.example.backend.scim.domain.ScimQueryVocabulary.Attribute;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The RFC 7644 §3.4.2.2 filter grammar, parsed into a validated {@link ScimFilter}.
 *
 * <p>Recursive descent over the ABNF with the RFC's precedence — {@code not} binds tighter than
 * {@code and}, which binds tighter than {@code or} — and its case rules: attribute names,
 * operators and the logical keywords are case-insensitive. Paths may be qualified with the core
 * User or Group schema URI.
 *
 * <p><strong>Bounded before it is expensive.</strong> The text is measured before a single
 * token is read, and depth and node count are checked as the tree is BUILT rather than after, so
 * an over-limit filter is refused having cost at most the limit's worth of work. Depth is also
 * what bounds this parser's own recursion: without it a filter of ten thousand opening
 * parentheses would be a stack overflow rather than a {@code 400}.
 *
 * <p><strong>Validated against a vocabulary.</strong> Every path must be an attribute of at
 * least one of the resource types being queried, and every operator and literal must suit that
 * attribute's type. A path that one of the types lacks is still valid when another has it — that
 * is how a base search spanning Users and Groups accepts {@code userName} — and the translation
 * then evaluates it as having no value for the type that lacks it.
 *
 * <p>The error messages never quote a literal. They may name a canonical attribute path, which
 * is this service's vocabulary rather than the client's text.
 */
public final class ScimFilterParser {

    /** The longest filter text accepted, in UTF-8 bytes. */
    public static final int MAX_FILTER_BYTES = 8 * 1024;

    /** The deepest nesting of logical expressions, parentheses and value paths accepted. */
    public static final int MAX_DEPTH = 20;

    /** The most expression nodes a filter may have: comparisons, presences and logical operators. */
    public static final int MAX_NODES = 100;

    private static final Pattern NUMBER =
            Pattern.compile("-?(0|[1-9][0-9]*)(\\.[0-9]+)?([eE][+-]?[0-9]+)?");

    private final String text;

    private final Set<ScimResourceType> types;

    private int position;

    private int nodes;

    private ScimFilterParser(String text, Set<ScimResourceType> types) {
        this.text = text;
        this.types = Set.copyOf(types);
    }

    /**
     * The filter this text denotes, validated for querying the given resource types.
     *
     * @param text  the filter as the client sent it
     * @param types the resource types being queried; two for a base search
     * @throws InvalidScimFilterException when the text is not a filter this service evaluates
     */
    public static ScimFilter parse(String text, Set<ScimResourceType> types) {
        if (text == null || text.isBlank()) {
            throw new InvalidScimFilterException("The filter is empty.");
        }
        if (text.getBytes(StandardCharsets.UTF_8).length > MAX_FILTER_BYTES) {
            throw new InvalidScimFilterException(
                    "The filter is longer than " + MAX_FILTER_BYTES + " bytes.");
        }
        ScimFilterParser parser = new ScimFilterParser(text, types);
        // or() ends by looking for another "or", which skips any trailing whitespace.
        ScimFilter filter = parser.or(0, null);
        if (parser.position < text.length()) {
            throw new InvalidScimFilterException("The filter has trailing text after a complete expression.");
        }
        return filter;
    }

    /**
     * A single attribute path — a {@code sortBy} — resolved against the given resource types.
     *
     * @return the reference and the attribute it denotes on the first of the types that has it
     * @throws InvalidScimFilterException when no queried type has that attribute, or it is the
     *                                    password
     */
    public static ResolvedPath parsePath(String text, Set<ScimResourceType> types) {
        if (text == null || text.isBlank()) {
            throw new InvalidScimFilterException("The attribute path is empty.");
        }
        String path = text.trim();
        return new ScimFilterParser(path, types).resolve(path, null);
    }

    /** A path together with the attribute it denotes, for type checks. */
    public record ResolvedPath(AttributeRef reference, Attribute attribute) {
    }

    // --- grammar --------------------------------------------------------------------------

    /** {@code orExp = andExp *("or" andExp)}. */
    private ScimFilter or(int depth, ScimFilterPath valuePathParent) {
        ScimFilter left = and(depth, valuePathParent);
        while (nextKeywordIs("or")) {
            ScimFilter right = and(depth, valuePathParent);
            left = node(new Or(left, right));
        }
        return left;
    }

    /** {@code andExp = unary *("and" unary)}. */
    private ScimFilter and(int depth, ScimFilterPath valuePathParent) {
        ScimFilter left = unary(depth, valuePathParent);
        while (nextKeywordIs("and")) {
            ScimFilter right = unary(depth, valuePathParent);
            left = node(new And(left, right));
        }
        return left;
    }

    /** {@code "not" "(" filter ")" / "(" filter ")" / attrExp / valuePath}. */
    private ScimFilter unary(int depth, ScimFilterPath valuePathParent) {
        int nested = depth + 1;
        if (nested > MAX_DEPTH) {
            throw new InvalidScimFilterException(
                    "The filter is nested deeper than " + MAX_DEPTH + " levels.");
        }
        skipWhitespace();
        if (peekWordIs("not")) {
            readWord();
            expect('(');
            ScimFilter inner = or(nested, valuePathParent);
            expect(')');
            return node(new Not(inner));
        }
        if (peek() == '(') {
            position++;
            ScimFilter inner = or(nested, valuePathParent);
            expect(')');
            return inner;
        }
        return attributeExpression(nested, valuePathParent);
    }

    private ScimFilter attributeExpression(int depth, ScimFilterPath valuePathParent) {
        String word = readWord();
        if (word.isEmpty()) {
            throw new InvalidScimFilterException("An attribute path was expected.");
        }
        ResolvedPath path = resolve(word, valuePathParent);
        skipWhitespace();
        if (peek() == '[') {
            return valuePath(path, depth, valuePathParent);
        }
        String operator = readWord().toLowerCase(Locale.ROOT);
        if (operator.equals("pr")) {
            return node(new Presence(path.reference()));
        }
        Operator comparison = operator(operator);
        Object literal = readLiteral();
        return node(comparison(path, comparison, literal));
    }

    private ScimFilter valuePath(ResolvedPath path, int depth, ScimFilterPath valuePathParent) {
        if (valuePathParent != null) {
            throw new InvalidScimFilterException("A value path cannot be nested in another.");
        }
        Attribute attribute = path.attribute();
        if (!attribute.isComplex() || !attribute.multiValued()) {
            throw new InvalidScimFilterException(
                    "Only a multi-valued complex attribute takes a value filter: "
                            + attribute.path().canonical());
        }
        position++;
        // The value path is a level of its own: its inner filter starts one below it, and the
        // inner filter's own first level is where an over-deep nesting is refused.
        ScimFilter inner = or(depth + 1, attribute.path());
        expect(']');
        return node(new ValuePath(path.reference(), inner));
    }

    // --- validation ------------------------------------------------------------------------

    /**
     * The attribute a path token denotes, in a top-level position or inside a value path.
     *
     * <p>Inside {@code emails[...]} a path is a bare sub-attribute name of {@code emails}; at the
     * top level it is {@code attr}, {@code attr.sub}, or either qualified with a schema URI.
     */
    private ResolvedPath resolve(String token, ScimFilterPath valuePathParent) {
        if (valuePathParent != null) {
            ScimFilterPath sub = valuePathParent.subAttribute(token)
                    .orElseThrow(() -> new InvalidScimFilterException(
                            "Not a sub-attribute of " + valuePathParent.canonical() + "."));
            return available(new AttributeRef(sub, null));
        }
        ScimResourceType schema = null;
        String unqualified = token;
        if (token.regionMatches(true, 0, "urn:", 0, 4)) {
            schema = schemaOf(token);
            unqualified = token.substring(schema.schemaUri().length() + 1);
        }
        int dot = unqualified.indexOf('.');
        String attribute = dot < 0 ? unqualified : unqualified.substring(0, dot);
        String subAttribute = dot < 0 ? null : unqualified.substring(dot + 1);
        ScimFilterPath path = ScimFilterPath.of(attribute, subAttribute)
                .orElseThrow(() -> new InvalidScimFilterException(
                        "The filter names an attribute this service does not support."));
        return available(new AttributeRef(path, schema));
    }

    private ScimResourceType schemaOf(String token) {
        for (ScimResourceType type : ScimResourceType.values()) {
            String prefix = type.schemaUri() + ":";
            if (token.regionMatches(true, 0, prefix, 0, prefix.length())) {
                if (!types.contains(type)) {
                    throw new InvalidScimFilterException(
                            "The filter names a schema that is not queried here.");
                }
                return type;
            }
        }
        throw new InvalidScimFilterException(
                "The filter names a schema this service does not support.");
    }

    /** The reference, provided at least one queried type has the attribute it names. */
    private ResolvedPath available(AttributeRef reference) {
        if (reference.path() == ScimFilterPath.PASSWORD) {
            throw new InvalidScimFilterException("password cannot be filtered on.");
        }
        for (ScimResourceType type : ScimResourceType.values()) {
            if (!types.contains(type) || !reference.appliesTo(type)) {
                continue;
            }
            Optional<Attribute> attribute = ScimQueryVocabulary.of(type).find(reference.path());
            if (attribute.isPresent()) {
                return new ResolvedPath(reference, attribute.get());
            }
        }
        throw new InvalidScimFilterException(
                "Not a filterable attribute of the queried resource types: "
                        + reference.path().canonical());
    }

    private static Operator operator(String token) {
        for (Operator operator : Operator.values()) {
            if (operator.token().equals(token)) {
                return operator;
            }
        }
        throw new InvalidScimFilterException("An operator was expected.");
    }

    /**
     * The comparison, with its operator and literal checked against the attribute's type.
     *
     * <p>A comparison against a multi-valued complex attribute with no sub-attribute compares
     * its {@code value}, which is how RFC 7644's own example {@code emails co "example.com"}
     * reads. A single-valued complex attribute has no {@code value} to fall back on, so it only
     * supports {@code pr}.
     */
    private Comparison comparison(ResolvedPath path, Operator operator, Object literal) {
        ResolvedPath target = path;
        if (path.attribute().isComplex()) {
            // Only the multi-valued complex attributes have a "value" sub-attribute to fall back on.
            Optional<ScimFilterPath> value = path.reference().path().subAttribute("value");
            if (value.isEmpty()) {
                throw new InvalidScimFilterException(
                        "A complex attribute is compared through a sub-attribute: "
                                + path.attribute().path().canonical());
            }
            target = available(new AttributeRef(value.get(), path.reference().schema()));
        }
        Attribute attribute = target.attribute();
        if (literal == null) {
            if (operator != Operator.EQ && operator != Operator.NE) {
                throw new InvalidScimFilterException("null is compared only with eq or ne.");
            }
            return new Comparison(target.reference(), operator, null);
        }
        // Each arm refuses a literal of the wrong type, a number included: no attribute here is
        // numeric.
        return switch (attribute.kind()) {
            case STRING, REFERENCE -> {
                if (!(literal instanceof String)) {
                    throw typeMismatch(attribute);
                }
                yield new Comparison(target.reference(), operator, literal);
            }
            case BOOLEAN -> {
                if (!(literal instanceof Boolean)) {
                    throw typeMismatch(attribute);
                }
                if (operator != Operator.EQ && operator != Operator.NE) {
                    throw new InvalidScimFilterException(
                            "A boolean is compared only with eq or ne: "
                                    + attribute.path().canonical());
                }
                yield new Comparison(target.reference(), operator, literal);
            }
            case DATE_TIME -> {
                if (!(literal instanceof String dateTime)) {
                    throw typeMismatch(attribute);
                }
                if (operator.isSubstring()) {
                    throw new InvalidScimFilterException(
                            "A dateTime does not support substring operators: "
                                    + attribute.path().canonical());
                }
                yield new Comparison(target.reference(), operator, instant(dateTime));
            }
            case COMPLEX -> throw typeMismatch(attribute);
        };
    }

    private static InvalidScimFilterException typeMismatch(Attribute attribute) {
        return new InvalidScimFilterException(
                "The compared value does not match the type of " + attribute.path().canonical() + ".");
    }

    private static Instant instant(String dateTime) {
        try {
            return OffsetDateTime.parse(dateTime).toInstant();
        } catch (DateTimeParseException notADateTime) {
            throw new InvalidScimFilterException("A dateTime value is not an xsd:dateTime.");
        }
    }

    private <T extends ScimFilter> T node(T filter) {
        nodes++;
        if (nodes > MAX_NODES) {
            throw new InvalidScimFilterException(
                    "The filter has more than " + MAX_NODES + " expressions.");
        }
        return filter;
    }

    // --- lexing ----------------------------------------------------------------------------

    /**
     * A literal: a JSON string, {@code true}, {@code false}, {@code null} or a number.
     *
     * @return a {@link String}, a {@link Boolean}, a {@link Number}, or {@code null}
     */
    private Object readLiteral() {
        skipWhitespace();
        if (peek() == '"') {
            return readString();
        }
        String word = readWord();
        switch (word.toLowerCase(Locale.ROOT)) {
            case "true":
                return Boolean.TRUE;
            case "false":
                return Boolean.FALSE;
            case "null":
                return null;
            default:
                if (NUMBER.matcher(word).matches()) {
                    return new BigDecimal(word);
                }
                throw new InvalidScimFilterException("A value was expected after the operator.");
        }
    }

    /** A JSON string literal, escapes decoded. The opening quote is at the current position. */
    private String readString() {
        position++;
        StringBuilder value = new StringBuilder();
        while (position < text.length()) {
            char c = text.charAt(position++);
            if (c == '"') {
                return value.toString();
            }
            if (c < 0x20) {
                throw new InvalidScimFilterException("A string value contains a control character.");
            }
            if (c != '\\') {
                value.append(c);
                continue;
            }
            if (position >= text.length()) {
                break;
            }
            char escaped = text.charAt(position++);
            switch (escaped) {
                case '"', '\\', '/' -> value.append(escaped);
                case 'b' -> value.append('\b');
                case 'f' -> value.append('\f');
                case 'n' -> value.append('\n');
                case 'r' -> value.append('\r');
                case 't' -> value.append('\t');
                case 'u' -> value.append(unicodeEscape());
                default -> throw new InvalidScimFilterException("A string value has an invalid escape.");
            }
        }
        throw new InvalidScimFilterException("A string value is not terminated.");
    }

    /**
     * A {@code \\uXXXX} escape, decoded. A NUL is refused: no stored attribute can contain one —
     * every write refuses it — so it could match nothing, and refusing it here answers the client
     * with a {@code 400} rather than a filter that silently matches nothing.
     */
    private char unicodeEscape() {
        if (position + 4 > text.length()) {
            throw new InvalidScimFilterException("A string value has an invalid escape.");
        }
        String hex = text.substring(position, position + 4);
        position += 4;
        char decoded;
        try {
            decoded = (char) Integer.parseInt(hex, 16);
        } catch (NumberFormatException notHex) {
            throw new InvalidScimFilterException("A string value has an invalid escape.");
        }
        if (decoded == '\0') {
            throw new InvalidScimFilterException("A string value contains a NUL character.");
        }
        return decoded;
    }

    /**
     * The run of characters up to whitespace, a bracket, a parenthesis or a quote, from the
     * current position. Every caller has already skipped the whitespace before it.
     */
    private String readWord() {
        int start = position;
        while (position < text.length() && !isDelimiter(text.charAt(position))) {
            position++;
        }
        return text.substring(start, position);
    }

    private boolean nextKeywordIs(String keyword) {
        skipWhitespace();
        if (peekWordIs(keyword)) {
            readWord();
            return true;
        }
        return false;
    }

    private boolean peekWordIs(String keyword) {
        int end = position;
        while (end < text.length() && !isDelimiter(text.charAt(end))) {
            end++;
        }
        return text.substring(position, end).equalsIgnoreCase(keyword);
    }

    private void expect(char expected) {
        skipWhitespace();
        if (peek() != expected) {
            throw new InvalidScimFilterException("'" + expected + "' was expected.");
        }
        position++;
    }

    private char peek() {
        return position < text.length() ? text.charAt(position) : '\0';
    }

    private void skipWhitespace() {
        while (position < text.length() && Character.isWhitespace(text.charAt(position))) {
            position++;
        }
    }

    private static boolean isDelimiter(char c) {
        return Character.isWhitespace(c) || c == '(' || c == ')' || c == '[' || c == ']' || c == '"';
    }
}

package com.example.backend.scim.controller;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The grammar of a SCIM PATCH {@code path}, one for every resource: text in, a parsed path or a
 * value filter's comparisons out — or a refusal.
 *
 * <p>RFC 7644 §3.5.2 defines {@code PATH = attrPath / valuePath [subAttr]}, and §3.4.2.2 defines
 * {@code attrPath = [URI ":"] ATTRNAME *1subAttr} and {@code valuePath = attrPath "[" valFilter
 * "]"}. The grammar has no whitespace before or after a path, and this profile refuses what falls
 * outside it, so a padded path is malformed.
 *
 * <p>A refusal is a {@link ScimErrorException} carrying the {@code scimType} and detail the
 * response uses, so a caller propagates it unchanged:
 *
 * <ul>
 *   <li>a path that does not parse, or a value filter with an unterminated string —
 *       {@code invalidPath};
 *   <li>a value filter using anything but {@code eq} comparisons joined by {@code and} —
 *       {@code invalidFilter}, because the full filter grammar is not implemented in a PATCH path
 *       and an approximated filter would select values the client did not ask for.
 * </ul>
 *
 * <p>This module knows the shape of the text alone. Which attributes a path may name, which
 * sub-attributes a filter compares, and what a parsed path then means are the resource's business:
 * {@link ScimPatchOpReader} classifies a path against the resource's schema, and
 * {@link ScimUserPatchReader} and {@link ScimGroupRequestReader} decide which edit it becomes. No
 * message produced here echoes a submitted value; a path is echoed only after
 * {@link ScimEcho#sanitized}.
 */
final class ScimPatchPathGrammar {

    /** One attribute or sub-attribute name (RFC 7643 §2.1 ATTRNAME), the grammar's only name rule. */
    private static final String NAME = "[A-Za-z][A-Za-z0-9_$-]*";

    /** {@code attr}, {@code attr.sub}, {@code attr[filter]}, {@code attr[filter].sub}. */
    private static final Pattern PATH =
            Pattern.compile("(" + NAME + ")(?:\\[(.+)])?(?:\\.(" + NAME + "))?");

    /**
     * A plain attribute name: {@link #PATH} with neither a filter nor a sub-attribute, which is
     * exactly {@link #PATH}'s first group, since both are built from {@link #NAME}.
     */
    private static final Pattern ATTRIBUTE_NAME = Pattern.compile(NAME);

    /** One {@code sub op value} comparison inside a value filter. */
    private static final Pattern COMPARISON =
            Pattern.compile("\\s*(" + NAME + ")\\s+([A-Za-z]+)\\s+(.+?)\\s*");

    private static final JsonMapper JSON = JsonMapper.builder().build();

    /**
     * A parsed path: the attribute and sub-attribute lower-cased, and the value filter's text as
     * written, or {@code null} for each part the path does not have.
     */
    record Path(String attribute, String filter, String subAttribute) {
    }

    /**
     * One {@code sub eq value} comparison of a value filter: the sub-attribute lower-cased, and the
     * compared value as the JSON literal it was written as.
     */
    record Comparison(String subAttribute, JsonNode value) {
    }

    private ScimPatchPathGrammar() {
    }

    /**
     * The path this text denotes. The resource's core schema URN and a colon may prefix it, value
     * paths included, and the prefix matches case-insensitively (RFC 7644 §3.10, §3.5.2).
     *
     * @param schemaUri the core schema URN of the resource being patched
     * @throws ScimErrorException {@code invalidPath} when the text is not an attribute path
     */
    static Path path(String rawPath, String schemaUri) {
        String prefix = schemaUri + ":";
        String path = rawPath.regionMatches(true, 0, prefix, 0, prefix.length())
                ? rawPath.substring(prefix.length())
                : rawPath;
        Matcher matcher = PATH.matcher(path);
        if (!matcher.matches()) {
            throw ScimErrorException.invalidPath(
                    "Not a valid attribute path: " + ScimEcho.sanitized(rawPath));
        }
        String sub = matcher.group(3);
        return new Path(
                matcher.group(1).toLowerCase(Locale.ROOT),
                matcher.group(2),
                sub == null ? null : sub.toLowerCase(Locale.ROOT));
    }

    /** Whether this text is a plain attribute name, with no filter and no sub-attribute. */
    static boolean isAttributeName(String text) {
        return ATTRIBUTE_NAME.matcher(text).matches();
    }

    /**
     * A value filter's comparisons: {@code eq} comparisons joined by {@code and}, each of a
     * sub-attribute with one JSON literal. Which sub-attributes exist, and which literal each
     * compares with, is the resource's to decide.
     *
     * <p>Split on {@code and} only outside quoted strings, so a compared value containing the word
     * is not mistaken for a conjunction. Grouping is refused here; {@code or}, {@code not} and every
     * other operator are refused as {@code invalidFilter} by the comparison and literal parsing
     * below — an {@code or} leaves the text after it trailing the compared value, which is then not
     * one JSON literal — rather than approximated.
     *
     * @param text the filter between a path's brackets, as written
     * @throws ScimErrorException {@code invalidFilter} for an unsupported filter, and
     *     {@code invalidPath} for an unterminated string
     */
    static List<Comparison> valueFilter(String text) {
        List<Comparison> comparisons = new ArrayList<>();
        for (String comparison : conjuncts(text)) {
            Matcher matcher = COMPARISON.matcher(comparison);
            if (!matcher.matches()) {
                throw ScimErrorException.invalidFilter(
                        "Only 'sub-attribute eq value' comparisons joined by 'and' are supported"
                                + " in a value filter.");
            }
            if (!matcher.group(2).equalsIgnoreCase("eq")) {
                throw ScimErrorException.invalidFilter(
                        "Only the eq operator is supported in a value filter.");
            }
            comparisons.add(new Comparison(
                    matcher.group(1).toLowerCase(Locale.ROOT), literal(matcher.group(3))));
        }
        return List.copyOf(comparisons);
    }

    /** The filter's comparisons, split at each top-level {@code and}; refuses what is unsupported. */
    private static List<String> conjuncts(String text) {
        List<String> parts = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean quoted = false;
        for (int index = 0; index < text.length(); index++) {
            char character = text.charAt(index);
            if (quoted) {
                current.append(character);
                if (character == '\\' && index + 1 < text.length()) {
                    current.append(text.charAt(++index));
                } else if (character == '"') {
                    quoted = false;
                }
                continue;
            }
            if (character == '"') {
                quoted = true;
            } else if (character == '(' || character == ')' || character == '[') {
                throw ScimErrorException.invalidFilter(
                        "Grouping is not supported in a value filter.");
            } else if (Character.isWhitespace(character) && keywordAt(text, index + 1, "and")) {
                parts.add(current.toString());
                current.setLength(0);
                index += "and".length();
                continue;
            }
            current.append(character);
        }
        if (quoted) {
            throw ScimErrorException.invalidPath("The value filter has an unterminated string.");
        }
        parts.add(current.toString());
        return parts;
    }

    /** Whether {@code keyword} stands alone at this index, followed by whitespace. */
    private static boolean keywordAt(String text, int index, String keyword) {
        int end = index + keyword.length();
        return end < text.length()
                && text.regionMatches(true, index, keyword, 0, keyword.length())
                && Character.isWhitespace(text.charAt(end));
    }

    /** A comparison's value, which must be one JSON literal. */
    private static JsonNode literal(String text) {
        try {
            return JSON.readTree(text);
        } catch (RuntimeException notJson) {
            throw ScimErrorException.invalidFilter(
                    "A compared value must be a JSON string, boolean or null.");
        }
    }
}

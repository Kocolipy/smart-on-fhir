package com.example.backend.scim.controller;

import com.example.backend.paging.domain.PageRequest;
import com.example.backend.scim.domain.InvalidScimQueryException;
import com.example.backend.scim.domain.ScimQuery;
import com.example.backend.scim.domain.ScimResourceType;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import tools.jackson.databind.JsonNode;

/**
 * A collection query, read from the two places RFC 7644 lets a client write one: the query
 * string of a {@code GET} (§3.4.2) and the {@code SearchRequest} body of a {@code POST .search}
 * (§3.4.3).
 *
 * <p>Both produce the same {@link ScimQuery}, which is the whole of why the two return the same
 * resources: a search body is not a second implementation of querying, only a second spelling
 * of it. Projection is carried beside the query as the two raw parameters, because which
 * vocabulary validates them depends on the endpoint.
 *
 * @param query              the query
 * @param attributes         the requested attributes, comma-separated, or {@code null}
 * @param excludedAttributes the excluded attributes, comma-separated, or {@code null}
 */
record ScimQueryRequest(ScimQuery query, String attributes, String excludedAttributes) {

    /** The members a {@code SearchRequest} may carry, by their lower-cased names. */
    private static final Set<String> SEARCH_REQUEST_MEMBERS = Set.of(
            "schemas", "attributes", "excludedattributes", "filter", "sortby", "sortorder",
            "startindex", "count");

    /**
     * The query a {@code GET}'s parameters describe.
     *
     * <p>{@code startIndex} and {@code count} arrive as strings and are parsed here, so a
     * non-numeric value is a SCIM {@code 400 invalidValue} rather than a binder's error in some
     * other shape.
     */
    static ScimQueryRequest fromParameters(
            Set<ScimResourceType> types,
            String filter,
            String sortBy,
            String sortOrder,
            String startIndex,
            String count,
            String attributes,
            String excludedAttributes) {
        PageRequest page = PageRequest.of(
                integer(startIndex, "startIndex"), integer(count, "count"));
        return new ScimQueryRequest(
                ScimQuery.of(types, filter, sortBy, sortOrder, page),
                attributes,
                excludedAttributes);
    }

    /**
     * The query a {@code SearchRequest} body describes.
     *
     * <p>The body must declare the {@code SearchRequest} schema, as every SCIM message declares
     * its own, and may carry only the members RFC 7644 §3.4.3 defines — matched
     * case-insensitively, as SCIM attribute names are. An unknown member is refused rather than
     * ignored: a misspelt {@code filtr} ignored would return every resource to a caller that
     * asked for some.
     */
    static ScimQueryRequest fromSearchRequest(Set<ScimResourceType> types, JsonNode body) {
        if (body == null || !body.isObject()) {
            throw ScimErrorException.invalidSyntax("A search request body is a JSON object.");
        }
        Map<String, JsonNode> members = new HashMap<>();
        for (String member : body.propertyNames()) {
            String name = member.toLowerCase(Locale.ROOT);
            if (!SEARCH_REQUEST_MEMBERS.contains(name)) {
                throw ScimErrorException.invalidSyntax(
                        "A SearchRequest has no member of that name.");
            }
            if (members.put(name, body.get(member)) != null) {
                throw ScimErrorException.invalidSyntax("A SearchRequest member appears twice.");
            }
        }
        requireSearchRequestSchema(members.get("schemas"));
        PageRequest page = PageRequest.of(
                integer(members.get("startindex"), "startIndex"),
                integer(members.get("count"), "count"));
        return new ScimQueryRequest(
                ScimQuery.of(
                        types,
                        text(members.get("filter"), "filter"),
                        text(members.get("sortby"), "sortBy"),
                        text(members.get("sortorder"), "sortOrder"),
                        page),
                attributeList(members.get("attributes"), "attributes"),
                attributeList(members.get("excludedattributes"), "excludedAttributes"));
    }

    private static void requireSearchRequestSchema(JsonNode schemas) {
        if (schemas == null || !schemas.isArray() || schemas.size() != 1
                || !schemas.get(0).isString()
                || !ScimSchemas.SEARCH_REQUEST.equals(schemas.get(0).stringValue())) {
            throw ScimErrorException.invalidSyntax(
                    "A search request declares exactly the schema " + ScimSchemas.SEARCH_REQUEST + ".");
        }
    }

    private static String text(JsonNode value, String member) {
        if (value == null || value.isNull()) {
            return null;
        }
        if (!value.isString()) {
            throw new InvalidScimQueryException(member + " must be a string.");
        }
        return value.stringValue();
    }

    /**
     * An attribute list as the projection parser takes it: comma-separated.
     *
     * <p>RFC 7644 §3.4.3 makes the member a multi-valued string, so an array is the form a
     * client sends; a single string is accepted too, since it is the same list the query
     * parameter form carries.
     */
    private static String attributeList(JsonNode value, String member) {
        if (value == null || value.isNull()) {
            return null;
        }
        if (value.isString()) {
            return value.stringValue();
        }
        if (!value.isArray()) {
            throw new InvalidScimQueryException(member + " must be a list of attribute paths.");
        }
        List<String> paths = new ArrayList<>();
        for (JsonNode path : value) {
            if (!path.isString()) {
                throw new InvalidScimQueryException(member + " must be a list of attribute paths.");
            }
            paths.add(path.stringValue());
        }
        return String.join(",", paths);
    }

    /** A paging value from a JSON body: an integer, or absent. */
    private static Integer integer(JsonNode value, String member) {
        if (value == null || value.isNull()) {
            return null;
        }
        if (!value.isIntegralNumber()) {
            throw new InvalidScimQueryException(member + " must be an integer.");
        }
        return clamped(value.bigIntegerValue());
    }

    /** A paging value from a query parameter: an integer, or absent. */
    private static Integer integer(String value, String parameter) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return clamped(new BigInteger(value.trim()));
        } catch (NumberFormatException notANumber) {
            throw new InvalidScimQueryException(parameter + " must be an integer.");
        }
    }

    /**
     * An integer of any size, brought into {@code int} range.
     *
     * <p>RFC 7644 asks for out-of-range paging values to be COERCED, and a value too large for
     * an {@code int} is simply further out of range: {@code count=99999999999} is a request for
     * the maximum page, not a malformed one. {@link PageRequest#of} applies the real limits.
     */
    private static Integer clamped(BigInteger value) {
        BigInteger max = BigInteger.valueOf(Integer.MAX_VALUE);
        BigInteger min = BigInteger.valueOf(Integer.MIN_VALUE);
        return value.max(min).min(max).intValueExact();
    }
}

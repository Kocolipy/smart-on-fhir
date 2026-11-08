package com.example.backend.scim.controller;

import com.example.backend.scim.domain.ScimAttribute;
import com.example.backend.scim.domain.ScimResourceSchema;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import tools.jackson.databind.JsonNode;

/**
 * One JSON object of a SCIM User or Group write body — the resource itself, or one complex value
 * inside it — read against the attributes the schema says it may carry, by their canonical names.
 *
 * <p>The envelope rules a resource body shares, whatever the resource, live here once so User
 * and Group writes cannot drift on them. The attribute facts are {@link ScimResourceSchema}'s and
 * {@link ScimAttribute}'s; this class adds no list of its own:
 *
 * <ul>
 *   <li><strong>Names match regardless of case</strong> (RFC 7643 §2.1, RFC 7644 §3.10), at the
 *       top level and within a complex value, and every value is read back by its canonical name.
 *   <li><strong>A name given twice</strong> in spellings that differ only by case is
 *       {@code 400 invalidSyntax}: the body does not say which value it means. Refused before
 *       anything else is checked or read, so the answer never depends on property order.
 *   <li>The resource declares exactly its own core schema, as a non-empty array.
 *   <li>An attribute the schema does not have is refused {@code 400 invalidValue}, not dropped;
 *       a read-only one is accepted and never read, so a client may PUT back what it read
 *       (RFC 7644 §3.5.2).
 *   <li>A string is a non-blank JSON string and a boolean a JSON boolean; anything else is
 *       {@code 400 invalidValue} naming the attribute.
 * </ul>
 *
 * <p>No message produced here contains a submitted value; a submitted name is echoed only through
 * {@link ScimEcho#sanitized}.
 */
final class ScimResourceBody {

    /** Each supplied value, by the canonical name of the attribute it is for. */
    private final Map<String, JsonNode> values;

    private ScimResourceBody(Map<String, JsonNode> values) {
        this.values = values;
    }

    /**
     * A resource body for this schema, refused unless it is an object declaring exactly the
     * schema's own core schema and naming only attributes the resource has, each once.
     *
     * @throws ScimErrorException {@code 400 invalidSyntax} for a body that is not an object, a
     *     name given twice, or a missing or malformed {@code schemas}; {@code 400 invalidValue}
     *     for another schema or an attribute the resource does not have
     */
    static ScimResourceBody resource(JsonNode body, ScimResourceSchema schema) {
        if (body == null || !body.isObject()) {
            throw ScimErrorException.invalidSyntax("The request body must be a SCIM resource.");
        }
        String type = schema.type().resourceTypeName();
        NameResolution names = resolveNames(body, schema.allAttributes(), "", schema::find);
        requireSchema(names.body.values.get("schemas"), schema.type().schemaUri(), type);
        names.rejectUnsupported("This service does not implement the " + type + " attribute: ");
        return names.body;
    }

    /**
     * One complex value of this attribute, refused unless it names only the attribute's own
     * sub-attributes, each once.
     *
     * @param value an object; the caller has refused anything else in its own words
     * @throws ScimErrorException {@code 400 invalidSyntax} for a sub-attribute given twice;
     *     {@code 400 invalidValue} for one the attribute does not have
     */
    static ScimResourceBody complex(JsonNode value, ScimAttribute attribute) {
        NameResolution names = resolveNames(value, attribute.subAttributes(),
                attribute.name() + ".", attribute::subAttribute);
        names.rejectUnsupported(
                "This service does not implement the " + attribute.name() + " sub-attribute: ");
        return names.body;
    }

    /** What a pass over an object's names found: its values, and the first name it lacks. */
    private record NameResolution(ScimResourceBody body, String unsupported) {

        void rejectUnsupported(String refusal) {
            if (unsupported != null) {
                throw ScimErrorException.invalidValue(refusal + ScimEcho.sanitized(unsupported));
            }
        }
    }

    /**
     * Every name in {@code object}, resolved to its canonical attribute. A canonical name reached
     * twice is refused here, before anything else, and the refusal names the first such attribute
     * in declaration order, so neither the check nor its message depends on property order.
     */
    private static NameResolution resolveNames(JsonNode object, List<ScimAttribute> declared, String prefix,
            Function<String, Optional<ScimAttribute>> lookup) {
        Map<String, JsonNode> values = new HashMap<>();
        Map<String, Integer> spellings = new HashMap<>();
        String unsupported = null;
        for (String name : object.propertyNames()) {
            Optional<ScimAttribute> attribute = lookup.apply(name);
            if (attribute.isEmpty()) {
                unsupported = unsupported == null ? name : unsupported;
                continue;
            }
            String canonical = attribute.get().name();
            values.put(canonical, object.get(name));
            spellings.merge(canonical, 1, Integer::sum);
        }
        for (ScimAttribute attribute : declared) {
            if (spellings.getOrDefault(attribute.name(), 0) > 1) {
                throw ScimErrorException.invalidSyntax("The attribute " + prefix + attribute.name()
                        + " is given more than once, in names that differ only by case.");
            }
        }
        return new NameResolution(new ScimResourceBody(values), unsupported);
    }

    /**
     * The body declares exactly the expected schema.
     *
     * <p>Exactly, not "contains": a body declaring an extension schema is asserting attributes this
     * service does not have, and accepting the declaration while ignoring the attributes is how a
     * connector comes to believe its extension is stored. The URI itself compares exactly, as
     * discovery compares it.
     */
    private static void requireSchema(JsonNode schemas, String expected, String type) {
        if (schemas == null || !schemas.isArray() || schemas.isEmpty()) {
            throw ScimErrorException.invalidSyntax(
                    "A resource must declare its schemas as a non-empty array.");
        }
        if (schemas.size() != 1 || !expected.equals(schemas.get(0).asText())) {
            throw ScimErrorException.invalidValue(
                    "This service implements one " + type + " schema only: " + expected);
        }
    }

    /** The value supplied for this attribute, by its canonical name; {@code null} when absent. */
    JsonNode value(String attribute) {
        return values.get(attribute);
    }

    /**
     * The non-blank string this required attribute carries.
     *
     * @throws ScimErrorException {@code 400 invalidSyntax} when it is absent or null, and
     *     {@code 400 invalidValue} when it is not a non-blank string
     */
    String requiredString(String attribute) {
        JsonNode value = values.get(attribute);
        if (value == null || value.isNull()) {
            throw ScimErrorException.invalidSyntax("A required attribute is missing: " + attribute);
        }
        return string(value, attribute);
    }

    /** The non-blank string this attribute carries, or {@code null} when it is absent or null. */
    String optionalString(String attribute) {
        JsonNode value = values.get(attribute);
        return value == null || value.isNull() ? null : string(value, attribute);
    }

    /** The boolean this attribute carries, or {@code whenAbsent} when it is absent or null. */
    boolean optionalBoolean(String attribute, boolean whenAbsent) {
        JsonNode value = values.get(attribute);
        if (value == null || value.isNull()) {
            return whenAbsent;
        }
        if (!value.isBoolean()) {
            throw ScimErrorException.invalidValue(attribute + " must be a boolean.");
        }
        return value.booleanValue();
    }

    /**
     * A present value as a non-blank string.
     *
     * @throws ScimErrorException {@code 400 invalidValue} naming {@code attribute} when it is not a
     *     JSON string, or is blank
     */
    static String string(JsonNode value, String attribute) {
        if (!value.isString()) {
            throw ScimErrorException.invalidValue(attribute + " must be a string.");
        }
        String text = value.stringValue();
        if (text.isBlank()) {
            throw ScimErrorException.invalidValue(attribute + " must not be blank.");
        }
        return text;
    }
}

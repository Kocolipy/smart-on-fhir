package com.example.backend.scim.controller;

import com.example.backend.scim.controller.ScimPatchOpReader.Op;
import com.example.backend.scim.controller.ScimPatchOpReader.Operation;
import com.example.backend.scim.controller.ScimPatchPathGrammar.Comparison;
import com.example.backend.scim.controller.ScimPatchPathGrammar.Path;
import com.example.backend.scim.domain.ScimAttribute;
import com.example.backend.scim.domain.ScimEmail;
import com.example.backend.scim.domain.ScimEmailFilter;
import com.example.backend.scim.domain.ScimEmailPart;
import com.example.backend.scim.domain.ScimName;
import com.example.backend.scim.domain.ScimResourceSchema;
import com.example.backend.scim.domain.ScimResourceType;
import com.example.backend.scim.domain.ScimUserPatchOperation;
import com.example.backend.scim.domain.ScimUserPatchOperation.EmailUpdate;
import com.example.backend.scim.domain.ScimUserPatchOperation.NamePart;
import com.example.backend.scim.domain.ScimUserPatchOperation.TextAttribute;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import tools.jackson.databind.JsonNode;

/**
 * Reads a SCIM User {@code PatchOp} body into the operations it means.
 *
 * <p>The envelope, {@code op}, the path grammar and the classification of a path against the User
 * schema are {@link ScimPatchOpReader}'s, shared with a Group, and their refusals pass through
 * unchanged. This reader contributes what a User adds: which edit a classified path becomes, the
 * {@code emails} sub-attributes a value filter compares, and the merge a path-less {@code add} or
 * {@code replace} is. Every refusal that depends only on the REQUEST is made here or there, so the
 * operations that reach the use case are all well-formed and only the refusals that depend on the
 * stored User remain for it ({@code noTarget}, removing a required value):
 *
 * <ul>
 *   <li>a filter or a sub-attribute the {@code emails} or {@code name} attribute does not have —
 *       {@code invalidPath}; a value filter comparing a sub-attribute {@code emails} does not have,
 *       or with a literal of the wrong type — {@code invalidFilter};
 *   <li>removing {@code active} — {@code mutability};
 *   <li>a value of the wrong JSON type for its target — {@code invalidValue}.
 * </ul>
 *
 * <p>No message produced here echoes a submitted value; a name is echoed only after
 * {@link ScimEcho#sanitized}.
 */
final class ScimUserPatchReader {

    /**
     * Which attributes exist, which are read-only, and which are complex or multi-valued: the User
     * schema's facts, which classify a path before this reader decides what its operation does.
     * Nothing below restates them; the maps that follow say only which edit a supported attribute
     * becomes.
     */
    private static final ScimResourceSchema SCHEMA = ScimResourceSchema.of(ScimResourceType.USER);

    /** The {@code name} attribute, whose sub-attributes a sub-path names. */
    private static final ScimAttribute NAME = SCHEMA.find("name").orElseThrow();

    /** The {@code emails} attribute, whose sub-attributes a value filter and a sub-path name. */
    private static final ScimAttribute EMAILS = SCHEMA.find("emails").orElseThrow();

    /**
     * The calling connector's alias: read-write, as the schema declares it. {@code add} and
     * {@code replace} set it and {@code remove} clears it; the use case writes it under the calling
     * connector alone, so no PATCH can reach another connector's alias.
     */
    private static final String EXTERNAL_ID = "externalId";

    /** The single-valued strings, each with the edit that sets or clears it. */
    private static final Map<String, TextAttribute> TEXT = Map.of(
            "userName", TextAttribute.USER_NAME,
            "displayName", TextAttribute.DISPLAY_NAME,
            "preferredLanguage", TextAttribute.PREFERRED_LANGUAGE,
            "locale", TextAttribute.LOCALE,
            "timezone", TextAttribute.TIMEZONE);

    /** {@code name}'s sub-attributes, by canonical name, as the part each edits. */
    private static final Map<String, NamePart> NAME_PARTS = Map.of(
            "formatted", NamePart.FORMATTED,
            "familyName", NamePart.FAMILY_NAME,
            "givenName", NamePart.GIVEN_NAME,
            "middleName", NamePart.MIDDLE_NAME,
            "honorificPrefix", NamePart.HONORIFIC_PREFIX,
            "honorificSuffix", NamePart.HONORIFIC_SUFFIX);

    /** {@code emails}' sub-attributes, by canonical name, as the part each edits or selects. */
    private static final Map<String, ScimEmailPart> EMAIL_PARTS = Map.of(
            "value", ScimEmailPart.VALUE,
            "type", ScimEmailPart.TYPE,
            "primary", ScimEmailPart.PRIMARY);

    private ScimUserPatchReader() {
    }

    /**
     * The operations this body describes, in order.
     *
     * @throws ScimErrorException {@code 400} for every request-only refusal, this class's and
     *     {@link ScimPatchOpReader}'s
     */
    static List<ScimUserPatchOperation> readPatch(JsonNode body) {
        return ScimPatchOpReader.read(body, SCHEMA, ScimUserPatchReader::meaning);
    }

    private static List<ScimUserPatchOperation> meaning(Operation operation) {
        if (operation.path() == null) {
            return pathless(operation.op(), operation.value());
        }
        return List.of(target(operation.op(), operation.path(), operation.value()));
    }

    /**
     * An {@code add} or {@code replace} with no path: its value is an object of attributes, each
     * applied as if it had been the operation's path. Read-only attributes in the value are
     * ignored, as they are in a PUT body, so a client may send back what it read. (A path-less
     * {@code remove} never reaches here: {@link ScimPatchOpReader} refuses it.)
     */
    private static List<ScimUserPatchOperation> pathless(Op op, JsonNode value) {
        if (value == null || !value.isObject()) {
            throw ScimErrorException.invalidValue(
                    "An operation without a path requires an object value.");
        }
        List<ScimUserPatchOperation> read = new ArrayList<>();
        for (String attribute : value.propertyNames()) {
            if (ScimPatchOpReader.isReadOnly(SCHEMA.find(attribute))) {
                continue;
            }
            if (!ScimPatchPathGrammar.isAttributeName(attribute)) {
                throw ScimErrorException.invalidPath(
                        "Not an attribute name: " + ScimEcho.sanitized(attribute));
            }
            read.add(target(op, new Path(attribute, null, null), value.get(attribute)));
        }
        return read;
    }

    /** The operation a path names, after {@link ScimPatchOpReader#target} has classified it. */
    private static ScimUserPatchOperation target(Op op, Path path, JsonNode value) {
        String name = ScimPatchOpReader.target(SCHEMA, path).name();
        if (EXTERNAL_ID.equals(name)) {
            return op == Op.REMOVE
                    ? new ScimUserPatchOperation.RemoveExternalId()
                    : new ScimUserPatchOperation.SetExternalId(stringValue(value, EXTERNAL_ID));
        }
        TextAttribute text = TEXT.get(name);
        if (text != null) {
            return op == Op.REMOVE
                    ? new ScimUserPatchOperation.RemoveText(text)
                    : new ScimUserPatchOperation.SetText(text, stringValue(value, name));
        }
        return switch (name) {
            // A remove is refused rather than read as "back to the create default". RFC 7644
            // §3.5.2.2 makes a removed attribute unassigned, and this service has no unassigned
            // `active` to store; reading it as `true` would let a remove reactivate a deactivated
            // User, which only an explicit false-to-true replace may do.
            case "active" -> {
                if (op == Op.REMOVE) {
                    throw ScimErrorException.mutability(
                            "active cannot be removed; replace it with true or false.");
                }
                yield new ScimUserPatchOperation.SetActive(booleanValue(value, "active"));
            }
            case "password" -> op == Op.REMOVE
                    ? new ScimUserPatchOperation.RemovePassword()
                    : new ScimUserPatchOperation.SetPassword(stringValue(value, "password"));
            case "name" -> name(op, path.subAttribute(), value);
            case "emails" -> emails(op, path, value);
            // Every writable User attribute has an arm above, so a writable attribute reaching here
            // is one the schema gained without this reader learning to edit it.
            default -> throw new IllegalStateException("No PATCH edit for the User attribute " + name);
        };
    }

    /** {@code name}'s sub-attribute as the part an edit targets, or empty when it has none such. */
    private static Optional<NamePart> namePart(String sub) {
        return NAME.subAttribute(sub).map(declared -> NAME_PARTS.get(declared.name()));
    }

    /** {@code emails}' sub-attribute this name denotes, case-insensitively, or empty for none. */
    static Optional<ScimEmailPart> emailPart(String sub) {
        return EMAILS.subAttribute(sub).map(declared -> EMAIL_PARTS.get(declared.name()));
    }

    /**
     * An {@code emails} value filter: {@link ScimPatchPathGrammar#valueFilter}'s comparisons, each
     * on an {@code emails} sub-attribute with a literal of its type — {@code primary} with a
     * boolean, {@code value} and {@code type} with a string or {@code null}.
     *
     * @param text the filter between a path's brackets, as written
     * @throws ScimErrorException {@code invalidFilter} for an unsupported filter, and
     *     {@code invalidPath} for an unterminated string
     */
    static ScimEmailFilter emailFilter(String text) {
        List<ScimEmailFilter.Condition> conditions = new ArrayList<>();
        for (Comparison comparison : ScimPatchPathGrammar.valueFilter(text)) {
            ScimEmailPart part = emailPart(comparison.subAttribute()).orElseThrow(
                    () -> ScimErrorException.invalidFilter("emails has no such sub-attribute."));
            conditions.add(new ScimEmailFilter.Condition(part, literal(part, comparison.value())));
        }
        return new ScimEmailFilter(conditions);
    }

    /** A comparison's value as the type its sub-attribute compares with. */
    private static Object literal(ScimEmailPart part, JsonNode literal) {
        if (part == ScimEmailPart.PRIMARY) {
            if (!literal.isBoolean()) {
                throw ScimErrorException.invalidFilter("primary compares with true or false.");
            }
            return literal.booleanValue();
        }
        if (literal.isNull()) {
            return null;
        }
        if (!literal.isString()) {
            throw ScimErrorException.invalidFilter(
                    "value and type compare with a quoted string.");
        }
        return literal.stringValue();
    }

    private static ScimUserPatchOperation name(Op op, String sub, JsonNode value) {
        if (sub == null) {
            if (op == Op.REMOVE) {
                return new ScimUserPatchOperation.RemoveName();
            }
            if (value == null || !value.isObject()) {
                throw ScimErrorException.invalidValue("name must be a complex value.");
            }
            return new ScimUserPatchOperation.MergeName(ScimUserRequestReader.readName(value));
        }
        NamePart part = namePart(sub).orElseThrow(
                () -> ScimErrorException.invalidPath("name has no such sub-attribute."));
        if (op == Op.REMOVE) {
            return new ScimUserPatchOperation.RemoveNamePart(part);
        }
        String given = stringValue(value, "name." + sub);
        return new ScimUserPatchOperation.MergeName(new ScimName(
                part == NamePart.FORMATTED ? given : null,
                part == NamePart.FAMILY_NAME ? given : null,
                part == NamePart.GIVEN_NAME ? given : null,
                part == NamePart.MIDDLE_NAME ? given : null,
                part == NamePart.HONORIFIC_PREFIX ? given : null,
                part == NamePart.HONORIFIC_SUFFIX ? given : null));
    }

    private static ScimUserPatchOperation emails(Op op, Path path, JsonNode value) {
        ScimEmailFilter filter = path.filter() == null
                ? ScimEmailFilter.ALL
                : emailFilter(path.filter());
        if (path.subAttribute() != null) {
            ScimEmailPart part = emailPart(path.subAttribute()).orElseThrow(
                    () -> ScimErrorException.invalidPath("emails has no such sub-attribute."));
            if (op == Op.REMOVE) {
                return new ScimUserPatchOperation.RemoveEmailPart(filter, part);
            }
            return new ScimUserPatchOperation.UpdateEmails(filter, switch (part) {
                case VALUE -> new EmailUpdate(stringValue(value, "emails.value"), null, null);
                case TYPE -> new EmailUpdate(null, stringValue(value, "emails.type"), null);
                case PRIMARY -> new EmailUpdate(null, null, booleanValue(value, "emails.primary"));
            });
        }
        if (op == Op.REMOVE) {
            return new ScimUserPatchOperation.RemoveEmails(filter);
        }
        if (path.filter() != null) {
            return new ScimUserPatchOperation.UpdateEmails(filter, emailUpdate(value));
        }
        if (value == null || !value.isArray()) {
            throw ScimErrorException.invalidValue("emails must be an array.");
        }
        List<ScimEmail> emails = ScimUserRequestReader.readEmails(value);
        return op == Op.ADD
                ? new ScimUserPatchOperation.AddEmails(emails)
                : new ScimUserPatchOperation.ReplaceEmails(emails);
    }

    /** The sub-attributes an object value writes onto filtered emails; unnamed ones are kept. */
    private static EmailUpdate emailUpdate(JsonNode value) {
        if (value == null || !value.isObject()) {
            throw ScimErrorException.invalidValue(
                    "A filtered emails operation requires an object value.");
        }
        for (String sub : value.propertyNames()) {
            if (emailPart(sub).isEmpty()) {
                throw ScimErrorException.invalidValue(
                        "This service does not implement the emails sub-attribute: "
                                + ScimEcho.sanitized(sub));
            }
        }
        JsonNode primary = value.get("primary");
        return new EmailUpdate(
                value.has("value") ? stringValue(value.get("value"), "emails.value") : null,
                value.has("type") ? stringValue(value.get("type"), "emails.type") : null,
                primary == null ? null : booleanValue(primary, "emails.primary"));
    }

    private static String stringValue(JsonNode value, String attribute) {
        if (value == null || value.isNull()) {
            throw ScimErrorException.invalidValue("This operation requires a value for "
                    + attribute + ".");
        }
        return ScimUserRequestReader.string(value, attribute);
    }

    private static boolean booleanValue(JsonNode value, String attribute) {
        if (value == null || !value.isBoolean()) {
            throw ScimErrorException.invalidValue(attribute + " must be a boolean.");
        }
        return value.booleanValue();
    }
}

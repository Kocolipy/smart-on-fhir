package com.example.backend.scim.controller;

import com.example.backend.scim.application.NewScimUser;
import com.example.backend.scim.application.ScimUserReplacement;
import com.example.backend.scim.domain.ScimAttribute;
import com.example.backend.scim.domain.ScimEmail;
import com.example.backend.scim.domain.ScimName;
import com.example.backend.scim.domain.ScimResourceSchema;
import com.example.backend.scim.domain.ScimResourceType;
import com.example.backend.scim.domain.ScimUserProfile;
import java.util.ArrayList;
import java.util.List;
import tools.jackson.databind.JsonNode;

/**
 * Reads a SCIM User write body, refusing what this service does not implement and
 * ignoring what the RFC says to ignore.
 *
 * <p>Hand-read from a {@link JsonNode} rather than bound to a DTO, because SCIM's rules
 * for a write body are not the rules a binder applies. Three of them matter here and
 * none is expressible as a field declaration:
 *
 * <ul>
 *   <li>a read-only attribute in the body is <strong>ignored</strong> (RFC 7644 §3.5.2),
 *       so a client may PUT back a resource it read;
 *   <li>an attribute this service does not implement is <strong>refused</strong>, not
 *       dropped — a silently ignored {@code title} is a client believing it stored one;
 *   <li>a value of the wrong JSON type is a {@code 400 invalidValue} naming the
 *       attribute, not a binder's message naming a Java field.
 * </ul>
 *
 * <p>Binding would give the middle rule the opposite default: Boot disables
 * {@code FAIL_ON_UNKNOWN_PROPERTIES}, so an unsupported attribute would be dropped, and
 * re-enabling it for one DTO is not something an annotation on that DTO can do.
 *
 * <p>Those rules, the schema declaration and the case-insensitive lookup of every name are
 * {@link ScimResourceBody}'s, shared with a Group; what stays here is turning the canonical
 * values into a User command.
 *
 * <p>No message produced here contains a submitted VALUE. Attribute paths are echoed
 * because the caller needs to know which attribute was wrong, and an attribute name is
 * this service's own vocabulary; a value could be a password.
 */
final class ScimUserRequestReader {

    /** The User schema: which attributes exist, and which of them a write may set. */
    private static final ScimResourceSchema SCHEMA = ScimResourceSchema.of(ScimResourceType.USER);

    /** The {@code name} attribute, whose declared sub-attributes a name value may carry. */
    private static final ScimAttribute NAME = SCHEMA.find("name").orElseThrow();

    /** The {@code emails} attribute, whose declared sub-attributes each email may carry. */
    private static final ScimAttribute EMAILS = SCHEMA.find("emails").orElseThrow();

    private ScimUserRequestReader() {
    }

    /**
     * The create command this body describes.
     *
     * @throws ScimErrorException {@code 400} for a body that is not an object, declares
     *                            the wrong schema, omits {@code userName}, asserts an
     *                            unimplemented attribute, names one twice, or carries a
     *                            value of the wrong type
     */
    static NewScimUser readCreate(JsonNode body) {
        return create(ScimResourceBody.resource(body, SCHEMA));
    }

    private static NewScimUser create(ScimResourceBody body) {
        ScimUserProfile profile = new ScimUserProfile(
                body.requiredString("userName"),
                readName(body.value("name")),
                body.optionalString("displayName"),
                body.optionalString("preferredLanguage"),
                body.optionalString("locale"),
                body.optionalString("timezone"),
                // RFC 7643's create default. Stated here, at the one place a create is
                // read, rather than as a column default that would also apply to writes
                // this reader never saw.
                body.optionalBoolean("active", true),
                readEmails(body.value("emails")));
        return new NewScimUser(
                profile, body.optionalString("password"), body.optionalString("externalId"));
    }

    /**
     * The replacement a PUT body describes.
     *
     * <p>Read exactly as a create is, because a PUT body IS a complete resource: the same schema
     * rule, the same refusal of unimplemented attributes, the same required {@code userName}.
     * What that means for the omitted attributes is the replacement semantics, and they fall out
     * of reading it this way — an omitted optional attribute is read as unassigned. {@code active}
     * is the exception: an omitted (or null) one is reported as not asserted, and the use case keeps
     * the stored value, so leaving it out never reactivates a deactivated User. An omitted
     * {@code password} is read as absent, and the use case
     * keeps the stored credential for it. {@code externalId} is an ordinary read-write attribute:
     * the submitted value becomes the calling connector's alias and an omitted one removes it.
     *
     * @throws ScimErrorException {@code 400} for every body {@link #readCreate} refuses
     */
    static ScimUserReplacement readReplace(JsonNode body) {
        ScimResourceBody resource = ScimResourceBody.resource(body, SCHEMA);
        NewScimUser read = create(resource);
        JsonNode active = resource.value("active");
        return new ScimUserReplacement(read.profile(), read.password(), read.externalId(),
                active != null && !active.isNull());
    }

    static ScimName readName(JsonNode name) {
        if (name == null || name.isNull()) {
            return ScimName.NONE;
        }
        if (!name.isObject()) {
            throw ScimErrorException.invalidValue("name must be a complex value.");
        }
        ScimResourceBody parts = ScimResourceBody.complex(name, NAME);
        return new ScimName(
                parts.optionalString("formatted"),
                parts.optionalString("familyName"),
                parts.optionalString("givenName"),
                parts.optionalString("middleName"),
                parts.optionalString("honorificPrefix"),
                parts.optionalString("honorificSuffix"));
    }

    static List<ScimEmail> readEmails(JsonNode emails) {
        if (emails == null || emails.isNull()) {
            return List.of();
        }
        if (!emails.isArray()) {
            throw ScimErrorException.invalidValue("emails must be an array.");
        }
        List<ScimEmail> read = new ArrayList<>();
        for (JsonNode email : emails) {
            if (!email.isObject()) {
                throw ScimErrorException.invalidValue("each emails value must be a complex value.");
            }
            ScimResourceBody parts = ScimResourceBody.complex(email, EMAILS);
            read.add(new ScimEmail(
                    parts.requiredString("value"),
                    parts.optionalString("type"),
                    parts.optionalBoolean("primary", false)));
        }
        return read;
    }
}

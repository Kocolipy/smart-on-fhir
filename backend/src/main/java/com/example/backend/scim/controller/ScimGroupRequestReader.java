package com.example.backend.scim.controller;

import com.example.backend.scim.application.NewScimGroup;
import com.example.backend.scim.application.ScimGroupPatchOperation;
import com.example.backend.scim.application.ScimGroupReplacement;
import com.example.backend.scim.controller.ScimPatchOpReader.Op;
import com.example.backend.scim.controller.ScimPatchOpReader.Operation;
import com.example.backend.scim.controller.ScimPatchPathGrammar.Comparison;
import com.example.backend.scim.controller.ScimPatchPathGrammar.Path;
import com.example.backend.scim.domain.ScimAttribute;
import com.example.backend.scim.domain.ScimResourceSchema;
import com.example.backend.scim.domain.ScimResourceType;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import tools.jackson.databind.JsonNode;

/**
 * Reads a SCIM Group write body — create, replace, or PATCH — refusing what this service does not
 * implement and ignoring what the RFC says to ignore.
 *
 * <p>Hand-read from a {@link JsonNode} for the reasons {@link ScimUserRequestReader} gives: a
 * read-only attribute in the body is ignored, an unimplemented attribute is refused rather than
 * dropped, and a wrong JSON type is a {@code 400 invalidValue} naming the attribute. None of the
 * three is expressible as a field declaration, and Boot's disabled
 * {@code FAIL_ON_UNKNOWN_PROPERTIES} gives the middle one the opposite default. Those rules, the
 * schema declaration and the case-insensitive lookup of every name are {@link ScimResourceBody}'s,
 * shared with a User; what stays here is turning the canonical values into a Group command.
 *
 * <p>No message produced here contains a submitted VALUE. Attribute paths are echoed because the
 * caller needs to know which attribute was wrong, and an attribute name is this service's own
 * vocabulary; a value could be anything.
 *
 * <h2>PATCH is narrowed to what a Group can mean</h2>
 *
 * <p>RFC 7644 §3.5.2 defines PATCH over an open-ended path grammar. The envelope, {@code op}, the
 * grammar and the classification of a path against the Group schema are
 * {@link ScimPatchOpReader}'s, shared with a User, and their refusals pass through unchanged. A
 * Group here has three writable attributes, so the set of things a PATCH can mean is finite, and
 * this reader's job is to turn each supported operation into one of
 * {@link ScimGroupPatchOperation}'s variants and refuse the rest. Refusing HERE is the point: an
 * unsupported path is still a request at this layer, so it becomes a {@code 400} the caller can act
 * on rather than something the use case has to interpret.
 */
final class ScimGroupRequestReader {

    /** The Group schema: which attributes exist, and which of them a write may set. */
    private static final ScimResourceSchema SCHEMA = ScimResourceSchema.of(ScimResourceType.GROUP);

    /** The {@code members} attribute, whose declared sub-attributes each member may carry. */
    private static final ScimAttribute MEMBERS = SCHEMA.find("members").orElseThrow();

    private ScimGroupRequestReader() {
    }

    /**
     * The create command this body describes.
     *
     * @throws ScimErrorException {@code 400} for a body that is not an object, declares the wrong
     *                            schema, omits {@code displayName}, asserts an unimplemented
     *                            attribute, names one twice, or carries a value of the wrong type
     */
    static NewScimGroup readCreate(JsonNode body) {
        ScimResourceBody group = ScimResourceBody.resource(body, SCHEMA);
        return new NewScimGroup(
                group.requiredString("displayName"),
                readMemberIds(group.value("members")),
                group.optionalString("externalId"));
    }

    /**
     * The replacement this body describes — a PUT.
     *
     * <p>An absent {@code members} becomes an empty list rather than "leave it alone", because that
     * is what replacing a resource means: a PUT states the resource's whole value, so an attribute
     * the document does not mention is being set to nothing. That is the difference between PUT and
     * PATCH and it is resolved here, so nothing below this has to know which verb it came from.
     *
     * <p>{@code externalId} is replaced on the same terms: the submitted value becomes the calling
     * connector's alias, and an omitted one removes it.
     */
    static ScimGroupReplacement readReplace(JsonNode body) {
        ScimResourceBody group = ScimResourceBody.resource(body, SCHEMA);
        return new ScimGroupReplacement(
                group.requiredString("displayName"),
                readMemberIds(group.value("members")),
                group.optionalString("externalId"));
    }

    /**
     * The PATCH operations this body describes, in the order they must be applied.
     *
     * <p>Order is preserved because PATCH is sequential: RFC 7644 applies operations in the order
     * given, so a remove followed by an add is not the same request as the reverse. The use case
     * folds them into one desired state, which is what makes partial failure unrepresentable.
     *
     * @throws ScimErrorException {@code 400} for every request-only refusal, this class's and
     *     {@link ScimPatchOpReader}'s
     */
    static List<ScimGroupPatchOperation> readPatch(JsonNode body) {
        return ScimPatchOpReader.read(body, SCHEMA, operation -> List.of(meaning(operation)));
    }

    /**
     * One operation, resolved to the variant it means.
     *
     * <p>An omitted {@code path} means the operation applies to the resource as a whole. A
     * path-less {@code remove} never reaches here ({@link ScimPatchOpReader} refuses it
     * {@code noTarget}); a path-less {@code add} or {@code replace} would be an
     * attribute-by-attribute merge whose semantics for a multi-valued attribute the RFC leaves
     * genuinely ambiguous, and guessing at it is worse than refusing — with {@code invalidSyntax},
     * which the SCIM interoperability profile assigns to a rejected path-less operation.
     */
    private static ScimGroupPatchOperation meaning(Operation operation) {
        Path path = operation.path();
        if (path == null) {
            throw ScimErrorException.invalidSyntax(
                    "This service requires a path on each PATCH operation, naming displayName,"
                            + " members or externalId.");
        }
        String attribute = ScimPatchOpReader.target(SCHEMA, path).name();
        return switch (attribute) {
            case "displayName" -> readDisplayNameOperation(operation.op(), operation.value());
            case "members" -> readMembersOperation(operation.op(), path, operation.value());
            case "externalId" -> readExternalIdOperation(operation.op(), operation.value());
            // Every writable Group attribute has an arm above, so a writable attribute reaching
            // here is one the schema gained without this reader learning to edit it.
            default -> throw new IllegalStateException(
                    "No PATCH edit for the Group attribute " + attribute);
        };
    }

    /**
     * {@code add} and {@code replace} on {@code displayName} are one operation, because the
     * attribute is single-valued: RFC 7644 §3.5.2.1 says adding to a single-valued attribute
     * replaces it. {@code remove} is refused — {@code displayName} is required, so removing it would
     * leave a Group the schema forbids.
     */
    private static ScimGroupPatchOperation readDisplayNameOperation(Op op, JsonNode value) {
        return switch (op) {
            case ADD, REPLACE -> new ScimGroupPatchOperation.SetDisplayName(
                    requiredStringValue(value, "displayName"));
            case REMOVE -> throw ScimErrorException.mutability(
                    "displayName is required and cannot be removed.");
        };
    }

    /**
     * {@code add} and {@code replace} on {@code externalId} set the calling connector's alias, and
     * {@code remove} clears it — the attribute is optional, so unlike {@code displayName} it may
     * be removed.
     */
    private static ScimGroupPatchOperation readExternalIdOperation(Op op, JsonNode value) {
        return switch (op) {
            case ADD, REPLACE -> new ScimGroupPatchOperation.SetExternalId(
                    requiredStringValue(value, "externalId"));
            case REMOVE -> new ScimGroupPatchOperation.RemoveExternalId();
        };
    }

    /**
     * {@code members}: add to the membership, replace it wholesale, or remove from it.
     *
     * <p>{@code remove} with a value is read as removing exactly those members, which is the form a
     * client that cannot build a value path uses; {@code remove} with no value clears the
     * membership, as the RFC defines for a path with no filter. A value path selects the members to
     * remove, and is supported for {@code remove} only: adding to or replacing selected members
     * would write a member's read-only sub-attributes. No sub-attribute path is supported, for the
     * same reason: {@code value} is the only writable one, and it is the member's identity.
     */
    private static ScimGroupPatchOperation readMembersOperation(
            Op op, Path path, JsonNode value) {
        if (path.subAttribute() != null) {
            throw ScimErrorException.invalidPath(
                    "This service does not implement a members sub-attribute path.");
        }
        if (path.filter() != null) {
            if (op != Op.REMOVE) {
                throw ScimErrorException.invalidPath(
                        "A members value path is supported for remove only.");
            }
            return new ScimGroupPatchOperation.RemoveMembers(selectedMembers(path.filter()));
        }
        return switch (op) {
            case ADD -> new ScimGroupPatchOperation.AddMembers(requiredMemberIds(value));
            case REPLACE -> new ScimGroupPatchOperation.ReplaceMembers(requiredMemberIds(value));
            case REMOVE -> value == null || value.isNull()
                    ? new ScimGroupPatchOperation.RemoveAllMembers()
                    : new ScimGroupPatchOperation.RemoveMembers(requiredMemberIds(value));
        };
    }

    /**
     * The members a value filter selects — {@code members[value eq "…"]}, the form every
     * provisioning system uses to revoke one membership.
     *
     * <p>Each comparison must be on {@code value}, with a quoted resource id: the one sub-attribute
     * a member is identified by. A conjunction of comparisons naming the same id selects that
     * member; naming different ids, it selects none, since no member has two ids. Selecting none is
     * not an error: removing it changes nothing, as removing a non-member does (RFC 7644 §3.5.2.2).
     * A compared value that is not a resource id is refused rather than matched loosely, as it is
     * in a {@code members} value.
     */
    private static List<UUID> selectedMembers(String filter) {
        Set<UUID> named = new LinkedHashSet<>();
        for (Comparison comparison : ScimPatchPathGrammar.valueFilter(filter)) {
            if (!comparison.subAttribute().equals("value")) {
                throw ScimErrorException.invalidFilter(
                        "A members filter may compare value only.");
            }
            if (!comparison.value().isString()) {
                throw ScimErrorException.invalidFilter("value compares with a quoted string.");
            }
            named.add(memberId(comparison.value().stringValue()));
        }
        return named.size() == 1 ? List.copyOf(named) : List.of();
    }

    /**
     * The member ids a {@code members} array names, or an empty list when it is absent.
     *
     * <p>Every sub-attribute the schema declares other than {@code value} is IGNORED rather than
     * refused, because RFC 7643 makes them read-only and RFC 7644 §3.5.2 says a read-only attribute
     * in a write body is ignored — so a client may send back the {@code display}, {@code $ref} and
     * {@code type} it read. An unknown sub-attribute IS refused, for the reason an unknown
     * top-level attribute is: silently dropping it is how a connector comes to believe it stored
     * something.
     */
    private static List<UUID> readMemberIds(JsonNode members) {
        if (members == null || members.isNull()) {
            return List.of();
        }
        if (!members.isArray()) {
            throw ScimErrorException.invalidValue("members must be an array.");
        }
        List<UUID> ids = new ArrayList<>();
        for (JsonNode member : members) {
            if (!member.isObject()) {
                throw ScimErrorException.invalidValue(
                        "each members value must be a complex value.");
            }
            ids.add(memberId(ScimResourceBody.complex(member, MEMBERS).requiredString("value")));
        }
        return ids;
    }

    /** The member ids a PATCH operation's value names, which must be present and non-empty. */
    private static List<UUID> requiredMemberIds(JsonNode value) {
        if (value == null || value.isNull()) {
            throw ScimErrorException.invalidSyntax(
                    "This PATCH operation requires a members value.");
        }
        List<UUID> ids = readMemberIds(value);
        if (ids.isEmpty()) {
            throw ScimErrorException.invalidValue("A members value must name at least one member.");
        }
        return ids;
    }

    /**
     * A submitted member reference as a resource id.
     *
     * <p>A value that is not a UUID is a {@code 400 invalidValue} rather than the {@code 404} a
     * malformed id gets on a retrieval path, and the difference is deliberate: here the id is a
     * VALUE inside a write, so the request is malformed, whereas a malformed id in the URL names no
     * resource. Refused before the write rather than left to the membership foreign key, which would
     * report it as an unknown member — a different and misleading answer.
     */
    private static UUID memberId(String value) {
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException notAnId) {
            throw ScimErrorException.invalidValue("Each members value must be a resource id.");
        }
    }

    private static String requiredStringValue(JsonNode value, String attribute) {
        if (value == null || value.isNull()) {
            throw ScimErrorException.invalidSyntax(
                    "This PATCH operation requires a value for " + attribute + ".");
        }
        return ScimResourceBody.string(value, attribute);
    }
}

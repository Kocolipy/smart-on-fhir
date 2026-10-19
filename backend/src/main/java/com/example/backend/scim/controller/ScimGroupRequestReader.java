package com.example.backend.scim.controller;

import com.example.backend.scim.application.NewScimGroup;
import com.example.backend.scim.application.ScimGroupPatchOperation;
import com.example.backend.scim.application.ScimGroupReplacement;
import com.example.backend.scim.domain.ScimRequestLimits;
import com.example.backend.scim.domain.ScimResourceSchema;
import com.example.backend.scim.domain.ScimResourceType;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import tools.jackson.databind.JsonNode;

/**
 * Reads a SCIM Group write body — create, replace, or PATCH — refusing what this service does not
 * implement and ignoring what the RFC says to ignore.
 *
 * <p>Hand-read from a {@link JsonNode} for the reasons {@link ScimUserRequestReader} gives: a
 * read-only attribute in the body is ignored, an unimplemented attribute is refused rather than
 * dropped, and a wrong JSON type is a {@code 400 invalidValue} naming the attribute. None of the
 * three is expressible as a field declaration, and Boot's disabled
 * {@code FAIL_ON_UNKNOWN_PROPERTIES} gives the middle one the opposite default.
 *
 * <p>No message produced here contains a submitted VALUE. Attribute paths are echoed because the
 * caller needs to know which attribute was wrong, and an attribute name is this service's own
 * vocabulary; a value could be anything.
 *
 * <h2>PATCH is narrowed to what a Group can mean</h2>
 *
 * <p>RFC 7644 §3.5.2 defines PATCH over an open-ended path grammar. A Group here has three writable
 * attributes, so the set of things a PATCH can mean is finite, and this reader's job is to turn each
 * supported request into one of {@link ScimGroupPatchOperation}'s variants and refuse the rest.
 * Refusing HERE is the point: an unsupported path is still a request at this layer, so it becomes a
 * {@code 400} the caller can act on rather than something the use case has to interpret.
 */
final class ScimGroupRequestReader {

    /** The Group schema: which attributes exist, and which of them a write may set. */
    private static final ScimResourceSchema SCHEMA = ScimResourceSchema.of(ScimResourceType.GROUP);

    /** Attributes accepted on a write, from the one definition that says what is implemented. */
    private static final Set<String> WRITABLE = SCHEMA.writableNames();

    /**
     * Read-only attributes: a resource body IGNORES them, per RFC 7644 §3.5.2, so a client that
     * round-trips a resource it read can PUT it back unchanged; a PATCH path naming one is
     * {@code mutability}. Lower-cased for the PATCH path match, which is case-insensitive.
     */
    private static final Set<String> READ_ONLY = SCHEMA.readOnlyNames();

    private static final Set<String> READ_ONLY_PATHS = READ_ONLY.stream()
            .map(name -> name.toLowerCase(Locale.ROOT))
            .collect(Collectors.toUnmodifiableSet());

    /** The core Group schema URN as a path prefix, matched case-insensitively. */
    private static final String SCHEMA_PREFIX = ScimSchemas.GROUP + ":";

    /**
     * A {@code members} value path naming one member by its id — {@code members[value eq "…"]}.
     *
     * <p>The one value-path form this service accepts, because it is the one that occurs: removing a
     * single member is how every provisioning system revokes a membership. The quoted value is
     * captured and must parse as a UUID; anything else is refused rather than matched loosely, so a
     * filter expression a connector expected to select several members cannot silently select none.
     *
     * <p>Case-insensitive on {@code value} and {@code eq}, which RFC 7644 §3.4.2.2 requires of
     * attribute names and operators alike.
     */
    private static final Pattern MEMBER_VALUE_PATH = Pattern.compile(
            "^members\\[\\s*value\\s+eq\\s+\"([^\"]*)\"\\s*]$", Pattern.CASE_INSENSITIVE);

    private enum Op { ADD, REMOVE, REPLACE }

    private ScimGroupRequestReader() {
    }

    /**
     * The create command this body describes.
     *
     * @throws ScimErrorException {@code 400} for a body that is not an object, declares the wrong
     *                            schema, omits {@code displayName}, asserts an unimplemented
     *                            attribute, or carries a value of the wrong type
     */
    static NewScimGroup readCreate(JsonNode body) {
        requireGroupBody(body);
        return new NewScimGroup(
                requiredString(body, "displayName"),
                readMemberIds(body.get("members")),
                optionalString(body, "externalId"));
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
        requireGroupBody(body);
        return new ScimGroupReplacement(
                requiredString(body, "displayName"),
                readMemberIds(body.get("members")),
                optionalString(body, "externalId"));
    }

    /**
     * The PATCH operations this body describes, in the order they must be applied.
     *
     * <p>Order is preserved because PATCH is sequential: RFC 7644 applies operations in the order
     * given, so a remove followed by an add is not the same request as the reverse. The use case
     * folds them into one desired state, which is what makes partial failure unrepresentable.
     *
     * @throws ScimErrorException {@code 400} for a body that is not a PatchOp, carries no
     *                            operations, or names a path this service does not implement
     */
    static List<ScimGroupPatchOperation> readPatch(JsonNode body) {
        if (body == null || !body.isObject()) {
            throw ScimErrorException.invalidSyntax("The request body must be a SCIM PatchOp.");
        }
        // A PatchOp that does not declare the PatchOp schema is not a PatchOp at all, so this is
        // a syntax refusal — as it is for a User — not the value refusal a resource body
        // declaring the wrong resource schema receives.
        JsonNode schemas = body.get("schemas");
        if (schemas == null || !schemas.isArray() || schemas.size() != 1
                || !ScimSchemas.PATCH_OP.equals(schemas.get(0).asText())) {
            throw ScimErrorException.invalidSyntax(
                    "A PATCH body must declare exactly the schema " + ScimSchemas.PATCH_OP);
        }
        JsonNode operations = body.get("Operations");
        if (operations == null || !operations.isArray() || operations.isEmpty()) {
            throw ScimErrorException.invalidSyntax(
                    "A PatchOp must carry a non-empty Operations array.");
        }
        if (operations.size() > ScimRequestLimits.MAX_PATCH_OPERATIONS) {
            throw ScimErrorException.invalidValue("A PatchOp may carry at most "
                    + ScimRequestLimits.MAX_PATCH_OPERATIONS + " operations.");
        }
        List<ScimGroupPatchOperation> read = new ArrayList<>(operations.size());
        for (JsonNode operation : operations) {
            if (!operation.isObject()) {
                throw ScimErrorException.invalidSyntax("Each PATCH operation must be an object.");
            }
            read.add(readOperation(operation));
        }
        return List.copyOf(read);
    }

    /**
     * One operation, resolved to the variant it means.
     *
     * <p>{@code op} is matched case-insensitively, which RFC 7644 §3.5.2 requires. An omitted
     * {@code path} means the operation applies to the resource as a whole, which this service
     * supports only for {@code replace} of the two writable attributes — a path-less {@code add}
     * would be an attribute-by-attribute merge whose semantics for a multi-valued attribute the RFC
     * leaves genuinely ambiguous, and guessing at it is worse than refusing.
     */
    private static ScimGroupPatchOperation readOperation(JsonNode operation) {
        Op op = op(operation.get("op"));
        String path = optionalString(operation, "path");
        JsonNode value = operation.get("value");

        if (path == null) {
            // RFC 7644 §3.5.2.2 names noTarget for a path-less remove. A path-less add or replace
            // is an attribute merge this service does not implement, which is a value refusal.
            if (op == Op.REMOVE) {
                throw ScimErrorException.noTarget("A remove operation requires a path.");
            }
            throw ScimErrorException.invalidValue(
                    "This service requires a path on each PATCH operation, naming displayName,"
                            + " members or externalId.");
        }

        Matcher memberValuePath = MEMBER_VALUE_PATH.matcher(path);
        if (memberValuePath.matches()) {
            if (op != Op.REMOVE) {
                throw ScimErrorException.invalidPath(
                        "A members value path is supported for remove only.");
            }
            return new ScimGroupPatchOperation.RemoveMembers(
                    List.of(memberId(memberValuePath.group(1))));
        }

        String attribute = unqualified(path).toLowerCase(Locale.ROOT);
        if (READ_ONLY_PATHS.contains(attribute)) {
            throw ScimErrorException.mutability(attribute + " is read-only.");
        }
        return switch (attribute) {
            case "displayname" -> readDisplayNameOperation(op, value);
            case "members" -> readMembersOperation(op, value);
            case "externalid" -> readExternalIdOperation(op, value);
            default -> throw ScimErrorException.invalidPath(
                    "This service does not implement the Group PATCH path: " + sanitized(path));
        };
    }

    /**
     * The operation's {@code op}, matched case-insensitively. A missing, non-string or unknown
     * {@code op} is {@code invalidSyntax}, as it is for a User: the operation is not one a PatchOp
     * can carry, so the message is malformed — not a value the Group schema refuses — and is
     * refused before its path is read, whatever the path names.
     */
    private static Op op(JsonNode op) {
        if (op == null || op.isNull()) {
            throw ScimErrorException.invalidSyntax("A required attribute is missing: op");
        }
        if (!op.isString()) {
            throw ScimErrorException.invalidSyntax("op must be a string.");
        }
        String name = op.stringValue().toLowerCase(Locale.ROOT);
        return switch (name) {
            case "add" -> Op.ADD;
            case "remove" -> Op.REMOVE;
            case "replace" -> Op.REPLACE;
            default -> throw ScimErrorException.invalidSyntax(
                    "Unsupported PATCH op: " + sanitized(name));
        };
    }

    /** A path with the core Group schema URN prefix removed, which RFC 7644 §3.10 allows. */
    private static String unqualified(String path) {
        return path.regionMatches(true, 0, SCHEMA_PREFIX, 0, SCHEMA_PREFIX.length())
                ? path.substring(SCHEMA_PREFIX.length())
                : path;
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
     * {@code members} without a value path: add to the membership, replace it wholesale, or clear
     * it.
     *
     * <p>{@code remove} with a value is read as removing exactly those members, which is the form a
     * client that cannot build a value path uses; {@code remove} with no value clears the
     * membership, as the RFC defines for a path with no filter.
     */
    private static ScimGroupPatchOperation readMembersOperation(Op op, JsonNode value) {
        return switch (op) {
            case ADD -> new ScimGroupPatchOperation.AddMembers(requiredMemberIds(value));
            case REPLACE -> new ScimGroupPatchOperation.ReplaceMembers(requiredMemberIds(value));
            case REMOVE -> value == null || value.isNull()
                    ? new ScimGroupPatchOperation.RemoveAllMembers()
                    : new ScimGroupPatchOperation.RemoveMembers(requiredMemberIds(value));
        };
    }

    /** A Group write body: an object, declaring this service's one Group schema, and nothing extra. */
    private static void requireGroupBody(JsonNode body) {
        if (body == null || !body.isObject()) {
            throw ScimErrorException.invalidSyntax("The request body must be a SCIM resource.");
        }
        requireSchema(
                body, ScimSchemas.GROUP, "This service implements one Group schema only: ");
        for (String attribute : body.propertyNames()) {
            if (WRITABLE.contains(attribute) || READ_ONLY.contains(attribute)) {
                continue;
            }
            throw ScimErrorException.invalidValue(
                    "This service does not implement the Group attribute: " + sanitized(attribute));
        }
    }

    /**
     * The body declares exactly the expected schema.
     *
     * <p>Exactly, not "contains": a body declaring an extension schema is asserting attributes this
     * service does not have, and accepting the declaration while ignoring the attributes is how a
     * connector comes to believe its extension is stored.
     */
    private static void requireSchema(JsonNode body, String expected, String refusal) {
        JsonNode schemas = body.get("schemas");
        if (schemas == null || !schemas.isArray() || schemas.isEmpty()) {
            throw ScimErrorException.invalidSyntax(
                    "A request body must declare its schemas as a non-empty array.");
        }
        if (schemas.size() != 1 || !expected.equals(schemas.get(0).asText())) {
            throw ScimErrorException.invalidValue(refusal + expected);
        }
    }

    /**
     * The member ids a {@code members} array names, or an empty list when it is absent.
     *
     * <p>Every sub-attribute other than {@code value} is IGNORED rather than refused, because RFC
     * 7643 makes them read-only and RFC 7644 §3.5.2 says a read-only attribute in a write body is
     * ignored — so a client may send back the {@code display}, {@code $ref} and {@code type} it
     * read. An unknown sub-attribute IS refused, for the reason an unknown top-level attribute is:
     * silently dropping it is how a connector comes to believe it stored something.
     */
    private static List<UUID> readMemberIds(JsonNode members) {
        if (members == null || members.isNull()) {
            return List.of();
        }
        if (!members.isArray()) {
            throw ScimErrorException.invalidValue("members must be an array.");
        }
        Set<String> declared = Set.of("value", "display", "$ref", "type");
        List<UUID> ids = new ArrayList<>(members.size());
        for (JsonNode member : members) {
            if (!member.isObject()) {
                throw ScimErrorException.invalidValue(
                        "each members value must be a complex value.");
            }
            for (String sub : member.propertyNames()) {
                if (!declared.contains(sub)) {
                    throw ScimErrorException.invalidValue(
                            "This service does not implement the members sub-attribute: "
                                    + sanitized(sub));
                }
            }
            ids.add(memberId(requiredString(member, "value")));
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

    private static String requiredString(JsonNode parent, String attribute) {
        JsonNode value = parent.get(attribute);
        if (value == null || value.isNull()) {
            throw ScimErrorException.invalidSyntax("A required attribute is missing: " + attribute);
        }
        return string(value, attribute);
    }

    private static String requiredStringValue(JsonNode value, String attribute) {
        if (value == null || value.isNull()) {
            throw ScimErrorException.invalidSyntax(
                    "This PATCH operation requires a value for " + attribute + ".");
        }
        return string(value, attribute);
    }

    private static String optionalString(JsonNode parent, String attribute) {
        JsonNode value = parent.get(attribute);
        return value == null || value.isNull() ? null : string(value, attribute);
    }

    private static String string(JsonNode value, String attribute) {
        if (!value.isString()) {
            throw ScimErrorException.invalidValue(attribute + " must be a string.");
        }
        String text = value.stringValue();
        if (text.isBlank()) {
            throw ScimErrorException.invalidValue(attribute + " must not be blank.");
        }
        return text;
    }

    /**
     * A client-supplied NAME or path, reduced to something safe to put in a response and in the log
     * line an error may produce.
     *
     * <p>Control characters and line breaks are what a crafted value would carry to forge a log
     * record (CWE-117), and the length cap keeps a megabyte-long key from being echoed at all. The
     * value is lower-cased so the echo cannot be mistaken for a canonical spelling this service
     * recognises.
     */
    private static String sanitized(String value) {
        return value.codePoints()
                .filter(codePoint -> !Character.isISOControl(codePoint))
                .limit(64)
                .collect(StringBuilder::new, StringBuilder::appendCodePoint, StringBuilder::append)
                .toString()
                .toLowerCase(Locale.ROOT);
    }
}

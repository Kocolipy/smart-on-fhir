package com.example.backend.scim.controller;

import com.example.backend.scim.controller.ScimPatchPathGrammar.Path;
import com.example.backend.scim.domain.ScimAttribute;
import com.example.backend.scim.domain.ScimRequestLimits;
import com.example.backend.scim.domain.ScimResourceSchema;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.function.Function;
import tools.jackson.databind.JsonNode;

/**
 * Reads a SCIM {@code PatchOp} body for any resource: the envelope, each operation's {@code op}
 * and {@code path}, and the classification of a path against the resource's schema. A resource
 * contributes only its schema and what each operation means for it.
 *
 * <p>The rules are the practical SCIM protocol profile's ({@code docs/domain/scim-protocol.md},
 * "PATCH paths"), and every refusal here is the same for a User and a Group:
 *
 * <ul>
 *   <li>a body that is not a {@code PatchOp}, has no operations, or an operation that is not an
 *       object or whose {@code op} is missing, not a string or not {@code add}, {@code remove} or
 *       {@code replace} — {@code invalidSyntax}. {@code op} matches regardless of case;
 *   <li>more operations than {@link ScimRequestLimits#MAX_PATCH_OPERATIONS} —
 *       {@code invalidValue};
 *   <li>a {@code path} that is not a string, or text outside {@link ScimPatchPathGrammar}'s grammar
 *       — {@code invalidPath} (RFC 7644 §3.12: "invalid or malformed");
 *   <li>a {@code remove} with no path — {@code noTarget} (RFC 7644 §3.5.2.2).
 * </ul>
 *
 * <p>No message produced here echoes a submitted value; a name or path is echoed only after
 * {@link ScimEcho#sanitized}.
 */
final class ScimPatchOpReader {

    /** A PATCH operation's {@code op}. */
    enum Op { ADD, REMOVE, REPLACE }

    /**
     * One operation as read: its {@code op}, its parsed path or {@code null} when it has none —
     * never for a {@code remove} — and its {@code value} as submitted, or {@code null}.
     */
    record Operation(Op op, Path path, JsonNode value) {
    }

    private ScimPatchOpReader() {
    }

    /**
     * What each operation of this body means for the resource, in order. Each operation is read
     * and then handed to {@code meaning} before the next is read, so the first refused operation
     * is the one the refusal names.
     *
     * @param schema the schema of the resource being patched, whose core URN may prefix a path
     * @param meaning the resource's reading of one operation, as zero or more edits
     * @throws ScimErrorException {@code 400} for every refusal listed on the class, and for any
     *     refusal {@code meaning} makes
     */
    static <T> List<T> read(
            JsonNode body, ScimResourceSchema schema, Function<Operation, List<T>> meaning) {
        if (body == null || !body.isObject()) {
            throw ScimErrorException.invalidSyntax("The request body must be a SCIM PatchOp.");
        }
        // A PatchOp that does not declare the PatchOp schema is not a PatchOp at all, so this is
        // a syntax refusal, not the value refusal a resource body declaring the wrong resource
        // schema receives.
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
        String schemaUri = schema.type().schemaUri();
        List<T> read = new ArrayList<>();
        for (JsonNode operation : operations) {
            if (!operation.isObject()) {
                throw ScimErrorException.invalidSyntax("Each PATCH operation must be an object.");
            }
            read.addAll(meaning.apply(operation(operation, schemaUri)));
        }
        return List.copyOf(read);
    }

    private static Operation operation(JsonNode operation, String schemaUri) {
        Op op = op(operation.get("op"));
        JsonNode pathNode = operation.get("path");
        if (pathNode == null || pathNode.isNull()) {
            if (op == Op.REMOVE) {
                throw ScimErrorException.noTarget("A remove operation requires a path.");
            }
            return new Operation(op, null, operation.get("value"));
        }
        if (!pathNode.isString()) {
            throw ScimErrorException.invalidPath("path must be a string.");
        }
        return new Operation(
                op, ScimPatchPathGrammar.path(pathNode.stringValue(), schemaUri),
                operation.get("value"));
    }

    /**
     * The operation's {@code op}, matched regardless of case. A missing, non-string or unknown
     * {@code op} is {@code invalidSyntax}: the operation is not one a PatchOp can carry, so the
     * message is malformed, and it is refused before its path is read, whatever the path names.
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
                    "Unsupported PATCH op: " + ScimEcho.sanitized(name));
        };
    }

    /**
     * The attribute a path names, once the schema has classified it.
     *
     * <p>The order of the refusals is the order a client can act on: a read-only attribute is
     * {@code mutability} whatever else the path says about it; a value filter on an attribute that
     * is not multi-valued, or a sub-path on one with no sub-attributes, is {@code invalidPath}; and
     * only then is an attribute the schema does not have refused as unimplemented. Names match
     * regardless of case (RFC 7643 §2.1, RFC 7644 §3.10).
     *
     * @throws ScimErrorException {@code mutability} or {@code invalidPath}, as above
     */
    static ScimAttribute target(ScimResourceSchema schema, Path path) {
        String attribute = path.attribute();
        Optional<ScimAttribute> declared = schema.find(attribute);
        if (isReadOnly(declared)) {
            throw ScimErrorException.mutability(declared.get().name() + " is read-only.");
        }
        if (path.filter() != null && !declared.map(ScimAttribute::multiValued).orElse(false)) {
            throw ScimErrorException.invalidPath(
                    "Only a multi-valued attribute accepts a value filter.");
        }
        if (path.subAttribute() != null
                && declared.map(found -> found.subAttributes().isEmpty()).orElse(true)) {
            throw ScimErrorException.invalidPath(attribute + " has no sub-attributes.");
        }
        return declared.orElseThrow(() -> ScimErrorException.invalidPath(
                "This service does not implement the " + schema.type().resourceTypeName()
                        + " attribute: " + ScimEcho.sanitized(attribute)));
    }

    /** Whether the schema declares this attribute read-only, which no write can change. */
    static boolean isReadOnly(Optional<ScimAttribute> declared) {
        return declared.map(attribute -> !attribute.isWritable()).orElse(false);
    }
}

package com.example.backend.contract;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.mock.web.MockHttpServletResponse;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The backend API contract, {@code docs/openapi.yaml}, read as data so a test can hold a real
 * response against it.
 *
 * <p>Read from the tracked file itself, not from a test-resources copy, so what is checked is what
 * is published: a drifted copy would let the check pass while the document a client reads is
 * wrong.
 *
 * <p>{@link #violations} is the contract check for one exchange. A response is in the contract
 * when the operation its request matched — or, for a refusal that happens before any operation is
 * selected, the namespace it was made in ({@code x-namespace-responses}) — documents its status;
 * when every header that documented response promises is present; when a response documented
 * without content has no body; and when a response documented with content has a documented media
 * type and a body that validates against the documented schema. A schema property marked
 * {@code writeOnly} present in a response is a violation in its own right, because it is how a
 * password would leak.
 *
 * <p>The schema check implements the subset of JSON Schema this document uses — {@code $ref},
 * {@code type} (single or a list), {@code properties}, {@code required},
 * {@code additionalProperties}, {@code items}, {@code enum}, {@code minItems}, {@code maxItems},
 * {@code minLength}, {@code maxLength}, {@code minimum}, {@code maximum}, {@code pattern}, and the
 * {@code uuid} and {@code date-time} formats. {@link #unsupportedKeywords} names any other keyword
 * the document starts using, so the subset cannot silently fall behind the document.
 */
public final class OpenApiContract {

    /** The keywords {@link #validate} understands, plus annotations that constrain nothing. */
    static final Set<String> UNDERSTOOD_SCHEMA_KEYWORDS = Set.of(
            "$ref", "type", "properties", "required", "additionalProperties", "items", "enum", "oneOf",
            "minItems", "maxItems", "minLength", "maxLength", "minimum", "maximum", "pattern",
            "format", "readOnly", "writeOnly", "description", "example", "examples", "default");

    private static final Path DOCUMENT = Path.of("docs", "openapi.yaml");

    private static final Set<String> METHODS =
            Set.of("get", "put", "post", "delete", "patch", "head", "options");

    private static final String NAMESPACE_RESPONSES = "x-namespace-responses";

    private final JsonNode root;

    private final List<Operation> operations;

    private OpenApiContract(JsonNode root) {
        this.root = root;
        List<Operation> read = new ArrayList<>();
        for (Map.Entry<String, JsonNode> path : root.get("paths").properties()) {
            for (Map.Entry<String, JsonNode> method : path.getValue().properties()) {
                if (METHODS.contains(method.getKey())) {
                    read.add(new Operation(method.getKey().toUpperCase(Locale.ROOT),
                            path.getKey(), method.getValue()));
                }
            }
        }
        this.operations = List.copyOf(read);
    }

    /** The published contract, read from the backend's own tracked copy. */
    public static OpenApiContract load() {
        try (InputStream in = Files.newInputStream(DOCUMENT)) {
            Object yaml = new Yaml(new SafeConstructor(new LoaderOptions())).load(in);
            return new OpenApiContract(JsonMapper.builder().build().valueToTree(yaml));
        } catch (IOException unreadable) {
            throw new IllegalStateException("Cannot read " + DOCUMENT.toAbsolutePath(), unreadable);
        }
    }

    /** Every documented operation. */
    public List<Operation> operations() {
        return operations;
    }

    /**
     * The operation a request reaches: the documented path template matching it with the most
     * literal segments — so {@code /Users/.search} is the search, not a User whose id is
     * {@code .search} — and that template's operation for the method.
     */
    public Optional<Operation> operation(String method, String requestPath) {
        return template(requestPath).flatMap(template -> operations.stream()
                .filter(operation -> operation.template().equals(template))
                .filter(operation -> operation.method().equalsIgnoreCase(method))
                .findFirst());
    }

    /** The documented path template a concrete path matches, most specific first. */
    public Optional<String> template(String requestPath) {
        return operations.stream()
                .map(Operation::template)
                .distinct()
                .filter(template -> matches(template, requestPath))
                .max(Comparator.comparingLong(OpenApiContract::literalSegments));
    }

    /** Every documented {@code (namespace, status)} pair: refusals made before an operation. */
    public Set<String> namespaceStatuses() {
        Set<String> statuses = new LinkedHashSet<>();
        JsonNode namespaces = root.get(NAMESPACE_RESPONSES);
        if (namespaces != null) {
            for (Map.Entry<String, JsonNode> namespace : namespaces.properties()) {
                for (String status : namespace.getValue().propertyNames()) {
                    statuses.add(namespaceKey(namespace.getKey(), Integer.parseInt(status)));
                }
            }
        }
        return statuses;
    }

    /** The coverage key for an operation's documented status. */
    public static String operationKey(Operation operation, int status) {
        return operation.method() + " " + operation.template() + " " + status;
    }

    /** The coverage key for a namespace-level documented status. */
    public static String namespaceKey(String namespace, int status) {
        return "ANY " + namespace + "/** " + status;
    }

    /**
     * Checks one exchange against the contract.
     *
     * @return the coverage key the exchange observed (empty when its status is undocumented) and
     *         every way it departs from the document; no violations means it conforms
     */
    public Verdict check(String method, String requestPath, MockHttpServletResponse response) {
        int status = response.getStatus();
        List<String> violations = new ArrayList<>();
        Optional<Operation> operation = operation(method, requestPath);
        JsonNode definition = operation.map(found -> found.response(status)).orElse(null);
        String key = definition == null ? null : operationKey(operation.get(), status);
        if (definition == null) {
            Optional<Map.Entry<String, JsonNode>> namespace = namespaceResponse(requestPath, status);
            if (namespace.isPresent()) {
                definition = namespace.get().getValue();
                key = namespaceKey(namespace.get().getKey(), status);
            }
        }
        String exchange = method + " " + requestPath + " -> " + status;
        if (definition == null) {
            violations.add(exchange + ": " + operation
                    .map(found -> "status not documented for " + found.method() + " "
                            + found.template())
                    .orElse("no documented operation and no namespace response for it"));
            return new Verdict(Optional.empty(), violations);
        }
        definition = resolve(definition);
        checkHeaders(exchange, definition, response, violations);
        checkBody(exchange, definition, response, violations);
        return new Verdict(Optional.of(key), violations);
    }

    private Optional<Map.Entry<String, JsonNode>> namespaceResponse(String requestPath, int status) {
        JsonNode namespaces = root.get(NAMESPACE_RESPONSES);
        if (namespaces == null) {
            return Optional.empty();
        }
        for (Map.Entry<String, JsonNode> namespace : namespaces.properties()) {
            String prefix = namespace.getKey();
            boolean inside = requestPath.equals(prefix) || requestPath.startsWith(prefix + "/");
            JsonNode response = namespace.getValue().get(String.valueOf(status));
            if (inside && response != null) {
                return Optional.of(Map.entry(prefix, response));
            }
        }
        return Optional.empty();
    }

    private void checkHeaders(String exchange, JsonNode definition,
            MockHttpServletResponse response, List<String> violations) {
        JsonNode headers = definition.get("headers");
        if (headers == null) {
            return;
        }
        for (String header : headers.propertyNames()) {
            if (response.getHeader(header) == null) {
                violations.add(exchange + ": documented header " + header + " is absent");
            }
        }
    }

    private void checkBody(String exchange, JsonNode definition,
            MockHttpServletResponse response, List<String> violations) {
        byte[] body = response.getContentAsByteArray();
        JsonNode content = definition.get("content");
        if (content == null) {
            if (body.length > 0) {
                violations.add(exchange + ": documented with no body, but one was sent");
            }
            return;
        }
        String contentType = response.getContentType();
        String mediaType = contentType == null
                ? null
                : contentType.split(";", 2)[0].strip().toLowerCase(Locale.ROOT);
        JsonNode media = mediaType == null ? null : content.get(mediaType);
        if (media == null) {
            violations.add(exchange + ": Content-Type " + contentType + " is not one of "
                    + content.propertyNames());
            return;
        }
        JsonNode schema = media.get("schema");
        if (schema == null || !mediaType.contains("json")) {
            // A non-JSON representation (the Prometheus text exposition) is checked for its
            // documented media type only; its schema is a string, which any body is.
            return;
        }
        JsonNode parsed;
        try {
            parsed = JsonMapper.builder().build().readTree(body);
        } catch (RuntimeException notJson) {
            violations.add(exchange + ": body is not JSON");
            return;
        }
        for (String violation : validate(schema, parsed, "$")) {
            violations.add(exchange + ": " + violation);
        }
    }

    /** Every way {@code value} departs from {@code schema}; empty means it conforms. */
    public List<String> validate(JsonNode schema, JsonNode value, String at) {
        List<String> violations = new ArrayList<>();
        validateInto(resolve(schema), value, at, violations);
        return violations;
    }

    private void validateInto(JsonNode schema, JsonNode value, String at, List<String> out) {
        schema = resolve(schema);
        JsonNode oneOf = schema.get("oneOf");
        if (oneOf != null) {
            validateOneOf(oneOf, value, at, out);
        }
        JsonNode type = schema.get("type");
        if (type != null && !typeMatches(type, value)) {
            out.add(at + ": expected type " + type + " but was " + value.getNodeType());
            return;
        }
        JsonNode allowed = schema.get("enum");
        if (allowed != null && allowed.valueStream().noneMatch(value::equals)) {
            out.add(at + ": " + value + " is not one of " + allowed);
        }
        if (value.isString()) {
            validateString(schema, value.stringValue(), at, out);
        }
        if (value.isNumber()) {
            validateNumber(schema, value, at, out);
        }
        if (value.isArray()) {
            validateArray(schema, value, at, out);
        }
        if (value.isObject()) {
            validateObject(schema, value, at, out);
        }
    }

    /** {@code oneOf}: the value conforms to exactly one branch, as JSON Schema defines it. */
    private void validateOneOf(JsonNode branches, JsonNode value, String at, List<String> out) {
        int matched = 0;
        for (JsonNode branch : branches) {
            List<String> departures = new ArrayList<>();
            validateInto(branch, value, at, departures);
            if (departures.isEmpty()) {
                matched++;
            }
        }
        if (matched != 1) {
            out.add(at + ": matches " + matched + " oneOf branches, not exactly 1");
        }
    }

    private static boolean typeMatches(JsonNode type, JsonNode value) {
        if (type.isArray()) {
            return type.valueStream().anyMatch(one -> typeMatches(one, value));
        }
        return switch (type.stringValue()) {
            case "string" -> value.isString();
            case "integer" -> value.isIntegralNumber();
            case "number" -> value.isNumber();
            case "boolean" -> value.isBoolean();
            case "array" -> value.isArray();
            case "object" -> value.isObject();
            case "null" -> value.isNull();
            default -> throw new IllegalStateException("Unknown schema type " + type);
        };
    }

    private static void validateString(JsonNode schema, String value, String at, List<String> out) {
        // JSON Schema measures a string in code points, not UTF-16 units; the document's SCIM
        // maxLength values are stated in code points because that is what the columns count.
        int length = value.codePointCount(0, value.length());
        JsonNode min = schema.get("minLength");
        if (min != null && length < min.intValue()) {
            out.add(at + ": shorter than minLength " + min);
        }
        JsonNode max = schema.get("maxLength");
        if (max != null && length > max.intValue()) {
            out.add(at + ": longer than maxLength " + max);
        }
        JsonNode pattern = schema.get("pattern");
        if (pattern != null && !Pattern.compile(pattern.stringValue()).matcher(value).find()) {
            out.add(at + ": does not match pattern " + pattern);
        }
        JsonNode format = schema.get("format");
        if (format == null) {
            return;
        }
        try {
            switch (format.stringValue()) {
                case "uuid" -> UUID.fromString(value);
                case "date-time" -> OffsetDateTime.parse(value);
                default -> {
                    // int32, int64, password: annotations a string value has nothing to obey.
                }
            }
        } catch (IllegalArgumentException | DateTimeParseException malformed) {
            out.add(at + ": not a valid " + format.stringValue());
        }
    }

    private static void validateNumber(JsonNode schema, JsonNode value, String at, List<String> out) {
        JsonNode min = schema.get("minimum");
        if (min != null && value.doubleValue() < min.doubleValue()) {
            out.add(at + ": below minimum " + min);
        }
        JsonNode max = schema.get("maximum");
        if (max != null && value.doubleValue() > max.doubleValue()) {
            out.add(at + ": above maximum " + max);
        }
    }

    private void validateArray(JsonNode schema, JsonNode value, String at, List<String> out) {
        JsonNode min = schema.get("minItems");
        if (min != null && value.size() < min.intValue()) {
            out.add(at + ": fewer items than minItems " + min);
        }
        JsonNode max = schema.get("maxItems");
        if (max != null && value.size() > max.intValue()) {
            out.add(at + ": more items than maxItems " + max);
        }
        JsonNode items = schema.get("items");
        if (items != null) {
            for (int i = 0; i < value.size(); i++) {
                validateInto(items, value.get(i), at + "[" + i + "]", out);
            }
        }
    }

    private void validateObject(JsonNode schema, JsonNode value, String at, List<String> out) {
        JsonNode required = schema.get("required");
        if (required != null && required.isArray()) {
            for (JsonNode name : required) {
                if (!value.has(name.stringValue())) {
                    out.add(at + ": required property " + name.stringValue() + " is absent");
                }
            }
        }
        JsonNode properties = schema.get("properties");
        JsonNode additional = schema.get("additionalProperties");
        for (Map.Entry<String, JsonNode> property : value.properties()) {
            String name = property.getKey();
            String here = at + "." + name;
            JsonNode declared = properties == null ? null : properties.get(name);
            if (declared != null) {
                JsonNode resolved = resolve(declared);
                if (resolved.path("writeOnly").asBoolean(false)) {
                    out.add(here + ": a writeOnly property was returned");
                }
                validateInto(resolved, property.getValue(), here, out);
            } else if (additional != null && additional.isBoolean() && !additional.booleanValue()) {
                out.add(here + ": not a declared property, and additionalProperties is false");
            } else if (additional != null && additional.isObject()) {
                validateInto(additional, property.getValue(), here, out);
            }
        }
    }

    /** Follows a local {@code $ref} to what it names. */
    JsonNode resolve(JsonNode node) {
        JsonNode ref = node.get("$ref");
        while (ref != null) {
            String pointer = ref.stringValue();
            if (!pointer.startsWith("#/")) {
                throw new IllegalStateException("Only local references are supported: " + pointer);
            }
            node = root.at(pointer.substring(1));
            if (node.isMissingNode()) {
                throw new IllegalStateException("Dangling reference " + pointer);
            }
            ref = node.get("$ref");
        }
        return node;
    }

    /**
     * Schema keywords the document uses that {@link #validate} would silently ignore, by location.
     * Empty while the validator covers the document.
     */
    public List<String> unsupportedKeywords() {
        List<String> found = new ArrayList<>();
        collectUnsupported(root.path("components").path("schemas"), "#/components/schemas",
                true, found);
        for (Operation operation : operations) {
            for (Map.Entry<String, JsonNode> response : operation.responses().properties()) {
                JsonNode content = resolve(response.getValue()).get("content");
                if (content == null) {
                    continue;
                }
                for (Map.Entry<String, JsonNode> media : content.properties()) {
                    JsonNode schema = media.getValue().get("schema");
                    if (schema != null) {
                        collectUnsupported(schema, operation.method() + " "
                                + operation.template() + " " + response.getKey(), false, found);
                    }
                }
            }
        }
        return found;
    }

    private static void collectUnsupported(JsonNode node, String at, boolean isSchemaMap,
            List<String> found) {
        if (isSchemaMap) {
            for (Map.Entry<String, JsonNode> named : node.properties()) {
                collectUnsupported(named.getValue(), at + "/" + named.getKey(), false, found);
            }
            return;
        }
        for (Map.Entry<String, JsonNode> keyword : node.properties()) {
            String name = keyword.getKey();
            if (!UNDERSTOOD_SCHEMA_KEYWORDS.contains(name)) {
                found.add(at + ": " + name);
            }
            switch (name) {
                case "properties" -> collectUnsupported(keyword.getValue(), at + "/properties",
                        true, found);
                case "items" -> collectUnsupported(keyword.getValue(), at + "/items", false, found);
                case "oneOf" -> {
                    for (int i = 0; i < keyword.getValue().size(); i++) {
                        collectUnsupported(keyword.getValue().get(i), at + "/oneOf/" + i, false,
                                found);
                    }
                }
                case "additionalProperties" -> {
                    if (keyword.getValue().isObject()) {
                        collectUnsupported(keyword.getValue(), at + "/additionalProperties",
                                false, found);
                    }
                }
                default -> {
                    // A leaf keyword: nothing nested to inspect.
                }
            }
        }
    }

    private static boolean matches(String template, String requestPath) {
        String[] expected = template.split("/", -1);
        String[] actual = requestPath.split("/", -1);
        if (expected.length != actual.length) {
            return false;
        }
        for (int i = 0; i < expected.length; i++) {
            boolean variable = expected[i].startsWith("{") && expected[i].endsWith("}");
            if (variable ? actual[i].isEmpty() : !expected[i].equals(actual[i])) {
                return false;
            }
        }
        return true;
    }

    private static long literalSegments(String template) {
        return java.util.Arrays.stream(template.split("/"))
                .filter(segment -> !segment.startsWith("{"))
                .count();
    }

    /** The scope of {@code sessionCookie} that names the Superuser rather than a Permission. */
    static final String SUPERUSER_SCOPE = "superuser";

    /** One documented operation: a method on a path template. */
    public record Operation(String method, String template, JsonNode definition) {

        /**
         * What the operation's OWN {@code security} requirement says a caller needs. Read from the
         * operation alone, never inherited from the document's top-level default, because the
         * authorization contract requires every operation to declare itself: an operation that
         * says nothing is {@link Access#UNDECLARED}, which the contract refuses.
         *
         * <ul>
         *   <li>{@code security: []} — {@link Access#PUBLIC};
         *   <li>{@code - sessionCookie: []} — {@link Access#SELF_SERVICE}: authenticated, no
         *       Permission;
         *   <li>{@code - sessionCookie: [<permission>, ...]} — {@link Access#PERMISSION}: one or more
         *       Permissions in the one entry, every one of them required;
         *   <li>{@code - sessionCookie: [superuser]} — {@link Access#SUPERUSER}: the Superuser,
         *       which is not a Permission and so names none;
         *   <li>{@code - connectorBearer: []} — {@link Access#BEARER}, the SCIM chain's: a valid
         *       token and no Permission;
         *   <li>{@code - connectorBearer: [<permission>]} — {@link Access#BEARER} naming the one
         *       Permission the token needs;
         *   <li>two or more {@code - connectorBearer: [<permission>]} alternatives —
         *       {@link Access#BEARER_ANY_OF}: any one of them suffices (the base {@code /.search}).
         * </ul>
         * Anything else — a session requirement among alternatives, two Permissions in one bearer
         * requirement, the Superuser beside a Permission, a Permission named twice, an unknown
         * scheme — is {@link Access#MALFORMED}.
         */
        public Requirement requirement() {
            JsonNode security = definition.get("security");
            if (security == null) {
                return new Requirement(Access.UNDECLARED, null);
            }
            if (!security.isArray()) {
                return new Requirement(Access.MALFORMED, null);
            }
            if (security.isEmpty()) {
                return new Requirement(Access.PUBLIC, null);
            }
            if (security.size() > 1) {
                return alternatives(security);
            }
            if (security.get(0).size() != 1) {
                return new Requirement(Access.MALFORMED, null);
            }
            Map.Entry<String, JsonNode> scheme = security.get(0).properties().iterator().next();
            JsonNode scopes = scheme.getValue();
            switch (scheme.getKey()) {
                case "connectorBearer":
                    if (scopes.isEmpty()) {
                        return new Requirement(Access.BEARER, null);
                    }
                    return scopes.size() == 1 && scopes.get(0).isTextual()
                            ? new Requirement(Access.BEARER, scopes.get(0).asText())
                            : new Requirement(Access.MALFORMED, null);
                case "sessionCookie":
                    if (scopes.isEmpty()) {
                        return new Requirement(Access.SELF_SERVICE, null);
                    }
                    if (scopes.size() == 1 && SUPERUSER_SCOPE.equals(scopes.get(0).asText(null))) {
                        return new Requirement(Access.SUPERUSER, null);
                    }
                    return permissions(scopes);
                default:
                    return new Requirement(Access.MALFORMED, null);
            }
        }

        /**
         * The Permissions one {@code sessionCookie} entry names, every one required: each a
         * string, none twice, and never the Superuser beside them.
         */
        private static Requirement permissions(JsonNode scopes) {
            List<String> allOf = new ArrayList<>();
            for (JsonNode scope : scopes) {
                if (!scope.isTextual() || SUPERUSER_SCOPE.equals(scope.asText())
                        || allOf.contains(scope.asText())) {
                    return new Requirement(Access.MALFORMED, null);
                }
                allOf.add(scope.asText());
            }
            return new Requirement(Access.PERMISSION, null, List.of(), allOf);
        }

        /** Alternatives, each one {@code connectorBearer} naming exactly one Permission. */
        private static Requirement alternatives(JsonNode security) {
            List<String> anyOf = new ArrayList<>();
            for (JsonNode alternative : security) {
                JsonNode scopes = alternative.get("connectorBearer");
                if (alternative.size() != 1 || scopes == null || scopes.size() != 1
                        || !scopes.get(0).isTextual()) {
                    return new Requirement(Access.MALFORMED, null);
                }
                anyOf.add(scopes.get(0).asText());
            }
            return new Requirement(Access.BEARER_ANY_OF, null, List.copyOf(anyOf));
        }

        /** The statuses this operation documents. */
        public Set<Integer> statuses() {
            Set<Integer> statuses = new LinkedHashSet<>();
            for (String status : responses().propertyNames()) {
                statuses.add(Integer.parseInt(status));
            }
            return statuses;
        }

        JsonNode responses() {
            return definition.get("responses");
        }

        JsonNode response(int status) {
            return responses().get(String.valueOf(status));
        }

        @Override
        public String toString() {
            return method + " " + template;
        }
    }

    /** How an operation's {@code security} requirement classifies it. */
    public enum Access {
        PUBLIC, SELF_SERVICE, PERMISSION, SUPERUSER, BEARER, BEARER_ANY_OF, UNDECLARED, MALFORMED
    }

    /**
     * An operation's declared requirement.
     *
     * @param permission the one Permission a {@link Access#BEARER} operation needs beyond a valid
     *                   token, as spelled in the document; {@code null} for every other access,
     *                   and for a bearer operation needing a valid token alone
     * @param anyOf      the alternatives of a {@link Access#BEARER_ANY_OF} operation; empty
     *                   otherwise
     * @param allOf      the Permissions a {@link Access#PERMISSION} operation names, in the
     *                   document's order, every one of them required; empty otherwise
     */
    public record Requirement(
            Access access, String permission, List<String> anyOf, List<String> allOf) {

        public Requirement {
            anyOf = anyOf == null ? List.of() : List.copyOf(anyOf);
            allOf = allOf == null ? List.of() : List.copyOf(allOf);
        }

        public Requirement(Access access, String permission) {
            this(access, permission, List.of(), List.of());
        }

        public Requirement(Access access, String permission, List<String> anyOf) {
            this(access, permission, anyOf, List.of());
        }
    }

    /** The outcome of checking one exchange: what it covered, and how it departed. */
    public record Verdict(Optional<String> covered, List<String> violations) {
    }
}

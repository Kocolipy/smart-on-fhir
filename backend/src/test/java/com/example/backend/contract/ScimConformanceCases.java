package com.example.backend.contract;

import static com.example.backend.contract.ScimConformanceFixtureTests.BASE;
import static com.example.backend.contract.ScimConformanceFixtureTests.ERROR_SCHEMA;
import static com.example.backend.contract.ScimConformanceFixtureTests.GROUP_SCHEMA;
import static com.example.backend.contract.ScimConformanceFixtureTests.LIST_SCHEMA;
import static com.example.backend.contract.ScimConformanceFixtureTests.ONE_MIB;
import static com.example.backend.contract.ScimConformanceFixtureTests.SCIM_JSON;
import static com.example.backend.contract.ScimConformanceFixtureTests.SEARCH_REQUEST;
import static com.example.backend.contract.ScimConformanceFixtureTests.SPC_SCHEMA;
import static com.example.backend.contract.ScimConformanceFixtureTests.USER_SCHEMA;
import static com.example.backend.contract.ScimConformanceFixtureTests.body;
import static com.example.backend.contract.ScimConformanceFixtureTests.json;
import static com.example.backend.contract.ScimConformanceFixtureTests.listed;
import static com.example.backend.contract.ScimConformanceFixtureTests.name;
import static com.example.backend.contract.ScimConformanceFixtureTests.names;
import static com.example.backend.contract.ScimConformanceFixtureTests.oversized;
import static com.example.backend.contract.ScimConformanceFixtureTests.texts;
import static org.assertj.core.api.Assertions.assertThat;

import com.example.backend.ForcedDeadlock;
import com.example.backend.contract.ScimConformanceFixtureTests.Fixture;
import com.example.backend.contract.ScimConformanceFixtureTests.Kind;
import com.example.backend.contract.ScimConformanceFixtureTests.Resource;
import com.example.backend.contract.ScimConformanceFixtureTests.Step;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.UnaryOperator;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.JsonNode;

/**
 * The conformance cases {@link ScimConformanceFixtureTests} runs, grouped by what RFC 7643/7644 and the
 * errors {@code docs/openapi.yaml} documents say: discovery, the resource lifecycle, every documented error condition,
 * attribute mutability, the filter and PATCH grammars, and pagination. Each case drives a request
 * and asserts the response, so this is a library of checks rather than a fixture.
 *
 * <p>Each group takes a {@link Kind} where the claim is the same for Users and Groups, so both are
 * exercised by one definition.
 */
final class ScimConformanceCases {

    private static final List<String> DISCOVERY = List.of(
            BASE + "/ServiceProviderConfig",
            BASE + "/ResourceTypes",
            BASE + "/ResourceTypes/User",
            BASE + "/Schemas",
            BASE + "/Schemas/" + USER_SCHEMA);

    private ScimConformanceCases() {
    }

    private static void add(List<Fixture> all, String name, Step step) {
        all.add(new Fixture(name, step));
    }

    // ==== discovery (RFC 7644 §4) ==========================================================

    static void discovery(List<Fixture> all) {
        add(all, "discovery: ServiceProviderConfig advertises exactly what is implemented", t -> {
            JsonNode spc = json(t.expect(t.as(t.writeOnlyToken, HttpMethod.GET,
                    BASE + "/ServiceProviderConfig"), 200));
            assertThat(texts(spc.get("schemas"))).containsExactly(SPC_SCHEMA);
            assertThat(spc.at("/patch/supported").asBoolean()).isTrue();
            assertThat(spc.at("/bulk/supported").asBoolean()).isFalse();
            assertThat(spc.at("/bulk/maxOperations").asInt()).isZero();
            assertThat(spc.at("/bulk/maxPayloadSize").asInt()).isZero();
            assertThat(spc.at("/filter/supported").asBoolean()).isTrue();
            assertThat(spc.at("/filter/maxResults").asInt()).isEqualTo(200);
            assertThat(spc.at("/changePassword/supported").asBoolean()).isTrue();
            assertThat(spc.at("/sort/supported").asBoolean()).isTrue();
            assertThat(spc.at("/etag/supported").asBoolean()).isTrue();
            assertThat(spc.get("authenticationSchemes")).hasSize(1);
            assertThat(spc.at("/authenticationSchemes/0/type").asText())
                    .isEqualTo("oauthbearertoken");
        });

        add(all, "discovery: ResourceTypes lists User and Group with their endpoints", t -> {
            JsonNode list = json(t.expect(t.as(t.writeOnlyToken, HttpMethod.GET, BASE + "/ResourceTypes"),
                    200));
            assertThat(texts(list.get("schemas"))).containsExactly(LIST_SCHEMA);
            assertThat(list.get("totalResults").asInt()).isEqualTo(2);
            assertThat(listed(list, "id")).containsExactlyInAnyOrder("User", "Group");
            assertThat(listed(list, "endpoint")).containsExactlyInAnyOrder("/Users", "/Groups");
            assertThat(listed(list, "schema")).containsExactlyInAnyOrder(USER_SCHEMA, GROUP_SCHEMA);
        });

        for (Kind kind : Kind.values()) {
            add(all, "discovery: ResourceTypes/" + kind.resourceType() + " is retrievable", t -> {
                JsonNode type = json(t.expect(t.as(t.writeOnlyToken, HttpMethod.GET,
                        BASE + "/ResourceTypes/" + kind.resourceType()), 200));
                assertThat(type.get("id").asText()).isEqualTo(kind.resourceType());
                assertThat(type.get("schema").asText()).isEqualTo(kind.schema);
                assertThat(type.get("endpoint").asText()).isEqualTo("/" + kind.endpoint);
            });
            add(all, "discovery: Schemas/" + kind.resourceType() + " is retrievable by its URI",
                    t -> {
                        JsonNode schema = json(t.expect(t.as(t.writeOnlyToken, HttpMethod.GET,
                                BASE + "/Schemas/" + kind.schema), 200));
                        assertThat(schema.get("id").asText()).isEqualTo(kind.schema);
                        assertThat(schema.get("attributes").isArray()).isTrue();
                    });
            add(all, "discovery: a schema URI is compared exactly (" + kind.resourceType() + ")",
                    t -> t.expectError(t.as(t.writeOnlyToken, HttpMethod.GET,
                            BASE + "/Schemas/" + kind.schema.toUpperCase()), 404, null));
        }

        add(all, "discovery: Schemas lists the User and Group schemas and nothing else", t -> {
            JsonNode list = json(t.expect(t.as(t.writeOnlyToken, HttpMethod.GET, BASE + "/Schemas"), 200));
            assertThat(listed(list, "id")).containsExactlyInAnyOrder(USER_SCHEMA, GROUP_SCHEMA);
        });

        add(all, "discovery: the User schema advertises exactly the implemented attributes", t -> {
            JsonNode schema = json(t.expect(t.as(t.writeOnlyToken, HttpMethod.GET,
                    BASE + "/Schemas/" + USER_SCHEMA), 200));
            Map<String, JsonNode> attributes = byName(schema.get("attributes"));
            assertThat(attributes.keySet()).containsExactlyInAnyOrder(
                    "userName", "name", "displayName", "preferredLanguage", "locale", "timezone",
                    "active", "password", "emails", "groups");
            JsonNode password = attributes.get("password");
            assertThat(password.get("mutability").asText()).isEqualTo("writeOnly");
            assertThat(password.get("returned").asText()).isEqualTo("never");
            assertThat(attributes.get("groups").get("mutability").asText()).isEqualTo("readOnly");
            assertThat(attributes.get("userName").get("required").asBoolean()).isTrue();
            assertThat(attributes.get("userName").get("caseExact").asBoolean()).isFalse();
            assertThat(attributes.get("displayName").get("caseExact").asBoolean()).isFalse();
            assertThat(subAttributes(attributes.get("emails")).get("type").get("canonicalValues"))
                    .hasSize(3);
            assertThat(subAttributes(attributes.get("groups")).get("$ref").get("referenceTypes")
                    .get(0).asText()).isEqualTo("Group");
        });

        add(all, "discovery: the Group schema advertises exactly the implemented attributes", t -> {
            JsonNode schema = json(t.expect(t.as(t.writeOnlyToken, HttpMethod.GET,
                    BASE + "/Schemas/" + GROUP_SCHEMA), 200));
            Map<String, JsonNode> attributes = byName(schema.get("attributes"));
            assertThat(attributes.keySet()).containsExactlyInAnyOrder("displayName", "members");
            assertThat(attributes.get("displayName").get("required").asBoolean()).isTrue();
        });

        add(all, "discovery: unknown resource type and unknown schema are 404", t -> {
            t.expectError(t.as(t.writeOnlyToken, HttpMethod.GET, BASE + "/ResourceTypes/Device"), 404, null);
            t.expectError(t.as(t.writeOnlyToken, HttpMethod.GET,
                    BASE + "/Schemas/urn:ietf:params:scim:schemas:extension:enterprise:2.0:User"),
                    404, null);
        });

        for (String path : DISCOVERY) {
            add(all, "discovery: a filter on " + path.substring(BASE.length()) + " is 403", t ->
                    t.expectError(t.as(t.writeOnlyToken, HttpMethod.GET, path)
                            .param("filter", "id eq \"User\""), 403, null));
            add(all, "discovery: an invalid token on " + path.substring(BASE.length())
                    + " is 401", t -> {
                        MvcResult refused = t.expect(t.as("not-a-token", HttpMethod.GET, path),
                                401);
                        assertThat(refused.getResponse().getHeader(HttpHeaders.WWW_AUTHENTICATE))
                                .isEqualTo("Bearer error=\"invalid_token\"");
                        assertThat(refused.getResponse().getContentAsByteArray()).isEmpty();
                    });
        }

        add(all, "discovery: paging parameters are ignored, as RFC 7644 §4 requires", t -> {
            JsonNode plain = json(t.expect(t.as(t.writeOnlyToken, HttpMethod.GET, BASE + "/ResourceTypes"),
                    200));
            JsonNode paged = json(t.expect(t.as(t.writeOnlyToken, HttpMethod.GET, BASE + "/ResourceTypes")
                    .param("startIndex", "2").param("count", "0"), 200));
            assertThat(paged.get("totalResults")).isEqualTo(plain.get("totalResults"));
            assertThat(paged.get("Resources")).isEqualTo(plain.get("Resources"));
        });

        // ADR 0015: discovery needs a valid token and no Permission. Every discovery case here
        // reads with a token holding the write Permissions alone, so none of them needs a read.
        add(all, "discovery: needs a token", t -> {
            MvcResult refused = t.expect(
                    t.as(null, HttpMethod.GET, BASE + "/ServiceProviderConfig"), 401);
            assertThat(refused.getResponse().getHeader(HttpHeaders.WWW_AUTHENTICATE))
                    .isEqualTo("Bearer");
            assertThat(refused.getResponse().getContentAsByteArray()).isEmpty();
        });

        // RFC 7643 §3.1: meta.location is the URI of the resource — absolute, and the URL it is
        // served at, on the list responses as well as the by-id ones.
        for (String path : List.of(
                BASE + "/ServiceProviderConfig",
                BASE + "/ResourceTypes/User",
                BASE + "/ResourceTypes/Group",
                BASE + "/Schemas/" + USER_SCHEMA,
                BASE + "/Schemas/" + GROUP_SCHEMA)) {
            add(all, "discovery: " + path.substring(BASE.length())
                    + " is located at its absolute URL", t -> {
                        MvcResult result = t.expect(t.as(t.writeOnlyToken, HttpMethod.GET, path), 200);
                        assertThat(json(result).at("/meta/location").asText())
                                .startsWith("http")
                                .isEqualTo(result.getRequest().getRequestURL().toString());
                    });
        }
        for (String path : List.of(BASE + "/ResourceTypes", BASE + "/Schemas")) {
            add(all, "discovery: every resource listed by " + path.substring(BASE.length())
                    + " is located at its absolute by-id URL", t -> {
                        MvcResult result = t.expect(t.as(t.writeOnlyToken, HttpMethod.GET, path), 200);
                        String collection = result.getRequest().getRequestURL().toString();
                        for (JsonNode resource : json(result).get("Resources")) {
                            assertThat(resource.at("/meta/location").asText())
                                    .isEqualTo(collection + "/" + resource.get("id").asText());
                        }
                    });
        }
    }

    private static Map<String, JsonNode> byName(JsonNode attributes) {
        Map<String, JsonNode> byName = new java.util.LinkedHashMap<>();
        for (JsonNode attribute : attributes) {
            byName.put(attribute.get("name").asText(), attribute);
        }
        return byName;
    }

    private static Map<String, JsonNode> subAttributes(JsonNode attribute) {
        return byName(attribute.get("subAttributes"));
    }

    // ==== lifecycle (RFC 7644 §3.3–§3.6) ===================================================

    static void lifecycle(List<Fixture> all, Kind kind) {
        String k = kind.resourceType();

        add(all, k + " create: 201 with Location, strong ETag and the canonical resource", t -> {
            String name = name();
            MvcResult result = t.expect(body(t.scim(HttpMethod.POST, kind.collection()),
                    kind.create(name)), 201);
            JsonNode created = json(result);
            t.track(UUID.fromString(created.get("id").asText()));
            assertThat(result.getResponse().getContentType()).startsWith("application/scim+json");
            assertThat(texts(created.get("schemas"))).containsExactly(kind.schema);
            assertThat(created.get(kind.identifying).asText()).isEqualTo(name);
            String etag = result.getResponse().getHeader(HttpHeaders.ETAG);
            assertThat(etag).as("a strong validator").startsWith("\"").doesNotStartWith("W/");
            assertThat(created.at("/meta/version").asText()).isEqualTo(etag);
            assertThat(created.at("/meta/resourceType").asText()).isEqualTo(k);
            assertThat(result.getResponse().getHeader(HttpHeaders.LOCATION))
                    .isEqualTo(created.at("/meta/location").asText())
                    .endsWith(kind.one(created.get("id").asText()));
            assertThat(created.at("/meta/created").asText()).endsWith("Z");
        });

        add(all, k + " read: GET by id returns the resource, its ETag and Location", t -> {
            Resource resource = t.create(kind);
            MvcResult read = t.expect(t.scim(HttpMethod.GET, kind.one(resource.id())), 200);
            assertThat(read.getResponse().getHeader(HttpHeaders.ETAG)).isEqualTo(resource.etag());
            assertThat(json(read)).isEqualTo(resource.body());
        });

        add(all, k + " read: application/json is accepted for a request and a response", t -> {
            Resource resource = t.create(kind);
            t.expect(t.scim(HttpMethod.GET, kind.one(resource.id()))
                    .accept(MediaType.APPLICATION_JSON), 200);
            Resource second = t.create(kind);
            t.expect(t.scim(HttpMethod.PATCH, kind.one(second.id()))
                    .header(HttpHeaders.IF_MATCH, second.etag())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(kind.patchRename(name())), 200);
        });

        add(all, k + " query: the collection lists a created resource", t -> {
            Resource resource = t.create(kind);
            String name = resource.body().get(kind.identifying).asText();
            JsonNode list = json(t.expect(t.scim(HttpMethod.GET, kind.collection())
                    .param("filter", kind.identifying + " eq \"" + name + "\""), 200));
            assertThat(texts(list.get("schemas"))).containsExactly(LIST_SCHEMA);
            assertThat(list.get("totalResults").asInt()).isEqualTo(1);
            assertThat(listed(list, "id")).containsExactly(resource.id().toString());
        });

        add(all, k + " query: POST .search returns what the GET query returns", t -> {
            Resource resource = t.create(kind);
            String filter = "id eq \"" + resource.id() + "\"";
            JsonNode got = json(t.expect(t.scim(HttpMethod.GET, kind.collection())
                    .param("filter", filter), 200));
            JsonNode searched = json(t.expect(body(t.scim(HttpMethod.POST,
                    kind.collection() + "/.search"), """
                    {"schemas":["%s"],"filter":"id eq \\"%s\\""}"""
                    .formatted(SEARCH_REQUEST, resource.id())), 200));
            assertThat(searched).isEqualTo(got);
        });

        add(all, k + " query: a read-only token may read and search", t -> {
            Resource resource = t.create(kind);
            t.expect(t.as(t.readOnlyToken, HttpMethod.GET, kind.one(resource.id())), 200);
            t.expect(t.as(t.readOnlyToken, HttpMethod.GET, kind.collection())
                    .param("count", "1"), 200);
            t.expect(body(t.as(t.readOnlyToken, HttpMethod.POST, kind.collection() + "/.search"),
                    "{\"schemas\":[\"" + SEARCH_REQUEST + "\"],\"count\":1}"), 200);
        });

        add(all, k + " replace: PUT returns 200, a new ETag and the replacement", t -> {
            Resource resource = t.create(kind);
            String renamed = name();
            MvcResult result = t.expect(body(t.scim(HttpMethod.PUT, kind.one(resource.id()))
                    .header(HttpHeaders.IF_MATCH, resource.etag()), kind.replace(renamed)), 200);
            JsonNode replaced = json(result);
            assertThat(replaced.get(kind.identifying).asText()).isEqualTo(renamed);
            assertThat(replaced.get("id").asText()).isEqualTo(resource.id().toString());
            String etag = result.getResponse().getHeader(HttpHeaders.ETAG);
            assertThat(etag).isNotEqualTo(resource.etag())
                    .isEqualTo(replaced.at("/meta/version").asText());
            assertThat(result.getResponse().getHeader(HttpHeaders.LOCATION))
                    .endsWith(kind.one(resource.id()));
        });

        add(all, k + " patch: PATCH returns 200, a new ETag and the patched resource", t -> {
            Resource resource = t.create(kind);
            String value = name();
            MvcResult result = t.expect(body(t.scim(HttpMethod.PATCH, kind.one(resource.id()))
                    .header(HttpHeaders.IF_MATCH, resource.etag()), kind.patchRename(value)), 200);
            JsonNode patched = json(result);
            assertThat(patched.get("displayName").asText()).isEqualTo(value);
            assertThat(result.getResponse().getHeader(HttpHeaders.ETAG))
                    .isNotEqualTo(resource.etag())
                    .isEqualTo(patched.at("/meta/version").asText());
        });

        add(all, k + " patch: a no-op PATCH changes neither version nor lastModified", t -> {
            Resource resource = t.create(kind);
            String current = resource.body().get("displayName").asText();
            MvcResult result = t.expect(body(t.scim(HttpMethod.PATCH, kind.one(resource.id()))
                    .header(HttpHeaders.IF_MATCH, resource.etag()),
                    kind.patchRename(current)), 200);
            assertThat(result.getResponse().getHeader(HttpHeaders.ETAG)).isEqualTo(resource.etag());
            assertThat(json(result).at("/meta/lastModified"))
                    .isEqualTo(resource.body().at("/meta/lastModified"));
        });

        add(all, k + " delete: 204 with no body, then 404 for every operation on the id", t -> {
            Resource resource = t.create(kind);
            MvcResult deleted = t.expect(t.scim(HttpMethod.DELETE, kind.one(resource.id()))
                    .header(HttpHeaders.IF_MATCH, resource.etag()), 204);
            assertThat(deleted.getResponse().getContentAsByteArray()).isEmpty();
            String one = kind.one(resource.id());
            t.expectError(t.scim(HttpMethod.GET, one), 404, null);
            t.expectError(body(t.scim(HttpMethod.PUT, one).header(HttpHeaders.IF_MATCH,
                    resource.etag()), kind.replace(name())), 404, null);
            t.expectError(body(t.scim(HttpMethod.PATCH, one).header(HttpHeaders.IF_MATCH,
                    resource.etag()), kind.patchRename("x")), 404, null);
            t.expectError(t.scim(HttpMethod.DELETE, one)
                    .header(HttpHeaders.IF_MATCH, resource.etag()), 404, null);
            JsonNode listed = json(t.expect(t.scim(HttpMethod.GET, kind.collection())
                    .param("filter", "id eq \"" + resource.id() + "\""), 200));
            assertThat(listed.get("totalResults").asInt()).isZero();
        });

        add(all, k + " delete: the identifying value is reusable, the id is not reassigned", t -> {
            Resource first = t.create(kind);
            String name = first.body().get(kind.identifying).asText();
            t.expect(t.scim(HttpMethod.DELETE, kind.one(first.id()))
                    .header(HttpHeaders.IF_MATCH, first.etag()), 204);
            Resource second = t.create(kind, kind.create(name));
            assertThat(second.id()).isNotEqualTo(first.id());
            t.expectError(t.scim(HttpMethod.GET, kind.one(first.id())), 404, null);
        });
    }

    // ==== every documented error condition (docs/openapi.yaml; RFC 7644 §3.12) ============

    /** A request against one existing resource of a kind. */
    private interface Probe extends BiFunction<ScimConformanceFixtureTests, Resource,
            MockHttpServletRequestBuilder> {
    }

    /** Every operation of a kind, as the token-less request a credential refusal is made on. */
    private static Map<String, Probe> operations(Kind kind, String token) {
        Map<String, Probe> probes = new java.util.LinkedHashMap<>();
        probes.put("POST collection", (t, r) -> body(t.as(token, HttpMethod.POST,
                kind.collection()), kind.create(name())));
        probes.put("GET collection", (t, r) -> t.as(token, HttpMethod.GET, kind.collection()));
        probes.put("POST .search", (t, r) -> body(t.as(token, HttpMethod.POST,
                kind.collection() + "/.search"), "{\"schemas\":[\"" + SEARCH_REQUEST + "\"]}"));
        probes.put("GET one", (t, r) -> t.as(token, HttpMethod.GET, kind.one(r.id())));
        probes.put("PUT one", (t, r) -> body(t.as(token, HttpMethod.PUT, kind.one(r.id()))
                .header(HttpHeaders.IF_MATCH, r.etag()), kind.replace(name())));
        probes.put("PATCH one", (t, r) -> body(t.as(token, HttpMethod.PATCH, kind.one(r.id()))
                .header(HttpHeaders.IF_MATCH, r.etag()), kind.patchRename(name())));
        probes.put("DELETE one", (t, r) -> t.as(token, HttpMethod.DELETE, kind.one(r.id()))
                .header(HttpHeaders.IF_MATCH, r.etag()));
        return probes;
    }

    /** The writes an existing resource can receive, with a valid body. */
    private static Map<String, Probe> writes(Kind kind, String token) {
        Map<String, Probe> writes = new java.util.LinkedHashMap<>(operations(kind, token));
        writes.keySet().retainAll(List.of("PUT one", "PATCH one", "DELETE one"));
        return writes;
    }

    static void errors(List<Fixture> all, Kind kind) {
        String k = kind.resourceType();

        // -- 401: no credential, or one that is not accepted --
        for (Map.Entry<String, Probe> op : operations(kind, null).entrySet()) {
            add(all, k + " 401: " + op.getKey() + " with no credential", t -> {
                Resource resource = t.create(kind);
                MvcResult refused = t.expect(op.getValue().apply(t, resource), 401);
                assertThat(refused.getResponse().getHeader(HttpHeaders.WWW_AUTHENTICATE))
                        .isEqualTo("Bearer");
                assertThat(refused.getResponse().getContentAsByteArray()).isEmpty();
                t.assertUnchanged(kind, resource);
            });
        }
        for (Map.Entry<String, Probe> op : operations(kind, "not-a-real-token").entrySet()) {
            add(all, k + " 401: " + op.getKey() + " with an invalid token", t -> {
                Resource resource = t.create(kind);
                MvcResult refused = t.expect(op.getValue().apply(t, resource), 401);
                assertThat(refused.getResponse().getHeader(HttpHeaders.WWW_AUTHENTICATE))
                        .isEqualTo("Bearer error=\"invalid_token\"");
                assertThat(refused.getResponse().getContentAsByteArray()).isEmpty();
                t.assertUnchanged(kind, resource);
            });
        }

        // -- 403: a token without the type's write Permission attempting a mutation --
        add(all, k + " 403: a read-only token cannot create", t -> {
            MvcResult refused = t.expect(body(t.as(t.readOnlyToken, HttpMethod.POST,
                    kind.collection()), kind.create(name())), 403);
            assertThat(refused.getResponse().getHeader(HttpHeaders.WWW_AUTHENTICATE))
                    .isEqualTo("Bearer error=\"insufficient_scope\"");
        });
        for (String op : List.of("PUT one", "PATCH one", "DELETE one")) {
            add(all, k + " 403: a read-only token cannot " + op, t -> {
                Resource resource = t.create(kind);
                Probe probe = writes(kind, t.readOnlyToken).get(op);
                MvcResult refused = t.expect(probe.apply(t, resource), 403);
                assertThat(refused.getResponse().getHeader(HttpHeaders.WWW_AUTHENTICATE))
                        .isEqualTo("Bearer error=\"insufficient_scope\"");
                assertThat(refused.getResponse().getContentAsByteArray()).isEmpty();
                t.assertUnchanged(kind, resource);
            });
        }

        // -- 404: unknown, malformed and deleted ids, ahead of the precondition --
        for (String op : List.of("GET one", "PUT one", "PATCH one", "DELETE one")) {
            add(all, k + " 404: " + op + " on an id that never existed", t -> {
                Resource ghost = new Resource(UUID.randomUUID(), "\"1\"", null);
                t.expectError(operations(kind, t.writeToken).get(op).apply(t, ghost), 404, null);
            });
        }
        add(all, k + " 404: an id that is not a UUID", t ->
                t.expectError(t.scim(HttpMethod.GET, kind.one("not-a-uuid")), 404, null));
        add(all, k + " 404: a 404 is decided before a missing precondition", t ->
                t.expectError(body(t.scim(HttpMethod.PUT, kind.one(UUID.randomUUID())),
                        kind.replace(name())), 404, null));

        // -- 409: the identifying attribute already held by a live resource --
        add(all, k + " 409: create with a held " + kind.identifying + ", case-insensitively", t -> {
            Resource held = t.create(kind);
            String name = held.body().get(kind.identifying).asText().toUpperCase();
            t.expectError(body(t.scim(HttpMethod.POST, kind.collection()), kind.create(name)),
                    409, "uniqueness");
        });
        add(all, k + " 409: replace onto a held " + kind.identifying, t -> {
            Resource held = t.create(kind);
            Resource other = t.create(kind);
            t.expectError(body(t.scim(HttpMethod.PUT, kind.one(other.id()))
                            .header(HttpHeaders.IF_MATCH, other.etag()),
                    kind.replace(held.body().get(kind.identifying).asText())), 409, "uniqueness");
            t.assertUnchanged(kind, other);
        });
        add(all, k + " 409: patch onto a held " + kind.identifying, t -> {
            Resource held = t.create(kind);
            Resource other = t.create(kind);
            t.expectError(body(t.scim(HttpMethod.PATCH, kind.one(other.id()))
                    .header(HttpHeaders.IF_MATCH, other.etag()), Kind.patch("""
                    {"op":"replace","path":"%s","value":"%s"}""".formatted(kind.identifying,
                    held.body().get(kind.identifying).asText()))), 409, "uniqueness");
            t.assertUnchanged(kind, other);
        });

        // -- 400 invalidValue: a value longer than its stored limit, never a 409 --
        // The limits are this service's, documented in docs/openapi.yaml as maxLength since RFC
        // 7643 has no such characteristic; the refusal names the attribute and the limit.
        String overLong = "q".repeat(257);
        String overLongDetail = kind.identifying + " must be at most 256 characters long.";
        add(all, k + " 400 invalidValue: create with an over-length " + kind.identifying, t -> {
            JsonNode error = t.expectError(body(t.scim(HttpMethod.POST, kind.collection()),
                    kind.create(overLong)), 400, "invalidValue");
            assertThat(error.get("detail").asText()).isEqualTo(overLongDetail);
            assertThat(error.toString()).doesNotContain(overLong);
        });
        add(all, k + " 400 invalidValue: replace with an over-length " + kind.identifying, t -> {
            Resource resource = t.create(kind);
            JsonNode error = t.expectError(body(t.scim(HttpMethod.PUT, kind.one(resource.id()))
                    .header(HttpHeaders.IF_MATCH, resource.etag()), kind.replace(overLong)),
                    400, "invalidValue");
            assertThat(error.get("detail").asText()).isEqualTo(overLongDetail);
            t.assertUnchanged(kind, resource);
        });
        add(all, k + " 400 invalidValue: patch with an over-length " + kind.identifying, t -> {
            Resource resource = t.create(kind);
            JsonNode error = t.expectError(body(t.scim(HttpMethod.PATCH, kind.one(resource.id()))
                    .header(HttpHeaders.IF_MATCH, resource.etag()), Kind.patch("""
                    {"op":"replace","path":"%s","value":"%s"}""".formatted(kind.identifying,
                    overLong))), 400, "invalidValue");
            assertThat(error.get("detail").asText()).isEqualTo(overLongDetail);
            t.assertUnchanged(kind, resource);
        });
        add(all, k + " 400 invalidValue: create with an over-length externalId", t -> {
            String body = kind.create(name());
            JsonNode error = t.expectError(body(t.scim(HttpMethod.POST, kind.collection()),
                    body.substring(0, body.length() - 1) + ",\"externalId\":\"" + overLong
                            + "\"}"), 400, "invalidValue");
            assertThat(error.get("detail").asText())
                    .isEqualTo("externalId must be at most 256 characters long.");
        });
        add(all, k + " 201: a " + kind.identifying + " at its 256-character limit is stored", t -> {
            String prefix = name();
            String longest = prefix + "x".repeat(256 - prefix.length());
            // The replacement body, which carries no displayName derived from the name.
            Resource resource = t.create(kind, kind.replace(longest));
            assertThat(resource.body().get(kind.identifying).asText()).isEqualTo(longest);
        });

        // -- 400 invalidValue: a character the column or the name refuses, never a 500 --
        // U+0000 is refused in every stored string, as input hygiene (ADR 0013); a name also
        // refuses every other C0 control and DEL. The values are JSON escapes, so the body is
        // what a connector actually sends, and the refusal never echoes them.
        String nul = name() + "\\" + "u0000x";
        String newline = name() + "\\" + "nforged";
        String controlDetail = kind.identifying
                + " must not contain control characters (U+0000 to U+001F, U+007F).";
        add(all, k + " 400 invalidValue: create with a NUL in " + kind.identifying, t -> {
            JsonNode error = t.expectError(body(t.scim(HttpMethod.POST, kind.collection()),
                    kind.create(nul)), 400, "invalidValue");
            // A name refuses every control, so it is named for the wider rule.
            assertThat(error.get("detail").asText()).isEqualTo(controlDetail);
        });
        add(all, k + " 400 invalidValue: patch a newline into " + kind.identifying, t -> {
            Resource resource = t.create(kind);
            JsonNode error = t.expectError(body(t.scim(HttpMethod.PATCH, kind.one(resource.id()))
                    .header(HttpHeaders.IF_MATCH, resource.etag()), Kind.patch("""
                    {"op":"replace","path":"%s","value":"%s"}""".formatted(kind.identifying,
                    newline))), 400, "invalidValue");
            assertThat(error.get("detail").asText()).isEqualTo(controlDetail);
            assertThat(error.toString()).doesNotContain("forged");
            t.assertUnchanged(kind, resource);
        });
        add(all, k + " 400 invalidValue: replace with a NUL in externalId", t -> {
            Resource resource = t.create(kind);
            String body = kind.replace(name());
            JsonNode error = t.expectError(body(t.scim(HttpMethod.PUT, kind.one(resource.id()))
                    .header(HttpHeaders.IF_MATCH, resource.etag()),
                    body.substring(0, body.length() - 1) + ",\"externalId\":\"" + nul + "\"}"),
                    400, "invalidValue");
            assertThat(error.get("detail").asText())
                    .isEqualTo("externalId must not contain the NUL character (U+0000).");
            t.assertUnchanged(kind, resource);
        });

        // -- 400 invalidSyntax: not JSON, not a resource, the wrong schema, a missing required --
        Map<String, String> unreadable = ScimConformanceFixtureTests.map(
                "malformed JSON", "{\"schemas\":",
                "an empty body", "",
                "a JSON array", "[]",
                "no schemas", kind.create(name()).replaceFirst("\"schemas\":\\[[^]]*],", ""),
                "the required " + kind.identifying + " missing", kind == Kind.USER
                        ? "{\"schemas\":[\"" + USER_SCHEMA + "\"],\"displayName\":\"x\"}"
                        : "{\"schemas\":[\"" + GROUP_SCHEMA + "\"]}");
        String otherSchema = kind.create(name()).replace(kind.schema,
                kind == Kind.USER ? GROUP_SCHEMA : USER_SCHEMA);
        add(all, k + " 400 invalidValue: create declaring another resource's schema", t ->
                t.expectError(body(t.scim(HttpMethod.POST, kind.collection()), otherSchema),
                        400, "invalidValue"));
        add(all, k + " 400 invalidValue: replace declaring another resource's schema", t -> {
            Resource resource = t.create(kind);
            t.expectError(body(t.scim(HttpMethod.PUT, kind.one(resource.id()))
                    .header(HttpHeaders.IF_MATCH, resource.etag()), otherSchema),
                    400, "invalidValue");
            t.assertUnchanged(kind, resource);
        });
        for (Map.Entry<String, String> bad : unreadable.entrySet()) {
            add(all, k + " 400 invalidSyntax: create with " + bad.getKey(), t ->
                    t.expectError(body(t.scim(HttpMethod.POST, kind.collection()),
                            bad.getValue()), 400, "invalidSyntax"));
            add(all, k + " 400 invalidSyntax: replace with " + bad.getKey(), t -> {
                Resource resource = t.create(kind);
                t.expectError(body(t.scim(HttpMethod.PUT, kind.one(resource.id()))
                                .header(HttpHeaders.IF_MATCH, resource.etag()), bad.getValue()),
                        400, "invalidSyntax");
                t.assertUnchanged(kind, resource);
            });
        }
        Map<String, String> notPatchOps = ScimConformanceFixtureTests.map(
                "malformed JSON", "{\"Operations\":[",
                "the wrong schema", "{\"schemas\":[\"" + kind.schema + "\"],\"Operations\":[]}",
                "no Operations", "{\"schemas\":[\"" + ScimConformanceFixtureTests.PATCH_OP + "\"]}",
                "empty Operations", Kind.patch());
        for (Map.Entry<String, String> bad : notPatchOps.entrySet()) {
            add(all, k + " 400 invalidSyntax: patch with " + bad.getKey(), t -> {
                Resource resource = t.create(kind);
                t.expectError(body(t.scim(HttpMethod.PATCH, kind.one(resource.id()))
                                .header(HttpHeaders.IF_MATCH, resource.etag()), bad.getValue()),
                        400, "invalidSyntax");
                t.assertUnchanged(kind, resource);
            });
        }
        add(all, k + " 400 invalidSyntax: search with a malformed body", t ->
                t.expectError(body(t.scim(HttpMethod.POST, kind.collection() + "/.search"), "{"),
                        400, "invalidSyntax"));

        // -- 400 invalidValue: unimplemented attribute, wrong type, bad projection or sort --
        add(all, k + " 400 invalidValue: create asserting an unimplemented attribute", t ->
                t.expectError(body(t.scim(HttpMethod.POST, kind.collection()),
                        kind.create(name()).replace("{", "{\"nickName\":\"n\",")), 400,
                        "invalidValue"));
        add(all, k + " 400 invalidValue: create with a value of the wrong JSON type", t ->
                t.expectError(body(t.scim(HttpMethod.POST, kind.collection()), kind == Kind.USER
                        ? "{\"schemas\":[\"" + USER_SCHEMA + "\"],\"userName\":\"" + name()
                                + "\",\"active\":\"yes\"}"
                        : "{\"schemas\":[\"" + GROUP_SCHEMA + "\"],\"displayName\":7}"),
                        400, "invalidValue"));
        add(all, k + " 400 invalidValue: a projection naming no attribute", t -> {
            Resource resource = t.create(kind);
            t.expectError(t.scim(HttpMethod.GET, kind.one(resource.id()))
                    .param("attributes", "noSuchAttribute"), 400, "invalidValue");
            t.expectError(t.scim(HttpMethod.GET, kind.collection())
                    .param("excludedAttributes", "noSuchAttribute"), 400, "invalidValue");
        });
        add(all, k + " 400 invalidValue: attributes and excludedAttributes together", t ->
                t.expectError(t.scim(HttpMethod.GET, kind.collection())
                        .param("attributes", "displayName")
                        .param("excludedAttributes", "meta"), 400, "invalidValue"));
        add(all, k + " 400 invalidValue: an unsortable sortBy or unknown sortOrder", t -> {
            t.expectError(t.scim(HttpMethod.GET, kind.collection()).param("sortBy", "nope"),
                    400, "invalidValue");
            t.expectError(t.scim(HttpMethod.GET, kind.collection())
                    .param("sortBy", kind.identifying).param("sortOrder", "sideways"),
                    400, "invalidValue");
        });
        add(all, k + " 400 invalidValue: a projection on create is validated too", t ->
                t.expectError(body(t.scim(HttpMethod.POST, kind.collection())
                        .param("attributes", "noSuchAttribute"), kind.create(name())),
                        400, "invalidValue"));
        add(all, k + " 400 invalidValue: a PatchOp over the 100-operation bound", t -> {
            Resource resource = t.create(kind);
            String[] operations = new String[101];
            java.util.Arrays.fill(operations, """
                    {"op":"replace","path":"displayName","value":"%s"}"""
                    .formatted(resource.body().get("displayName").asText()));
            t.expectError(body(t.scim(HttpMethod.PATCH, kind.one(resource.id()))
                    .header(HttpHeaders.IF_MATCH, resource.etag()), Kind.patch(operations)),
                    400, "invalidValue");
            t.assertUnchanged(kind, resource);
        });
        add(all, k + " patch: exactly 100 operations are accepted", t -> {
            Resource resource = t.create(kind);
            String[] operations = new String[100];
            java.util.Arrays.fill(operations, """
                    {"op":"replace","path":"displayName","value":"%s"}"""
                    .formatted(resource.body().get("displayName").asText()));
            t.expect(body(t.scim(HttpMethod.PATCH, kind.one(resource.id()))
                    .header(HttpHeaders.IF_MATCH, resource.etag()), Kind.patch(operations)), 200);
        });

        // -- 400 invalidFilter --
        add(all, k + " 400 invalidFilter: a malformed filter, by GET and by search", t -> {
            t.expectError(t.scim(HttpMethod.GET, kind.collection())
                    .param("filter", kind.identifying + " eq"), 400, "invalidFilter");
            t.expectError(body(t.scim(HttpMethod.POST, kind.collection() + "/.search"),
                    "{\"schemas\":[\"" + SEARCH_REQUEST + "\"],\"filter\":\"(\"}"),
                    400, "invalidFilter");
        });

        // -- 400 invalidPath / noTarget / mutability: PATCH targets --
        add(all, k + " 400 invalidPath: a PATCH path this service does not implement", t -> {
            Resource resource = t.create(kind);
            t.expectError(body(t.scim(HttpMethod.PATCH, kind.one(resource.id()))
                    .header(HttpHeaders.IF_MATCH, resource.etag()), Kind.patch("""
                    {"op":"replace","path":"nickName","value":"n"}""")), 400, "invalidPath");
            t.assertUnchanged(kind, resource);
        });
        add(all, k + " 400 noTarget: a remove with no path", t -> {
            Resource resource = t.create(kind);
            t.expectError(body(t.scim(HttpMethod.PATCH, kind.one(resource.id()))
                    .header(HttpHeaders.IF_MATCH, resource.etag()), Kind.patch("""
                    {"op":"remove"}""")), 400, "noTarget");
            t.assertUnchanged(kind, resource);
        });
        add(all, k + " 400 mutability: a PATCH aimed at the read-only id", t -> {
            Resource resource = t.create(kind);
            t.expectError(body(t.scim(HttpMethod.PATCH, kind.one(resource.id()))
                    .header(HttpHeaders.IF_MATCH, resource.etag()), Kind.patch("""
                    {"op":"replace","path":"id","value":"%s"}""".formatted(UUID.randomUUID()))),
                    400, "mutability");
            t.assertUnchanged(kind, resource);
        });
        add(all, k + " 400 mutability: removing the required " + kind.identifying, t -> {
            Resource resource = t.create(kind);
            t.expectError(body(t.scim(HttpMethod.PATCH, kind.one(resource.id()))
                    .header(HttpHeaders.IF_MATCH, resource.etag()), Kind.patch("""
                    {"op":"remove","path":"%s"}""".formatted(kind.identifying))),
                    400, "mutability");
            t.assertUnchanged(kind, resource);
        });
        add(all, k + " 400: a refused operation late in a PatchOp applies none of the earlier ones",
                t -> {
                    Resource resource = t.create(kind);
                    t.expectError(body(t.scim(HttpMethod.PATCH, kind.one(resource.id()))
                            .header(HttpHeaders.IF_MATCH, resource.etag()), Kind.patch("""
                            {"op":"replace","path":"displayName","value":"%s"}""".formatted(name()),
                            """
                            {"op":"remove","path":"%s"}""".formatted(kind.identifying))),
                            400, "mutability");
                    t.assertUnchanged(kind, resource);
                    assertThat(json(t.expect(t.scim(HttpMethod.GET, kind.one(resource.id())),
                            200)).get("displayName")).isEqualTo(resource.body().get("displayName"));
                });

        // -- the precondition: optional (RFC 7644 §3.14), and exact when sent: 400 / 412 --
        for (Map.Entry<String, Probe> write : writes(kind, null).entrySet()) {
            String op = write.getKey();
            add(all, k + " 2xx: " + op + " without If-Match is applied unconditionally", t -> {
                Resource resource = t.create(kind);
                MockHttpServletRequestBuilder request =
                        writes(kind, t.writeToken).get(op).apply(t, resource);
                stripIfMatch(request);
                if (op.startsWith("DELETE")) {
                    t.expect(request, 204);
                    t.expect(t.scim(HttpMethod.GET, kind.one(resource.id())), 404);
                } else {
                    MvcResult applied = t.expect(request, 200);
                    assertThat(applied.getResponse().getHeader(HttpHeaders.ETAG))
                            .as("an unconditional write advances the ETag as any write does")
                            .isNotEqualTo(resource.etag())
                            .isEqualTo(t.etag(kind, resource.id()));
                }
            });
            add(all, k + " 400 invalidValue: " + op + " with a wildcard If-Match", t -> {
                Resource resource = t.create(kind);
                t.expectError(writes(kind, t.writeToken).get(op)
                        .apply(t, new Resource(resource.id(), "*", null)), 400, "invalidValue");
                t.assertUnchanged(kind, resource);
            });
            add(all, k + " 412: " + op + " with a stale If-Match", t -> {
                Resource resource = t.create(kind);
                t.expect(body(t.scim(HttpMethod.PATCH, kind.one(resource.id()))
                        .header(HttpHeaders.IF_MATCH, resource.etag()),
                        kind.patchRename(name())), 200);
                String current = t.etag(kind, resource.id());
                t.expectError(writes(kind, t.writeToken).get(op).apply(t, resource), 412, null);
                assertThat(t.etag(kind, resource.id())).isEqualTo(current);
            });
        }

        // -- 413: a body over 1 MiB, declared, refused before it is read or authenticated --
        add(all, k + " 413: create with a body over 1 MiB", t ->
                t.expectError(body(t.scim(HttpMethod.POST, kind.collection()),
                        oversized(kind, ONE_MIB + 1)), 413, null));
        add(all, k + " 413: replace and patch with a body over 1 MiB change nothing", t -> {
            Resource resource = t.create(kind);
            t.expectError(body(t.scim(HttpMethod.PUT, kind.one(resource.id()))
                    .header(HttpHeaders.IF_MATCH, resource.etag()),
                    oversized(kind, ONE_MIB + 1)), 413, null);
            t.expectError(body(t.scim(HttpMethod.PATCH, kind.one(resource.id()))
                    .header(HttpHeaders.IF_MATCH, resource.etag()),
                    oversized(kind, ONE_MIB + 1)), 413, null);
            t.assertUnchanged(kind, resource);
        });
        add(all, k + " 413: search with a body over 1 MiB", t ->
                t.expectError(body(t.scim(HttpMethod.POST, kind.collection() + "/.search"),
                        oversized(kind, ONE_MIB + 1)), 413, null));
        add(all, k + " 413: decided before authentication", t ->
                t.expectError(body(t.as(null, HttpMethod.POST, kind.collection()),
                        oversized(kind, ONE_MIB + 1)), 413, null));
        add(all, k + " create: a body of exactly 1 MiB is read, not refused for size", t ->
                t.expectError(body(t.scim(HttpMethod.POST, kind.collection()),
                        oversized(kind, ONE_MIB)), 400, "invalidValue"));

        // -- 415 / 405 / 406: refusals the dispatcher makes, rendered as SCIM errors --
        add(all, k + " 415: a body that is neither application/scim+json nor application/json",
                t -> {
                    Resource resource = t.create(kind);
                    t.expectError(t.scim(HttpMethod.POST, kind.collection())
                            .contentType(MediaType.TEXT_PLAIN).content(kind.create(name())),
                            415, null);
                    t.expectError(t.scim(HttpMethod.PUT, kind.one(resource.id()))
                            .header(HttpHeaders.IF_MATCH, resource.etag())
                            .contentType(MediaType.TEXT_PLAIN).content(kind.replace(name())),
                            415, null);
                    t.expectError(t.scim(HttpMethod.PATCH, kind.one(resource.id()))
                            .header(HttpHeaders.IF_MATCH, resource.etag())
                            .contentType(MediaType.TEXT_PLAIN).content(kind.patchRename("x")),
                            415, null);
                    t.expectError(t.scim(HttpMethod.POST, kind.collection() + "/.search")
                            .contentType(MediaType.TEXT_PLAIN).content("{}"), 415, null);
                    t.assertUnchanged(kind, resource);
                });
        add(all, k + " 405: a method the collection does not implement", t -> {
            for (HttpMethod method : List.of(HttpMethod.PUT, HttpMethod.PATCH, HttpMethod.DELETE)) {
                MvcResult refused = t.expect(t.scim(method, kind.collection()), 405);
                assertThat(json(refused).get("status").asText()).isEqualTo("405");
                assertThat(refused.getResponse().getHeader(HttpHeaders.ALLOW))
                        .contains("GET").contains("POST");
            }
        });
        add(all, k + " 406: an Accept this service cannot satisfy", t -> {
            Resource resource = t.create(kind);
            t.expectError(t.scim(HttpMethod.GET, kind.one(resource.id()))
                    .accept(MediaType.TEXT_HTML), 406, null);
        });
    }

    private static void stripIfMatch(MockHttpServletRequestBuilder request) {
        request.with(built -> {
            built.removeHeader(HttpHeaders.IF_MATCH);
            return built;
        });
    }

    // ==== attribute mutability (RFC 7643 §2.2, §7) ========================================

    static void mutability(List<Fixture> all, Kind kind) {
        String k = kind.resourceType();

        add(all, k + " mutability: id and meta in a create body are ignored", t -> {
            UUID claimed = UUID.randomUUID();
            Resource resource = t.create(kind, kind.create(name()).replace("{", """
                    {"id":"%s","meta":{"version":"\\"99\\"","resourceType":"Nope"},"""
                    .formatted(claimed)));
            assertThat(resource.id()).isNotEqualTo(claimed);
            assertThat(resource.body().at("/meta/resourceType").asText())
                    .isEqualTo(kind.resourceType());
            assertThat(resource.etag()).isNotEqualTo("\"99\"");
        });

        add(all, k + " mutability: a different id in a replace body is ignored", t -> {
            Resource resource = t.create(kind);
            JsonNode replaced = json(t.expect(body(t.scim(HttpMethod.PUT, kind.one(resource.id()))
                    .header(HttpHeaders.IF_MATCH, resource.etag()), kind.replace(name())
                    .replace("{", "{\"id\":\"" + UUID.randomUUID() + "\",")), 200));
            assertThat(replaced.get("id").asText()).isEqualTo(resource.id().toString());
        });

        add(all, k + " mutability: a resource read back can be replaced as read", t -> {
            Resource resource = t.create(kind);
            t.expect(body(t.scim(HttpMethod.PUT, kind.one(resource.id()))
                    .header(HttpHeaders.IF_MATCH, resource.etag()), resource.body().toString()),
                    200);
        });

        add(all, k + " mutability: externalId is visible only to the connector that set it", t -> {
            Resource resource = t.create(kind, kind.create(name())
                    .replace("{", "{\"externalId\":\"ext-1\","));
            assertThat(resource.body().get("externalId").asText()).isEqualTo("ext-1");
            JsonNode seenByOther = json(t.expect(t.as(t.otherConnectorToken, HttpMethod.GET,
                    kind.one(resource.id())), 200));
            assertThat(seenByOther.has("externalId")).isFalse();
            JsonNode filtered = json(t.expect(t.as(t.otherConnectorToken, HttpMethod.GET,
                    kind.collection()).param("filter", "externalId eq \"ext-1\" and id eq \""
                    + resource.id() + "\""), 200));
            assertThat(filtered.get("totalResults").asInt()).isZero();
        });

        add(all, k + " mutability: PUT re-keys, restates and removes the caller's externalId",
                t -> {
            String name = name();
            Resource resource = t.create(kind, kind.create(name)
                    .replace("{", "{\"externalId\":\"ext-1\","));
            MvcResult changed = t.expect(body(t.scim(HttpMethod.PUT, kind.one(resource.id()))
                    .header(HttpHeaders.IF_MATCH, resource.etag()), kind.replace(name)
                    .replace("{", "{\"externalId\":\"ext-2\",")), 200);
            assertThat(json(changed).get("externalId").asText()).isEqualTo("ext-2");
            String changedTag = changed.getResponse().getHeader(HttpHeaders.ETAG);
            assertThat(changedTag).isNotEqualTo(resource.etag());
            MvcResult restated = t.expect(body(t.scim(HttpMethod.PUT, kind.one(resource.id()))
                    .header(HttpHeaders.IF_MATCH, changedTag), kind.replace(name)
                    .replace("{", "{\"externalId\":\"ext-2\",")), 200);
            assertThat(restated.getResponse().getHeader(HttpHeaders.ETAG)).isEqualTo(changedTag);
            JsonNode omitted = json(t.expect(body(t.scim(HttpMethod.PUT, kind.one(resource.id()))
                    .header(HttpHeaders.IF_MATCH, changedTag), kind.replace(name)), 200));
            assertThat(omitted.has("externalId")).as("omitted on replace is unassigned").isFalse();
        });

        add(all, k + " mutability: PATCH adds, replaces and removes the caller's externalId",
                t -> {
            Resource resource = t.create(kind);
            String qualified = kind.schema + ":externalId";
            MvcResult added = t.expect(body(t.scim(HttpMethod.PATCH, kind.one(resource.id()))
                    .header(HttpHeaders.IF_MATCH, resource.etag()), Kind.patch("""
                    {"op":"add","path":"externalId","value":"ext-1"}""")), 200);
            assertThat(json(added).get("externalId").asText()).isEqualTo("ext-1");
            MvcResult replaced = t.expect(body(t.scim(HttpMethod.PATCH, kind.one(resource.id()))
                    .header(HttpHeaders.IF_MATCH, added.getResponse().getHeader(HttpHeaders.ETAG)),
                    Kind.patch("""
                    {"op":"replace","path":"%s","value":"ext-2"}""".formatted(qualified))), 200);
            assertThat(json(replaced).get("externalId").asText()).isEqualTo("ext-2");
            JsonNode filtered = json(t.expect(t.scim(HttpMethod.GET, kind.collection())
                    .param("filter", "externalId eq \"ext-2\" and id eq \"" + resource.id()
                            + "\""), 200));
            assertThat(filtered.get("totalResults").asInt()).isEqualTo(1);
            JsonNode removed = json(t.expect(body(t.scim(HttpMethod.PATCH, kind.one(resource.id()))
                    .header(HttpHeaders.IF_MATCH,
                            replaced.getResponse().getHeader(HttpHeaders.ETAG)), Kind.patch("""
                    {"op":"remove","path":"externalId"}""")), 200));
            assertThat(removed.has("externalId")).isFalse();
        });

        add(all, k + " mutability: an unassigned attribute is omitted, never null", t -> {
            Resource resource = t.create(kind);
            for (JsonNode value : resource.body()) {
                assertThat(value.isNull()).isFalse();
            }
            assertThat(resource.body().has(kind == Kind.USER ? "emails" : "members")).isFalse();
        });

        add(all, k + " projection: attributes keeps always-returned id and schemas", t -> {
            Resource resource = t.create(kind);
            JsonNode projected = json(t.expect(t.scim(HttpMethod.GET, kind.one(resource.id()))
                    .param("attributes", "displayName"), 200));
            assertThat(names(projected)).contains("id", "schemas", "displayName")
                    .doesNotContain("meta");
            JsonNode excluded = json(t.expect(t.scim(HttpMethod.GET, kind.one(resource.id()))
                    .param("excludedAttributes", "displayName,id"), 200));
            assertThat(names(excluded)).contains("id", "schemas").doesNotContain("displayName");
        });
    }

    // ==== pagination (RFC 7644 §3.4.2.4) ==================================================

    static void pagination(List<Fixture> all, Kind kind) {
        String k = kind.resourceType();

        add(all, k + " paging: startIndex, count, itemsPerPage and totalResults", t -> {
            String prefix = name();
            for (String suffix : List.of("-a", "-b", "-c")) {
                t.create(kind, kind.create(prefix + suffix));
            }
            String filter = kind.identifying + " sw \"" + prefix + "\"";
            Map<String, int[]> cases = new java.util.LinkedHashMap<>();
            // params -> {startIndex, itemsPerPage, resources}; totalResults is always 3
            cases.put("", new int[] {1, 3, 3});
            cases.put("count=2", new int[] {1, 2, 2});
            cases.put("startIndex=3&count=2", new int[] {3, 1, 1});
            cases.put("startIndex=0", new int[] {1, 3, 3});
            cases.put("startIndex=-5", new int[] {1, 3, 3});
            cases.put("count=0", new int[] {1, 0, 0});
            cases.put("count=-1", new int[] {1, 0, 0});
            cases.put("startIndex=9", new int[] {9, 0, 0});
            for (Map.Entry<String, int[]> c : cases.entrySet()) {
                MockHttpServletRequestBuilder request = t.scim(HttpMethod.GET, kind.collection())
                        .param("filter", filter).param("sortBy", kind.identifying);
                for (String pair : c.getKey().isEmpty() ? new String[0] : c.getKey().split("&")) {
                    String[] kv = pair.split("=");
                    request.param(kv[0], kv[1]);
                }
                JsonNode page = json(t.expect(request, 200));
                assertThat(page.get("totalResults").asInt()).as(c.getKey()).isEqualTo(3);
                assertThat(page.get("startIndex").asInt()).as(c.getKey()).isEqualTo(c.getValue()[0]);
                assertThat(page.get("itemsPerPage").asInt()).as(c.getKey())
                        .isEqualTo(c.getValue()[1]);
                assertThat(listed(page, "id")).as(c.getKey()).hasSize(c.getValue()[2]);
                if (c.getValue()[2] == 0) {
                    assertThat(page.has("Resources")).as("%s: empty Resources omitted", c.getKey())
                            .isFalse();
                }
            }
            JsonNode last = json(t.expect(t.scim(HttpMethod.GET, kind.collection())
                    .param("filter", filter).param("sortBy", kind.identifying)
                    .param("startIndex", "3").param("count", "2"), 200));
            assertThat(listed(last, kind.identifying)).containsExactly(prefix + "-c");
        });

        add(all, k + " paging: the same page by POST .search", t -> {
            String prefix = name();
            for (String suffix : List.of("-a", "-b", "-c")) {
                t.create(kind, kind.create(prefix + suffix));
            }
            JsonNode page = json(t.expect(body(t.scim(HttpMethod.POST,
                    kind.collection() + "/.search"), """
                    {"schemas":["%s"],"filter":"%s sw \\"%s\\"","sortBy":"%s",
                     "sortOrder":"descending","startIndex":2,"count":1}"""
                    .formatted(SEARCH_REQUEST, kind.identifying, prefix, kind.identifying)), 200));
            assertThat(page.get("totalResults").asInt()).isEqualTo(3);
            assertThat(listed(page, kind.identifying)).containsExactly(prefix + "-b");
        });

        add(all, k + " sorting: ascending by default, descending on request", t -> {
            String prefix = name();
            for (String suffix : List.of("-b", "-c", "-a")) {
                t.create(kind, kind.create(prefix + suffix));
            }
            String filter = kind.identifying + " sw \"" + prefix + "\"";
            JsonNode ascending = json(t.expect(t.scim(HttpMethod.GET, kind.collection())
                    .param("filter", filter).param("sortBy", kind.identifying), 200));
            assertThat(listed(ascending, kind.identifying))
                    .containsExactly(prefix + "-a", prefix + "-b", prefix + "-c");
            JsonNode descending = json(t.expect(t.scim(HttpMethod.GET, kind.collection())
                    .param("filter", filter).param("sortBy", kind.identifying)
                    .param("sortOrder", "descending"), 200));
            assertThat(listed(descending, kind.identifying))
                    .containsExactly(prefix + "-c", prefix + "-b", prefix + "-a");
        });

        add(all, k + " paging: a count above 200 is capped at 200", t -> {
            String prefix = name();
            for (int i = 0; i < 201; i++) {
                t.create(kind, kind.create(prefix + "-" + String.format("%03d", i)));
            }
            JsonNode page = json(t.expect(t.scim(HttpMethod.GET, kind.collection())
                    .param("filter", kind.identifying + " sw \"" + prefix + "\"")
                    .param("count", "500"), 200));
            assertThat(page.get("totalResults").asInt()).isEqualTo(201);
            assertThat(page.get("itemsPerPage").asInt()).isEqualTo(200);
            assertThat(listed(page, "id")).hasSize(200);
            JsonNode defaulted = json(t.expect(t.scim(HttpMethod.GET, kind.collection())
                    .param("filter", kind.identifying + " sw \"" + prefix + "\""), 200));
            assertThat(defaulted.get("itemsPerPage").asInt()).as("count defaults to 100")
                    .isEqualTo(100);
        });
    }

    // ==== PATCH grammar (RFC 7644 §3.5.2) =================================================

    static void patchGrammar(List<Fixture> all, Kind kind) {
        String k = kind.resourceType();
        String schemaPrefixed = kind.schema + ":displayName";
        Map<String, String> accepted = ScimConformanceFixtureTests.map(
                "op matched case-insensitively", "{\"op\":\"Replace\",\"path\":\"displayName\","
                        + "\"value\":\"VALUE\"}",
                "path matched case-insensitively", "{\"op\":\"replace\",\"path\":\"DISPLAYNAME\","
                        + "\"value\":\"VALUE\"}",
                "a schema-qualified path", "{\"op\":\"replace\",\"path\":\"" + schemaPrefixed
                        + "\",\"value\":\"VALUE\"}",
                "add to a single-valued attribute replaces it", "{\"op\":\"add\","
                        + "\"path\":\"displayName\",\"value\":\"VALUE\"}");
        for (Map.Entry<String, String> grammar : accepted.entrySet()) {
            add(all, k + " patch grammar: " + grammar.getKey(), t -> {
                Resource resource = t.create(kind);
                String value = name();
                JsonNode patched = json(t.expect(body(t.scim(HttpMethod.PATCH,
                        kind.one(resource.id())).header(HttpHeaders.IF_MATCH, resource.etag()),
                        Kind.patch(grammar.getValue().replace("VALUE", value))), 200));
                assertThat(patched.get("displayName").asText()).isEqualTo(value);
            });
        }
        Map<String, String[]> refused = new java.util.LinkedHashMap<>();
        refused.put("an unparseable path", new String[] {
            "{\"op\":\"replace\",\"path\":\"displayName[\",\"value\":\"x\"}", "invalidPath"});
        refused.put("a sub-attribute on a simple attribute", new String[] {
            "{\"op\":\"replace\",\"path\":\"displayName.value\",\"value\":\"x\"}", "invalidPath"});
        refused.put("the read-only meta", new String[] {
            "{\"op\":\"replace\",\"path\":\"meta\",\"value\":{}}", "mutability"});
        refused.put("an op that is not add, remove or replace", new String[] {
            "{\"op\":\"move\",\"path\":\"displayName\",\"value\":\"x\"}", "invalidSyntax"});
        refused.put("a value of the wrong type", new String[] {
            "{\"op\":\"replace\",\"path\":\"displayName\",\"value\":5}", "invalidValue"});
        for (Map.Entry<String, String[]> grammar : refused.entrySet()) {
            add(all, k + " patch grammar refused: " + grammar.getKey(), t -> {
                Resource resource = t.create(kind);
                MvcResult result = t.expect(body(t.scim(HttpMethod.PATCH, kind.one(resource.id()))
                        .header(HttpHeaders.IF_MATCH, resource.etag()),
                        Kind.patch(grammar.getValue()[0])), 400);
                JsonNode error = json(result);
                assertThat(texts(error.get("schemas"))).containsExactly(ERROR_SCHEMA);
                assertThat(error.get("scimType").asText()).isEqualTo(grammar.getValue()[1]);
                t.assertUnchanged(kind, resource);
            });
        }
    }

    // ==== edge whitespace on the keys (ADR 0013) ==========================================

    /**
     * A key of {@code kind} — an attribute that is unique or looked up — with how a create or
     * replacement body and a PATCH operation carry a value for it, and where it reads back.
     */
    private record Key(String attribute, String pointer,
            BiFunction<Kind, String, String> body, UnaryOperator<String> op) {
    }

    /** A complete body of {@code kind} with each key at a fresh, unbordered default. */
    private static String keyed(Kind kind, String userName, String displayName,
            String emailValue, String emailType, String externalId) {
        String alias = externalId == null ? "" : ",\"externalId\":\"" + externalId + "\"";
        return kind == Kind.USER
                ? """
                  {"schemas":["%s"],"userName":"%s","displayName":"%s",\
                  "emails":[{"value":"%s","type":"%s"}]%s}"""
                        .formatted(USER_SCHEMA, userName, displayName, emailValue, emailType, alias)
                : """
                  {"schemas":["%s"],"displayName":"%s"%s}"""
                        .formatted(GROUP_SCHEMA, displayName, alias);
    }

    private static List<Key> keys(Kind kind) {
        Function<String, UnaryOperator<String>> replacing = attribute -> value -> """
                {"op":"replace","path":"%s","value":"%s"}""".formatted(attribute, value);
        Key displayName = new Key("displayName", "/displayName",
                (k, v) -> keyed(k, name(), v, "ada@example.org", "work", null),
                replacing.apply("displayName"));
        Key externalId = new Key("externalId", "/externalId",
                (k, v) -> keyed(k, name(), name(), "ada@example.org", "work", v),
                replacing.apply("externalId"));
        if (kind == Kind.GROUP) {
            return List.of(displayName, externalId);
        }
        return List.of(
                new Key("userName", "/userName",
                        (k, v) -> keyed(k, v, "Conformance", "ada@example.org", "work", null),
                        replacing.apply("userName")),
                displayName,
                new Key("emails.value", "/emails/0/value",
                        (k, v) -> keyed(k, name(), "Conformance", v, "work", null),
                        v -> """
                             {"op":"replace","path":"emails","value":[{"value":"%s","type":"work"}]}"""
                                .formatted(v)),
                new Key("emails.type", "/emails/0/type",
                        (k, v) -> keyed(k, name(), "Conformance", "ada@example.org", v, null),
                        v -> """
                             {"op":"replace","path":"emails","value":[{"value":"ada@example.org",\
                             "type":"%s"}]}""".formatted(v)),
                externalId);
    }

    /**
     * Every key refuses a value with whitespace at either edge — leading, trailing, a trailing
     * tab — on create, {@code PUT} and {@code PATCH}, with {@code 400 invalidValue} naming the
     * attribute and never the value, and accepts whitespace inside it. A name ending in a tab is
     * named for the control it holds, which it already refused; the edge rule names the rest.
     */
    static void edgeWhitespace(List<Fixture> all, Kind kind) {
        String k = kind.resourceType();
        List<Map.Entry<String, UnaryOperator<String>>> bordered = List.of(
                Map.entry("a leading space", name -> " " + name),
                Map.entry("a trailing space", name -> name + " "),
                // A JSON escape, so the body is what a connector sends.
                Map.entry("a trailing tab", name -> name + "\\t"));
        for (Key key : keys(kind)) {
            String attribute = key.attribute();
            for (Map.Entry<String, UnaryOperator<String>> edge : bordered) {
                boolean control = edge.getKey().endsWith("tab")
                        && (attribute.equals("userName") || attribute.equals("displayName"));
                String detail = control
                        ? attribute + " must not contain control characters (U+0000 to U+001F,"
                                + " U+007F)."
                        : attribute + " must not begin or end with whitespace.";
                String what = attribute + " with " + edge.getKey();
                add(all, k + " 400 invalidValue: create with " + what, t -> {
                    JsonNode error = t.expectError(body(t.scim(HttpMethod.POST, kind.collection()),
                            key.body().apply(kind, edge.getValue().apply(name()))),
                            400, "invalidValue");
                    assertThat(error.get("detail").asText()).isEqualTo(detail);
                });
                add(all, k + " 400 invalidValue: replace with " + what, t -> {
                    Resource resource = t.create(kind);
                    JsonNode error = t.expectError(body(t.scim(HttpMethod.PUT, kind.one(resource.id()))
                                    .header(HttpHeaders.IF_MATCH, resource.etag()),
                            key.body().apply(kind, edge.getValue().apply(name()))),
                            400, "invalidValue");
                    assertThat(error.get("detail").asText()).isEqualTo(detail);
                    t.assertUnchanged(kind, resource);
                });
                add(all, k + " 400 invalidValue: patch with " + what, t -> {
                    Resource resource = t.create(kind);
                    JsonNode error = t.expectError(body(t.scim(HttpMethod.PATCH, kind.one(resource.id()))
                                    .header(HttpHeaders.IF_MATCH, resource.etag()),
                            Kind.patch(key.op().apply(edge.getValue().apply(name())))),
                            400, "invalidValue");
                    assertThat(error.get("detail").asText()).isEqualTo(detail);
                    t.assertUnchanged(kind, resource);
                });
            }
            add(all, k + " 201: whitespace inside " + attribute + " is stored as sent", t -> {
                String inner = name() + " b";
                Resource resource = t.create(kind, key.body().apply(kind, inner));
                assertThat(resource.body().at(key.pointer()).asText()).isEqualTo(inner);
            });
            add(all, k + " 200: a patch putting whitespace inside " + attribute + " is stored", t -> {
                Resource resource = t.create(kind);
                String inner = name() + " b";
                JsonNode patched = json(t.expect(body(t.scim(HttpMethod.PATCH,
                                kind.one(resource.id())).header(HttpHeaders.IF_MATCH, resource.etag()),
                        Kind.patch(key.op().apply(inner))), 200));
                assertThat(patched.at(key.pointer()).asText()).isEqualTo(inner);
            });
        }
    }

    // ==== a write deadlocked on its retry too (docs/openapi.yaml; ADR 0013, as-13) ========

    /**
     * Each write the database chooses as a deadlock's victim on its first attempt and on the one
     * retry: {@code 503} with {@code Retry-After: 1}, a SCIM error document with no
     * {@code scimType}, and nothing applied.
     */
    static void deadlockedWrites(List<Fixture> all, Kind kind) {
        String k = kind.resourceType();
        String table = kind == Kind.USER ? "scim_users" : "scim_groups";
        String column = kind == Kind.USER ? "user_name" : "display_name";

        add(all, k + " create: 503 with Retry-After when it deadlocks on its retry too", t -> {
            String name = name();
            try (ForcedDeadlock victim = t.deadlocked(table, column, name)) {
                expectDeadlocked(t, body(t.scim(HttpMethod.POST, kind.collection()),
                        kind.create(name)));
            }
            JsonNode list = json(t.expect(t.scim(HttpMethod.GET, kind.collection())
                    .param("filter", kind.identifying + " eq \"" + name + "\""), 200));
            assertThat(list.get("totalResults").asInt()).as("nothing was created").isZero();
        });

        add(all, k + " replace: 503 with Retry-After when it deadlocks on its retry too", t -> {
            Resource resource = t.create(kind);
            try (ForcedDeadlock victim = t.deadlocked("scim_resources", "id", resource.id())) {
                expectDeadlocked(t, body(t.scim(HttpMethod.PUT, kind.one(resource.id())),
                        kind.replace(name())));
            }
            t.assertUnchanged(kind, resource);
        });

        add(all, k + " patch: 503 with Retry-After when it deadlocks on its retry too", t -> {
            Resource resource = t.create(kind);
            try (ForcedDeadlock victim = t.deadlocked("scim_resources", "id", resource.id())) {
                expectDeadlocked(t, body(t.scim(HttpMethod.PATCH, kind.one(resource.id())),
                        kind.patchRename(name())));
            }
            t.assertUnchanged(kind, resource);
        });

        add(all, k + " delete: 503 with Retry-After when it deadlocks on its retry too", t -> {
            Resource resource = t.create(kind);
            try (ForcedDeadlock victim = t.deadlocked("scim_resources", "id", resource.id())) {
                expectDeadlocked(t, t.scim(HttpMethod.DELETE, kind.one(resource.id())));
            }
            t.assertUnchanged(kind, resource);
        });
    }

    private static void expectDeadlocked(
            ScimConformanceFixtureTests t, MockHttpServletRequestBuilder request) throws Exception {
        MvcResult result = t.expect(request, 503);
        assertThat(result.getResponse().getHeader(HttpHeaders.RETRY_AFTER)).isEqualTo("1");
        assertThat(result.getResponse().getContentType()).startsWith("application/scim+json");
        JsonNode error = json(result);
        assertThat(texts(error.get("schemas"))).containsExactly(ERROR_SCHEMA);
        assertThat(error.get("status").stringValue()).isEqualTo("503");
        assertThat(error.has("scimType")).as("no scimType for 503").isFalse();
        assertThat(error.path("detail").asText()).isNotBlank().doesNotContain("deadlock");
    }

    // ==== User-only conditions ============================================================

    static void userOnly(List<Fixture> all) {
        Kind user = Kind.USER;

        // -- stored-length limits on attributes only a User has, including a sub-attribute --
        add(all, "User 400 invalidValue: an over-length emails type, not a userName conflict", t -> {
            String userName = name();
            JsonNode error = t.expectError(body(t.scim(HttpMethod.POST, user.collection()), """
                    {"schemas":["%s"],"userName":"%s",
                     "emails":[{"value":"a@example.com","type":"%s"}]}"""
                    .formatted(USER_SCHEMA, userName, "t".repeat(40))), 400, "invalidValue");
            assertThat(error.get("detail").asText())
                    .isEqualTo("emails.type must be at most 32 characters long.");
            JsonNode found = json(t.expect(t.scim(HttpMethod.GET, user.collection())
                    .param("filter", "userName eq \"" + userName + "\""), 200));
            assertThat(found.get("totalResults").asInt()).as("nothing was created").isZero();
        });
        add(all, "User 400 invalidValue: an over-length locale on PUT and PATCH", t -> {
            Resource resource = t.create(user);
            String userName = resource.body().get("userName").asText();
            String locale = "l".repeat(80);
            JsonNode put = t.expectError(body(t.scim(HttpMethod.PUT, user.one(resource.id()))
                    .header(HttpHeaders.IF_MATCH, resource.etag()), """
                    {"schemas":["%s"],"userName":"%s","locale":"%s"}"""
                    .formatted(USER_SCHEMA, userName, locale)), 400, "invalidValue");
            JsonNode patch = t.expectError(body(t.scim(HttpMethod.PATCH, user.one(resource.id()))
                    .header(HttpHeaders.IF_MATCH, resource.etag()), Kind.patch("""
                    {"op":"replace","path":"locale","value":"%s"}""".formatted(locale))),
                    400, "invalidValue");
            assertThat(List.of(put.get("detail").asText(), patch.get("detail").asText()))
                    .containsOnly("locale must be at most 64 characters long.");
            t.assertUnchanged(user, resource);
        });
        add(all, "User password: write-only — accepted on create, never returned", t -> {
            Resource resource = t.create(user, """
                    {"schemas":["%s"],"userName":"%s","password":"conformance-pass-1"}"""
                    .formatted(USER_SCHEMA, name()));
            assertThat(resource.body().has("password")).isFalse();
            MvcResult read = t.expect(t.scim(HttpMethod.GET, user.one(resource.id()))
                    .param("attributes", "password"), 200);
            assertThat(json(read).has("password")).isFalse();
            assertThat(read.getResponse().getContentAsString()).doesNotContain("conformance-pass-1");
        });

        add(all, "User password: a password filter is invalidFilter", t ->
                t.expectError(t.scim(HttpMethod.GET, user.collection())
                        .param("filter", "password eq \"x\""), 400, "invalidFilter"));

        add(all, "User password: a recently used password is refused, value not echoed", t -> {
            Resource resource = t.create(user, """
                    {"schemas":["%s"],"userName":"%s","password":"conformance-pass-1"}"""
                    .formatted(USER_SCHEMA, name()));
            JsonNode error = t.expectError(body(t.scim(HttpMethod.PATCH, user.one(resource.id()))
                    .header(HttpHeaders.IF_MATCH, resource.etag()), Kind.patch("""
                    {"op":"replace","path":"password","value":"conformance-pass-1"}""")),
                    400, "invalidValue");
            assertThat(error.toString()).doesNotContain("conformance-pass-1");
            t.assertUnchanged(user, resource);
        });

        // -- the password policy, on every path that sets a password --
        String[][] subPolicy = {
                {"too short", "short-pw-1", "TOO_SHORT"},
                {"containing the userName", "i-am-%s-truly", "CONTAINS_USER_NAME"}};
        for (String[] bad : subPolicy) {
            String label = bad[0];
            String rule = bad[2];
            add(all, "User password: create with a password " + label + " is invalidValue", t -> {
                String userName = name();
                String candidate = bad[1].formatted(userName);
                JsonNode error = t.expectError(body(t.scim(HttpMethod.POST, user.collection()), """
                        {"schemas":["%s"],"userName":"%s","password":"%s"}"""
                        .formatted(USER_SCHEMA, userName, candidate)), 400, "invalidValue");
                assertThat(error.get("detail").asText()).contains(rule);
                assertThat(error.toString()).doesNotContain(candidate);
                JsonNode found = json(t.expect(t.scim(HttpMethod.GET, user.collection())
                        .param("filter", "userName eq \"" + userName + "\""), 200));
                assertThat(found.get("totalResults").asInt()).as("nothing was created").isZero();
            });
            add(all, "User password: replace with a password " + label + " is invalidValue", t -> {
                Resource resource = t.create(user);
                String userName = resource.body().get("userName").asText();
                String candidate = bad[1].formatted(userName);
                JsonNode error = t.expectError(body(t.scim(HttpMethod.PUT, user.one(resource.id()))
                        .header(HttpHeaders.IF_MATCH, resource.etag()), """
                        {"schemas":["%s"],"userName":"%s","password":"%s"}"""
                        .formatted(USER_SCHEMA, userName, candidate)), 400, "invalidValue");
                assertThat(error.get("detail").asText()).contains(rule);
                assertThat(error.toString()).doesNotContain(candidate);
                t.assertUnchanged(user, resource);
            });
            add(all, "User password: patch with a password " + label + " is invalidValue", t -> {
                Resource resource = t.create(user);
                String candidate = bad[1].formatted(resource.body().get("userName").asText());
                JsonNode error = t.expectError(body(t.scim(HttpMethod.PATCH,
                        user.one(resource.id())).header(HttpHeaders.IF_MATCH, resource.etag()),
                        Kind.patch("""
                        {"op":"replace","path":"password","value":"%s"}""".formatted(candidate))),
                        400, "invalidValue");
                assertThat(error.get("detail").asText()).contains(rule);
                assertThat(error.toString()).doesNotContain(candidate);
                t.assertUnchanged(user, resource);
            });
        }

        add(all, "User mutability: groups is read-only and ignored on create", t -> {
            Resource group = t.create(Kind.GROUP);
            Resource resource = t.create(user, user.create(name()).replace("{",
                    "{\"groups\":[{\"value\":\"" + group.id() + "\"}],"));
            assertThat(resource.body().has("groups")).isFalse();
            JsonNode reread = json(t.expect(t.scim(HttpMethod.GET, Kind.GROUP.one(group.id())),
                    200));
            assertThat(reread.has("members")).isFalse();
        });

        add(all, "User mutability: PUT clears an omitted optional attribute", t -> {
            Resource resource = t.create(user, """
                    {"schemas":["%s"],"userName":"%s","displayName":"D","locale":"en-GB"}"""
                    .formatted(USER_SCHEMA, name()));
            JsonNode replaced = json(t.expect(body(t.scim(HttpMethod.PUT, user.one(resource.id()))
                    .header(HttpHeaders.IF_MATCH, resource.etag()), """
                    {"schemas":["%s"],"userName":"%s"}"""
                    .formatted(USER_SCHEMA, resource.body().get("userName").asText())), 200));
            assertThat(replaced.has("displayName")).isFalse();
            assertThat(replaced.has("locale")).isFalse();
        });

        add(all, "User mutability: PATCH on groups is refused as read-only", t -> {
            Resource resource = t.create(user);
            t.expectError(body(t.scim(HttpMethod.PATCH, user.one(resource.id()))
                    .header(HttpHeaders.IF_MATCH, resource.etag()), Kind.patch("""
                    {"op":"add","path":"groups","value":[{"value":"%s"}]}"""
                    .formatted(UUID.randomUUID()))), 400, "mutability");
        });

        add(all, "User patch: a filtered replace matching no value is noTarget", t -> {
            Resource resource = t.create(user, """
                    {"schemas":["%s"],"userName":"%s",
                     "emails":[{"value":"a@example.com","type":"work"}]}"""
                    .formatted(USER_SCHEMA, name()));
            t.expectError(body(t.scim(HttpMethod.PATCH, user.one(resource.id()))
                    .header(HttpHeaders.IF_MATCH, resource.etag()), Kind.patch("""
                    {"op":"replace","path":"emails[type eq \\"home\\"].value",
                     "value":"b@example.com"}""")), 400, "noTarget");
            t.assertUnchanged(user, resource);
        });

        add(all, "User patch: value-path add, replace and remove on emails", t -> {
            Resource resource = t.create(user, """
                    {"schemas":["%s"],"userName":"%s",
                     "emails":[{"value":"a@example.com","type":"work"},
                               {"value":"h@example.com","type":"home"}]}"""
                    .formatted(USER_SCHEMA, name()));
            JsonNode patched = json(t.expect(body(t.scim(HttpMethod.PATCH,
                    user.one(resource.id())).header(HttpHeaders.IF_MATCH, resource.etag()),
                    Kind.patch("""
                            {"op":"replace","path":"emails[type eq \\"work\\"].value",
                             "value":"w@example.com"}""", """
                            {"op":"remove","path":"emails[type eq \\"home\\"]"}""", """
                            {"op":"add","path":"name.givenName","value":"Given"}""")), 200));
            assertThat(listed(wrap(patched.get("emails")), "value"))
                    .containsExactly("w@example.com");
            assertThat(patched.at("/name/givenName").asText()).isEqualTo("Given");
        });

        add(all, "User patch: a path-less replace applies each attribute of its value", t -> {
            Resource resource = t.create(user);
            JsonNode patched = json(t.expect(body(t.scim(HttpMethod.PATCH,
                    user.one(resource.id())).header(HttpHeaders.IF_MATCH, resource.etag()),
                    Kind.patch("""
                            {"op":"replace","value":{"displayName":"Whole","locale":"fr-FR"}}""")),
                    200));
            assertThat(patched.get("displayName").asText()).isEqualTo("Whole");
            assertThat(patched.get("locale").asText()).isEqualTo("fr-FR");
        });

        add(all, "User protected: the Bootstrap Admin cannot be deleted", t -> {
            JsonNode found = json(t.expect(t.scim(HttpMethod.GET, user.collection())
                    .param("filter", "userName eq \"test-admin\""), 200));
            String id = found.at("/Resources/0/id").asText();
            String etag = t.etag(user, UUID.fromString(id));
            JsonNode refused = t.expectError(t.scim(HttpMethod.DELETE, user.one(id))
                    .header(HttpHeaders.IF_MATCH, etag), 400, "mutability");
            assertThat(refused.get("detail").asText()).startsWith("This User is reserved");
            assertThat(t.etag(user, UUID.fromString(id))).isEqualTo(etag);
        });
    }

    private static JsonNode wrap(JsonNode resources) {
        return tools.jackson.databind.node.JsonNodeFactory.instance.objectNode()
                .set("Resources", resources);
    }

    // ==== Group-only conditions ===========================================================

    static void groupOnly(List<Fixture> all) {
        Kind group = Kind.GROUP;

        add(all, "Group members: create, add, replace and remove by value path", t -> {
            Resource ada = t.create(Kind.USER);
            Resource bob = t.create(Kind.USER);
            Resource resource = t.create(group, """
                    {"schemas":["%s"],"displayName":"%s","members":[{"value":"%s"}]}"""
                    .formatted(GROUP_SCHEMA, name(), ada.id()));
            JsonNode member = resource.body().at("/members/0");
            assertThat(member.get("value").asText()).isEqualTo(ada.id().toString());
            assertThat(member.get("type").asText()).isEqualTo("User");
            assertThat(member.get("$ref").asText()).endsWith(Kind.USER.one(ada.id()));
            MvcResult added = t.expect(body(t.scim(HttpMethod.PATCH, group.one(resource.id()))
                    .header(HttpHeaders.IF_MATCH, resource.etag()), Kind.patch("""
                    {"op":"add","path":"members","value":[{"value":"%s"}]}"""
                    .formatted(bob.id()))), 200);
            assertThat(listed(wrap(json(added).get("members")), "value"))
                    .containsExactlyInAnyOrder(ada.id().toString(), bob.id().toString());
            MvcResult removed = t.expect(body(t.scim(HttpMethod.PATCH, group.one(resource.id()))
                    .header(HttpHeaders.IF_MATCH, added.getResponse().getHeader(HttpHeaders.ETAG)),
                    Kind.patch("""
                            {"op":"remove","path":"members[value eq \\"%s\\"]"}"""
                            .formatted(ada.id()))), 200);
            assertThat(listed(wrap(json(removed).get("members")), "value"))
                    .containsExactly(bob.id().toString());
            JsonNode bobs = json(t.expect(t.scim(HttpMethod.GET, Kind.USER.one(bob.id())), 200));
            assertThat(bobs.at("/groups/0/value").asText()).isEqualTo(resource.id().toString());
            assertThat(bobs.at("/groups/0/type").asText()).isEqualTo("direct");
        });

        add(all, "Group members: a member that is not a live User is invalidValue", t -> {
            Resource other = t.create(group);
            for (String id : List.of(UUID.randomUUID().toString(), other.id().toString())) {
                t.expectError(body(t.scim(HttpMethod.POST, group.collection()), """
                        {"schemas":["%s"],"displayName":"%s","members":[{"value":"%s"}]}"""
                        .formatted(GROUP_SCHEMA, name(), id)), 400, "invalidValue");
            }
        });

        add(all, "Group members: read-only member sub-attributes are ignored", t -> {
            Resource ada = t.create(Kind.USER);
            Resource resource = t.create(group, """
                    {"schemas":["%s"],"displayName":"%s",
                     "members":[{"value":"%s","display":"Forged","$ref":"https://evil/x"}]}"""
                    .formatted(GROUP_SCHEMA, name(), ada.id()));
            assertThat(resource.body().at("/members/0/display").asText()).isNotEqualTo("Forged");
            assertThat(resource.body().at("/members/0/$ref").asText()).doesNotContain("evil");
        });

        add(all, "Group members: a value path on anything but remove is invalidPath", t -> {
            Resource resource = t.create(group);
            t.expectError(body(t.scim(HttpMethod.PATCH, group.one(resource.id()))
                    .header(HttpHeaders.IF_MATCH, resource.etag()), Kind.patch("""
                    {"op":"add","path":"members[value eq \\"%s\\"]"}"""
                    .formatted(UUID.randomUUID()))), 400, "invalidPath");
        });

        add(all, "Group protected: the Admin group cannot be renamed or deleted", t -> {
            JsonNode found = json(t.expect(t.scim(HttpMethod.GET, group.collection())
                    .param("filter", "displayName eq \"Admins\""), 200));
            String id = found.at("/Resources/0/id").asText();
            String etag = t.etag(group, UUID.fromString(id));
            t.expectError(body(t.scim(HttpMethod.PATCH, group.one(id))
                    .header(HttpHeaders.IF_MATCH, etag), group.patchRename(name())),
                    400, "mutability");
            JsonNode refused = t.expectError(t.scim(HttpMethod.DELETE, group.one(id))
                    .header(HttpHeaders.IF_MATCH, etag), 400, "mutability");
            assertThat(refused.get("detail").asText()).startsWith("This Group is reserved");
            assertThat(t.etag(group, UUID.fromString(id))).isEqualTo(etag);
        });
    }

    // ==== filter grammar (RFC 7644 §3.4.2.2) ==============================================

    static void filterGrammar(List<Fixture> all) {
        // filter -> expected suffixes of the three seeded Users (-a Alice, -b Bob, -c Carol)
        Map<String, List<String>> users = new java.util.LinkedHashMap<>();
        users.put("userName eq \"P-A\" (case-insensitive value)", List.of("-a"));
        users.put("displayName eq \"alice smith\" (case-insensitive value)", List.of("-a"));
        users.put("displayName co \"o\"", List.of("-b"));
        users.put("userName ew \"-b\"", List.of("-b"));
        users.put("displayName pr", List.of("-a", "-b"));
        users.put("not (displayName pr)", List.of("-c"));
        users.put("active eq false", List.of("-b"));
        users.put("displayName ne \"Alice Smith\"", List.of("-b", "-c"));
        users.put("emails[type eq \"work\" and value co \"@example.com\"]", List.of("-a"));
        users.put("emails co \"example.org\"", List.of("-b"));
        users.put("emails.type eq \"home\"", List.of("-b"));
        users.put("name.givenName sw \"Car\"", List.of("-c"));
        users.put("userName eq \"P-a\" or userName eq \"P-b\" and active eq true", List.of("-a"));
        users.put("(userName eq \"P-a\" or userName eq \"P-b\") and active eq false",
                List.of("-b"));
        users.put("USERNAME EQ \"P-c\" (case-insensitive name and operator)", List.of("-c"));
        users.put(USER_SCHEMA + ":userName eq \"P-c\"", List.of("-c"));
        users.put("meta.created gt \"2000-01-01T00:00:00Z\"", List.of("-a", "-b", "-c"));
        users.put("meta.lastModified lt \"2000-01-01T00:00:00Z\"", List.of());
        users.put("displayName eq null", List.of("-c"));
        for (Map.Entry<String, List<String>> filter : users.entrySet()) {
            add(all, "User filter grammar: " + filter.getKey(), t -> {
                String prefix = name();
                seedUsers(t, prefix);
                String expression = filter.getKey().replaceAll(" \\((case|caseExact)[^)]*\\)$", "")
                        .replace("P-", prefix + "-");
                JsonNode result = json(t.expect(body(t.scim(HttpMethod.POST,
                        Kind.USER.collection() + "/.search"), """
                        {"schemas":["%s"],"filter":%s,"sortBy":"userName"}"""
                        .formatted(SEARCH_REQUEST, quote("(" + expression + ") and userName sw \""
                                + prefix + "\""))), 200));
                assertThat(listed(result, "userName")).as(expression).containsExactlyElementsOf(
                        filter.getValue().stream().map(suffix -> prefix + suffix).toList());
            });
        }

        Map<String, List<String>> groups = new java.util.LinkedHashMap<>();
        groups.put("members pr", List.of("-eng"));
        groups.put("not (members pr)", List.of("-ops"));
        groups.put("members.value eq \"MEMBER\"", List.of("-eng"));
        groups.put("members eq \"MEMBER\"", List.of("-eng"));
        groups.put("displayName ew \"-ops\"", List.of("-ops"));
        for (Map.Entry<String, List<String>> filter : groups.entrySet()) {
            add(all, "Group filter grammar: " + filter.getKey(), t -> {
                String prefix = name();
                Resource member = t.create(Kind.USER);
                t.create(Kind.GROUP, """
                        {"schemas":["%s"],"displayName":"%s-eng","members":[{"value":"%s"}]}"""
                        .formatted(GROUP_SCHEMA, prefix, member.id()));
                t.create(Kind.GROUP, Kind.GROUP.create(prefix + "-ops"));
                String expression = filter.getKey().replace("MEMBER", member.id().toString());
                JsonNode result = json(t.expect(t.scim(HttpMethod.GET, Kind.GROUP.collection())
                        .param("filter", "(" + expression + ") and displayName sw \"" + prefix
                                + "\"").param("sortBy", "displayName"), 200));
                assertThat(listed(result, "displayName")).as(expression).containsExactlyElementsOf(
                        filter.getValue().stream().map(suffix -> prefix + suffix).toList());
            });
        }

        add(all, "Base search: /.search spans Users and Groups", t -> {
            String prefix = name();
            t.create(Kind.USER, Kind.USER.create(prefix + "-u"));
            t.create(Kind.GROUP, Kind.GROUP.create(prefix + "-g"));
            JsonNode result = json(t.expect(body(t.scim(HttpMethod.POST, BASE + "/.search"), """
                    {"schemas":["%s"],"filter":"displayName sw \\"%s\\" or userName sw \\"%s\\""}"""
                    .formatted(SEARCH_REQUEST, prefix, prefix)), 200));
            assertThat(result.get("totalResults").asInt()).isEqualTo(2);
            List<String> types = new ArrayList<>();
            for (JsonNode resource : result.get("Resources")) {
                types.add(resource.at("/meta/resourceType").asText());
            }
            assertThat(types).containsExactlyInAnyOrder("User", "Group");
        });

        add(all, "Base search: malformed filter and read-only token", t -> {
            t.expectError(body(t.scim(HttpMethod.POST, BASE + "/.search"),
                    "{\"schemas\":[\"" + SEARCH_REQUEST + "\"],\"filter\":\"userName eq\"}"),
                    400, "invalidFilter");
            t.expect(body(t.as(t.readOnlyToken, HttpMethod.POST, BASE + "/.search"),
                    "{\"schemas\":[\"" + SEARCH_REQUEST + "\"],\"count\":0}"), 200);
            MvcResult unauthenticated = t.expect(body(t.as(null, HttpMethod.POST,
                    BASE + "/.search"), "{\"schemas\":[\"" + SEARCH_REQUEST + "\"]}"), 401);
            assertThat(unauthenticated.getResponse().getContentAsByteArray()).isEmpty();
        });

        // ADR 0015: the base search returns only the types the token may read.
        add(all, "Base search: a user:read-only token gets Users and no Groups", t -> {
            Resource user = t.create(Kind.USER);
            Resource group = t.create(Kind.GROUP);
            String filter = "id eq \\\"" + user.id() + "\\\" or id eq \\\"" + group.id() + "\\\"";
            String request = "{\"schemas\":[\"" + SEARCH_REQUEST + "\"],\"filter\":\"" + filter
                    + "\"}";
            JsonNode both = json(t.expect(body(t.as(t.readOnlyToken, HttpMethod.POST,
                    BASE + "/.search"), request), 200));
            assertThat(both.get("totalResults").asInt()).as("both types match").isEqualTo(2);

            JsonNode usersOnly = json(t.expect(body(t.as(t.userReadToken, HttpMethod.POST,
                    BASE + "/.search"), request), 200));
            assertThat(usersOnly.get("totalResults").asInt()).isEqualTo(1);
            assertThat(usersOnly.get("Resources")).singleElement().satisfies(resource -> {
                assertThat(resource.get("id").asText()).isEqualTo(user.id().toString());
                assertThat(resource.at("/meta/resourceType").asText()).isEqualTo("User");
            });
        });

        add(all, "Base search: a token holding no read Permission is 403", t -> {
            MvcResult refused = t.expect(body(t.as(t.writeOnlyToken, HttpMethod.POST,
                    BASE + "/.search"), "{\"schemas\":[\"" + SEARCH_REQUEST + "\"]}"), 403);
            assertThat(refused.getResponse().getHeader(HttpHeaders.WWW_AUTHENTICATE))
                    .isEqualTo("Bearer error=\"insufficient_scope\"");
            assertThat(refused.getResponse().getContentAsByteArray()).isEmpty();
        });

        List<String> invalid = List.of(
                "userName eq",
                "userName xx \"a\"",
                "nickName eq \"a\"",
                "active gt true",
                "meta.created co \"2020\"",
                "(userName eq \"a\"",
                "userName eq \"a\" and",
                "emails[type eq \"work\"",
                "userName eq 'single-quoted'",
                "x".repeat(8193),
                "(".repeat(21) + "userName pr" + ")".repeat(21));
        for (String filter : invalid) {
            String label = filter.length() > 40 ? filter.substring(0, 12) + "… (" + filter.length()
                    + " chars)" : filter;
            add(all, "User filter grammar refused: " + label, t ->
                    t.expectError(t.scim(HttpMethod.GET, Kind.USER.collection())
                            .param("filter", filter), 400, "invalidFilter"));
        }
        add(all, "User filter grammar refused: more than 100 expressions", t -> {
            String filter = String.join(" or ",
                    java.util.Collections.nCopies(101, "userName eq \"a\""));
            t.expectError(body(t.scim(HttpMethod.POST, Kind.USER.collection() + "/.search"),
                    "{\"schemas\":[\"" + SEARCH_REQUEST + "\"],\"filter\":" + quote(filter) + "}"),
                    400, "invalidFilter");
        });
    }

    private static void seedUsers(ScimConformanceFixtureTests t, String prefix) throws Exception {
        t.create(Kind.USER, """
                {"schemas":["%s"],"userName":"%s-a","displayName":"Alice Smith",
                 "emails":[{"value":"alice@example.com","type":"work"}]}"""
                .formatted(USER_SCHEMA, prefix));
        t.create(Kind.USER, """
                {"schemas":["%s"],"userName":"%s-b","displayName":"Bob Jones","active":false,
                 "emails":[{"value":"bob@example.org","type":"home"}]}"""
                .formatted(USER_SCHEMA, prefix));
        t.create(Kind.USER, """
                {"schemas":["%s"],"userName":"%s-c","name":{"givenName":"Carol"}}"""
                .formatted(USER_SCHEMA, prefix));
    }

    private static String quote(String value) {
        return tools.jackson.databind.node.JsonNodeFactory.instance.stringNode(value).toString();
    }

    // ==== namespace-wide conditions =======================================================

    static void namespace(List<Fixture> all) {
        add(all, "namespace 404: a path no endpoint serves, as a SCIM error", t -> {
            t.expectError(t.scim(HttpMethod.GET, BASE + "/Devices"), 404, null);
            t.expectError(t.scim(HttpMethod.GET, BASE + "/Users/" + UUID.randomUUID() + "/x"),
                    404, null);
        });
        add(all, "namespace 404: Bulk is not implemented and not served", t ->
                t.expectError(body(t.scim(HttpMethod.POST, BASE + "/Bulk"), "{}"), 404, null));
        add(all, "namespace 401: a path no endpoint serves still needs a credential", t -> {
            MvcResult refused = t.expect(t.as(null, HttpMethod.GET, BASE + "/Devices"), 401);
            assertThat(refused.getResponse().getContentAsByteArray()).isEmpty();
        });
        add(all, "namespace 405: GET on the base search endpoint", t ->
                t.expectError(t.scim(HttpMethod.GET, BASE + "/.search"), 405, null));
        add(all, "namespace 413: an oversized body on the base search endpoint", t ->
                t.expectError(body(t.scim(HttpMethod.POST, BASE + "/.search"),
                        oversized(Kind.USER, ONE_MIB + 1)), 413, null));
        add(all, "namespace 415: a non-JSON body on the base search endpoint", t ->
                t.expectError(t.scim(HttpMethod.POST, BASE + "/.search")
                        .contentType(MediaType.TEXT_PLAIN).content("{}"), 415, null));
        for (HttpMethod method : List.of(HttpMethod.GET, HttpMethod.POST, HttpMethod.PUT,
                HttpMethod.PATCH, HttpMethod.DELETE)) {
            add(all, "/Me 501: " + method + " is not implemented", t ->
                    t.expectError(t.scim(method, BASE + "/Me").contentType(SCIM_JSON)
                            .content("{}"), 501, null));
            add(all, "/Me 401: " + method + " needs a credential first", t -> {
                MvcResult refused = t.expect(t.as(null, method, BASE + "/Me"), 401);
                assertThat(refused.getResponse().getContentAsByteArray()).isEmpty();
            });
        }
        add(all, "namespace: responses default to application/scim+json", t -> {
            MvcResult result = t.expect(t.scim(HttpMethod.GET, Kind.USER.collection())
                    .param("count", "0"), 200);
            assertThat(result.getResponse().getContentType()).startsWith("application/scim+json");
        });
    }
}

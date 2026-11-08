package com.example.backend.scim.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import com.example.backend.TokenPermissions;
import com.example.backend.scim.InMemoryScimUserRepository;
import com.example.backend.scim.domain.AuthenticatedConnector;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * What the User endpoints answer besides the resource document: the status, the {@code Location}
 * and {@code ETag} each write and read carries, the one {@code 404} for an id that names no User,
 * a delete, and the projection a query applies to each resource it lists. What each writable
 * attribute reads back as is {@code ScimUserAttributeContractTests}'.
 */
class ScimUserControllerTests {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private static final AuthenticatedConnector CONNECTOR = new AuthenticatedConnector(
            UUID.randomUUID(), UUID.randomUUID(), TokenPermissions.of(TokenPermissions.ALL));

    private static final String USERS = "http://localhost/scim/v2/Users/";

    private static final String SEARCH_REQUEST =
            "urn:ietf:params:scim:api:messages:2.0:SearchRequest";

    private final ScimUserController controller =
            ScimUserControllers.over(new InMemoryScimUserRepository());

    /** The renderer builds every location from the current request. */
    @BeforeEach
    void bindARequest() {
        RequestContextHolder.setRequestAttributes(
                new ServletRequestAttributes(new MockHttpServletRequest()));
    }

    @AfterEach
    void unbindTheRequest() {
        RequestContextHolder.resetRequestAttributes();
    }

    // ---- what a write and a read carry ----------------------------------------------------

    /** RFC 7644 §3.3: a create answers 201 with the new resource's location and version. */
    @Test
    void a_create_answers_201_with_the_users_location_and_etag() {
        ResponseEntity<Map<String, Object>> created = create("ada");

        assertThat(answer(created)).isEqualTo(List.of(
                HttpStatus.CREATED, USERS + idOf(created), "\"1\""));
    }

    @Test
    void a_read_answers_200_with_the_users_location_and_etag() {
        UUID id = idOf(create("ada"));

        assertThat(answer(controller.byId(CONNECTOR, id.toString(), null, null)))
                .isEqualTo(List.of(HttpStatus.OK, USERS + id, "\"1\""));
    }

    @Test
    void a_replacement_answers_200_with_the_users_location_and_new_etag() {
        UUID id = idOf(create("ada"));

        assertThat(answer(controller.replace(
                        CONNECTOR, id.toString(), null, user("ada.lovelace"), null, null)))
                .isEqualTo(List.of(HttpStatus.OK, USERS + id, "\"2\""));
    }

    @Test
    void a_patch_answers_200_with_the_users_location_and_new_etag() {
        UUID id = idOf(create("ada"));

        assertThat(answer(controller.patch(CONNECTOR, id.toString(), null, JSON.readTree("""
                        {"schemas":["urn:ietf:params:scim:api:messages:2.0:PatchOp"],
                         "Operations":[{"op":"replace","path":"displayName","value":"Ada"}]}"""),
                        null, null)))
                .isEqualTo(List.of(HttpStatus.OK, USERS + id, "\"2\""));
    }

    // ---- delete ---------------------------------------------------------------------------

    /** RFC 7644 §3.6: a delete answers 204 with no body. */
    @Test
    void a_delete_answers_204_with_no_body() {
        UUID id = idOf(create("ada"));

        ResponseEntity<Void> deleted = controller.delete(CONNECTOR, id.toString(), null);

        assertThat(List.of(deleted.getStatusCode(), deleted.hasBody()))
                .isEqualTo(List.of(HttpStatus.NO_CONTENT, false));
    }

    @Test
    void a_deleted_user_is_not_found() {
        UUID id = idOf(create("ada"));
        controller.delete(CONNECTOR, id.toString(), null);

        assertThat(notFound(Verb.READ, id.toString())).isEqualTo(NOT_FOUND);
    }

    // ---- an id that names no User ---------------------------------------------------------

    private static final List<Object> NOT_FOUND = List.of(HttpStatus.NOT_FOUND, "No User has that id.");

    /** Each verb on an id that names nothing, whether a UUID or not, gets the same 404. */
    enum Verb {
        READ, REPLACE, PATCH, DELETE
    }

    static Stream<Arguments> idsNamingNoUser() {
        return Stream.of(Verb.values()).flatMap(verb -> Stream.of(
                Arguments.of(verb, UUID.randomUUID().toString()),
                Arguments.of(verb, "not-a-uuid")));
    }

    @ParameterizedTest(name = "{0} {1}")
    @MethodSource("idsNamingNoUser")
    void an_id_naming_no_user_is_not_found(Verb verb, String id) {
        assertThat(notFound(verb, id)).isEqualTo(NOT_FOUND);
    }

    // ---- a query --------------------------------------------------------------------------

    /**
     * Each listed resource is projected as the query asked, whichever form carries the query:
     * {@code attributes} keeps the named ones beside those always returned, and
     * {@code excludedAttributes} drops the named ones.
     */
    static Stream<Arguments> projectedQueries() {
        return Stream.of(
                Arguments.of("GET with attributes", Projection.ATTRIBUTES, false),
                Arguments.of("GET with excludedAttributes", Projection.EXCLUDED, false),
                Arguments.of("POST .search with attributes", Projection.ATTRIBUTES, true),
                Arguments.of("POST .search with excludedAttributes", Projection.EXCLUDED, true));
    }

    enum Projection {
        ATTRIBUTES, EXCLUDED
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("projectedQueries")
    void a_query_projects_each_listed_user_as_asked(
            String description, Projection projection, boolean searchRequest) {
        UUID id = idOf(controller.create(CONNECTOR, JSON.readTree("""
                {"schemas":["urn:ietf:params:scim:schemas:core:2.0:User"],
                 "userName":"ada","displayName":"Ada Lovelace"}"""), null, null));
        String attributes = projection == Projection.ATTRIBUTES ? "displayName" : null;
        String excluded = projection == Projection.EXCLUDED ? "displayName" : null;

        ResponseEntity<Map<String, Object>> listed = searchRequest
                ? controller.search(CONNECTOR, searchRequest(attributes, excluded))
                : controller.page(CONNECTOR, null, null, null, null, null, attributes, excluded);

        assertThat(JSON.valueToTree(listed.getBody()).get("Resources")).isEqualTo(JSON.readTree(
                projection == Projection.ATTRIBUTES
                        ? """
                          [{"schemas":["urn:ietf:params:scim:schemas:core:2.0:User"],
                            "id":"%1$s","displayName":"Ada Lovelace"}]""".formatted(id)
                        : """
                          [{"schemas":["urn:ietf:params:scim:schemas:core:2.0:User"],
                            "id":"%1$s","userName":"ada","active":true,
                            "meta":{"resourceType":"User",
                                    "created":"2026-01-01T00:00:00Z",
                                    "lastModified":"2026-01-01T00:00:00Z",
                                    "location":"http://localhost/scim/v2/Users/%1$s",
                                    "version":"\\"1\\""}}]""".formatted(id)));
    }

    // ---- the interface --------------------------------------------------------------------

    private static JsonNode user(String userName) {
        return JSON.readTree("""
                {"schemas":["urn:ietf:params:scim:schemas:core:2.0:User"],"userName":"%s"}"""
                .formatted(userName));
    }

    private ResponseEntity<Map<String, Object>> create(String userName) {
        return controller.create(CONNECTOR, user(userName), null, null);
    }

    private static UUID idOf(ResponseEntity<Map<String, Object>> response) {
        return UUID.fromString((String) response.getBody().get("id"));
    }

    /** The status, {@code Location} and {@code ETag} a response carries. */
    private static List<Object> answer(ResponseEntity<?> response) {
        return List.of(response.getStatusCode(),
                response.getHeaders().getFirst(HttpHeaders.LOCATION),
                response.getHeaders().getETag());
    }

    private static JsonNode searchRequest(String attributes, String excluded) {
        var body = JSON.createObjectNode();
        body.set("schemas", JSON.createArrayNode().add(SEARCH_REQUEST));
        if (attributes != null) {
            body.set("attributes", JSON.createArrayNode().add(attributes));
        }
        if (excluded != null) {
            body.set("excludedAttributes", JSON.createArrayNode().add(excluded));
        }
        return body;
    }

    /** The status and detail a verb on this id is refused with. */
    private List<Object> notFound(Verb verb, String id) {
        ScimErrorException refused = catchThrowableOfType(ScimErrorException.class, () -> {
            switch (verb) {
                case READ -> controller.byId(CONNECTOR, id, null, null);
                case REPLACE -> controller.replace(CONNECTOR, id, null, user("ada"), null, null);
                case PATCH -> controller.patch(CONNECTOR, id, null, JSON.readTree("""
                        {"schemas":["urn:ietf:params:scim:api:messages:2.0:PatchOp"],
                         "Operations":[{"op":"replace","path":"displayName","value":"Ada"}]}"""),
                        null, null);
                case DELETE -> controller.delete(CONNECTOR, id, null);
            }
        });
        return List.of(refused.status(), refused.detail());
    }
}

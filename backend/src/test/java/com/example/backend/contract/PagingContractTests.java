package com.example.backend.contract;

import static com.example.backend.ScimHttp.asConnector;
import static com.example.backend.SessionLogin.logInSession;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.example.backend.ContainerTestConfiguration;
import com.example.backend.ScimTestRows;
import com.example.backend.TestJson;
import com.example.backend.TokenPermissions;
import com.example.backend.contract.OpenApiContract.Access;
import com.example.backend.contract.OpenApiContract.Operation;
import com.example.backend.scim.application.ConnectorAdministrationService;
import com.example.backend.scim.domain.ReservedResourceName;
import com.example.backend.scim.domain.ScimGroupRepository;
import jakarta.servlet.Filter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.JsonNode;

/**
 * The paging contract, driven by the API document: every documented operation that takes
 * {@code startIndex} and {@code count} coerces them over the real binder, never refusing a value
 * out of range or too large for an {@code int} (ADR 0017, D1).
 *
 * <p>The coercion's table — every boundary, the default and the cap — is
 * {@code PageRequestTests}' and {@code PageParameterTests}'. This sweep proves only that each
 * listing reads its paging through them, end to end, so a listing added to the document is held
 * to it the moment it exists. For each operation, as a caller holding what its {@code security}
 * asks — a Superuser session on the application chain, a reading connector token on SCIM's:
 *
 * <ul>
 *   <li>no paging at all is the first page;
 *   <li>a {@code startIndex} far below one and a {@code count} beyond the {@code int} range are the
 *       first page, answered rather than refused;
 *   <li>a {@code startIndex} beyond the {@code int} range is the last index there is, and a
 *       negative {@code count} is a page with no rows.
 * </ul>
 *
 * <p>The rows are read generically: whatever array the page carries them in, beside SCIM's
 * {@code schemas}, is empty, and SCIM's {@code Resources} may be left out of an empty page
 * altogether.
 */
@SpringBootTest
@Import(ContainerTestConfiguration.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PagingContractTests {

    private static final OpenApiContract CONTRACT = OpenApiContract.load();

    private static final Pattern PATH_VARIABLE = Pattern.compile("\\{([^}]+)}");

    @Autowired
    private WebApplicationContext context;

    @Autowired
    @Qualifier("springSecurityFilterChain")
    private Filter springSecurityFilterChain;

    @Autowired
    private ConnectorAdministrationService connectors;

    @Autowired
    private ScimGroupRepository groups;

    private MockMvc mvc;

    private MockHttpSession superuser;

    private UUID connectorId;

    private String readToken;

    /** Each path variable a paged operation names, and the resource this sweep reads under it. */
    private Map<String, String> pathValues;

    @BeforeAll
    void setUp() throws Exception {
        mvc = MockMvcBuilders.webAppContextSetup(context)
                .addFilters(springSecurityFilterChain)
                .build();
        superuser = logInSession(mvc, "test-admin", "test-admin-password");
        connectorId = connectors.create(ScimTestRows.unique("Paging contract"), null).id();
        readToken = connectors.issueToken(
                connectorId, TokenPermissions.READ, null, null).presentedValue();
        pathValues = Map.of(
                "id", groups.findByReservedName(ReservedResourceName.ADMIN_GROUP)
                        .orElseThrow().id().toString(),
                "connectorId", connectorId.toString());
    }

    @AfterAll
    void tearDown() {
        connectors.delete(connectorId, null);
    }

    /** A guard on the reading itself: an empty sweep would hold nothing. */
    @Test
    void the_document_has_paged_operations_on_both_chains() {
        assertThat(pagedOperations().map(operation -> operation.requirement().access()))
                .contains(Access.PERMISSION, Access.BEARER);
    }

    @ParameterizedTest(name = "{0} {1}")
    @MethodSource("pagedOperationCases")
    void every_paged_operation_coerces_its_paging(
            Operation operation, String query, long expectedStart, boolean noRows) throws Exception {
        MockHttpServletResponse response =
                mvc.perform(asItsCaller(operation, get(path(operation) + query))).andReturn()
                        .getResponse();

        assertThat(response.getStatus()).as("%s %s", operation, query).isEqualTo(200);
        JsonNode page = TestJson.JSON.readTree(response.getContentAsString());
        assertThat(page.get("startIndex").asLong()).as("startIndex").isEqualTo(expectedStart);
        if (noRows) {
            assertThat(rowArrays(page)).as("every array of rows").allMatch(JsonNode::isEmpty);
        }
    }

    static Stream<Arguments> pagedOperationCases() {
        return pagedOperations().flatMap(operation -> Stream.of(
                Arguments.of(operation, "", 1L, false),
                Arguments.of(operation, "?startIndex=-99999999999&count=99999999999", 1L, false),
                Arguments.of(operation, "?startIndex=99999999999&count=-1",
                        (long) Integer.MAX_VALUE, true)));
    }

    /** Every documented GET declaring both {@code startIndex} and {@code count} in its query. */
    private static Stream<Operation> pagedOperations() {
        return CONTRACT.operations().stream()
                .filter(operation -> operation.method().equals("GET"))
                .filter(operation -> queryParameters(operation).containsAll(
                        List.of("startIndex", "count")));
    }

    private static List<String> queryParameters(Operation operation) {
        JsonNode declared = operation.definition().path("parameters");
        return StreamSupport.stream(declared.spliterator(), false)
                .map(CONTRACT::resolve)
                .filter(parameter -> parameter.path("in").asText("").equals("query"))
                .map(parameter -> parameter.path("name").asText(""))
                .toList();
    }

    private String path(Operation operation) {
        Matcher variable = PATH_VARIABLE.matcher(operation.template());
        StringBuilder path = new StringBuilder();
        while (variable.find()) {
            String value = pathValues.get(variable.group(1));
            assertThat(value)
                    .as("a resource for {%s} of %s: a new paged operation names it in pathValues",
                            variable.group(1), operation)
                    .isNotNull();
            variable.appendReplacement(path, value);
        }
        return variable.appendTail(path).toString();
    }

    private MockHttpServletRequestBuilder asItsCaller(
            Operation operation, MockHttpServletRequestBuilder request) {
        return switch (operation.requirement().access()) {
            case BEARER -> asConnector(request, readToken);
            case PERMISSION, SUPERUSER -> request.session(superuser);
            default -> throw new AssertionError(
                    operation + " is paged but neither a session's nor a connector's");
        };
    }

    private static List<JsonNode> rowArrays(JsonNode page) {
        List<JsonNode> rows = new ArrayList<>();
        page.properties().forEach(field -> {
            if (field.getValue().isArray() && !field.getKey().equals("schemas")) {
                rows.add(field.getValue());
            }
        });
        return rows;
    }
}

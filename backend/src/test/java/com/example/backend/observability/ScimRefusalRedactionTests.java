package com.example.backend.observability;

import static com.example.backend.ScimHttp.SCIM_JSON;
import static com.example.backend.ScimHttp.asConnector;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.example.backend.ContainerTestConfiguration;
import com.example.backend.CreatedScimRows;
import com.example.backend.TokenPermissions;
import com.example.backend.scim.application.ConnectorAdministrationService;
import jakarta.servlet.Filter;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * A SCIM refusal's record never carries the value that was refused.
 *
 * <p>For each field the ticket names — userName, displayName, filter, password — a request
 * carrying a distinctive marker in that field is refused through the real stack, and the whole
 * encoded stream is searched for the marker. Each case first asserts its refusal record was
 * written, with the reason the refusal is, so the absence below is a statement about a stream
 * that recorded the refusal rather than about one that recorded nothing.
 */
@SpringBootTest
@Import(ContainerTestConfiguration.class)
class ScimRefusalRedactionTests {

    private static final String USER_SCHEMA = "urn:ietf:params:scim:schemas:core:2.0:User";

    private static final String GROUP_SCHEMA = "urn:ietf:params:scim:schemas:core:2.0:Group";

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private Environment environment;

    @Autowired
    private ConnectorAdministrationService connectors;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private RequestIdFilter requestIdFilter;

    @Autowired
    @Qualifier("springSecurityFilterChain")
    private Filter springSecurityFilterChain;

    private final CreatedScimRows created = new CreatedScimRows();

    private MockMvc mvc;

    private String token;

    private EcsLogCapture logs;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context)
                .addFilters(requestIdFilter, springSecurityFilterChain)
                .build();
        UUID connectorId = connectors.create("refusal-redaction", null).id();
        token = connectors.issueToken(
                connectorId, TokenPermissions.ALL, null, null).presentedValue();
        logs = EcsLogCapture.attach(environment);
    }

    @AfterEach
    void tearDown() {
        logs.close();
        created.deleteAll(jdbc);
    }

    /** Where the marker goes, the refusal that provokes, and the reason it is recorded as. */
    enum RefusedField {
        USER_NAME("uniqueness", 409),
        DISPLAY_NAME("uniqueness", 409),
        FILTER("invalidFilter", 400),
        PASSWORD("invalidValue", 400);

        final String reason;
        final int status;

        RefusedField(String reason, int status) {
            this.reason = reason;
            this.status = status;
        }
    }

    @ParameterizedTest
    @EnumSource(RefusedField.class)
    void the_refused_value_is_in_no_record(RefusedField field) throws Exception {
        String marker = "refusal-marker-" + UUID.randomUUID();

        MvcResult refused = switch (field) {
            case USER_NAME -> {
                created.create(mvc, token, "/scim/v2/Users", user(marker, null));
                logs.reset();
                yield mvc.perform(scim(post("/scim/v2/Users")).content(user(marker, null)))
                        .andReturn();
            }
            case DISPLAY_NAME -> {
                created.create(mvc, token, "/scim/v2/Groups", group(marker));
                logs.reset();
                yield mvc.perform(scim(post("/scim/v2/Groups")).content(group(marker)))
                        .andReturn();
            }
            case FILTER -> mvc.perform(scim(get("/scim/v2/Users"))
                            .queryParam("filter", "userName zz \"" + marker + "\""))
                    .andReturn();
            // A password containing the userName breaks the policy; the marker is the password.
            case PASSWORD -> {
                String userName = "pw-" + UUID.randomUUID();
                yield mvc.perform(scim(post("/scim/v2/Users"))
                                .content(user(userName, marker + userName)))
                        .andReturn();
            }
        };

        assertThat(refused.getResponse().getStatus()).isEqualTo(field.status);
        List<JsonNode> refusals = logs.records().stream()
                .filter(record -> "SCIM request refused".equals(record.at("/message").asText()))
                .toList();
        assertThat(refusals).as("the refusal's record").singleElement().satisfies(record -> {
            assertThat(record.at("/log/level").asText()).isEqualTo("WARN");
            assertThat(record.at("/event/reason").asText()).isEqualTo(field.reason);
            assertThat(record.at("/scim/connector/id").asText()).isNotBlank();
        });
        assertThat(logs.lines()).as("the whole log stream")
                .doesNotContain(marker)
                .doesNotContain("refusal-marker")
                .doesNotContain(token);
    }

    private MockHttpServletRequestBuilder scim(MockHttpServletRequestBuilder request) {
        return asConnector(request, token)
                .contentType(SCIM_JSON)
                .accept(SCIM_JSON);
    }

    private static String user(String userName, String password) {
        return password == null
                ? """
                        {"schemas":["%s"],"userName":"%s"}""".formatted(USER_SCHEMA, userName)
                : """
                        {"schemas":["%s"],"userName":"%s","password":"%s"}"""
                        .formatted(USER_SCHEMA, userName, password);
    }

    private static String group(String displayName) {
        return """
                {"schemas":["%s"],"displayName":"%s"}""".formatted(GROUP_SCHEMA, displayName);
    }
}

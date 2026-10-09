package com.example.backend.observability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import com.example.backend.ContainerTestConfiguration;
import com.example.backend.TokenPermissions;
import com.example.backend.scim.application.ConnectorAdministrationService;
import jakarta.servlet.Filter;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.PropertySourcesPropertyResolver;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.core.io.UrlResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.json.JsonMapper;

/**
 * A write the database refuses, through the real stack against the real Postgres schema, and
 * the whole log stream it produces searched for the value that was refused.
 *
 * <p>Postgres quotes the refused value in the message of every constraint violation — a unique
 * violation as {@code Key (normalized_user_name)=(...) already exists}, a CHECK or NOT NULL
 * violation as {@code Failing row contains (...)} — and Hibernate's JDBC error logger writes that
 * message out as it stands. The call is inside Hibernate, so Semgrep's {@code
 * be-log-sensitive-value} cannot see it; only a test of the emitted bytes can.
 *
 * <p>The capture is {@link EcsLogCapture} on the root logger, at the levels the context resolved
 * from the imported {@code log-levels.yaml}: a record from any logger is caught whether or not
 * this class knew to expect it.
 */
@SpringBootTest
@Import(ContainerTestConfiguration.class)
class JdbcErrorLogRedactionTests {

    private static final String JDBC_ERROR_LOGGER = "org.hibernate.orm.jdbc.error";

    private static final String USERS = "/scim/v2/Users";

    private static final String GROUPS = "/scim/v2/Groups";

    private static final String USER_SCHEMA = "urn:ietf:params:scim:schemas:core:2.0:User";

    private static final String GROUP_SCHEMA = "urn:ietf:params:scim:schemas:core:2.0:Group";

    private static final MediaType SCIM_JSON = MediaType.valueOf("application/scim+json");

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

    private final JsonMapper json = JsonMapper.builder().build();

    private final List<UUID> created = new ArrayList<>();

    private MockMvc mvc;

    private String writeToken;

    private EcsLogCapture logs;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context)
                .addFilters(requestIdFilter, springSecurityFilterChain)
                .build();
        UUID connectorId = connectors.create("Log redaction", "test-admin").id();
        writeToken = connectors.issueToken(connectorId, TokenPermissions.ALL, null,
                "test-admin", TokenPermissions.ALL).presentedValue();
        logs = EcsLogCapture.attach(environment);
    }

    @AfterEach
    void tearDown() {
        logs.close();
        for (UUID id : created) {
            jdbc.update("DELETE FROM scim_resources WHERE id = ? AND reserved_name IS NULL", id);
        }
        created.clear();
    }

    // ---- the setting, as deployed ------------------------------------------------------------

    /**
     * The committed default, resolved against {@code log-levels.yaml} alone so the assertion is
     * about the file that ships and not about a property some other source happens to set.
     */
    @Test
    void the_deployed_configuration_turns_hibernates_jdbc_error_logger_off() throws Exception {
        MutablePropertySources sources = new MutablePropertySources();
        for (PropertySource<?> source : new YamlPropertySourceLoader()
                .load("log-levels", new ClassPathResource("log-levels.yaml"))) {
            sources.addLast(source);
        }

        assertThat(new PropertySourcesPropertyResolver(sources)
                .getProperty("logging.level." + JDBC_ERROR_LOGGER))
                .isEqualToIgnoringCase("OFF");
    }

    /**
     * Both {@code application.yaml} documents on the test classpath — the main one under
     * {@code classes/} and the test one under {@code test-classes/} that shadows it — import the
     * document, so the deployed service and every test context resolve the same level.
     */
    @Test
    void the_main_and_the_test_application_configs_both_import_it() throws Exception {
        List<URL> applicationConfigs = Collections.list(
                getClass().getClassLoader().getResources("application.yaml"));

        assertThat(applicationConfigs)
                .as("the main and the test application.yaml")
                .hasSize(2)
                .anySatisfy(url -> assertThat(url.toString()).contains("/test-classes/"))
                .anySatisfy(url -> assertThat(url.toString()).doesNotContain("/test-classes/"));
        for (URL url : applicationConfigs) {
            assertThat(importsOf(new UrlResource(url)))
                    .as("spring.config.import of %s", url)
                    .contains("classpath:log-levels.yaml");
        }
    }

    /** The running context resolved the level from the import, not from a default. */
    @Test
    void the_running_context_applies_the_level() {
        LoggerContext loggerContext = (LoggerContext) LoggerFactory.getILoggerFactory();

        assertThat(loggerContext.getLogger(JDBC_ERROR_LOGGER).getEffectiveLevel())
                .isEqualTo(Level.OFF);
    }

    // ---- a refused write, end to end ---------------------------------------------------------

    /**
     * A {@code userName} collision and a Group {@code displayName} collision, each a {@code 409
     * uniqueness} decided by the database's unique constraint: no record carries the name.
     */
    @Test
    void a_name_collision_puts_the_submitted_name_in_no_log_record() throws Exception {
        String userName = "redact-user-" + UUID.randomUUID();
        String displayName = "redact-group-" + UUID.randomUUID();
        assertThat(status(createUser(userName, null))).isEqualTo(201);
        assertThat(status(createGroup(displayName))).isEqualTo(201);
        logs.reset();

        MvcResult userConflict = createUser(userName, null);
        MvcResult groupConflict = createGroup(displayName);

        assertThat(status(userConflict)).isEqualTo(409);
        assertThat(scimType(userConflict)).isEqualTo("uniqueness");
        assertThat(status(groupConflict)).isEqualTo(409);
        assertThat(scimType(groupConflict)).isEqualTo("uniqueness");
        assertNoRecordCarries(userName, displayName);
    }

    /**
     * A violation of a constraint that is not the live-name uniqueness — a CHECK added for this
     * test alone, which Postgres reports by quoting the whole failing row — on a create and on a
     * replace. Neither the refused value nor the {@code userName} in the same row reaches a log
     * record, while the application's own fault record for each does.
     */
    @Test
    void a_forced_non_uniqueness_violation_puts_no_value_of_the_row_in_any_log_record()
            throws Exception {
        String forced = "redact-forced-" + UUID.randomUUID();
        String newUserName = "redact-new-" + UUID.randomUUID();
        String existingUserName = "redact-existing-" + UUID.randomUUID();
        MvcResult existing = createUser(existingUserName, null);
        assertThat(status(existing)).isEqualTo(201);
        logs.reset();

        jdbc.execute("ALTER TABLE scim_users ADD CONSTRAINT ck_test_redaction_locale"
                + " CHECK (locale IS DISTINCT FROM '" + forced + "')");
        MvcResult refusedCreate;
        MvcResult refusedReplace;
        try {
            refusedCreate = createUser(newUserName, forced);
            refusedReplace = mvc.perform(asConnector(put(USERS + "/" + id(existing)))
                            .header(HttpHeaders.IF_MATCH,
                                    existing.getResponse().getHeader(HttpHeaders.ETAG))
                            .contentType(SCIM_JSON)
                            .content(userBody(existingUserName, forced)))
                    .andReturn();
        } finally {
            jdbc.execute("ALTER TABLE scim_users DROP CONSTRAINT ck_test_redaction_locale");
        }

        assertThat(status(refusedCreate)).isEqualTo(500);
        assertThat(status(refusedReplace)).isEqualTo(500);
        // The capture saw the two requests' own records, so the absence below is a statement
        // about a stream that was written to, not about an empty buffer.
        assertThat(logs.records())
                .filteredOn(record -> "SCIM write refused by an unmapped integrity violation"
                        .equals(record.at("/message").asText()))
                .hasSize(2);
        assertNoRecordCarries(forced, newUserName, existingUserName);
    }

    // ---- harness -----------------------------------------------------------------------------

    /**
     * Searches the raw encoded stream rather than selected fields, so a value in a message, a
     * field, a stack trace or the logging context all fail it — and the driver's own phrasing as
     * well, which would mean the logger is back even if this run's values happened not to show.
     */
    private void assertNoRecordCarries(String... values) {
        String stream = logs.lines();
        for (String value : values) {
            assertThat(stream).as("the log stream").doesNotContainIgnoringCase(value);
        }
        assertThat(stream)
                .doesNotContain("already exists")
                .doesNotContain("Failing row contains")
                .doesNotContain("HHH000247");
    }

    private static List<String> importsOf(Resource config) throws Exception {
        MutablePropertySources sources = new MutablePropertySources();
        for (PropertySource<?> source : new YamlPropertySourceLoader().load("config", config)) {
            sources.addLast(source);
        }
        String imports = new PropertySourcesPropertyResolver(sources)
                .getProperty("spring.config.import", "");
        return List.of(imports.split("\\s*,\\s*"));
    }

    private static String userBody(String userName, String locale) {
        return locale == null
                ? """
                        {"schemas":["%s"],"userName":"%s"}""".formatted(USER_SCHEMA, userName)
                : """
                        {"schemas":["%s"],"userName":"%s","locale":"%s"}"""
                        .formatted(USER_SCHEMA, userName, locale);
    }

    private MvcResult createUser(String userName, String locale) throws Exception {
        return track(mvc.perform(asConnector(post(USERS))
                        .contentType(SCIM_JSON)
                        .content(userBody(userName, locale)))
                .andReturn());
    }

    private MvcResult createGroup(String displayName) throws Exception {
        return track(mvc.perform(asConnector(post(GROUPS))
                        .contentType(SCIM_JSON)
                        .content("""
                                {"schemas":["%s"],"displayName":"%s"}"""
                                .formatted(GROUP_SCHEMA, displayName)))
                .andReturn());
    }

    private MvcResult track(MvcResult result) throws Exception {
        if (status(result) == 201) {
            created.add(id(result));
        }
        return result;
    }

    private MockHttpServletRequestBuilder asConnector(MockHttpServletRequestBuilder request) {
        return request.header(HttpHeaders.AUTHORIZATION, "Bearer " + writeToken);
    }

    private static int status(MvcResult result) {
        return result.getResponse().getStatus();
    }

    private String scimType(MvcResult result) throws Exception {
        return json.readTree(result.getResponse().getContentAsString()).get("scimType").asText();
    }

    private UUID id(MvcResult result) throws Exception {
        return UUID.fromString(
                json.readTree(result.getResponse().getContentAsString()).get("id").asText());
    }
}

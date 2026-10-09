package com.example.backend.observability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.backend.SessionCsrf;
import com.example.backend.TokenPermissions;
import com.example.backend.audit.domain.AuditOperation;
import com.example.backend.audit.domain.AuditRetentionPolicy;
import com.example.backend.audit.domain.OperationalAlerts;
import com.example.backend.counter.controller.FaultInjectionController;
import com.example.backend.scim.application.ConnectorAdministrationService;
import com.example.backend.scim.config.ScimErrorDocumentRecords;
import com.example.backend.scim.controller.ScimFaultRecords;
import com.example.backend.scim.domain.NormalizedUserName;
import com.example.backend.scim.domain.ScimAttributeLimits;
import com.example.backend.scim.domain.ScimUserRepository;
import com.example.backend.web.ApiExceptionHandler;
import jakarta.servlet.Filter;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.xpath.XPathFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.web.servlet.DelegatingFilterProxyRegistrationBean;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Import;
import org.springframework.core.annotation.OrderUtils;
import org.springframework.core.env.Environment;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.PropertySourcesPropertyResolver;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.scheduling.config.CronTask;
import org.springframework.scheduling.config.ScheduledTask;
import org.springframework.scheduling.config.ScheduledTaskHolder;
import org.springframework.scheduling.config.Task;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.filter.ServerHttpObservationFilter;
import org.w3c.dom.Document;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The log stream as an operator and a collector meet it: real requests through
 * the real filter chain, and every record they produce encoded in the format the
 * deployed service emits.
 *
 * <p>Two properties are asserted together because they hold each other up. The
 * output is ECS JSON, so a record's variable parts are named fields; and no
 * forbidden value appears in any of them, because a value that is never
 * concatenated into a message has nowhere to hide. Testing the format without the
 * redaction would bless a well-formed record that leaks a password; testing the
 * redaction without the format would pass over output no collector can read.
 *
 * <p>The three flows are the ones an investigation actually reads — an accepted
 * login, a refused login, and an administrative change — and are exactly the
 * three whose inputs are sensitive.
 */
@SpringBootTest
@Import(com.example.backend.ContainerTestConfiguration.class)
class EcsLogFormatTests {

    /**
     * Values that must never appear anywhere in the log stream. The credentials and
     * identifiers are the test fixtures' own, so a leak shows up as a literal
     * match; the rest are the shapes a secret takes in this service — a BCrypt
     * hash's prefix, and the two cookie names whose values are a session and a CSRF
     * token.
     */
    private static final List<String> FORBIDDEN = List.of(
            "test-user",
            "test-admin",
            "test-password",
            "test-admin-password",
            "not-a-real-account",
            "wrong-password",
            "$2a$",
            "JSESSIONID",
            "XSRF-TOKEN");

    private static final JsonMapper JSON = JsonMapper.builder().build();

    /** A W3C trace id: 32 lowercase hex digits, and not the all-zero invalid id. */
    private static final String TRACE_ID = "(?!0{32})[0-9a-f]{32}";

    /** A W3C span id: 16 lowercase hex digits, and not the all-zero invalid id. */
    private static final String SPAN_ID = "(?!0{16})[0-9a-f]{16}";

    /** Local Singapore time to the millisecond, with its offset. */
    private static final String PLUS_EIGHT_TIMESTAMP =
            "\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}\\.\\d{3}\\+08:00";

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private Environment environment;

    @Autowired
    private RequestIdFilter requestIdFilter;

    @Autowired
    @Qualifier("springSecurityFilterChain")
    private Filter springSecurityFilterChain;

    @Autowired
    private ScimUserRepository users;

    @Autowired
    private FilterRegistrationBean<ServerHttpObservationFilter> observationFilter;

    @Autowired
    @Qualifier("securityFilterChainRegistration")
    private DelegatingFilterProxyRegistrationBean securityRegistration;

    @Autowired
    private ScheduledTaskHolder scheduledTasks;

    @Autowired
    private AuditRetentionPolicy retentionPolicy;

    @Autowired
    private ConnectorAdministrationService connectors;

    @Autowired
    private ScheduledJobMetrics jobMetrics;

    @Autowired
    private OperationalAlerts alerts;

    @Autowired
    private ApiExceptionHandler apiExceptionHandler;

    private MockMvc mvc;

    private EcsLogCapture logs;

    @BeforeEach
    void setUp() {
        // The filters in their deployed order. The observation filter first
        // (HIGHEST_PRECEDENCE + 1): it opens the request's span, so every record of
        // the request — the request record the next filter writes on its way out
        // among them — carries its trace and span ids. Then the request-id filter,
        // ahead of the security chain: a request refused by the chain must still be
        // logged under an id, and still get its request record.
        mvc = MockMvcBuilders.webAppContextSetup(context)
                .addFilters(observationFilter.getFilter(), requestIdFilter, springSecurityFilterChain)
                .build();
        logs = EcsLogCapture.attach(environment);
    }

    @AfterEach
    void tearDown() {
        logs.close();
    }

    /**
     * The deployed value, not a copy of it. `logging.yaml` exists precisely so this
     * can be read: the test resources' `application.yaml` shadows the main one, so
     * a setting stated there would be invisible here and this test would be
     * asserting a duplicate of the production configuration rather than the
     * production configuration.
     */
    @Test
    void theDeployedConfigurationSelectsEcs() throws Exception {
        MutablePropertySources sources = new MutablePropertySources();
        for (PropertySource<?> source
                : new YamlPropertySourceLoader().load("logging", new ClassPathResource("logging.yaml"))) {
            sources.addLast(source);
        }

        // Resolved against this document alone, so the assertion is about the
        // committed default and not about whatever LOG_STRUCTURED_FORMAT happens to
        // be set to on the machine running the test.
        assertThat(new PropertySourcesPropertyResolver(sources)
                .getProperty("logging.structured.format.console"))
                .isEqualTo("ecs");
    }

    /**
     * The rest of the record envelope, as committed — the service fields, the timestamp
     * customizer, and a log file that exists only when {@code LOG_FILE} says so. The
     * version placeholder must have been filtered by the build; an unfiltered
     * {@code @project.version@} would be logged literally.
     */
    @Test
    void theDeployedConfigurationStatesTheEnvelope() throws Exception {
        MutablePropertySources sources = new MutablePropertySources();
        for (PropertySource<?> source
                : new YamlPropertySourceLoader().load("logging", new ClassPathResource("logging.yaml"))) {
            sources.addLast(source);
        }
        PropertySourcesPropertyResolver deployed = new PropertySourcesPropertyResolver(sources);

        assertThat(deployed.getProperty("logging.structured.format.file")).isEqualTo("ecs");
        assertThat(deployed.getProperty("logging.structured.ecs.service.name")).isEqualTo("backend");
        assertThat(deployed.getProperty("logging.structured.ecs.service.version"))
                .isEqualTo(builtProjectVersion());
        assertThat(deployed.getProperty("logging.structured.json.customizer[0]"))
                .isEqualTo(EcsTimestampCustomizer.class.getName());
        assertThat(deployed.getProperty("logging.structured.json.customizer[1]"))
                .isEqualTo(EcsErrorFieldsCustomizer.class.getName());
        // Resolved against this document alone, so neither LOG_FILE nor APP_ENVIRONMENT
        // on the machine running the test is seen: these are the committed defaults.
        assertThat(deployed.getProperty("logging.structured.ecs.service.environment"))
                .isEqualTo("local");
        assertThat(deployed.getProperty("logging.file.name")).isEmpty();
    }

    @Test
    void anAcceptedLoginIsOneEcsRecordCarryingTheRequestsCorrelationId() throws Exception {
        logIn("test-user", "test-password").andExpect(status().isOk());

        JsonNode record = onlyRecordWithMessage("Login accepted");

        assertThatIsValidEcs(record);
        assertThatClassifiedAs(record, "user-authentication", "process", "user", "allowed");
        // The record does carry app.login.method (D15); what it must not carry is a local name.
        assertThat(record.at("/app/event/action").isMissingNode())
                .as("an exact action keeps no local name").isTrue();
        assertThat(record.at("/event/outcome").asText()).isEqualTo("success");
        assertThat(record.at("/http/request/id").asText()).isNotBlank();
        assertThat(record.at("/log/level").asText()).isEqualTo("INFO");
    }

    /**
     * The login-success record names the identity that logged in, by its stable id —
     * set on the record itself, because the session's principal index that carries it
     * on later requests is written only after the record is emitted.
     */
    @Test
    void anAcceptedLoginCarriesTheIdentitysStableId() throws Exception {
        logIn("test-user", "test-password").andExpect(status().isOk());

        JsonNode record = onlyRecordWithMessage("Login accepted");

        assertThat(record.at("/user/id").asText()).isEqualTo(userId("test-user").toString());
    }

    /**
     * A refusal says only that the Login was refused (Logging §2.2): what kind of refusal
     * it was — a wrong password, a name that matches no account, a locked or deactivated
     * one — tells whether the account exists, so it is the audit trail's alone. Nor does
     * it say which account was named.
     */
    @Test
    void aRefusedLoginIsOneEcsRecordNamingNoReasonAndNoSubmittedValue()
            throws Exception {
        logIn("not-a-real-account", "wrong-password").andExpect(status().isUnauthorized());

        JsonNode record = onlyRecordWithMessage("Login refused");

        assertThatIsValidEcs(record);
        assertThatClassifiedAs(record, "user-authentication", "process", "user", "denied");
        assertThat(record.at("/event/outcome").asText()).isEqualTo("failure");
        assertThat(record.at("/event/reason").isMissingNode()).as("no refusal reason").isTrue();
        assertThat(record.toString()).doesNotContain("not-a-real-account");
        assertThat(record.at("/http/request/id").asText()).isNotBlank();
        assertThat(record.at("/log/level").asText()).isEqualTo("WARN");
        assertThat(record.has("user")).as("an unresolved identity carries no user field").isFalse();
    }

    /**
     * A refused attempt made from a session that is already authenticated still names
     * nobody: the session's User is not whom the attempt was for, so the record must not
     * inherit the request's {@code user.id}. The session's id really is in the context —
     * the earlier administrative record in the same session carries it — so the absence is
     * the refusal's doing, not the context's. The refusal then ends that session.
     */
    @Test
    void aRefusedLoginFromAnAuthenticatedSessionStillCarriesNoUserField() throws Exception {
        MockHttpSession admin = loggedInSession("test-admin", "test-admin-password");
        logs.reset();

        mvc.perform(withCsrf(post("/api/admin/accounts/{id}/unlock", userId("test-user")))
                        .session(admin))
                .andExpect(status().isOk());
        mvc.perform(withCsrf(post("/api/auth/login"))
                        .session(admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"not-a-real-account\",\"password\":\"wrong-password\"}"))
                .andExpect(status().isUnauthorized());

        assertThat(onlyRecordWithMessage("Login refused").has("user")).isFalse();
        assertThat(onlyRecordWithMessage("Administrative identity change applied")
                        .at("/user/id").asText())
                .isEqualTo(userId("test-admin").toString());
        assertThat(admin.isInvalid()).as("the refused Login ended the session").isTrue();
    }

    /**
     * An authenticated request's records name the caller by the stable id its session's
     * principal index holds, and an administrative change names the identity acted on
     * separately, as {@code user.target.id}.
     */
    @Test
    void anAdministrativeChangeIsOneEcsRecordNamingTheActorAndTheSubjectByStableId()
            throws Exception {
        MockHttpSession admin = loggedInSession("test-admin", "test-admin-password");
        logs.reset();

        mvc.perform(withCsrf(post("/api/admin/accounts/{id}/unlock", userId("test-user")))
                        .session(admin))
                .andExpect(status().isOk());

        JsonNode record = onlyRecordWithMessage("Administrative identity change applied");

        assertThatIsValidEcs(record);
        assertThatClassifiedAs(record, "access-control", "process", "admin", "user", "change");
        assertThat(record.at("/app/event/action").asText()).isEqualTo("identity.unlock");
        assertThat(record.at("/event/outcome").asText()).isEqualTo("success");
        assertThat(record.at("/http/request/id").asText()).isNotBlank();
        assertThat(record.at("/user/id").asText()).isEqualTo(userId("test-admin").toString());
        assertThat(record.at("/user/target/id").asText())
                .isEqualTo(userId("test-user").toString());
    }

    /**
     * Each connector token lifecycle write names the administrator who made it. No
     * {@code user.target.id}: the subject is a connector, which is not a User.
     */
    @Test
    void everyConnectorLifecycleRecordCarriesTheActorsStableId() throws Exception {
        MockHttpSession admin = loggedInSession("test-admin", "test-admin-password");
        logs.reset();

        String connectorId = json(mvc.perform(withCsrf(post("/api/admin/connectors"))
                        .session(admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"displayName\":\"ecs-log-connector\"}"))
                .andExpect(status().isCreated())).at("/id").asText();
        String issuedId = json(mvc.perform(withCsrf(
                                post("/api/admin/connectors/{c}/tokens", connectorId))
                        .session(admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"permissions\":" + TokenPermissions.ALL_JSON + "}"))
                .andExpect(status().isCreated())).at("/tokenId").asText();
        String rotatedId = json(mvc.perform(withCsrf(
                                post("/api/admin/connectors/{c}/tokens/{t}/rotate",
                                        connectorId, issuedId))
                        .session(admin))
                .andExpect(status().isCreated())).at("/tokenId").asText();
        mvc.perform(withCsrf(post("/api/admin/connectors/{c}/tokens/{t}/revoke",
                                connectorId, rotatedId))
                        .session(admin))
                .andExpect(status().isNoContent());
        mvc.perform(withCsrf(delete("/api/admin/connectors/{c}", connectorId)).session(admin))
                .andExpect(status().isNoContent());

        List<JsonNode> lifecycle = recordsWithMessage("SCIM connector lifecycle change applied");
        assertThat(lifecycle)
                .extracting(record -> record.at("/app/event/action").asText())
                .containsExactly(
                        "scim.connector.create",
                        "scim.connector.token.issue",
                        "scim.connector.token.rotate",
                        "scim.connector.token.revoke",
                        "scim.connector.delete");
        assertThat(lifecycle).allSatisfy(record -> {
            assertThat(record.at("/user/id").asText()).isEqualTo(userId("test-admin").toString());
            assertThat(record.at("/user").has("target")).isFalse();
        });
        assertThatClassifiedAs(lifecycle.get(0),
                "user-provisioning", "configuration", "admin", "creation");
        // Issue and rotation grant Permissions, so they are user administration (#117); the
        // connector's own lifecycle and a revocation remain the provisioning channel's.
        assertThatClassifiedAs(lifecycle.get(1),
                "user-administration", "configuration", "admin", "creation");
        assertThatClassifiedAs(lifecycle.get(2),
                "user-administration", "configuration", "admin", "change");
        assertThatClassifiedAs(lifecycle.get(3),
                "user-provisioning", "configuration", "admin", "deletion");
        assertThatClassifiedAs(lifecycle.get(4),
                "user-provisioning", "configuration", "admin", "deletion");
    }

    /**
     * Every record emitted inside a request carries that request's trace and span ids,
     * as ECS {@code trace.id} and {@code span.id}: one trace per request, and a different
     * one for the next request.
     */
    @Test
    void everyRecordInsideARequestCarriesItsTraceAndSpanIds() throws Exception {
        logIn("test-user", "test-password").andExpect(status().isOk());
        logIn("not-a-real-account", "wrong-password").andExpect(status().isUnauthorized());

        List<JsonNode> inRequest = logs.records().stream()
                .filter(record -> !record.at("/http/request/id").asText().isEmpty())
                .toList();
        assertThat(inRequest).as("records emitted inside a request").hasSizeGreaterThanOrEqualTo(2);
        assertThat(inRequest).allSatisfy(record -> {
            assertThat(record.at("/trace/id").asText()).matches(TRACE_ID);
            assertThat(record.at("/span/id").asText()).matches(SPAN_ID);
        });

        String accepted = onlyRecordWithMessage("Login accepted").at("/trace/id").asText();
        String refused = onlyRecordWithMessage("Login refused").at("/trace/id").asText();
        assertThat(accepted).isNotEqualTo(refused);
        Map<String, Set<String>> tracesByRequest = inRequest.stream().collect(Collectors.groupingBy(
                record -> record.at("/http/request/id").asText(),
                Collectors.mapping(record -> record.at("/trace/id").asText(), Collectors.toSet())));
        assertThat(tracesByRequest.values()).allSatisfy(traces -> assertThat(traces).hasSize(1));
    }

    /**
     * The trace id is minted here. A caller's {@code traceparent} is not adopted, for the
     * reason {@link RequestIdFilter} ignores {@code X-Request-Id}: a client able to choose
     * the id could merge unrelated requests in a log search.
     */
    @Test
    void aCallersTraceparentIsNotAdopted() throws Exception {
        String callersTrace = "4bf92f3577b34da6a3ce929d0e0e4736";

        mvc.perform(withCsrf(post("/api/auth/login"))
                        .header("traceparent", "00-" + callersTrace + "-00f067aa0ba902b7-01")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"test-user\",\"password\":\"test-password\"}"))
                .andExpect(status().isOk());

        assertThat(onlyRecordWithMessage("Login accepted").at("/trace/id").asText())
                .matches(TRACE_ID)
                .isNotEqualTo(callersTrace);
    }

    /**
     * A scheduled job runs off any request, so it gets a trace of its own from
     * {@link ScheduledJobMetrics#instrumentLocked}: every record of one run shares a trace id,
     * and the next run's differs. Run through the task the scheduler actually holds, not
     * a re-wrapped copy of the job.
     */
    @Test
    void eachScheduledJobRunIsItsOwnTrace() {
        List<Runnable> retentionTasks = scheduledTasks.getScheduledTasks().stream()
                .map(ScheduledTask::getTask)
                .filter(CronTask.class::isInstance)
                .map(CronTask.class::cast)
                .filter(task -> task.getExpression().equals(retentionPolicy.schedule()))
                .map(Task::getRunnable)
                .toList();
        assertThat(retentionTasks).as("the scheduled retention task").hasSize(1);
        Runnable retention = retentionTasks.getFirst();
        String thread = Thread.currentThread().getName();

        List<String> runTraces = new ArrayList<>();
        List<String> runIds = new ArrayList<>();
        for (int run = 0; run < 2; run++) {
            logs.reset();
            retention.run();

            List<JsonNode> runRecords = logs.records().stream()
                    .filter(record -> thread.equals(record.at("/process/thread/name").asText()))
                    .toList();
            String trace = onlyRecordWithMessage("Audit retention run complete").at("/trace/id").asText();
            String runId = onlyRecordWithMessage("Audit retention run complete")
                    .at("/batch/job/run/id").asText();
            assertThat(trace).matches(TRACE_ID);
            assertThat(runId).isNotBlank();
            assertThat(runRecords).extracting(record -> record.at("/message").asText())
                    .startsWith("Scheduled job started").endsWith("Scheduled job completed");
            assertThat(runRecords).allSatisfy(record -> {
                assertThat(record.at("/trace/id").asText()).isEqualTo(trace);
                assertThat(record.at("/span/id").asText()).matches(SPAN_ID);
                assertThat(record.at("/batch/job/run/id").asText()).isEqualTo(runId);
                assertThat(record.at("/batch/job/name").asText()).isEqualTo("audit-retention");
                assertThat(record.at("/trigger/type").asText()).isEqualTo("scheduled");
                assertThat(record.has("http")).as("off-request").isFalse();
            });
            runTraces.add(trace);
            runIds.add(runId);
        }
        assertThat(runTraces.get(0)).isNotEqualTo(runTraces.get(1));
        assertThat(runIds.get(0)).isNotEqualTo(runIds.get(1));
    }

    /**
     * The service fields on every record, whatever emitted it: the name stated in
     * {@code logging.yaml}, the version of the build — read here from the POM itself, so
     * the oracle is not the filtered resource it is checking — and the environment
     * ({@code APP_ENVIRONMENT}, {@code local} when unset).
     */
    @Test
    void everyRecordCarriesTheServiceNameVersionAndEnvironment() throws Exception {
        logIn("test-user", "test-password").andExpect(status().isOk());
        logIn("not-a-real-account", "wrong-password").andExpect(status().isUnauthorized());

        String version = builtProjectVersion();
        assertThat(version).isNotBlank().doesNotContain("@");
        assertThat(logs.records()).hasSizeGreaterThanOrEqualTo(2).allSatisfy(record -> {
            assertThat(record.at("/service/name").asText()).isEqualTo("backend");
            assertThat(record.at("/service/version").asText()).isEqualTo(version);
            assertThat(record.at("/service/environment").asText())
                    .isEqualTo(System.getenv().getOrDefault("APP_ENVIRONMENT", "local"));
        });
    }

    /**
     * Log timestamps are Singapore time; the SCIM wire is not. A record's
     * {@code @timestamp} ends in {@code +08:00} while a {@code meta.lastModified} read in
     * the same test still ends in {@code Z} — the zone was changed for the log, not for
     * the JVM.
     */
    @Test
    void recordTimestampsArePlusEightWhileScimWireTimesStayUtc() throws Exception {
        UUID connectorId = connectors.create("ecs-timestamp-connector", "test-admin").id();
        String token = connectors.issueToken(
                connectorId, TokenPermissions.ALL, null, "test-admin", TokenPermissions.ALL).presentedValue();
        logIn("test-user", "test-password").andExpect(status().isOk());

        JsonNode user = json(mvc.perform(get("/scim/v2/Users/{id}", userId("test-user"))
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk()));

        assertThat(user.at("/meta/lastModified").asText()).isNotBlank().endsWith("Z");
        assertThat(logs.records()).hasSizeGreaterThanOrEqualTo(2).allSatisfy(record ->
                assertThat(record.at("/@timestamp").asText()).matches(PLUS_EIGHT_TIMESTAMP));
    }

    /**
     * The ticket's oracle. All three flows in one exchange sequence, then the whole
     * captured stream read as JSON and searched for every value that must not be in
     * it — in a message, in a field name, in a field value, in the logging context.
     * Searching the raw encoded text rather than selected fields is deliberate:
     * a leak into a field nobody thought to check still fails this.
     */
    @Test
    void noRecordFromAnyFlowContainsAForbiddenValue() throws Exception {
        logs.reset();

        MockHttpSession admin = loggedInSession("test-admin", "test-admin-password");
        logIn("test-user", "test-password").andExpect(status().isOk());
        logIn("not-a-real-account", "wrong-password").andExpect(status().isUnauthorized());
        mvc.perform(withCsrf(post("/api/admin/accounts/{id}/unlock", userId("test-user")))
                        .session(admin))
                .andExpect(status().isOk());

        // The flows really did log, and did name their identities by id, so the
        // assertion below is not vacuously true.
        assertThat(logs.records()).hasSizeGreaterThanOrEqualTo(4);
        assertThat(logs.records()).anySatisfy(record ->
                assertThat(record.at("/user/target/id").asText()).isNotBlank());
        logs.records().forEach(EcsLogFormatTests::assertThatIsValidEcs);
        assertThat(logs.lines()).doesNotContain(FORBIDDEN.toArray(String[]::new));
    }

    // ---- the request record (#68) -------------------------------------------------------------

    /**
     * The deployed order, which the request record's correlation depends on: the observation
     * filter opens the span, the request-id filter runs inside it, and both run ahead of the
     * security chain so a refusal is recorded too. The MockMvc chain above mirrors this; here
     * it is read off the registrations the running server uses.
     */
    @Test
    void theRequestIdFilterRunsInsideTheSpanAndAheadOfTheSecurityChain() {
        Integer requestIdOrder = OrderUtils.getOrder(RequestIdFilter.class);

        assertThat(requestIdOrder).isEqualTo(RequestIdFilter.ORDER);
        assertThat(observationFilter.getOrder()).isLessThan(requestIdOrder);
        assertThat(requestIdOrder).isLessThan(securityRegistration.getOrder());
    }

    /**
     * The ticket's oracle: one request record for an authenticated {@code GET /api/self},
     * naming the template, the status, a duration and the outcome, under the request's own
     * correlation ids.
     */
    @Test
    void aSelfReadEndsInExactlyOneRequestRecord() throws Exception {
        MockHttpSession user = loggedInSession("test-user", "test-password");
        logs.reset();

        mvc.perform(get("/api/self").session(user)).andExpect(status().isOk());

        JsonNode record = onlyRequestRecord();
        assertThatIsValidEcs(record);
        assertThat(record.at("/message").asText()).isEqualTo("HTTP request completed");
        assertThat(record.at("/event/kind").asText()).isEqualTo("event");
        assertThat(record.at("/event/category").valueStream().map(JsonNode::asText).toList())
                .containsExactly("network");
        assertThat(record.at("/event/type").valueStream().map(JsonNode::asText).toList())
                .containsExactly("access", "end");
        assertThat(record.at("/app/event/action").asText()).isEqualTo("http.request");
        assertThat(record.at("/http/request/method").asText()).isEqualTo("GET");
        assertThat(record.at("/http/route").asText()).isEqualTo("/api/self");
        assertThat(record.at("/http/response/status_code").asInt()).isEqualTo(200);
        assertThat(record.at("/event/duration_ms").isIntegralNumber()).isTrue();
        assertThat(record.at("/event/duration_ms").asLong()).isNotNegative();
        assertThat(record.at("/event/outcome").asText()).isEqualTo("success");
        assertThat(record.at("/log/level").asText()).isEqualTo("INFO");
        assertThat(record.at("/http/request/id").asText()).isNotBlank();
        assertThat(record.at("/trace/id").asText()).matches(TRACE_ID);
        // #95: the record says who made the request, though user.id's own scope closed
        // inside the security chain before this record was written.
        assertThat(record.at("/user/id").asText()).isEqualTo(userId("test-user").toString());
        assertThat(record.has("scim")).isFalse();
    }

    /**
     * An anonymous request that succeeds still gets its record, and the record names nobody:
     * the absence below is of a field, on a record that exists.
     */
    @Test
    void anAnonymousRequestsRecordCarriesNoUserId() throws Exception {
        mvc.perform(get("/api/auth/csrf")).andExpect(status().isOk());

        JsonNode record = onlyRequestRecord();
        assertThat(record.at("/http/route").asText()).isEqualTo("/api/auth/csrf");
        assertThat(record.at("/http/response/status_code").asInt()).isEqualTo(200);
        assertThat(record.has("user")).isFalse();
        assertThat(record.has("scim")).isFalse();
    }

    /**
     * A SCIM bearer call's record names the connector whose token authenticated it, by its
     * non-secret id, and carries no User and nothing of the token.
     */
    @Test
    void aScimBearerRequestsRecordCarriesTheConnectorIdAndNoTokenMaterial() throws Exception {
        UUID connectorId = connectors.create("ecs-actor-connector", "test-admin").id();
        String token = connectors.issueToken(
                connectorId, TokenPermissions.ALL, null, "test-admin", TokenPermissions.ALL).presentedValue();
        logs.reset();

        mvc.perform(get("/scim/v2/Users").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk());

        JsonNode record = onlyRequestRecord();
        assertThat(record.at("/http/route").asText()).isEqualTo("/scim/v2/Users");
        assertThat(record.at("/scim/connector/id").asText()).isEqualTo(connectorId.toString());
        assertThat(record.has("user")).isFalse();
        assertThat(requestRecordLines().getFirst())
                .doesNotContain(token)
                .doesNotContain(token.substring(0, 12));
    }

    /**
     * A token the service does not accept is a refusal before authentication: one request
     * record, naming no connector — the presented token named one, but it never authenticated.
     */
    @Test
    void aRefusedBearerRequestsRecordCarriesNoConnectorId() throws Exception {
        UUID connectorId = connectors.create("ecs-refused-connector", "test-admin").id();
        String issued = connectors.issueToken(
                connectorId, TokenPermissions.ALL, null, "test-admin", TokenPermissions.ALL).presentedValue();
        String presented = issued.substring(0, issued.length() - 4) + "XXXX";
        logs.reset();

        mvc.perform(get("/scim/v2/Users").header(HttpHeaders.AUTHORIZATION, "Bearer " + presented))
                .andExpect(status().isUnauthorized());

        JsonNode record = onlyRequestRecord();
        assertThat(record.at("/http/response/status_code").asInt()).isEqualTo(401);
        assertThat(record.has("scim")).isFalse();
        assertThat(record.has("user")).isFalse();
    }

    /**
     * The request record files under the same {@code http.request.id} and {@code trace.id} as
     * the request's other records — read off a login, whose handler writes a record of its
     * own to compare with.
     */
    @Test
    void theRequestRecordSharesItsRequestsCorrelationIds() throws Exception {
        logIn("test-user", "test-password").andExpect(status().isOk());

        JsonNode accepted = onlyRecordWithMessage("Login accepted");
        // The login's own record, not the CSRF fetch the helper makes before it.
        List<JsonNode> logins = requestRecords().stream()
                .filter(record -> "/api/auth/login".equals(record.at("/http/route").asText()))
                .toList();
        assertThat(logins).hasSize(1);
        JsonNode request = logins.getFirst();
        assertThat(request.at("/http/request/method").asText()).isEqualTo("POST");
        assertThat(request.at("/http/request/id").asText())
                .isNotBlank()
                .isEqualTo(accepted.at("/http/request/id").asText());
        assertThat(request.at("/trace/id").asText())
                .matches(TRACE_ID)
                .isEqualTo(accepted.at("/trace/id").asText());
    }

    /**
     * A SCIM read by id with a filter in its query: the record names the template, and
     * neither the id nor the filter text appears anywhere in its encoded bytes.
     */
    @Test
    void aScimReadIsRecordedByTemplateWithoutItsIdOrFilterText() throws Exception {
        UUID connectorId = connectors.create("ecs-route-connector", "test-admin").id();
        String token = connectors.issueToken(
                connectorId, TokenPermissions.ALL, null, "test-admin", TokenPermissions.ALL).presentedValue();
        String id = userId("test-user").toString();
        String filterText = "ecs-route-filter-probe";
        logs.reset();

        mvc.perform(get("/scim/v2/Users/{id}", id)
                        .queryParam("filter", "userName eq \"" + filterText + "\"")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk());

        JsonNode record = onlyRequestRecord();
        assertThat(record.at("/http/route").asText()).isEqualTo("/scim/v2/Users/{id}");
        assertThat(record.at("/http/response/status_code").asInt()).isEqualTo(200);
        String encoded = requestRecordLines().getFirst();
        assertThat(encoded).doesNotContain(id, filterText, "userName eq", token);
    }

    /**
     * A {@code 401} the security chain answers before any handler is one record at
     * {@code WARN}, filed under the unmatched bucket because no route was ever matched, and
     * naming nobody: the request never authenticated.
     */
    @Test
    void aRefusalByTheSecurityChainIsOneWarnRecord() throws Exception {
        mvc.perform(get("/api/self")).andExpect(status().isUnauthorized());

        JsonNode record = onlyRequestRecord();
        assertThat(record.at("/log/level").asText()).isEqualTo("WARN");
        assertThat(record.at("/http/response/status_code").asInt()).isEqualTo(401);
        assertThat(record.at("/http/route").asText()).isEqualTo("unmatched");
        assertThat(record.at("/event/outcome").asText()).isEqualTo("failure");
        assertThat(record.at("/trace/id").asText()).matches(TRACE_ID);
        assertThat(record.has("user")).isFalse();
    }

    /**
     * An exception escaping the chain is one record at {@code ERROR}, as the {@code 500} the
     * container answers it with. The exception is raised behind the security chain of an
     * authenticated request, where a failing handler would raise it.
     */
    @Test
    void anExceptionEscapingTheChainIsOneErrorRecord() throws Exception {
        MockHttpSession user = loggedInSession("test-user", "test-password");
        IllegalStateException failure = new IllegalStateException("downstream failure");
        MockMvc failing = MockMvcBuilders.webAppContextSetup(context)
                .addFilters(observationFilter.getFilter(), requestIdFilter, springSecurityFilterChain,
                        (request, response, chain) -> {
                            throw failure;
                        })
                .build();
        logs.reset();

        assertThatThrownBy(() -> failing.perform(get("/api/self").session(user)))
                .isSameAs(failure);

        JsonNode record = onlyRequestRecord();
        assertThat(record.at("/log/level").asText()).isEqualTo("ERROR");
        assertThat(record.at("/http/response/status_code").asInt()).isEqualTo(500);
        assertThat(record.at("/event/outcome").asText()).isEqualTo("failure");
        assertThatCarriesNestedErrorFields(record, 500, "application", true);
    }

    /**
     * The health probe and the Prometheus scrape get no record; an actuator request beside
     * them does, so the absence is the exclusion's doing and not the capture's.
     */
    @Test
    void theHealthProbeAndThePrometheusScrapeAreNotRecorded() throws Exception {
        mvc.perform(get("/actuator/health")).andExpect(status().isOk());
        mvc.perform(get("/actuator/health/liveness"));
        mvc.perform(get("/actuator/prometheus"));
        assertThat(requestRecords()).isEmpty();

        mvc.perform(get("/actuator/info"));
        assertThat(requestRecords()).hasSize(1);
    }

    // ---- refusal, logout and session-end records (#69) ----------------------------------------

    /**
     * The ticket's oracle: a non-Admin reading the administrative interface is one WARN
     * {@code access-control} failure naming the caller by stable id and the route by template,
     * and nothing in it says which role the route wanted.
     */
    @Test
    void aNonAdminAdminReadIsOneAccessControlFailureNamingNoRole() throws Exception {
        MockHttpSession user = loggedInSession("test-user", "test-password");
        logs.reset();

        mvc.perform(get("/api/admin/accounts").session(user)).andExpect(status().isForbidden());

        JsonNode record = onlyRecordWithMessage("Request refused: access denied");
        assertThatIsValidEcs(record);
        assertThatClassifiedAs(record, "access-control", "process", "access", "denied");
        assertThat(record.at("/app/event/action").asText()).isEqualTo("access.denied");
        assertThat(record.at("/log/level").asText()).isEqualTo("WARN");
        assertThat(record.at("/event/outcome").asText()).isEqualTo("failure");
        assertThat(record.at("/event/reason").asText()).isEqualTo("insufficient-permissions");
        assertThat(record.at("/user/id").asText()).isEqualTo(userId("test-user").toString());
        assertThat(record.at("/http/request/method").asText()).isEqualTo("GET");
        assertThat(record.at("/http/route").asText()).isEqualTo("/api/admin/accounts");
        assertThat(record.at("/http/response/status_code").asInt()).isEqualTo(403);
        assertThat(record.at("/http/request/id").asText()).isNotBlank();
        assertThat(record.toString()).doesNotContain("ROLE_", "ADMIN", "hasRole", "authorit");
    }

    /** The route is a template: an id in the path does not reach the record. */
    @Test
    void aRefusedRequestIsNamedByItsTemplateNotItsPath() throws Exception {
        MockHttpSession user = loggedInSession("test-user", "test-password");
        String subject = userId("test-admin").toString();
        logs.reset();

        mvc.perform(withCsrf(post("/api/admin/accounts/{id}/unlock", subject)).session(user))
                .andExpect(status().isForbidden());

        JsonNode record = onlyRecordWithMessage("Request refused: access denied");
        assertThat(record.at("/http/route").asText()).isEqualTo("/api/admin/accounts/{id}/unlock");
        assertThat(record.toString()).doesNotContain(subject);
    }

    /** A missing CSRF token is its own refusal, never also an authorization one. */
    @Test
    void aMissingCsrfTokenIsOneRefusalWithReasonCsrf() throws Exception {
        MockHttpSession user = loggedInSession("test-user", "test-password");
        logs.reset();

        mvc.perform(post("/api/count/increment").session(user)).andExpect(status().isForbidden());

        JsonNode record = onlyRecordWithMessage("Request refused: access denied");
        assertThat(record.at("/event/reason").asText()).isEqualTo("csrf");
        assertThat(record.at("/user/id").asText()).isEqualTo(userId("test-user").toString());
    }

    @Test
    void anAnonymousSelfReadIsOneUnauthenticatedWarnRecord() throws Exception {
        mvc.perform(get("/api/self")).andExpect(status().isUnauthorized());

        JsonNode record = onlyRecordWithMessage("Request refused: authentication required");
        assertThatIsValidEcs(record);
        assertThatClassifiedAs(record, "access-control", "process", "access", "denied");
        assertThat(record.at("/app/event/action").asText()).isEqualTo("access.unauthenticated");
        assertThat(record.at("/log/level").asText()).isEqualTo("WARN");
        assertThat(record.at("/event/reason").asText()).isEqualTo("no-session");
        assertThat(record.at("/http/route").asText()).isEqualTo("/api/self");
        assertThat(record.has("user")).isFalse();
    }

    /**
     * A SCIM call with a bad bearer, and one with none: one WARN record each, and the presented
     * value appears in no record — whole or in part.
     */
    @Test
    void aScimCallWithABadOrMissingBearerIsOneWarnRecordCarryingNoTokenValue() throws Exception {
        UUID connectorId = connectors.create("ecs-refusal-connector", "test-admin").id();
        String issued = connectors.issueToken(
                connectorId, TokenPermissions.ALL, null, "test-admin", TokenPermissions.ALL).presentedValue();
        // A real token with its secret half altered: the lookup half still names a token, so
        // the refusal is the "presented and not accepted" path, and the prefix a leak would show
        // is a real one.
        String presented = issued.substring(0, issued.length() - 4) + "XXXX";
        logs.reset();

        mvc.perform(get("/scim/v2/Users").header(HttpHeaders.AUTHORIZATION, "Bearer " + presented))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/scim/v2/Users")).andExpect(status().isUnauthorized());

        List<JsonNode> refusals = recordsWithMessage("Request refused: authentication required");
        assertThat(refusals).extracting(record -> record.at("/event/reason").asText())
                .containsExactly("bearer-invalid", "bearer-missing");
        assertThat(refusals).allSatisfy(record ->
                assertThat(record.at("/log/level").asText()).isEqualTo("WARN"));
        assertThat(logs.lines())
                .doesNotContain(presented)
                .doesNotContain(issued)
                .doesNotContain(issued.substring(0, 12));
    }

    /** Logout is one INFO {@code user-logout} record naming who logged out. */
    @Test
    void aLogoutIsOneUserLogoutRecordWithTheUsersId() throws Exception {
        MockHttpSession user = loggedInSession("test-user", "test-password");
        logs.reset();

        mvc.perform(withCsrf(delete("/api/auth/logout")).session(user))
                .andExpect(status().isNoContent());

        JsonNode record = onlyRecordWithMessage("Logout completed");
        assertThatIsValidEcs(record);
        assertThatClassifiedAs(record, "user-logout", "process", "user", "end");
        assertThat(record.has("app")).as("an exact action keeps no local name").isFalse();
        assertThat(record.at("/log/level").asText()).isEqualTo("INFO");
        assertThat(record.at("/event/outcome").asText()).isEqualTo("success");
        assertThat(record.at("/user/id").asText()).isEqualTo(userId("test-user").toString());
    }

    /** A logout from a session that was never signed in to logged nobody out: no record. */
    @Test
    void aLogoutOfAGuestSessionWritesNoLogoutRecord() throws Exception {
        mvc.perform(withCsrf(delete("/api/auth/logout")));

        assertThat(recordsWithMessage("Logout completed")).isEmpty();
    }

    /**
     * The once-per-exchange guard, through the real chain: an error dispatch the chain refuses
     * again — the second pass a {@code sendError} refusal may get — is answered as before but
     * writes no record. MockMvc performs no error dispatch of its own, so the dispatch is
     * stated here; over a real socket it is {@code RefusalLogIntegrationTests} that observes
     * one record per refusal.
     */
    @Test
    void anErrorDispatchTheChainRefusesAgainWritesNoSecondRefusalRecord() throws Exception {
        mvc.perform(get("/api/self").with(request -> {
                    request.setDispatcherType(jakarta.servlet.DispatcherType.ERROR);
                    return request;
                }))
                .andExpect(status().isUnauthorized());

        assertThat(recordsWithMessage("Request refused: authentication required")).isEmpty();
    }

    /**
     * And a refusal on an exchange that already has its record — a second refusal of the same
     * request dispatch — writes none either, while it is still answered.
     */
    @Test
    void aSecondRefusalOfAnAlreadyRecordedExchangeWritesNoRecord() throws Exception {
        mvc.perform(get("/api/self").requestAttr(AccessRefusalLog.RECORDED_ATTRIBUTE,
                        AccessRefusalLog.Refusal.NO_SESSION))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/scim/v2/Users").requestAttr(AccessRefusalLog.RECORDED_ATTRIBUTE,
                        AccessRefusalLog.Refusal.BEARER_MISSING))
                .andExpect(status().isUnauthorized());

        assertThat(recordsWithMessage("Request refused: authentication required")).isEmpty();
    }

    // ---- the app-wide error handler and error.* (#94) ----------------------------------------

    /**
     * The ticket's oracle: a test-only route throwing {@code IllegalStateException} is exactly one
     * {@code ERROR} — the exception attached, classified under {@code error}, under the request's
     * own trace — and the client is told nothing about it.
     */
    @Test
    void anUnexpectedExceptionIsOneClassifiedErrorAndAGeneric500() throws Exception {
        MockMvc failing = applicationRouteFailing();
        logs.reset();

        MvcResult result = failing.perform(get(FaultInjectionController.PATH)).andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(500);
        String body = result.getResponse().getContentAsString();
        assertThat(JSON.readTree(body).at("/code").asText()).isEqualTo("server-error");
        assertThat(body).doesNotContain(
                FaultInjectionController.FAILURE_MESSAGE, "IllegalStateException", "Exception",
                "java.", "trace");

        List<JsonNode> errors = errorRecords();
        assertThat(errors).hasSize(1);
        JsonNode record = errors.getFirst();
        assertThatIsValidEcs(record);
        assertThat(record.at("/message").asText())
                .isEqualTo("Request failed with an unexpected exception");
        assertThat(record.at("/error/type").asText()).isEqualTo(IllegalStateException.class.getName());
        assertThat(record.at("/error/stack_trace").asText())
                .contains("IllegalStateException", FaultInjectionController.class.getName());
        assertThatCarriesNestedErrorFields(record, 500, "application", true);
        assertThat(record.at("/event/outcome").asText()).isEqualTo("failure");
        assertThat(record.at("/app/event/action").asText()).isEqualTo("http.request.fault");
        assertThat(record.at("/http/request/id").asText()).isNotBlank();
        assertThat(record.at("/trace/id").asText())
                .matches(TRACE_ID)
                .isEqualTo(onlyRequestRecord().at("/trace/id").asText());
    }

    /**
     * And not two: the handler's record is the fault's one {@code ERROR}, so the request record
     * that follows it is {@code WARN} — still the {@code 500}, still a failure, still there.
     */
    @Test
    void oneUnexpectedExceptionIsOneErrorNotTwo() throws Exception {
        MockMvc failing = applicationRouteFailing();
        logs.reset();

        failing.perform(get(FaultInjectionController.PATH));

        assertThat(logs.records()).extracting(record -> record.at("/log/level").asText())
                .containsOnlyOnce("ERROR");
        JsonNode request = onlyRequestRecord();
        assertThat(request.at("/log/level").asText()).isEqualTo("WARN");
        assertThat(request.at("/http/response/status_code").asInt()).isEqualTo(500);
        assertThat(request.at("/event/outcome").asText()).isEqualTo("failure");
        assertThat(request.has("error")).as("the request record classifies no second error").isFalse();
    }

    /**
     * An over-length login is refused {@code 400} with the stable body rather than Boot's
     * default {@code timestamp/status/error/path} one, logged as the caller's {@code data} error
     * at {@code WARN} — and the value it refused appears in no record.
     */
    @Test
    void anOversizedLoginIsA400WithTheStableBodyAndTheValueInNoRecord() throws Exception {
        String marker = "oversized-login-marker-q8z";
        String username = marker.repeat(
                ScimAttributeLimits.USER_NAME / marker.length() + 1);
        assertThat(username.length()).isGreaterThan(ScimAttributeLimits.USER_NAME);

        MvcResult result = mvc.perform(withCsrf(post("/api/auth/login"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"%s\",\"password\":\"%s\"}".formatted(username, marker)))
                .andExpect(status().isBadRequest())
                .andReturn();

        JsonNode body = JSON.readTree(result.getResponse().getContentAsString());
        assertThat(body.at("/status").asInt()).isEqualTo(400);
        assertThat(body.at("/code").asText()).isEqualTo("invalid-request");
        assertThat(body.at("/detail").asText()).isNotBlank();
        assertThat(body.has("timestamp")).isFalse();
        assertThat(body.has("path")).isFalse();
        assertThat(body.has("error")).isFalse();
        assertThat(body.has("message")).isFalse();
        assertThat(result.getResponse().getContentAsString()).doesNotContain(marker);

        JsonNode refusal = onlyRecordWithMessage("Request refused");
        assertThatIsValidEcs(refusal);
        assertThat(refusal.at("/log/level").asText()).isEqualTo("WARN");
        assertThat(refusal.at("/event/reason").asText()).isEqualTo("MethodArgumentNotValidException");
        assertThatCarriesNestedErrorFields(refusal, 400, "data", false);
        assertThat(logs.records()).as("the exchange did log").hasSizeGreaterThanOrEqualTo(2);
        assertThat(logs.lines()).doesNotContain(marker);
    }

    /**
     * Every {@code ERROR} the service writes, on the encoded stream: the three error fields
     * nested under {@code error}, and none of them left at the top level. Each of the records
     * the ticket names is produced, and found, so the check is over all of them, not over
     * whichever happened to appear.
     */
    @Test
    void everyErrorRecordCarriesTheErrorFieldsNestedUnderError() throws Exception {
        UUID connectorId = connectors.create("ecs-error-fields-connector", "test-admin").id();
        String token = connectors.issueToken(
                connectorId, TokenPermissions.ALL, null, "test-admin", TokenPermissions.ALL).presentedValue();
        MockMvc failing = applicationRouteFailing();
        logs.reset();

        RuntimeException jobFailure = new IllegalStateException("job failed");
        assertThatThrownBy(() -> jobMetrics.instrumentLocked("ecs-error-fields-job",
                        LogEvent.Operation.AUDIT_RETENTION, () -> {
                            throw jobFailure;
                        }).run())
                .isSameAs(jobFailure);
        mvc.perform(get("/scim/v2/Me").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isNotImplemented());
        ScimFaultRecords.serverError();
        ScimFaultRecords.integrityViolation();
        ScimErrorDocumentRecords.serverError();
        alerts.auditAppendFailed(AuditOperation.values()[0], IllegalStateException.class);
        failing.perform(get(FaultInjectionController.PATH));

        List<JsonNode> errors = errorRecords();
        assertThat(errors).extracting(record -> record.at("/message").asText()).contains(
                "Scheduled job failed",
                "SCIM request refused",
                "SCIM write refused by an unmapped integrity violation",
                "Audit event could not be appended; the request was not altered",
                "Request failed with an unexpected exception");
        assertThat(errors.stream().filter(record ->
                        "SCIM request refused".equals(record.at("/message").asText())))
                .as("the /Me 501, the advice's 500 and the filter's 500").hasSize(3);
        assertThat(errors).allSatisfy(record -> {
            assertThat(record.at("/error/code").isIntegralNumber()).as(record.toString()).isTrue();
            assertThat(record.at("/error/category").asText()).as(record.toString())
                    .isIn("application", "database");
            assertThat(record.at("/error/follow_up_action").asBoolean()).as(record.toString()).isTrue();
            assertThat(record.has(LogEvent.ERROR_CODE)).isFalse();
            assertThat(record.has(LogEvent.ERROR_CATEGORY)).isFalse();
            assertThat(record.has(LogEvent.ERROR_FOLLOW_UP_ACTION)).isFalse();
        });
        assertThat(logs.lines()).doesNotContain(
                "\"" + LogEvent.ERROR_CODE + "\"",
                "\"" + LogEvent.ERROR_CATEGORY + "\"",
                "\"" + LogEvent.ERROR_FOLLOW_UP_ACTION + "\"");
        JsonNode integrity = onlyRecordWithMessage("SCIM write refused by an unmapped integrity violation");
        assertThat(integrity.at("/error/type").asText()).isEqualTo(RedactedFaultException.class.getName());
        assertThatCarriesNestedErrorFields(integrity, 500, "database", true);
        JsonNode alert = onlyRecordWithMessage(
                "Audit event could not be appended; the request was not altered");
        assertThatCarriesNestedErrorFields(alert, 500, "database", true);
        assertThat(alert.at("/app/error/cause_omitted").asText()).isNotBlank();
        assertThatCarriesNestedErrorFields(onlyRecordWithMessage("Scheduled job failed"),
                500, "application", true);
    }

    /** SCIM keeps its own error document: the app-wide handler never answers a SCIM route. */
    @Test
    void aScimRefusalIsStillTheScimErrorDocument() throws Exception {
        UUID connectorId = connectors.create("ecs-scim-document-connector", "test-admin").id();
        String token = connectors.issueToken(
                connectorId, TokenPermissions.ALL, null, "test-admin", TokenPermissions.ALL).presentedValue();

        MvcResult result = mvc.perform(get("/scim/v2/Me")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isNotImplemented())
                .andReturn();

        JsonNode body = JSON.readTree(result.getResponse().getContentAsString());
        assertThat(body.at("/schemas/0").asText())
                .isEqualTo("urn:ietf:params:scim:api:messages:2.0:Error");
        assertThat(body.at("/status").asText()).isEqualTo("501");
        assertThat(body.has("code")).isFalse();
        assertThat(onlyRequestRecord().at("/log/level").asText())
                .as("the advice wrote the fault's ERROR").isEqualTo("WARN");
    }

    // ---- helpers ------------------------------------------------------------------------------

    /**
     * The test-only route behind the deployed filters and the deployed app-wide handler, in a
     * standalone MockMvc so the route exists in no other context.
     */
    private MockMvc applicationRouteFailing() {
        return MockMvcBuilders.standaloneSetup(new FaultInjectionController())
                .setControllerAdvice(apiExceptionHandler)
                .addFilters(observationFilter.getFilter(), requestIdFilter)
                .build();
    }

    private List<JsonNode> errorRecords() {
        return logs.records().stream()
                .filter(record -> "ERROR".equals(record.at("/log/level").asText()))
                .toList();
    }

    private static void assertThatCarriesNestedErrorFields(
            JsonNode record, int code, String category, boolean followUp) {
        assertThat(record.at("/error/code").asInt()).isEqualTo(code);
        assertThat(record.at("/error/category").asText()).isEqualTo(category);
        assertThat(record.at("/error/follow_up_action").isBoolean()).isTrue();
        assertThat(record.at("/error/follow_up_action").asBoolean()).isEqualTo(followUp);
        assertThat(record.has(LogEvent.ERROR_CODE)).isFalse();
        assertThat(record.has(LogEvent.ERROR_CATEGORY)).isFalse();
        assertThat(record.has(LogEvent.ERROR_FOLLOW_UP_ACTION)).isFalse();
    }

    /** The fields a collector indexes on. Absent any one of them, the record is not ECS. */
    private static void assertThatIsValidEcs(JsonNode record) {
        assertThat(record.at("/@timestamp").asText()).isNotBlank();
        assertThat(record.at("/ecs/version").asText()).isEqualTo("8.11");
        assertThat(record.at("/log/level").asText()).isNotBlank();
        assertThat(record.at("/log/logger").asText()).isNotBlank();
        assertThat(record.at("/message").asText()).isNotBlank();
    }

    /**
     * The record's classification in the standard's vocabulary: {@code event.kind}
     * {@code event}, a one-element {@code event.category} array, and the
     * {@code event.type} array exactly — both encoded as JSON arrays, as ECS has them.
     */
    private static void assertThatClassifiedAs(
            JsonNode record, String action, String category, String... types) {
        assertThat(record.at("/event/action").asText()).isEqualTo(action);
        assertThat(record.at("/event/kind").asText()).isEqualTo("event");
        assertThat(record.at("/event/category").isArray()).isTrue();
        assertThat(record.at("/event/category").valueStream().map(JsonNode::asText).toList())
                .containsExactly(category);
        assertThat(record.at("/event/type").isArray()).isTrue();
        assertThat(record.at("/event/type").valueStream().map(JsonNode::asText).toList())
                .containsExactly(types);
    }

    private JsonNode onlyRecordWithMessage(String message) {
        List<JsonNode> matching = recordsWithMessage(message);
        assertThat(matching)
                .as("records with message '%s'", message)
                .hasSize(1);
        return matching.getFirst();
    }

    private List<JsonNode> recordsWithMessage(String message) {
        return logs.records().stream()
                .filter(record -> message.equals(record.at("/message").asText()))
                .toList();
    }

    private List<JsonNode> requestRecords() {
        return recordsWithMessage("HTTP request completed");
    }

    private JsonNode onlyRequestRecord() {
        List<JsonNode> records = requestRecords();
        assertThat(records).as("request records").hasSize(1);
        return records.getFirst();
    }

    /** The request records exactly as encoded, for a search of their raw bytes. */
    private List<String> requestRecordLines() {
        return logs.lines().lines()
                .filter(line -> "HTTP request completed".equals(JSON.readTree(line).at("/message").asText()))
                .toList();
    }

    private UUID userId(String userName) {
        return users.findByNormalizedUserName(NormalizedUserName.of(userName))
                .orElseThrow().id();
    }

    /**
     * {@code /project/version} of the POM this module is built from. The test runs from
     * the module's directory, as Maven runs it.
     */
    private static String builtProjectVersion() throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setXIncludeAware(false);
        factory.setExpandEntityReferences(false);
        Document pom = factory.newDocumentBuilder().parse(Path.of("pom.xml").toFile());
        return XPathFactory.newInstance().newXPath().evaluate("/project/version", pom).strip();
    }

    /**
     * A session as a real login leaves it — its principal index holding the identity's
     * stable id, written by the login itself — rather than a fixture placed by hand.
     */
    private MockHttpSession loggedInSession(String username, String password) throws Exception {
        MvcResult login = logIn(username, password).andExpect(status().isOk()).andReturn();
        return (MockHttpSession) login.getRequest().getSession(false);
    }

    private static JsonNode json(ResultActions result) throws Exception {
        return JSON.readTree(result.andReturn().getResponse().getContentAsString());
    }

    private ResultActions logIn(
            String username, String password) throws Exception {
        return mvc.perform(withCsrf(post("/api/auth/login"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"%s\",\"password\":\"%s\"}".formatted(username, password)));
    }

    private MockHttpServletRequestBuilder withCsrf(MockHttpServletRequestBuilder request) {
        return SessionCsrf.withCsrf(mvc, request);
    }
}

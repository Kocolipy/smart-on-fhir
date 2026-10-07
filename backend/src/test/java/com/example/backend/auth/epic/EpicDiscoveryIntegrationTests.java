package com.example.backend.auth.epic;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import ch.qos.logback.classic.Level;
import com.example.backend.ContainerTestConfiguration;
import com.example.backend.audit.CapturedLog;
import com.example.backend.observability.LogEvent;
import com.example.backend.observability.RequestIdFilter;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.Filter;
import jakarta.servlet.http.Cookie;
import java.io.IOException;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.session.FindByIndexNameSessionRepository;
import org.springframework.session.Session;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * Discovery failing at the launch (D23, D26): the authorize hop, which reads Epic's endpoints
 * from discovery before it can send the browser anywhere, lands at {@code /?signin=unavailable}
 * when Epic gives no answer in time or a {@code 5xx}, and at {@code /?signin=refused} when the
 * document is not one.
 *
 * <p>A context of its own, whose discovery never succeeds, so every launch here reads discovery
 * afresh: a failed read is kept for no time at all, and a successful one — which another test
 * class's context would have made — would be kept for 24 hours.
 */
@SpringBootTest
@ActiveProfiles("dev")
@Import(ContainerTestConfiguration.class)
class EpicDiscoveryIntegrationTests {

    private static final String FHIR_BASE = "https://fhir.example.org/api/FHIR/R4";

    private static final int EPIC_PORT = freePort();

    @DynamicPropertySource
    static void epicLoginOn(DynamicPropertyRegistry registry) {
        registry.add("app.epic.enabled", () -> "true");
        registry.add("app.epic.fhir-base", () -> FHIR_BASE);
        registry.add("app.epic.oauth-issuer", () -> "http://localhost:" + EPIC_PORT + "/oauth2");
        registry.add("app.epic.client-id", () -> "epic-client-id");
        registry.add("app.epic.redirect-uri",
                () -> "https://app.example.org/api/auth/epic/callback");
        registry.add("app.epic.client-key", () -> EpicTestKeys.pem(EpicTestKeys.p384KeyPair()));
        registry.add("app.epic.client-key-id", () -> "active-kid");
        registry.add("app.epic.connect-timeout", () -> "1s");
        registry.add("app.epic.read-timeout", () -> "1s");
    }

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private RequestIdFilter requestIdFilter;

    @Autowired
    @Qualifier("springSecurityFilterChain")
    private Filter springSecurityFilterChain;

    @Autowired
    @Qualifier("springSessionRepositoryFilter")
    private Filter springSessionRepositoryFilter;

    @Autowired
    private FindByIndexNameSessionRepository<? extends Session> sessionRepository;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private MeterRegistry meters;

    @Value("${server.servlet.session.cookie.name:SESSION}")
    private String sessionCookieName;

    private MockMvc mvc;

    private FakeEpic epic;

    @BeforeEach
    void setUp() throws IOException {
        mvc = MockMvcBuilders.webAppContextSetup(context)
                .addFilters(requestIdFilter, springSessionRepositoryFilter, springSecurityFilterChain)
                .build();
        epic = FakeEpic.start(EPIC_PORT, "epic-client-id", "active-kid",
                com.nimbusds.jose.jwk.JWKSet::new);
    }

    @AfterEach
    void tearDown() {
        epic.close();
    }

    @Test
    void aDiscoveryTimeoutLandsAtTheUnavailableNotice() throws Exception {
        epic.failing(FakeEpic.Endpoint.DISCOVERY, FakeEpic.Failure.STALL);

        assertThat(authorize().result().getResponse().getRedirectedUrl())
                .isEqualTo("/?signin=unavailable");
    }

    @Test
    void aDiscovery5xxLandsAtTheUnavailableNotice() throws Exception {
        epic.failing(FakeEpic.Endpoint.DISCOVERY, FakeEpic.Failure.SERVER_ERROR);

        assertThat(authorize().result().getResponse().getRedirectedUrl())
                .isEqualTo("/?signin=unavailable");
    }

    /** D24: the launch's session does not outlive a launch Epic could not serve. */
    @Test
    void aDiscoveryFailureEndsTheLaunchSession() throws Exception {
        epic.failing(FakeEpic.Endpoint.DISCOVERY, FakeEpic.Failure.SERVER_ERROR);

        Hop hop = authorize();

        assertThat(sessionRepository.findById(sessionId(hop.launched()))).isNull();
    }

    @Test
    void aDiscoveryFailureIsAuditedAsEpicUnavailable() throws Exception {
        epic.failing(FakeEpic.Endpoint.DISCOVERY, FakeEpic.Failure.SERVER_ERROR);
        int before = unavailableFailures();

        authorize();

        assertThat(unavailableFailures()).isEqualTo(before + 1);
    }

    @Test
    void aDiscoveryFailureIsCountedUnderOutcomeUnavailable() throws Exception {
        epic.failing(FakeEpic.Endpoint.DISCOVERY, FakeEpic.Failure.STALL);
        double before = unavailables();

        authorize();

        assertThat(unavailables()).isEqualTo(before + 1);
    }

    @Test
    void aDiscoveryTimeoutIsOneErrorUnderTheNetworkCategoryNamingDiscovery() throws Exception {
        epic.failing(FakeEpic.Endpoint.DISCOVERY, FakeEpic.Failure.STALL);

        List<Map<String, Object>> errors;
        try (CapturedLog captured = CapturedLog.attach()) {
            authorize();
            errors = captured.withAction(Level.ERROR, LogEvent.LOCAL_ACTION, "epic.login")
                    .stream().map(CapturedLog::fields).toList();
        }

        assertThat(errors).singleElement().satisfies(fields -> assertThat(fields)
                .containsEntry(LogEvent.ERROR_CATEGORY, "network")
                .containsEntry(LogEvent.ERROR_FOLLOW_UP_ACTION, false)
                .containsEntry(LogEvent.EPIC_CALL, "discovery"));
    }

    /** A failure is not kept (D26): the next launch reads discovery again. */
    @Test
    void eachLaunchAfterAFailureReadsDiscoveryAgain() throws Exception {
        epic.failing(FakeEpic.Endpoint.DISCOVERY, FakeEpic.Failure.SERVER_ERROR);
        authorize();

        authorize();

        assertThat(epic.requests(FakeEpic.Endpoint.DISCOVERY)).isEqualTo(2);
    }

    /** Epic answered, with no discovery document: a refusal, and an error to follow up. */
    @Test
    void aMalformedDiscoveryDocumentLandsAtTheRefusedNotice() throws Exception {
        epic.failing(FakeEpic.Endpoint.DISCOVERY, FakeEpic.Failure.MALFORMED);

        assertThat(authorize().result().getResponse().getRedirectedUrl())
                .isEqualTo("/?signin=refused");
    }

    @Test
    void aMalformedDiscoveryDocumentIsOneErrorUnderTheDataCategory() throws Exception {
        epic.failing(FakeEpic.Endpoint.DISCOVERY, FakeEpic.Failure.MALFORMED);

        List<Map<String, Object>> errors;
        try (CapturedLog captured = CapturedLog.attach()) {
            authorize();
            errors = captured.withAction(Level.ERROR, LogEvent.LOCAL_ACTION, "epic.login")
                    .stream().map(CapturedLog::fields).toList();
        }

        assertThat(errors).singleElement().satisfies(fields -> assertThat(fields)
                .containsEntry(LogEvent.ERROR_CATEGORY, "data")
                .containsEntry(LogEvent.ERROR_FOLLOW_UP_ACTION, true)
                .containsEntry(LogEvent.EPIC_CALL, "discovery"));
    }

    /** The launch session, and the authorize hop's answer to it. */
    private record Hop(Cookie launched, MvcResult result) {
    }

    /** Epic opens the launch URL, and the browser follows it to the authorize hop. */
    private Hop authorize() throws Exception {
        MvcResult launch = mvc.perform(get("/api/auth/epic/launch")
                        .param("iss", FHIR_BASE)
                        .param("launch", "launch-context-from-hyperspace"))
                .andReturn();
        assertThat(launch.getResponse().getStatus()).as("the launch redirects").isEqualTo(302);
        Cookie issued = launch.getResponse().getCookie(sessionCookieName);
        assertThat(issued).as("the launch opened a session").isNotNull();
        // A MockMvc request cookie, replayed in-process and never sent over the wire, so it has
        // no transport for a Secure flag to protect.
        // nosemgrep: java.servlets.security.cookie-issecure-false.cookie-issecure-false
        Cookie session = new Cookie(issued.getName(), issued.getValue());
        MvcResult hop = mvc.perform(
                get(launch.getResponse().getRedirectedUrl()).cookie(session)).andReturn();
        return new Hop(session, hop);
    }

    private int unavailableFailures() {
        return jdbc.queryForObject("""
                SELECT count(*) FROM audit_events
                WHERE operation = 'LOGIN_FAILURE' AND error_code = 'EPIC_UNAVAILABLE'
                AND login_method = 'sso' AND subject_id IS NULL""", Integer.class);
    }

    private double unavailables() {
        Counter counter = meters.find("epic.login").tag("outcome", "unavailable")
                .tag("reason", "EPIC_UNAVAILABLE").counter();
        return counter == null ? 0 : counter.count();
    }

    private static String sessionId(Cookie session) {
        return new String(Base64.getDecoder().decode(session.getValue()), StandardCharsets.UTF_8);
    }

    private static int freePort() {
        // Test-only: binds an ephemeral local port just to learn a free number for the fake
        // Epic, and closes at once. Nothing is ever sent over it, so there is no traffic for
        // TLS to protect.
        // nosemgrep: java.lang.security.audit.crypto.unencrypted-socket.unencrypted-socket
        try (ServerSocket socket = new ServerSocket(0)) {
            socket.setReuseAddress(true);
            return socket.getLocalPort();
        } catch (IOException unavailable) {
            throw new IllegalStateException(unavailable);
        }
    }
}

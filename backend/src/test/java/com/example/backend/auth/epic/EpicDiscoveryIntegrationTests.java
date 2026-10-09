package com.example.backend.auth.epic;

import static com.example.backend.auth.epic.EpicTestEnvironment.sessionId;
import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import com.example.backend.ContainerTestConfiguration;
import com.example.backend.audit.CapturedLog;
import com.example.backend.auth.epic.EpicBrowser.Hop;
import com.example.backend.auth.epic.EpicMeters.Ending;
import com.example.backend.observability.EcsLogCapture;
import com.example.backend.observability.LogEvent;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.session.FindByIndexNameSessionRepository;
import org.springframework.session.Session;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Discovery failing at the launch (D23, D26): the authorize hop, which reads Epic's endpoints
 * from discovery before it can send the browser anywhere, lands at {@code /?signin=unavailable}
 * when Epic gives no answer in time or a {@code 5xx}, and at {@code /?signin=refused} when the
 * document is not one.
 *
 * <p>A context of its own, whose discovery never succeeds, so every launch here reads discovery
 * afresh: a failed read is kept for no time at all, and a successful one — which another test
 * class's context would have made — would be kept for 24 hours. Its own
 * {@code @DynamicPropertySource} is what keeps it so ({@link EpicTestEnvironment}).
 */
@SpringBootTest
@ActiveProfiles("dev")
@Import(ContainerTestConfiguration.class)
class EpicDiscoveryIntegrationTests {

    @RegisterExtension
    static final EpicTestEnvironment EPIC = EpicTestEnvironment.epicLoginOn();

    @DynamicPropertySource
    static void epicLoginOn(DynamicPropertyRegistry registry) {
        EPIC.register(registry);
    }

    @Autowired
    private FindByIndexNameSessionRepository<? extends Session> sessionRepository;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private MeterRegistry meters;

    @Autowired
    private Environment environment;

    @Test
    void aDiscoveryTimeoutLandsAtTheUnavailableNotice() throws Exception {
        EPIC.fake().failing(FakeEpic.Endpoint.DISCOVERY, FakeEpic.Failure.STALL);

        assertThat(authorize().authorize().getResponse().getRedirectedUrl())
                .isEqualTo("/?signin=unavailable");
    }

    @Test
    void aDiscovery5xxLandsAtTheUnavailableNotice() throws Exception {
        EPIC.fake().failing(FakeEpic.Endpoint.DISCOVERY, FakeEpic.Failure.SERVER_ERROR);

        assertThat(authorize().authorize().getResponse().getRedirectedUrl())
                .isEqualTo("/?signin=unavailable");
    }

    /** D24: the launch's session does not outlive a launch Epic could not serve. */
    @Test
    void aDiscoveryFailureEndsTheLaunchSession() throws Exception {
        EPIC.fake().failing(FakeEpic.Endpoint.DISCOVERY, FakeEpic.Failure.SERVER_ERROR);

        Hop hop = authorize();

        assertThat(sessionRepository.findById(sessionId(hop.launched()))).isNull();
    }

    @Test
    void aDiscoveryFailureIsAuditedAsEpicUnavailable() throws Exception {
        EPIC.fake().failing(FakeEpic.Endpoint.DISCOVERY, FakeEpic.Failure.SERVER_ERROR);
        int before = unavailableFailures();

        authorize();

        assertThat(unavailableFailures()).isEqualTo(before + 1);
    }

    @Test
    void aDiscoveryFailureIsCountedUnderOutcomeUnavailable() throws Exception {
        EPIC.fake().failing(FakeEpic.Endpoint.DISCOVERY, FakeEpic.Failure.STALL);
        double before = unavailables();

        authorize();

        assertThat(unavailables()).isEqualTo(before + 1);
    }

    /**
     * Logging §3.3: one {@code ERROR} in all — the outbound call's, which saw it fail — and not a
     * second one when the Login ends for it.
     */
    @Test
    void aDiscoveryTimeoutIsOneErrorUnderTheNetworkCategoryNamingDiscovery() throws Exception {
        EPIC.fake().failing(FakeEpic.Endpoint.DISCOVERY, FakeEpic.Failure.STALL);

        List<Map<String, Object>> errors;
        try (CapturedLog captured = CapturedLog.attach()) {
            authorize();
            errors = captured.withAction(Level.ERROR, LogEvent.ACTION, "user-authentication")
                    .stream().map(CapturedLog::fields).toList();
        }

        assertThat(errors).singleElement().satisfies(fields -> assertThat(fields)
                .containsEntry(LogEvent.LOCAL_ACTION, "epic.outbound")
                .containsEntry(LogEvent.ERROR_CATEGORY, "network")
                .containsEntry(LogEvent.ERROR_FOLLOW_UP_ACTION, false)
                .containsEntry(LogEvent.EPIC_CALL, "discovery"));
    }

    /** A failure is not kept (D26): the next launch reads discovery again. */
    @Test
    void eachLaunchAfterAFailureReadsDiscoveryAgain() throws Exception {
        EPIC.fake().failing(FakeEpic.Endpoint.DISCOVERY, FakeEpic.Failure.SERVER_ERROR);
        authorize();

        authorize();

        assertThat(EPIC.fake().requests(FakeEpic.Endpoint.DISCOVERY)).isEqualTo(2);
    }

    /** Epic answered, with no discovery document: a refusal, and an error to follow up. */
    @Test
    void aMalformedDiscoveryDocumentLandsAtTheRefusedNotice() throws Exception {
        EPIC.fake().failing(FakeEpic.Endpoint.DISCOVERY, FakeEpic.Failure.MALFORMED);

        assertThat(authorize().authorize().getResponse().getRedirectedUrl())
                .isEqualTo("/?signin=refused");
    }

    @Test
    void aMalformedDiscoveryDocumentIsOneErrorUnderTheDataCategory() throws Exception {
        EPIC.fake().failing(FakeEpic.Endpoint.DISCOVERY, FakeEpic.Failure.MALFORMED);

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

    // ---- D22 and the meters, on each way discovery fails (ADR 0013, "Masking", "Metrics") -------

    /**
     * Each way discovery fails, with how the Login ends. The {@code epic.outbound.errors} counter
     * moves for a call that got no answer or a {@code 5xx} only, and every launch here times
     * discovery once: a failed read is never kept.
     */
    static Stream<Arguments> discoveryFailures() {
        return Stream.of(
                Arguments.of(FakeEpic.Failure.STALL,
                        Ending.unavailable("discovery", "network", "discovery")),
                Arguments.of(FakeEpic.Failure.SERVER_ERROR,
                        Ending.unavailable("discovery", "server", "discovery")),
                Arguments.of(FakeEpic.Failure.MALFORMED,
                        Ending.refusedByCall("IDP_ERROR", "discovery", "data", "discovery")));
    }

    /**
     * The one D22 value a launch has handled when discovery fails — the {@code launch}, held for
     * the authorize hop — and the signing key's material, in neither the log the launch wrote nor
     * the audit trail. {@code EpicLoginRedactionIntegrationTests} holds every later path to it.
     */
    @ParameterizedTest(name = "a discovery {0} puts no D22 value in the log or the audit trail")
    @MethodSource("discoveryFailures")
    void aDiscoveryFailurePutsNoD22ValueInTheLogOrTheAuditTrail(FakeEpic.Failure failure,
            Ending ending) throws Exception {
        EPIC.fake().failing(FakeEpic.Endpoint.DISCOVERY, failure);
        D22Values seen = new D22Values().signingKey("active signing key", EPIC.activeKey());

        String log;
        try (EcsLogCapture logs = EcsLogCapture.attach(environment)) {
            EPIC.browser().authorizeHop(seen.launch(), null);
            log = logs.lines();
        }

        assertThat(log).as("the records the launch wrote").isNotEmpty();
        seen.assertNoneIn("the log", log);
        seen.assertNoneInTheAuditTrail(jdbc);
    }

    @ParameterizedTest(name = "a discovery {0} moves the Epic Login meters under the expected tags")
    @MethodSource("discoveryFailures")
    void aDiscoveryFailureMovesTheEpicLoginMetersUnderTheExpectedTags(FakeEpic.Failure failure,
            Ending ending) throws Exception {
        EPIC.fake().failing(FakeEpic.Endpoint.DISCOVERY, failure);
        Map<String, Double> before = EpicMeters.read(meters);

        authorize();

        assertThat(EpicMeters.change(before, EpicMeters.read(meters)))
                .isEqualTo(ending.expected());
    }

    /** An ordinary launch, followed to the authorize hop. */
    private static Hop authorize() throws Exception {
        return EPIC.browser().authorizeHop(EpicBrowser.LAUNCH, null);
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
}

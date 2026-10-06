package com.example.backend.lifecycle.config;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import com.example.backend.audit.CapturedLog;
import com.example.backend.audit.domain.AuditRetentionPolicy;
import com.example.backend.auth.epic.EcP384PrivateKeyPem;
import com.example.backend.auth.epic.EpicReleaseGate;
import com.example.backend.auth.epic.EpicSigningKey;
import com.example.backend.auth.epic.EpicSigningKeys;
import com.example.backend.auth.epic.EpicTestKeys;
import com.example.backend.observability.LogEvent;
import com.example.backend.scim.config.ScimReleaseGate;
import com.example.backend.scim.domain.DormancyPolicy;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.mock.env.MockEnvironment;

/**
 * The two lifecycle records' content, from the listener alone: each field the startup record
 * promises, the shutdown record's uptime, and the rule that a listener answers only for its
 * own context. {@code ApplicationLifecycleLogIntegrationTests} shows the same records arriving
 * from a real application's start and close.
 */
class ApplicationLifecycleLogTests {

    private final GenericApplicationContext context = new GenericApplicationContext();

    private final MockEnvironment environment = new MockEnvironment();

    private CapturedLog logs;

    @BeforeEach
    void setUp() {
        context.refresh();
        environment.setActiveProfiles("prod", "feature-x");
        logs = CapturedLog.attach();
    }

    @AfterEach
    void tearDown() {
        logs.close();
        context.close();
    }

    @Test
    void theStartupRecordStatesTheHostTheProfilesAndTheBehaviouralSettings() throws Exception {
        listener(true).started(ready(context));

        ILoggingEvent record = onlyRecord("application-startup");
        Map<String, Object> fields = CapturedLog.fields(record);
        InetAddress host = InetAddress.getLocalHost();
        assertThat(record.getLevel()).isEqualTo(Level.INFO);
        assertThat(record.getFormattedMessage()).isEqualTo("Application started");
        assertThat(fields)
                .containsEntry(LogEvent.KIND, "event")
                .containsEntry(LogEvent.CATEGORY, List.of("process"))
                .containsEntry(LogEvent.TYPE, List.of("start"))
                .containsEntry(LogEvent.OUTCOME, "success")
                .containsEntry(LogEvent.SEVERITY, "low")
                .doesNotContainKey(LogEvent.LOCAL_ACTION)
                .containsEntry(LogEvent.HOST_NAME, host.getHostName())
                .containsEntry(LogEvent.HOST_IP, host.getHostAddress())
                .containsEntry(ApplicationLifecycleLog.PROFILES, List.of("prod", "feature-x"))
                .containsEntry(ApplicationLifecycleLog.SCIM_ENABLED, true)
                .containsEntry(ApplicationLifecycleLog.DORMANCY_LOCKOUT_WINDOW, "PT1464H")
                .containsEntry(ApplicationLifecycleLog.DORMANCY_ROLE_REVOCATION_WINDOW,
                        "PT2928H")
                .containsEntry(ApplicationLifecycleLog.AUDIT_RETENTION_PERIOD, "PT9600H")
                .doesNotContainKeys(AUTH_FLOW_KEYS.toArray(String[]::new));
    }

    /**
     * The logging standard forbids timeout or retry values for authentication flows, so the
     * session timeouts and the lockout threshold stay off the record (ADR 0003).
     */
    static final List<String> AUTH_FLOW_KEYS = List.of(
            "app.session.idle_timeout",
            "app.session.absolute_lifetime",
            "app.auth.lockout.max_attempts");

    /** The gate's state is read, not assumed open. */
    @Test
    void aClosedScimGateIsRecordedAsClosed() {
        listener(false).started(ready(context));

        assertThat(CapturedLog.fields(onlyRecord("application-startup")))
                .containsEntry(ApplicationLifecycleLog.SCIM_ENABLED, false);
    }

    /** Epic Login off: the switch, and no key id, since none was read. */
    @Test
    void aClosedEpicGateIsRecordedAsOffWithNoKeyIds() {
        listener(true, new EpicReleaseGate(false), Optional.empty()).started(ready(context));

        assertThat(CapturedLog.fields(onlyRecord("application-startup")))
                .containsEntry("app.epic.enabled", false)
                .doesNotContainKeys("app.epic.client_key_id", "app.epic.client_next_key_id");
    }

    /**
     * Epic Login on: the switch and both {@code kid}s, so each redeploy that promotes a key leaves
     * a record. A {@code kid} is a public label; nothing else about Epic — no URL, no client id, no
     * key material — is on the record.
     */
    @Test
    void anOpenEpicGateIsRecordedWithItsActiveAndNextKeyIds() {
        listener(true, new EpicReleaseGate(true),
                Optional.of(signingKeys("active-2026-04", "next-2026-10")))
                .started(ready(context));

        Map<String, Object> fields = CapturedLog.fields(onlyRecord("application-startup"));
        assertThat(fields)
                .containsEntry("app.epic.enabled", true)
                .containsEntry("app.epic.client_key_id", "active-2026-04")
                .containsEntry("app.epic.client_next_key_id", "next-2026-10");
        assertThat(fields.keySet().stream().filter(key -> key.startsWith("app.epic.")))
                .containsExactlyInAnyOrder("app.epic.enabled", "app.epic.client_key_id",
                        "app.epic.client_next_key_id");
    }

    /** No next key configured: the active {@code kid} alone. */
    @Test
    void anOpenEpicGateWithoutANextKeyRecordsTheActiveKeyIdAlone() {
        listener(true, new EpicReleaseGate(true), Optional.of(signingKeys("active-2026-04", null)))
                .started(ready(context));

        assertThat(CapturedLog.fields(onlyRecord("application-startup")))
                .containsEntry("app.epic.client_key_id", "active-2026-04")
                .doesNotContainKey("app.epic.client_next_key_id");
    }

    @Test
    void theShutdownRecordCarriesTheContextsUptime() throws Exception {
        Thread.sleep(40);
        listener(true).stopping(new ContextClosedEvent(context));

        ILoggingEvent record = onlyRecord("application-shutdown");
        Map<String, Object> fields = CapturedLog.fields(record);
        assertThat(record.getLevel()).isEqualTo(Level.INFO);
        assertThat(record.getFormattedMessage()).isEqualTo("Application shutting down");
        assertThat(fields)
                .containsEntry(LogEvent.KIND, "event")
                .containsEntry(LogEvent.CATEGORY, List.of("process"))
                .containsEntry(LogEvent.TYPE, List.of("end"))
                .containsEntry(LogEvent.OUTCOME, "success")
                .containsEntry(LogEvent.SEVERITY, "low");
        long uptime = (Long) fields.get(LogEvent.DURATION_MS);
        assertThat(uptime).isBetween(40L, System.currentTimeMillis() - context.getStartupDate());
    }

    /**
     * Another context's events — a management child context closing, above all, which reaches
     * its parent's listeners too — write nothing.
     */
    @Test
    void anotherContextsEventsWriteNoRecord() {
        try (GenericApplicationContext child = new GenericApplicationContext()) {
            child.refresh();
            ApplicationLifecycleLog listener = listener(true);

            listener.started(ready(child));
            listener.stopping(new ContextClosedEvent(child));
        }

        assertThat(logs.withAction(Level.TRACE, LogEvent.KIND, "event")).isEmpty();
    }

    /**
     * A host that cannot resolve its own name still gets its record, without {@code host.*}
     * fields rather than with a placeholder.
     */
    @Test
    void anUnresolvableHostLeavesTheHostFieldsOff() {
        ApplicationLifecycleLog.withHost(
                        LoggerFactory.getLogger(ApplicationLifecycleLogTests.class).atInfo()
                                .addKeyValue(LogEvent.KIND, "event"),
                        () -> {
                            throw new UnknownHostException("no resolver");
                        })
                .log("probe");

        List<ILoggingEvent> records = logs.withAction(Level.TRACE, LogEvent.KIND, "event");
        assertThat(records).hasSize(1);
        assertThat(CapturedLog.fields(records.getFirst()))
                .doesNotContainKeys(LogEvent.HOST_NAME, LogEvent.HOST_IP);
    }

    /** Signing keys under these kids; {@code nextKeyId} {@code null} for no next key. */
    private static EpicSigningKeys signingKeys(String activeKeyId, String nextKeyId) {
        return new EpicSigningKeys(signingKey(activeKeyId),
                Optional.ofNullable(nextKeyId).map(ApplicationLifecycleLogTests::signingKey));
    }

    private static EpicSigningKey signingKey(String keyId) {
        return new EpicSigningKey(
                keyId, EcP384PrivateKeyPem.parse(EpicTestKeys.p384Pem()).orElseThrow());
    }

    private ApplicationLifecycleLog listener(boolean scimOpen) {
        return listener(scimOpen, new EpicReleaseGate(false), Optional.empty());
    }

    private ApplicationLifecycleLog listener(
            boolean scimOpen, EpicReleaseGate epicGate, Optional<EpicSigningKeys> epicKeys) {
        return new ApplicationLifecycleLog(
                context,
                environment,
                new ScimReleaseGate(scimOpen),
                epicGate,
                epicKeys,
                new DormancyPolicy(Duration.ofDays(61), Duration.ofDays(122)),
                new AuditRetentionPolicy(Duration.ofDays(400), null));
    }

    private static ApplicationReadyEvent ready(GenericApplicationContext context) {
        return new ApplicationReadyEvent(new SpringApplication(), new String[0], context, Duration.ZERO);
    }

    private ILoggingEvent onlyRecord(String action) {
        List<ILoggingEvent> records = logs.withAction(Level.TRACE, LogEvent.ACTION, action);
        assertThat(records).hasSize(1);
        return records.getFirst();
    }
}

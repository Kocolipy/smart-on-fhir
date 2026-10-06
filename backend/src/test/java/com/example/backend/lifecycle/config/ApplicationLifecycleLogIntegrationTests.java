package com.example.backend.lifecycle.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.backend.BackendApplication;
import com.example.backend.ContainerTestConfiguration;
import com.example.backend.auth.epic.EpicTestKeys;
import com.example.backend.observability.EcsLogCapture;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.context.event.ApplicationPreparedEvent;
import org.springframework.boot.jdbc.autoconfigure.JdbcConnectionDetails;
import org.springframework.context.ApplicationEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.context.ConfigurableApplicationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The lifecycle records as a real application emits them: one startup record once the
 * context is refreshed and serving, one shutdown record when it closes, each encoded by the
 * production ECS encoder.
 *
 * <p>A context of its own, started and closed by the test, rather than the shared
 * {@code @SpringBootTest} one: the startup record is written before any test of a cached
 * context could attach a capture, and closing a shared context would take it from every other
 * class. The capture is attached on {@link ApplicationPreparedEvent} — after Boot has
 * initialised logging, which may reset the logger context, and before refresh.
 *
 * <p>The settings are given distinctive values here, and one is left unset, so the record is
 * shown to report the configuration this context resolved — a default from its policy
 * included — rather than a constant.
 */
class ApplicationLifecycleLogIntegrationTests {

    /** The test configuration's credentials and identities: none may reach the record. */
    private static final List<String> FORBIDDEN = List.of(
            "test-user", "test-admin", "test-password", "test-admin-password");

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final AtomicReference<EcsLogCapture> capture = new AtomicReference<>();

    private ConfigurableApplicationContext application;

    @AfterEach
    void tearDown() {
        if (application != null) {
            application.close();
        }
        if (capture.get() != null) {
            capture.get().close();
        }
    }

    @Test
    void startingAndClosingTheApplicationWritesOneStartupAndOneShutdownRecord() {
        // Command-line arguments rather than builder properties: those are defaults, which the
        // test application.yaml's own lockout setting would override.
        application = new SpringApplicationBuilder(
                        BackendApplication.class, ContainerTestConfiguration.class)
                .listeners((ApplicationListener<ApplicationEvent>) event -> {
                    if (event instanceof ApplicationPreparedEvent prepared) {
                        capture.set(EcsLogCapture.attach(
                                prepared.getApplicationContext().getEnvironment()));
                    }
                })
                .run(
                        "--server.port=0",
                        "--spring.profiles.active=lifecycle-probe",
                        "--server.servlet.session.timeout=17m",
                        "--app.auth.lockout.max-attempts=4",
                        "--app.dormancy.lockout.window=61d",
                        "--app.audit.retention.period=400d");
        String jdbcUrl = application.getBean(JdbcConnectionDetails.class).getJdbcUrl();

        List<String> startup = linesWithAction("application-startup");
        assertThat(startup).as("startup records").hasSize(1);
        JsonNode record = JSON.readTree(startup.getFirst());
        assertThat(record.at("/message").asText()).isEqualTo("Application started");
        assertThat(record.at("/log/level").asText()).isEqualTo("INFO");
        assertThat(record.at("/event/type/0").asText()).isEqualTo("start");
        assertThat(record.at("/host/name").asText()).isNotBlank();
        assertThat(record.at("/host/ip").asText()).isNotBlank();
        assertThat(record.at("/service/name").asText()).isEqualTo("backend");
        assertThat(record.at("/service/version").asText()).isNotBlank().doesNotContain("@");
        assertThat(record.at("/service/environment").asText()).isNotBlank();
        assertThat(record.at("/spring/profiles/active").valueStream().map(JsonNode::asText).toList())
                .containsExactly("lifecycle-probe");
        assertThat(record.at("/app/scim/enabled").asBoolean(false)).isTrue();
        assertThat(record.at("/app/epic/enabled").isBoolean())
                .as("Epic Login's switch is recorded, and with no variable set it is off")
                .isTrue();
        assertThat(record.at("/app/epic/enabled").asBoolean(true)).isFalse();
        assertThat(record.at("/app/session").isMissingNode())
                .as("session timeouts are authentication-flow values the standard keeps out")
                .isTrue();
        assertThat(record.at("/app/auth").isMissingNode())
                .as("the lockout threshold is an authentication-flow value the standard keeps out")
                .isTrue();
        assertThat(record.at("/app/dormancy/lockout/window").asText()).isEqualTo("PT1464H");
        assertThat(record.at("/app/dormancy/role_revocation/window").asText())
                .as("unset, so the policy's 180-day default")
                .isEqualTo("PT4320H");
        assertThat(record.at("/app/audit/retention/period").asText()).isEqualTo("PT9600H");
        assertThat(startup.getFirst())
                .doesNotContain(FORBIDDEN.toArray(String[]::new))
                .doesNotContain(jdbcUrl, "jdbc:", "redis:");
        assertThat(linesWithAction("application-shutdown")).as("before close").isEmpty();

        application.close();
        application = null;

        List<String> shutdown = linesWithAction("application-shutdown");
        assertThat(shutdown).as("shutdown records").hasSize(1);
        JsonNode closed = JSON.readTree(shutdown.getFirst());
        assertThat(closed.at("/message").asText()).isEqualTo("Application shutting down");
        assertThat(closed.at("/event/type/0").asText()).isEqualTo("end");
        assertThat(closed.at("/event/duration_ms").isIntegralNumber()).isTrue();
        assertThat(closed.at("/event/duration_ms").asLong()).isPositive();
        assertThat(linesWithAction("application-startup")).as("still one startup").hasSize(1);
    }

    /**
     * Epic Login on: the one startup record gives the switch and both {@code kid}s, and nothing
     * else Epic Login was configured with — no URL, no issuer, no client id and no key material.
     */
    @Test
    void withEpicLoginOnTheStartupRecordGivesTheKeyIdsAndNoOtherEpicSetting() {
        String activeKey = EpicTestKeys.p384Pem();
        String nextKey = EpicTestKeys.p384Pem();
        application = new SpringApplicationBuilder(
                        BackendApplication.class, ContainerTestConfiguration.class)
                .listeners((ApplicationListener<ApplicationEvent>) event -> {
                    if (event instanceof ApplicationPreparedEvent prepared) {
                        capture.set(EcsLogCapture.attach(
                                prepared.getApplicationContext().getEnvironment()));
                    }
                })
                .run(
                        "--server.port=0",
                        "--app.epic.enabled=true",
                        "--app.epic.fhir-base=https://fhir.example.org/api/FHIR/R4",
                        "--app.epic.oauth-issuer=https://issuer.example.org/oauth2",
                        "--app.epic.client-id=epic-client-id-probe",
                        "--app.epic.redirect-uri=https://app.example.org/api/auth/epic/callback",
                        "--app.epic.client-key=" + activeKey,
                        "--app.epic.client-key-id=active-2026-04",
                        "--app.epic.client-next-key=" + nextKey,
                        "--app.epic.client-next-key-id=next-2026-10");

        List<String> startup = linesWithAction("application-startup");
        assertThat(startup).as("startup records").hasSize(1);
        JsonNode record = JSON.readTree(startup.getFirst());
        assertThat(record.at("/app/epic/enabled").asBoolean(false)).isTrue();
        assertThat(record.at("/app/epic/client_key_id").asText()).isEqualTo("active-2026-04");
        assertThat(record.at("/app/epic/client_next_key_id").asText()).isEqualTo("next-2026-10");
        assertThat(capture.get().lines())
                .as("no record at all carries an Epic URL, the client id or either key")
                .doesNotContain("fhir.example.org", "issuer.example.org", "app.example.org",
                        "epic-client-id-probe", "PRIVATE KEY", base64Body(activeKey),
                        base64Body(nextKey));
    }

    /** The base64 of a PEM's first line of key material, which identifies the key. */
    private static String base64Body(String pem) {
        return pem.lines().skip(1).findFirst().orElseThrow();
    }

    /**
     * The records of the operation whose ECS action this is and which has no local name of its
     * own — so {@code application-startup} is the lifecycle record alone, not the role mapping's
     * startup records, which share the action and carry {@code app.event.action}.
     */
    private List<String> linesWithAction(String action) {
        return capture.get().lines().lines()
                .filter(line -> action.equals(JSON.readTree(line).at("/event/action").asText()))
                .filter(line -> JSON.readTree(line).at("/app/event/action").isMissingNode())
                .toList();
    }
}

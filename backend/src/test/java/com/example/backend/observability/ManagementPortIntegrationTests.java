package com.example.backend.observability;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.backend.ContainerTestConfiguration;
import com.example.backend.DevFixtures;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.assertj.core.api.SoftAssertions;
import org.junit.jupiter.api.Test;
import org.springframework.boot.micrometer.metrics.test.autoconfigure.AutoConfigureMetrics;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalManagementPort;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The internal-interface deployment: actuator moved to its own port with
 * {@code MANAGEMENT_SERVER_PORT}, as infra/docs/operational-telemetry.md's "Operational telemetry" section
 * describes.
 *
 * <p>What the documentation promises and this pins: moving the scrape off the public port
 * takes it off the public port — the application port no longer serves it — and does NOT
 * take it out from behind its Permission, because the application chain guards the
 * management port too. A network boundary is added, never swapped for the access rule —
 * and the rule still admits the Auditors Group's account there, holding {@code ops:read}, so
 * Prometheus has a working path; the guest, in no Group and so without {@code ops:read}, is
 * refused.
 *
 * <p>Runs under the shipped development fixtures and their {@code local} Group Permissions, so the two
 * accounts are the ones local runs and the e2e suite sign in as.
 *
 * <p>Every expectation sits in ONE test method, with soft assertions so each still reports
 * on its own. {@code ManagementSessionConfiguration} runs once, while this class's context
 * boots. PIT credits that boot to whichever test method triggered it and re-runs only that
 * method against each mutant, so an expectation in any other method is invisible to
 * mutation testing. Splitting this class up again would let those mutants survive.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "management.server.port=0",
            "app.dev-fixtures.enabled=true",
            "app.dev-fixtures.password=" + ManagementPortIntegrationTests.FIXTURE_PASSWORD})
@ActiveProfiles({"dev-fixtures", "local"})
@Import(ContainerTestConfiguration.class)
@AutoConfigureMetrics
class ManagementPortIntegrationTests {

    static final String FIXTURE_PASSWORD = DevFixtures.PASSWORD;

    /** The Auditors Group's fixture User: every read, {@code ops:read} among them. */
    private static final String AUDITOR = "auditor";

    /** The baseline fixture User, in no Group: the baseline Permissions alone. */
    private static final String GUEST = "guest";

    private static final String REQUESTS = "http_server_requests_seconds_count";

    @LocalServerPort
    private int applicationPort;

    @LocalManagementPort
    private int managementPort;

    private final HttpClient anonymous = HttpClient.newHttpClient();

    @Test
    void the_scrape_moves_to_the_management_port_and_stays_behind_ops_read()
            throws Exception {
        SoftAssertions softly = new SoftAssertions();

        // Off the application port.
        softly.assertThat(managementPort).isNotEqualTo(applicationPort);
        HttpResponse<String> onApplicationPort =
                get(anonymous, applicationPort, "/actuator/prometheus");
        softly.assertThat(onApplicationPort.statusCode())
                .as("the application port no longer serves actuator")
                .isIn(401, 404);
        softly.assertThat(onApplicationPort.body()).doesNotContain(REQUESTS);

        // Still behind the gate on the management port.
        HttpResponse<String> anonymousScrape =
                get(anonymous, managementPort, "/actuator/prometheus");
        softly.assertThat(anonymousScrape.statusCode())
                .as("anonymous scrape on the management port")
                .isEqualTo(401);
        softly.assertThat(anonymousScrape.body()).doesNotContain(REQUESTS);

        // Health stays public, so the ALB health check keeps working on the new port.
        softly.assertThat(get(anonymous, managementPort, "/actuator/health").statusCode())
                .as("health on the management port")
                .isEqualTo(200);

        // The Auditors' account, holding ops:read, is admitted. Cookies are scoped to the host,
        // not the port (RFC 6265 §8.5), so a session opened on the application port is
        // presented to the management port as well.
        HttpClient auditor = session(AUDITOR);
        HttpResponse<String> auditorScrape =
                get(auditor, managementPort, "/actuator/prometheus");
        softly.assertThat(auditorScrape.statusCode())
                .as("Auditor scrape on the management port")
                .isEqualTo(200);
        softly.assertThat(auditorScrape.body()).contains(REQUESTS);

        // The guest, signed in but without ops:read, is refused there.
        HttpResponse<String> guestScrape =
                get(session(GUEST), managementPort, "/actuator/prometheus");
        softly.assertThat(guestScrape.statusCode())
                .as("guest scrape on the management port")
                .isEqualTo(403);
        softly.assertThat(guestScrape.body()).doesNotContain(REQUESTS);

        softly.assertAll();
    }

    /**
     * A password login through the real CSRF handshake, as the SPA does it: fetch the
     * session's token from {@code GET /api/auth/csrf}, then echo it in the header named there.
     */
    private HttpClient session(String userName) throws Exception {
        CookieManager jar = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
        HttpClient client = HttpClient.newBuilder().cookieHandler(jar).build();
        HttpResponse<String> issued = get(client, applicationPort, "/api/auth/csrf");
        assertThat(issued.statusCode()).as("the CSRF token fetch").isEqualTo(200);
        JsonNode csrf = JsonMapper.builder().build().readTree(issued.body());
        HttpResponse<String> login = client.send(request(applicationPort, "/api/auth/login")
                .header("Content-Type", "application/json")
                .header(csrf.get("headerName").asText(), csrf.get("token").asText())
                .POST(HttpRequest.BodyPublishers.ofString(
                        "{\"username\":\"" + userName + "\",\"password\":\"" + FIXTURE_PASSWORD
                                + "\"}"))
                .build(), HttpResponse.BodyHandlers.ofString());
        assertThat(login.statusCode()).as("the %s login itself", userName).isEqualTo(200);
        return client;
    }

    private static HttpResponse<String> get(HttpClient client, int port, String path)
            throws Exception {
        return client.send(request(port, path).GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    private static HttpRequest.Builder request(int port, String path) {
        return HttpRequest.newBuilder(URI.create("http://localhost:" + port + path));
    }
}

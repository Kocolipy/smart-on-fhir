package com.example.backend.observability;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.backend.ContainerTestConfiguration;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.HttpCookie;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The {@code session-start} record as the deployed service writes it, over a real socket.
 *
 * <p>A running server rather than MockMvc because the property under test is about the real
 * session: MockMvc's mock session has a short counter for an id, which a search of the encoded
 * stream would find in every timestamp, while the deployed repository's id is a UUID carried in
 * the cookie Base64-encoded. Both spellings, before and after the login rotates the id, are
 * searched for in the raw encoded bytes of every record the flow wrote.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(ContainerTestConfiguration.class)
class SessionStartLogIntegrationTests {

    private static final String SESSION_STARTED = "Session started";

    private final JsonMapper json = JsonMapper.builder().build();

    @LocalServerPort
    private int port;

    @Autowired
    private Environment environment;

    private EcsLogCapture logs;

    private CookieManager cookies;

    private HttpClient client;

    @BeforeEach
    void setUp() {
        logs = EcsLogCapture.attach(environment);
        cookies = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
        client = HttpClient.newBuilder().cookieHandler(cookies).build();
    }

    @AfterEach
    void tearDown() {
        logs.close();
    }

    /**
     * The anonymous session the CSRF grant mints is not a session start: the grant writes no
     * {@code session-start}, and the login that follows writes exactly one, carrying the idle
     * bound the login answered with and the User's stable id, and no spelling of either session
     * id anywhere in the stream.
     */
    @Test
    void aLoginWritesOneSessionStartAndTheCsrfGrantWritesNone() throws Exception {
        JsonNode csrf = json.readTree(send(request("/api/auth/csrf").GET()).body());
        String anonymousCookie = sessionCookie();
        awaitRequestRecords(1);
        assertThat(sessionStarts()).as("the CSRF grant's anonymous session").isEmpty();

        HttpResponse<String> login = send(request("/api/auth/login")
                .header("Content-Type", "application/json")
                .header(csrf.get("headerName").asText(), csrf.get("token").asText())
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(
                        Map.of("username", "test-user", "password", "test-password")))));
        assertThat(login.statusCode()).isEqualTo(200);
        String signedInCookie = sessionCookie();
        awaitRequestRecords(2);

        List<JsonNode> starts = sessionStarts();
        assertThat(starts).hasSize(1);
        JsonNode record = starts.getFirst();
        assertThat(record.at("/log/level").asText()).isEqualTo("INFO");
        assertThat(record.at("/event/action").asText()).isEqualTo("session-start");
        assertThat(record.at("/event/kind").asText()).isEqualTo("event");
        assertThat(record.at("/event/category").valueStream().map(JsonNode::asText).toList())
                .containsExactly("process");
        assertThat(record.at("/event/type").valueStream().map(JsonNode::asText).toList())
                .containsExactly("start");
        assertThat(record.at("/event/outcome").asText()).isEqualTo("success");
        assertThat(record.at("/event/severity").asText()).isEqualTo("low");
        int idleTimeoutSeconds = json.readTree(login.body()).get("idleTimeoutSeconds").asInt();
        assertThat(idleTimeoutSeconds).isPositive();
        assertThat(record.at("/session/max_inactive_interval").asInt())
                .isEqualTo(idleTimeoutSeconds);
        assertThat(record.at("/user/id").asText()).isNotBlank();
        assertThat(record.at("/app/event/action").isMissingNode())
                .as("an exact action keeps no local name").isTrue();
        assertThat(record.at("/app/login/method").asText())
                .as("D15: a password Login's session-start names its method")
                .isEqualTo("password");

        assertThat(signedInCookie).as("the login rotated the id").isNotEqualTo(anonymousCookie);
        List<String> forbidden = List.of(
                anonymousCookie, decoded(anonymousCookie),
                signedInCookie, decoded(signedInCookie));
        assertThat(forbidden).allSatisfy(value -> assertThat(value).hasSizeGreaterThan(16));
        String encoded = logs.lines();
        assertThat(encoded).as("the stream the flow wrote").contains(SESSION_STARTED);
        assertThat(forbidden).allSatisfy(value -> assertThat(encoded).doesNotContain(value));
    }

    /** A refused login started no session, so it writes no {@code session-start}. */
    @Test
    void aRefusedLoginWritesNoSessionStart() throws Exception {
        JsonNode csrf = json.readTree(send(request("/api/auth/csrf").GET()).body());

        HttpResponse<String> login = send(request("/api/auth/login")
                .header("Content-Type", "application/json")
                .header(csrf.get("headerName").asText(), csrf.get("token").asText())
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(
                        Map.of("username", "test-user", "password", "wrong-password")))));
        assertThat(login.statusCode()).isEqualTo(401);
        awaitRequestRecords(2);

        assertThat(logs.records()).as("the flow wrote records").isNotEmpty();
        assertThat(sessionStarts()).isEmpty();
    }

    // ---- harness -----------------------------------------------------------------------------

    private List<JsonNode> sessionStarts() {
        return logs.records().stream()
                .filter(record -> SESSION_STARTED.equals(record.at("/message").asText())
                        || "session-start".equals(record.at("/event/action").asText()))
                .toList();
    }

    /** The session cookie the client now holds, exactly as the server set it. */
    private String sessionCookie() {
        // Read from the context's own configuration: the test resources' application.yaml
        // shadows the main one and leaves the name to Spring Session's default.
        String name = environment.getProperty("server.servlet.session.cookie.name", "SESSION");
        return cookies.getCookieStore().getCookies().stream()
                .filter(cookie -> name.equals(cookie.getName()))
                .map(HttpCookie::getValue)
                .findFirst()
                .orElseThrow();
    }

    /** The session id the cookie carries: Spring Session Base64-encodes it. */
    private static String decoded(String cookieValue) {
        return new String(Base64.getDecoder().decode(cookieValue), StandardCharsets.UTF_8);
    }

    /**
     * The request record is written as the request dispatch unwinds, which can be after the
     * client has its response; wait for the flow's {@code count}th so every record of the
     * requests sent so far has landed.
     */
    private void awaitRequestRecords(int count) throws InterruptedException {
        long deadline = System.nanoTime() + 5_000_000_000L;
        while (logs.records().stream()
                .filter(record -> "HTTP request completed".equals(record.at("/message").asText()))
                .count() < count
                && System.nanoTime() < deadline) {
            Thread.sleep(20);
        }
        Thread.sleep(250);
    }

    private HttpRequest.Builder request(String path) {
        return HttpRequest.newBuilder(URI.create("http://localhost:" + port + path));
    }

    private HttpResponse<String> send(HttpRequest.Builder request) throws Exception {
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }
}

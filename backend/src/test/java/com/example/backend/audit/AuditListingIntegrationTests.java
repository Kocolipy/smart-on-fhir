package com.example.backend.audit;

import static com.example.backend.SessionLogin.logInSession;
import static com.example.backend.TestJson.JSON;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.example.backend.ContainerTestConfiguration;
import com.example.backend.DatabaseLogins;
import com.example.backend.observability.EcsLogCapture;
import com.example.backend.observability.RequestIdFilter;
import jakarta.servlet.Filter;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.JsonNode;

/**
 * The listing's paging and filtering, against a seeded fixture that spans more than one page, over
 * the real filter chain, the real controller and the application's own JSON converters, read as a
 * real administrator after a real login.
 *
 * <p>The fixture is inserted as rows rather than produced by driving operations, because what is
 * under test here is the READ — its order, its page boundaries and its filters — and that needs
 * events whose instants, operations and outcomes are chosen, which driving the operations cannot
 * give. What each operation actually records is {@code AuditListingEndToEndIntegrationTests}'s.
 *
 * <p>The trail is append-only, so the rows outlive the test. Every row of a test carries an actor id
 * minted for that test, and every read filters by it, so rows other tests append — and this
 * test's own rows from another method — cannot reach an assertion.
 */
@SpringBootTest
@Import(ContainerTestConfiguration.class)
class AuditListingIntegrationTests {

    private static final String LISTING = "/api/admin/audit-events";

    /** More than two pages of {@link #PAGE_SIZE}, and not a multiple of it. */
    private static final int FIXTURE_SIZE = 23;

    private static final int PAGE_SIZE = 10;

    private static final String INSERT = """
            INSERT INTO audit_events (id, occurred_at, operation, outcome, actor_id, subject_id,
                                      resource_type, resource_id, changed_paths, status_class,
                                      error_code, http_method, http_path, request_id,
                                      result_count, filter_shape)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private DatabaseLogins logins;

    @Autowired
    private Environment environment;

    @Autowired
    private RequestIdFilter requestIdFilter;

    @Autowired
    @Qualifier("springSecurityFilterChain")
    private Filter springSecurityFilterChain;

    private MockMvc mvc;

    private MockHttpSession admin;

    /** This test's own actor id: the key that isolates its fixture from every other row. */
    private final UUID actor = UUID.randomUUID();

    /** A base instant in the past but inside retention, so no retention run removes the fixture. */
    private final Instant base = Instant.now().truncatedTo(ChronoUnit.MICROS).minus(1, ChronoUnit.DAYS);

    private final UUID resource = UUID.randomUUID();

    /** The fixture as seeded, in the order the listing must return it. */
    private final List<Seeded> seeded = new ArrayList<>();

    private record Seeded(UUID id, Instant occurredAt, String operation, String outcome,
            UUID resourceId) {
    }

    @BeforeEach
    void setUp() throws Exception {
        mvc = MockMvcBuilders.webAppContextSetup(context)
                .addFilters(requestIdFilter, springSecurityFilterChain)
                .build();
        admin = logInSession(mvc, "test-admin", "test-admin-password");
        seedFixture();
    }

    /**
     * Every seeded event appears exactly once across the pages, newest first — including the two
     * that share an instant, which only a total order keeps on one page each — and the totals
     * describe the whole filtered set rather than the page.
     */
    @Test
    void pagesAcrossTheFixtureNewestFirstWithNoEventRepeatedOrSkipped() throws Exception {
        List<String> listed = new ArrayList<>();
        for (int page = 0; page < 3; page++) {
            JsonNode body = list("actorId=%s&size=%d&page=%d".formatted(actor, PAGE_SIZE, page));
            assertThat(body.get("page").asInt()).isEqualTo(page);
            assertThat(body.get("size").asInt()).isEqualTo(PAGE_SIZE);
            assertThat(body.get("totalElements").asLong()).isEqualTo(FIXTURE_SIZE);
            assertThat(body.get("totalPages").asLong()).isEqualTo(3);
            assertThat(body.get("events"))
                    .as("page %d holds a full page, or the remainder on the last", page)
                    .hasSize(page < 2 ? PAGE_SIZE : FIXTURE_SIZE - 2 * PAGE_SIZE);
            body.get("events").forEach(event -> listed.add(event.get("id").asText()));
        }

        assertThat(listed).containsExactlyElementsOf(expectedOrder(seeded));

        JsonNode past = list("actorId=%s&size=%d&page=3".formatted(actor, PAGE_SIZE));
        assertThat(past.get("events")).as("a page past the end is empty, not an error").isEmpty();
        assertThat(past.get("totalElements").asLong()).isEqualTo(FIXTURE_SIZE);
    }

    /** No size asked for: one page of the default size, which the fixture does not fill. */
    @Test
    void theDefaultPageIsTheFirstOfFifty() throws Exception {
        JsonNode body = list("actorId=" + actor);

        assertThat(body.get("page").asInt()).isZero();
        assertThat(body.get("size").asInt()).isEqualTo(50);
        assertThat(body.get("events")).hasSize(FIXTURE_SIZE);
    }

    /** Filtering by operation narrows the set before it is paged, so the totals narrow with it. */
    @Test
    void filtersByOperationAcrossPages() throws Exception {
        List<Seeded> failures = seeded.stream()
                .filter(event -> event.operation().equals("LOGIN_FAILURE")).toList();
        assertThat(failures).as("the fixture must hold more than one page of them").hasSizeGreaterThan(5);

        List<String> listed = new ArrayList<>();
        JsonNode first = list("actorId=%s&operation=LOGIN_FAILURE&size=5".formatted(actor));
        assertThat(first.get("totalElements").asLong()).isEqualTo(failures.size());
        assertThat(first.get("totalPages").asLong()).isEqualTo((failures.size() + 4) / 5);
        for (int page = 0; page < first.get("totalPages").asLong(); page++) {
            JsonNode body = list("actorId=%s&operation=LOGIN_FAILURE&size=5&page=%d"
                    .formatted(actor, page));
            body.get("events").forEach(event -> {
                assertThat(event.get("operation").asText()).isEqualTo("LOGIN_FAILURE");
                listed.add(event.get("id").asText());
            });
        }
        assertThat(listed).containsExactlyElementsOf(expectedOrder(failures));
    }

    @Test
    void filtersByOutcome() throws Exception {
        JsonNode body = list("actorId=%s&outcome=SUCCESS".formatted(actor));

        assertThat(ids(body)).containsExactlyElementsOf(expectedOrder(seeded.stream()
                .filter(event -> event.outcome().equals("SUCCESS")).toList()));
    }

    @Test
    void filtersByResource() throws Exception {
        JsonNode body = list("actorId=%s&resourceId=%s".formatted(actor, resource));

        List<Seeded> about = seeded.stream()
                .filter(event -> resource.equals(event.resourceId())).toList();
        assertThat(about).isNotEmpty().hasSizeLessThan(FIXTURE_SIZE);
        assertThat(ids(body)).containsExactlyElementsOf(expectedOrder(about));
    }

    /** {@code from} is inclusive and {@code to} exclusive, so adjacent windows never overlap. */
    @Test
    void filtersByATimeWindowInclusiveOfItsStartAndExclusiveOfItsEnd() throws Exception {
        Instant from = base.plusSeconds(5);
        Instant to = base.plusSeconds(12);

        JsonNode body = list("actorId=%s&from=%s&to=%s".formatted(actor, from, to));

        List<Seeded> inside = seeded.stream()
                .filter(event -> !event.occurredAt().isBefore(from) && event.occurredAt().isBefore(to))
                .toList();
        assertThat(inside).extracting(Seeded::occurredAt).contains(from).doesNotContain(to);
        assertThat(ids(body)).containsExactlyElementsOf(expectedOrder(inside));
    }

    /** Filters combine with AND. */
    @Test
    void combinesFilters() throws Exception {
        JsonNode body = list("actorId=%s&operation=LOGIN_FAILURE&outcome=SUCCESS".formatted(actor));

        assertThat(body.get("totalElements").asLong()).isZero();
        assertThat(body.get("events")).isEmpty();
    }

    /**
     * Every stored column reaches the wire under its own name, the changed-path list as an array and
     * an event that changed nothing as an empty one — the shape the API contract documents.
     */
    @Test
    void rendersEveryRecordedFieldOfAnEvent() throws Exception {
        UUID actorOnly = UUID.randomUUID();
        UUID id = UUID.randomUUID();
        UUID subject = UUID.randomUUID();
        Instant at = base.plusSeconds(100);
        jdbc.update(INSERT, id, at.atOffset(ZoneOffset.UTC), "SCIM_USER_LIST", "SUCCESS", actorOnly,
                subject, "User", subject, "active,password", "ok", "INVALID_VALUE", "GET",
                "/scim/v2/Users", "req-123", 7, "userName eq ?");
        UUID quiet = UUID.randomUUID();
        jdbc.update(INSERT, quiet, at.minusSeconds(1).atOffset(ZoneOffset.UTC), "LOGIN_SUCCESS",
                "SUCCESS", actorOnly, null, "User", null, null, "ok", null, null, null, null, null,
                null);

        JsonNode events = list("actorId=" + actorOnly).get("events");

        assertThat(events).hasSize(2);
        JsonNode full = events.get(0);
        assertThat(full.get("id").asText()).isEqualTo(id.toString());
        assertThat(Instant.parse(full.get("occurredAt").asText())).isEqualTo(at);
        assertThat(full.get("operation").asText()).isEqualTo("SCIM_USER_LIST");
        assertThat(full.get("outcome").asText()).isEqualTo("SUCCESS");
        assertThat(full.get("actorId").asText()).isEqualTo(actorOnly.toString());
        assertThat(full.get("subjectId").asText()).isEqualTo(subject.toString());
        assertThat(full.get("resourceType").asText()).isEqualTo("User");
        assertThat(full.get("resourceId").asText()).isEqualTo(subject.toString());
        assertThat(full.get("changedPaths").valueStream().map(JsonNode::asText))
                .containsExactly("active", "password");
        assertThat(full.get("statusClass").asText()).isEqualTo("ok");
        assertThat(full.get("errorCode").asText()).isEqualTo("INVALID_VALUE");
        assertThat(full.get("httpMethod").asText()).isEqualTo("GET");
        assertThat(full.get("httpPath").asText()).isEqualTo("/scim/v2/Users");
        assertThat(full.get("requestId").asText()).isEqualTo("req-123");
        assertThat(full.get("resultCount").asInt()).isEqualTo(7);
        assertThat(full.get("filterShape").asText()).isEqualTo("userName eq ?");

        JsonNode empty = events.get(1);
        assertThat(empty.get("id").asText()).isEqualTo(quiet.toString());
        assertThat(empty.get("changedPaths")).as("nothing changed is an empty list").isEmpty();
        assertThat(empty.get("subjectId").isNull()).isTrue();
        assertThat(empty.get("resultCount").isNull()).isTrue();
        assertThat(empty.get("filterShape").isNull()).isTrue();
    }

    @Test
    void aMalformedQueryIsABadRequest() throws Exception {
        for (String query : List.of("size=0", "size=201", "page=-1", "operation=NOT_AN_OPERATION",
                "outcome=MAYBE", "actorId=not-a-uuid", "resourceId=42", "from=yesterday")) {
            assertThat(status(get(LISTING + "?" + query).session(admin)))
                    .as("?%s", query)
                    .isEqualTo(400);
        }
    }

    /** The listing is an Admin's: the filter chain refuses anyone else before the controller. */
    @Test
    void onlyAnAdminMayReadTheTrail() throws Exception {
        assertThat(status(get(LISTING))).as("anonymous").isEqualTo(401);
        assertThat(status(get(LISTING).session(logInSession(mvc, "test-user", "test-password"))))
                .as("an ordinary User")
                .isEqualTo(403);
    }

    // ---- fixture -------------------------------------------------------------------------

    /**
     * 23 events one second apart, except the last two, which share an instant so the id tiebreak is
     * exercised. Operations alternate between two, outcomes follow them, and every third event is
     * about {@link #resource}.
     */
    private void seedFixture() {
        for (int i = 0; i < FIXTURE_SIZE; i++) {
            UUID id = UUID.randomUUID();
            Instant at = base.plusSeconds(Math.min(i, FIXTURE_SIZE - 2));
            boolean failure = i % 2 == 0;
            String operation = failure ? "LOGIN_FAILURE" : "LOGIN_SUCCESS";
            String outcome = failure ? "FAILURE" : "SUCCESS";
            UUID resourceId = i % 3 == 0 ? resource : UUID.randomUUID();
            jdbc.update(INSERT, id, at.atOffset(ZoneOffset.UTC), operation, outcome, actor,
                    resourceId, "User", resourceId, null, failure ? "client_error" : "ok",
                    failure ? "BAD_CREDENTIALS" : null, "POST", "/api/auth/login",
                    "fixture-" + i, null, null);
            seeded.add(new Seeded(id, at, operation, outcome, resourceId));
        }
    }

    /**
     * Newest first; among equal instants, the greater id first — in SQL Server's own
     * {@code uniqueidentifier} order (ADR 0013, "Observable changes").
     */
    private static List<String> expectedOrder(List<Seeded> events) {
        return events.stream()
                .sorted(Comparator.comparing(Seeded::occurredAt)
                        .thenComparing(Seeded::id, AuditListingIntegrationTests::sqlServerUuidOrder)
                        .reversed())
                .map(event -> event.id().toString())
                .toList();
    }

    /**
     * The bytes of an id as its text writes them, in the order SQL Server compares a
     * {@code uniqueidentifier}: the last group first, then the fourth, then the first three
     * groups each from its least significant byte — not the text's lexical order, nor
     * {@link UUID#compareTo}'s signed longs.
     */
    private static final int[] SQL_SERVER_BYTE_ORDER =
            {10, 11, 12, 13, 14, 15, 8, 9, 7, 6, 5, 4, 3, 2, 1, 0};

    private static int sqlServerUuidOrder(UUID left, UUID right) {
        byte[] leftBytes = bytes(left);
        byte[] rightBytes = bytes(right);
        for (int index : SQL_SERVER_BYTE_ORDER) {
            int compared = Integer.compare(leftBytes[index] & 0xff, rightBytes[index] & 0xff);
            if (compared != 0) {
                return compared;
            }
        }
        return 0;
    }

    private static byte[] bytes(UUID id) {
        return java.nio.ByteBuffer.allocate(16)
                .putLong(id.getMostSignificantBits())
                .putLong(id.getLeastSignificantBits())
                .array();
    }

    private static List<String> ids(JsonNode body) {
        return body.get("events").valueStream().map(event -> event.get("id").asText()).toList();
    }

    /**
     * The server refusing the app login a privilege it needs (error 229) is a fault on this side,
     * answered as the generic {@code 500} with nothing of the driver's text — not the object, the
     * schema, the statement or the error number — and recorded as the one {@code ERROR} whose
     * category says to check configuration (ADR 0013's as-13 and its mapping). The refusal is
     * made real by denying the app login's role its read of the trail, and the grant is given back
     * however the test ends.
     */
    @Test
    void aPermissionTheDatabaseDeniesIsTheGeneric500QuotingNothing() throws Exception {
        JdbcTemplate owner = logins.owner().jdbc();
        MvcResult result;
        try (EcsLogCapture logs = EcsLogCapture.attach(environment)) {
            owner.execute("DENY SELECT ON audit_events TO backend_app");
            try {
                result = mvc.perform(get(LISTING + "?actorId=" + actor).session(admin)).andReturn();
            } finally {
                owner.execute("GRANT SELECT ON audit_events TO backend_app");
            }

            assertThat(logs.records().stream()
                            .filter(record -> "ERROR".equals(record.at("/log/level").asText()))
                            .toList())
                    .singleElement()
                    .satisfies(error -> {
                        assertThat(error.at("/error/code").asInt()).isEqualTo(500);
                        assertThat(error.at("/error/category").asText()).isEqualTo("cert/auth");
                    });
            assertThat(logs.lines()).as("the stream")
                    .doesNotContain("permission was denied", "audit_events");
        }

        assertThat(result.getResponse().getStatus()).isEqualTo(500);
        assertThat(result.getResponse().getContentAsString())
                .doesNotContain("permission", "denied", "audit_events", "SELECT", "229",
                        "SQLServerException");
    }

    // ---- HTTP ----------------------------------------------------------------------------

    private JsonNode list(String query) throws Exception {
        MvcResult result = mvc.perform(get(LISTING + "?" + query).session(admin)).andReturn();
        assertThat(result.getResponse().getStatus()).as("?%s", query).isEqualTo(200);
        return JSON.readTree(result.getResponse().getContentAsString());
    }

    private int status(MockHttpServletRequestBuilder request) throws Exception {
        return mvc.perform(request).andReturn().getResponse().getStatus();
    }
}

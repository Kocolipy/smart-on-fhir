package com.example.backend.auth.epic;

import static com.example.backend.auth.epic.EpicPractitioners.unprovisioned;
import static com.example.backend.auth.epic.EpicTestEnvironment.FHIR_BASE;
import static com.example.backend.auth.epic.EpicTestEnvironment.sessionId;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.example.backend.ContainerTestConfiguration;
import com.example.backend.SessionCsrf;
import com.example.backend.auth.application.SessionRevocationService;
import com.example.backend.auth.domain.EpicTokenSet;
import com.example.backend.auth.domain.EpicTokens;
import com.example.backend.auth.domain.FhircastHub;
import com.example.backend.auth.domain.SessionRevocationCause;
import com.example.backend.auth.epic.EpicBrowser.Landing;
import com.example.backend.contract.OpenApiContract;
import jakarta.servlet.http.Cookie;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.connection.DataType;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.session.FindByIndexNameSessionRepository;
import org.springframework.session.Session;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.convention.TestBean;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The Epic tokens a successful Epic Login keeps (ADR 0012, D29), end to end: the
 * access token, its expiry, its scope, the refresh token when Epic issues one and the
 * {@code id_token}, kept server-side for the signed-in session alone and retrieved through the
 * {@link EpicTokens} port — and gone with the session however it ends.
 *
 * <p>Against the real filter chain and the real, indexed Redis session store, as
 * {@code backend/AGENTS.md} requires of a change to what the session store holds: each ending
 * of a session is driven the way it happens — logout, a revocation, the idle bound, the absolute
 * lifetime, the next launch — and the tokens are then asked for through the port.
 *
 * <p>The application's clock is one this test can move forward, so the access token's expiry and
 * the absolute session lifetime are reached without waiting. It runs on the system clock until a
 * test moves it, which it does only after the Login: Epic's {@code id_token} is checked against
 * it.
 */
@SpringBootTest
@ActiveProfiles("dev")
@Import(ContainerTestConfiguration.class)
class EpicTokensIntegrationTests {

    @RegisterExtension
    static final EpicTestEnvironment EPIC = EpicTestEnvironment.epicLoginOn();

    @DynamicPropertySource
    static void epicLoginOn(DynamicPropertyRegistry registry) {
        EPIC.register(registry);
    }

    /** The application's notion of now: the system clock, moved forward by what a test asks. */
    private static final OffsetSystemClock CLOCK = new OffsetSystemClock();

    @TestBean
    private Clock clock;

    static Clock clock() {
        return CLOCK;
    }

    /** No wait before a D26 JWKS refetch: this class is not about them. */
    @TestBean(methodName = EpicTestFixtures.NO_RETRY_PAUSE)
    private EpicRetryPause epicRetryPause;

    @Autowired
    private EpicTokens epicTokens;

    @Autowired
    private SessionRevocationService revocation;

    @Autowired
    private PlatformTransactionManager transactions;

    @Autowired
    private FindByIndexNameSessionRepository<? extends Session> sessionRepository;

    @Autowired
    private RedisConnectionFactory redis;

    @Autowired
    private JdbcTemplate jdbc;

    /** The FHIRcast release: the SMART access token and the hub, for the SPA (ADR 0016). */
    private static final String FHIRCAST_RELEASE = "/api/auth/epic/fhircast";

    @Value("${server.servlet.session.cookie.name:SESSION}")
    private String sessionCookieName;

    @BeforeEach
    void setUp() {
        CLOCK.reset();
    }

    // ---- what is kept, and for which session --------------------------------------------------

    /**
     * The signed-in session keeps what Epic issued: the access token, the {@code id_token}, the
     * granted scope — and no refresh token, since Epic issues none for identity and the FHIRcast
     * scopes, which is what we ask for today.
     */
    @Test
    void theTokensEpicIssuedAreKeptForTheSignedInSession() throws Exception {
        Cookie signedIn = signInFromEpic();

        EpicTokenSet kept = tokensOf(signedIn).orElseThrow();

        assertThat(kept.accessToken()).isEqualTo(EPIC.fake().accessToken);
        assertThat(kept.idToken()).isEqualTo(EPIC.fake().idTokens.getFirst());
        assertThat(kept.scope()).isEqualTo(Set.of("launch", "openid", "fhirUser",
                "fhircast/Patient-open.read", "fhircast/Patient-close.read"));
        assertThat(kept.refreshToken()).isEmpty();
    }

    /**
     * Epic's {@code expires_in} of 3600, made absolute from the application's clock rather than
     * the system's: the clock runs 45 seconds ahead, which the expiry carries. No further ahead,
     * because our client assertion is signed by the same clock and Epic holds its {@code exp} to
     * five minutes of its own time.
     */
    @Test
    void theAccessTokenExpiresAnHourAfterTheLoginByTheApplicationsClock() throws Exception {
        CLOCK.advanceBy(Duration.ofSeconds(45));
        Instant before = CLOCK.instant();

        Cookie signedIn = signInFromEpic();

        assertThat(tokensOf(signedIn).orElseThrow().accessTokenExpiresAt())
                .isBetween(before.plusSeconds(3600), CLOCK.instant().plusSeconds(3600));
    }

    @Test
    void theRefreshTokenIsKeptWhenEpicIssuesOne() throws Exception {
        EPIC.fake().issuingRefreshTokens();

        Cookie signedIn = signInFromEpic();

        assertThat(tokensOf(signedIn).flatMap(EpicTokenSet::refreshToken))
                .contains(EPIC.fake().refreshToken);
    }

    /** Kept only under the rotated id: the launch's own session id holds nothing. */
    @Test
    void nothingIsKeptForThePreLoginSessionId() throws Exception {
        Landing landing = EPIC.signInFromEpic(EPIC.practitioners().provision(), null);

        assertThat(tokensOf(landing.launched())).isEmpty();
    }

    @Test
    void aPasswordLoginKeepsNoEpicTokens() throws Exception {
        Cookie byPassword = logIn(EPIC.practitioners().provision());

        assertThat(tokensOf(byPassword)).isEmpty();
    }

    @Test
    void anUnknownSessionHasNoEpicTokens() {
        assertThat(epicTokens.forSession("no-such-session")).isEmpty();
    }

    @Test
    void noSessionIdHasNoEpicTokensAndNoHub() {
        assertThat(List.of(epicTokens.forSession(null), epicTokens.fhircastHubForSession(null)))
                .containsExactly(Optional.empty(), Optional.empty());
    }

    /** A refused launch keeps nothing anywhere: not under its session, not under any key. */
    @Test
    void aRefusedLaunchKeepsNoTokenAnywhereInTheStore() throws Exception {
        EPIC.signInFromEpic(unprovisioned(), null);

        String everythingInRedis = everythingInRedis();
        FakeEpic epic = EPIC.fake();
        assertThat(List.of(epic.accessToken, epic.idTokens.getFirst()))
                .allSatisfy(token -> assertThat(everythingInRedis).doesNotContain(token));
    }

    // ---- the FHIRcast hub (ADR 0016) ----------------------------------------------------------

    /** Epic's local hub on loopback and a topic within the grammar are kept beside the tokens. */
    @Test
    void aLoopbackHubAndAValidTopicAreKeptForTheSignedInSession() throws Exception {
        Cookie signedIn = signInFromEpic();

        assertThat(hubOf(signedIn).map(hub -> List.of(hub.url(), hub.topic())))
                .contains(List.of("http://127.0.0.1:8103/fhircast/STU3", EPIC.fake().hubTopic));
    }

    /** Loopback on any port, over https as over http. */
    @Test
    void anHttpsLoopbackHubOnAnyPortIsKept() throws Exception {
        EPIC.fake().hub("https://127.0.0.1:61443/hub", "Topic.with-every_char~1");

        Cookie signedIn = signInFromEpic();

        assertThat(hubOf(signedIn).map(hub -> List.of(hub.url(), hub.topic())))
                .contains(List.of("https://127.0.0.1:61443/hub", "Topic.with-every_char~1"));
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "http://192.168.1.20:8103/fhircast/STU3",
        "http://localhost:8103/fhircast/STU3",
        "http://127.0.0.2:8103/fhircast/STU3",
        "http://evil@127.0.0.1:8103/fhircast/STU3",
        "ws://127.0.0.1:8103/fhircast/STU3",
        "not a url"})
    void aHubUrlOffLoopbackIsDropped(String url) throws Exception {
        EPIC.fake().hub(url, EPIC.fake().hubTopic);

        Cookie signedIn = signInFromEpic();

        assertThat(hubOf(signedIn)).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "has space", "slash/inside", "percent%20", "ünicode"})
    void aMalformedTopicIsDropped(String topic) throws Exception {
        EPIC.fake().hub(EPIC.fake().hubUrl, topic);

        Cookie signedIn = signInFromEpic();

        assertThat(hubOf(signedIn)).isEmpty();
    }

    @Test
    void aTopicLongerThan128CharactersIsDropped() throws Exception {
        EPIC.fake().hub(EPIC.fake().hubUrl, "t".repeat(129));

        Cookie signedIn = signInFromEpic();

        assertThat(hubOf(signedIn)).isEmpty();
    }

    @Test
    void aTokenResponseNamingNoHubKeepsNone() throws Exception {
        EPIC.fake().hub(null, null);

        Cookie signedIn = signInFromEpic();

        assertThat(hubOf(signedIn)).isEmpty();
    }

    /** Dropping the hub drops nothing else: the tokens and the landing's launch context stay. */
    @Test
    void aDroppedHubLeavesTheTokensAndTheLaunchContext() throws Exception {
        EPIC.fake().hub("http://192.168.1.20:8103/fhircast/STU3", EPIC.fake().hubTopic);

        Cookie signedIn = signInFromEpic();

        assertThat(tokensOf(signedIn)).isPresent();
    }

    @Test
    void aPasswordLoginKeepsNoHub() throws Exception {
        Cookie byPassword = logIn(EPIC.practitioners().provision());

        assertThat(hubOf(byPassword)).isEmpty();
    }

    // ---- the FHIRcast release (ADR 0016) ------------------------------------------------------

    /** The access token, its expiry, its scope and the hub: what the SPA connects to the hub with. */
    @Test
    void theReleaseAnswersTheAccessTokenItsExpiryItsScopeAndTheHub() throws Exception {
        Cookie signedIn = signInFromEpic();
        Instant expiresAt = tokensOf(signedIn).orElseThrow().accessTokenExpiresAt();

        JsonNode released = json(release(signedIn));

        assertThat(List.of(released.get("accessToken").asString(),
                        Instant.parse(released.get("expiresAt").asString()),
                        released.get("hubUrl").asString(), released.get("hubTopic").asString()))
                .containsExactly(EPIC.fake().accessToken, expiresAt,
                        "http://127.0.0.1:8103/fhircast/STU3", EPIC.fake().hubTopic);
    }

    /** The granted scope, sorted, as the API document says. */
    @Test
    void theReleaseAnswersTheGrantedScopeSorted() throws Exception {
        Cookie signedIn = signInFromEpic();

        JsonNode scope = json(release(signedIn)).get("scope");

        assertThat(scope.valueStream().map(JsonNode::asString).toList())
                .containsExactly("fhirUser", "fhircast/Patient-close.read",
                        "fhircast/Patient-open.read", "launch", "openid");
    }

    @Test
    void theReleaseNamesExactlyItsFiveMembers() throws Exception {
        Cookie signedIn = signInFromEpic();

        assertThat(json(release(signedIn)).propertyNames())
                .containsExactlyInAnyOrder("accessToken", "expiresAt", "scope", "hubUrl",
                        "hubTopic");
    }

    /** The 200 the contract fixtures cannot produce, held to the API document here. */
    @Test
    void theReleaseConformsToTheApiDocument() throws Exception {
        Cookie signedIn = signInFromEpic();

        assertThat(OpenApiContract.load()
                .check("GET", FHIRCAST_RELEASE, release(signedIn).getResponse()).violations())
                .isEmpty();
    }

    @Test
    void theReleaseIsNeverStored() throws Exception {
        Cookie signedIn = signInFromEpic();

        assertThat(release(signedIn).getResponse().getHeader("Cache-Control"))
                .isEqualTo("no-store");
    }

    /** The access token alone: the id_token and the refresh token never leave the backend. */
    @Test
    void theReleaseCarriesNeitherTheIdTokenNorTheRefreshToken() throws Exception {
        EPIC.fake().issuingRefreshTokens();
        Cookie signedIn = signInFromEpic();

        String answer = release(signedIn).getResponse().getContentAsString();

        assertThat(List.of(EPIC.fake().idTokens.getFirst(), EPIC.fake().refreshToken))
                .noneSatisfy(token -> assertThat(answer).contains(token));
    }

    @Test
    void aPasswordSessionIsAnsweredNotFound() throws Exception {
        Cookie byPassword = logIn(EPIC.practitioners().provision());

        assertThat(release(byPassword).getResponse().getStatus()).isEqualTo(404);
    }

    @Test
    void aSessionThatKeptNoHubIsAnsweredNotFound() throws Exception {
        EPIC.fake().hub("http://192.168.1.20:8103/fhircast/STU3", EPIC.fake().hubTopic);
        Cookie signedIn = signInFromEpic();

        assertThat(release(signedIn).getResponse().getStatus()).isEqualTo(404);
    }

    @Test
    void anExpiredAccessTokenIsAnsweredNotFound() throws Exception {
        Cookie signedIn = signInFromEpic();
        CLOCK.advanceBy(Duration.ofHours(2));

        assertThat(release(signedIn).getResponse().getStatus()).isEqualTo(404);
    }

    @Test
    void aRequestWithNoSessionIsAnsweredUnauthorized() throws Exception {
        int status = EPIC.mvc().perform(get(FHIRCAST_RELEASE)).andReturn().getResponse()
                .getStatus();

        assertThat(status).isEqualTo(401);
    }

    @Test
    void aReleaseIsAuditedAsOneSuccess() throws Exception {
        String practitioner = EPIC.practitioners().provision();
        Cookie signedIn = EPIC.signInFromEpic(practitioner, null).signedIn();

        release(signedIn);

        assertThat(releasesRecordedFor(practitioner)).containsExactly("SUCCESS/");
    }

    @Test
    void aPasswordSessionsRefusalIsAuditedAsOneFailure() throws Exception {
        String practitioner = EPIC.practitioners().provision();
        Cookie byPassword = logIn(practitioner);

        release(byPassword);

        assertThat(releasesRecordedFor(practitioner)).containsExactly("FAILURE/NO_EPIC_TOKENS");
    }

    @Test
    void aSessionWithNoHubsRefusalIsAuditedAsOneFailure() throws Exception {
        EPIC.fake().hub(null, null);
        String practitioner = EPIC.practitioners().provision();
        Cookie signedIn = EPIC.signInFromEpic(practitioner, null).signedIn();

        release(signedIn);

        assertThat(releasesRecordedFor(practitioner)).containsExactly("FAILURE/NO_FHIRCAST_HUB");
    }

    @Test
    void anExpiredTokensRefusalIsAuditedAsOneFailure() throws Exception {
        String practitioner = EPIC.practitioners().provision();
        Cookie signedIn = EPIC.signInFromEpic(practitioner, null).signedIn();
        CLOCK.advanceBy(Duration.ofHours(2));

        release(signedIn);

        assertThat(releasesRecordedFor(practitioner)).containsExactly("FAILURE/TOKEN_EXPIRED");
    }

    /** D22: whatever the release answers, the audit trail holds none of Epic's tokens. */
    @Test
    void theAuditTrailHoldsNoTokenValue() throws Exception {
        EPIC.fake().issuingRefreshTokens();
        Cookie signedIn = signInFromEpic();

        release(signedIn);

        String trail = jdbc.queryForList("SELECT * FROM audit_events").toString();
        FakeEpic epic = EPIC.fake();
        assertThat(List.of(epic.accessToken, epic.refreshToken, epic.idTokens.getFirst()))
                .noneSatisfy(token -> assertThat(trail).contains(token));
    }

    // ---- the tokens never leave the backend -----------------------------------------------------

    @Test
    void theCallbacksAnswerCarriesNoToken() throws Exception {
        EPIC.fake().issuingRefreshTokens();

        Landing landing = EPIC.signInFromEpic(EPIC.practitioners().provision(), null);

        assertCarriesNoToken(landing.callback());
    }

    @Test
    void meCarriesNoToken() throws Exception {
        EPIC.fake().issuingRefreshTokens();
        Cookie signedIn = signInFromEpic();

        MvcResult me = EPIC.mvc().perform(get("/api/auth/me").cookie(signedIn)).andReturn();

        assertCarriesNoToken(me);
    }

    // ---- the tokens go with the session -------------------------------------------------------

    @Test
    void loggingOutLeavesNoTokens() throws Exception {
        Cookie signedIn = signInFromEpic();
        assertThat(tokensOf(signedIn)).as("kept until the session ends").isPresent();

        int status = EPIC.mvc().perform(SessionCsrf.withCsrf(EPIC.mvc(),
                delete("/api/auth/logout").cookie(signedIn))).andReturn().getResponse().getStatus();

        assertThat(status).as("the logout was accepted").isLessThan(300);
        assertThat(tokensOf(signedIn)).isEmpty();
    }

    /**
     * A session revocation — here the one a connector's deactivation imposes, through the module
     * every trigger goes through — ends the session and its tokens.
     */
    @Test
    void aSessionRevocationLeavesNoTokens() throws Exception {
        String practitioner = EPIC.practitioners().provision();
        Cookie signedIn = EPIC.signInFromEpic(practitioner, null).signedIn();
        assertThat(tokensOf(signedIn)).as("kept until the session ends").isPresent();

        new TransactionTemplate(transactions).executeWithoutResult(status ->
                revocation.revokeAllAfterCommit(EPIC.practitioners().idOf(practitioner),
                        SessionRevocationCause.DEACTIVATED, null));

        assertThat(tokensOf(signedIn)).isEmpty();
    }

    /** One session per User: the same User's next Login ends the Epic session and its tokens. */
    @Test
    void theUsersNextLoginLeavesTheEarlierSessionNoTokens() throws Exception {
        String practitioner = EPIC.practitioners().provision();
        Cookie byEpic = EPIC.signInFromEpic(practitioner, null).signedIn();
        assertThat(tokensOf(byEpic)).as("kept until the session ends").isPresent();

        logIn(practitioner);

        assertThat(tokensOf(byEpic)).isEmpty();
    }

    /** The idle bound, as the store measures it: last accessed longer ago than it allows. */
    @Test
    void anIdleSessionLeavesNoTokens() throws Exception {
        Cookie signedIn = signInFromEpic();
        assertThat(tokensOf(signedIn)).as("kept until the session ends").isPresent();

        idleFor(signedIn, Duration.ofHours(1));

        assertThat(tokensOf(signedIn)).isEmpty();
    }

    /** Past the absolute session lifetime the tokens are gone, before any request reaps it. */
    @Test
    void aSessionPastItsAbsoluteLifetimeLeavesNoTokens() throws Exception {
        Cookie signedIn = signInFromEpic();
        assertThat(tokensOf(signedIn)).as("kept until the session ends").isPresent();

        CLOCK.advanceBy(Duration.ofHours(8).plusMinutes(1));

        assertThat(tokensOf(signedIn)).isEmpty();
    }

    /** D9: the next launch in that browser ends the session it held, and its tokens with it. */
    @Test
    void theNextLaunchInThatBrowserLeavesTheEarlierSessionNoTokens() throws Exception {
        Cookie signedIn = signInFromEpic();
        assertThat(tokensOf(signedIn)).as("kept until the session ends").isPresent();

        EPIC.browser().open(FHIR_BASE, EpicBrowser.LAUNCH, signedIn);

        assertThat(tokensOf(signedIn)).isEmpty();
    }

    /**
     * Far from the 8-hour lifetime's end, keeping the tokens leaves the session the idle bound
     * every session has: Spring Boot's default 30 minutes, which the test configuration keeps. A
     * request does not change it either. Nearer the end, see
     * {@code EpicTokensStorageLifetimeIntegrationTests}.
     */
    @Test
    void farFromTheAbsoluteLifetimesEndTheSessionKeepsItsIdleBound() throws Exception {
        Cookie signedIn = signInFromEpic();
        CLOCK.advanceBy(Duration.ofHours(1));

        int me = EPIC.mvc().perform(get("/api/auth/me").cookie(signedIn)).andReturn()
                .getResponse().getStatus();

        assertThat(me).as("still signed in").isEqualTo(200);
        assertThat(sessionRepository.findById(sessionId(signedIn)).getMaxInactiveInterval())
                .isEqualTo(Duration.ofMinutes(30));
    }

    // ---- an expired access token --------------------------------------------------------------

    @Test
    void theAccessTokenReportsExpiredOnceTheApplicationsClockPassesItsExpiry() throws Exception {
        Cookie signedIn = signInFromEpic();

        CLOCK.advanceBy(Duration.ofHours(2));

        assertThat(tokensOf(signedIn).orElseThrow().isExpired(clock)).isTrue();
    }

    @Test
    void theAccessTokenIsNotExpiredRightAfterTheLogin() throws Exception {
        Cookie signedIn = signInFromEpic();

        assertThat(tokensOf(signedIn).orElseThrow().isExpired(clock)).isFalse();
    }

    /** The session outlives the access token, and the rest of what was kept stays with it. */
    @Test
    void theRefreshTokenAndIdTokenOutliveTheAccessToken() throws Exception {
        EPIC.fake().issuingRefreshTokens();
        Cookie signedIn = signInFromEpic();

        CLOCK.advanceBy(Duration.ofHours(2));

        EpicTokenSet kept = tokensOf(signedIn).orElseThrow();
        assertThat(List.of(kept.refreshToken().orElseThrow(), kept.idToken()))
                .containsExactly(EPIC.fake().refreshToken, EPIC.fake().idTokens.getFirst());
    }

    // ---- helpers ------------------------------------------------------------------------------

    /** A freshly provisioned clinician's whole Epic Login; the signed-in session cookie. */
    private Cookie signInFromEpic() throws Exception {
        Landing landing = EPIC.signInFromEpic(EPIC.practitioners().provision(), null);
        assertThat(landing.callback().getResponse().getRedirectedUrl()).as("signed in")
                .isEqualTo(EPIC.fake().defaultHome());
        return landing.signedIn();
    }

    private Optional<EpicTokenSet> tokensOf(Cookie session) {
        return epicTokens.forSession(sessionId(session));
    }

    private Optional<FhircastHub> hubOf(Cookie session) {
        return epicTokens.fhircastHubForSession(sessionId(session));
    }

    /** The FHIRcast release, asked for by {@code session}. */
    private static MvcResult release(Cookie session) throws Exception {
        return EPIC.mvc().perform(get(FHIRCAST_RELEASE).cookie(session)).andReturn();
    }

    private static JsonNode json(MvcResult result) throws Exception {
        assertThat(result.getResponse().getStatus()).as("released").isEqualTo(200);
        return JsonMapper.builder().build().readTree(result.getResponse().getContentAsString());
    }

    /** Every FHIRcast release recorded against {@code practitioner}'s User, as outcome/reason. */
    private List<String> releasesRecordedFor(String practitioner) {
        return jdbc.queryForList(
                "SELECT outcome + '/' + COALESCE(error_code, '') FROM audit_events"
                        + " WHERE operation = 'FHIRCAST_TOKEN_RELEASE' AND subject_id = ?"
                        + " AND actor_id = subject_id",
                String.class, EPIC.practitioners().idOf(practitioner));
    }

    /** A password Login; the signed-in session cookie. */
    private Cookie logIn(String userName) throws Exception {
        MvcResult login = EPIC.mvc().perform(SessionCsrf.withCsrf(EPIC.mvc(),
                        post("/api/auth/login"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"%s\",\"password\":\"%s\"}"
                                .formatted(userName, EpicPractitioners.PASSWORD)))
                .andReturn();
        assertThat(login.getResponse().getStatus()).as("signed in by password").isEqualTo(200);
        Cookie issued = login.getResponse().getCookie(sessionCookieName);
        assertThat(issued).as("a session cookie was issued").isNotNull();
        return new Cookie(issued.getName(), issued.getValue());
    }

    /** Moves the session's last access back by {@code idle}, as if nothing had used it since. */
    @SuppressWarnings("unchecked")
    private void idleFor(Cookie session, Duration idle) {
        FindByIndexNameSessionRepository<Session> sessions =
                (FindByIndexNameSessionRepository<Session>) sessionRepository;
        Session stored = sessions.findById(sessionId(session));
        stored.setLastAccessedTime(Instant.now().minus(idle));
        sessions.save(stored);
    }

    /** The answer's status line, headers and body carry none of Epic's tokens. */
    private static void assertCarriesNoToken(MvcResult result) throws Exception {
        String answer = result.getResponse().getStatus() + "\n"
                + result.getResponse().getHeaderNames().stream()
                        .map(name -> name + ": " + result.getResponse().getHeaders(name))
                        .collect(Collectors.joining("\n"))
                + "\n" + result.getResponse().getContentAsString();
        FakeEpic epic = EPIC.fake();
        assertThat(List.of(epic.accessToken, epic.refreshToken, epic.idTokens.getFirst()))
                .allSatisfy(token -> assertThat(answer).doesNotContain(token));
    }

    /** Every key and value in the session store, as text: what the store would hand an attacker. */
    private String everythingInRedis() {
        StringBuilder all = new StringBuilder();
        try (RedisConnection connection = redis.getConnection();
                Cursor<byte[]> keys = connection.keyCommands().scan(ScanOptions.NONE)) {
            while (keys.hasNext()) {
                byte[] key = keys.next();
                all.append(new String(key, StandardCharsets.ISO_8859_1)).append('\n');
                DataType type = connection.keyCommands().type(key);
                if (type == DataType.HASH) {
                    connection.hashCommands().hGetAll(key).forEach((field, value) -> all
                            .append(new String(field, StandardCharsets.ISO_8859_1)).append('=')
                            .append(new String(value, StandardCharsets.ISO_8859_1)).append('\n'));
                } else if (type == DataType.STRING) {
                    all.append(new String(connection.stringCommands().get(key),
                            StandardCharsets.ISO_8859_1)).append('\n');
                } else if (type == DataType.SET) {
                    connection.setCommands().sMembers(key).forEach(member -> all
                            .append(new String(member, StandardCharsets.ISO_8859_1)).append('\n'));
                }
            }
        }
        return all.toString();
    }
}

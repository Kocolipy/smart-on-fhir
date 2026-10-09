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
import com.example.backend.auth.application.IdentityAdministrationService;
import com.example.backend.auth.domain.EpicTokenSet;
import com.example.backend.auth.domain.EpicTokens;
import com.example.backend.auth.epic.EpicBrowser.Landing;
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
import org.springframework.session.FindByIndexNameSessionRepository;
import org.springframework.session.Session;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.convention.TestBean;
import org.springframework.test.web.servlet.MvcResult;

/**
 * The Epic tokens a successful Epic Login keeps (ADR 0013, addendum 2026-10-09), end to end: the
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

    /** The test profile's Bootstrap Admin, acting as the administrator of a forced change. */
    private static final String BOOTSTRAP_ADMIN = "test-admin";

    @Autowired
    private EpicTokens epicTokens;

    @Autowired
    private IdentityAdministrationService administration;

    @Autowired
    private FindByIndexNameSessionRepository<? extends Session> sessionRepository;

    @Autowired
    private RedisConnectionFactory redis;

    @Value("${server.servlet.session.cookie.name:SESSION}")
    private String sessionCookieName;

    @BeforeEach
    void setUp() {
        CLOCK.reset();
    }

    // ---- what is kept, and for which session --------------------------------------------------

    @Test
    void theAccessTokenIsKeptForTheSignedInSession() throws Exception {
        Cookie signedIn = signInFromEpic();

        assertThat(tokensOf(signedIn).map(EpicTokenSet::accessToken))
                .contains(EPIC.fake().accessToken);
    }

    @Test
    void theIdTokenIsKeptForTheSignedInSession() throws Exception {
        Cookie signedIn = signInFromEpic();

        assertThat(tokensOf(signedIn).map(EpicTokenSet::idToken))
                .contains(EPIC.fake().idTokens.getFirst());
    }

    @Test
    void theGrantedScopeIsKeptForTheSignedInSession() throws Exception {
        Cookie signedIn = signInFromEpic();

        assertThat(tokensOf(signedIn).map(EpicTokenSet::scope))
                .contains(Set.of("launch", "openid", "fhirUser"));
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

    /** Epic issues none for {@code launch openid fhirUser}, which is what we ask for today. */
    @Test
    void theTokensAreKeptWithNoRefreshTokenWhenEpicIssuesNone() throws Exception {
        Cookie signedIn = signInFromEpic();

        assertThat(tokensOf(signedIn).orElseThrow().refreshToken()).isEmpty();
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

    /** A refused launch keeps nothing anywhere: not under its session, not under any key. */
    @Test
    void aRefusedLaunchKeepsNoTokenAnywhereInTheStore() throws Exception {
        EPIC.signInFromEpic(unprovisioned(), null);

        String everythingInRedis = everythingInRedis();
        FakeEpic epic = EPIC.fake();
        assertThat(List.of(epic.accessToken, epic.idTokens.getFirst()))
                .allSatisfy(token -> assertThat(everythingInRedis).doesNotContain(token));
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

    /** A session revocation — here a forced password change — ends the session and its tokens. */
    @Test
    void aSessionRevocationLeavesNoTokens() throws Exception {
        String practitioner = EPIC.practitioners().provision();
        Cookie signedIn = EPIC.signInFromEpic(practitioner, null).signedIn();
        assertThat(tokensOf(signedIn)).as("kept until the session ends").isPresent();

        administration.forcePasswordChange(EPIC.practitioners().idOf(practitioner), BOOTSTRAP_ADMIN);

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
                .isEqualTo("/");
        return landing.signedIn();
    }

    private Optional<EpicTokenSet> tokensOf(Cookie session) {
        return epicTokens.forSession(sessionId(session));
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

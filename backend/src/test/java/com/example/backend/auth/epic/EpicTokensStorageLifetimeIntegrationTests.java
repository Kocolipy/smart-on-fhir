package com.example.backend.auth.epic;

import static com.example.backend.auth.epic.EpicTestEnvironment.sessionId;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.example.backend.ContainerTestConfiguration;
import com.example.backend.auth.epic.EpicBrowser.Landing;
import jakarta.servlet.http.Cookie;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.session.FindByIndexNameSessionRepository;
import org.springframework.session.Session;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.convention.TestBean;

/**
 * How long the session store keeps Epic's tokens: no longer than the session's remaining absolute
 * lifetime (ADR 0013, D29), against the real, indexed Redis session store.
 *
 * <p>The store expires a session its idle bound after its last request, and every request renews
 * that, so near the lifetime's end the idle bound alone would keep the session — and the tokens
 * on it — stored past the end. Here the absolute lifetime is 10 minutes, shorter than the
 * 30-minute idle bound the test configuration leaves every session, so every Login in this class
 * is one made near its lifetime's end: the bound is cut at the Login, when the tokens are
 * written, and again on each request after it.
 *
 * <p>The application's clock runs on the system's time until a test moves it, which it does only
 * after the Login: Epic's {@code id_token} is checked against it.
 */
@SpringBootTest(properties = "app.session.absolute-lifetime=10m")
@ActiveProfiles("dev")
@Import(ContainerTestConfiguration.class)
class EpicTokensStorageLifetimeIntegrationTests {

    @RegisterExtension
    static final EpicTestEnvironment EPIC = EpicTestEnvironment.epicLoginOn();

    @DynamicPropertySource
    static void epicLoginOn(DynamicPropertyRegistry registry) {
        EPIC.register(registry);
    }

    private static final OffsetSystemClock CLOCK = new OffsetSystemClock();

    @TestBean
    private Clock clock;

    static Clock clock() {
        return CLOCK;
    }

    /** No wait before a D26 JWKS refetch: this class is not about them. */
    @TestBean(methodName = EpicTestFixtures.NO_RETRY_PAUSE)
    private EpicRetryPause epicRetryPause;

    /**
     * Spring Session's indexed repository keeps an expired session's key this long past its
     * expiry, so the expiry can still be read back when Redis reports it; the repository answers
     * nothing for the session throughout.
     */
    private static final Duration SPRING_SESSION_GRACE = Duration.ofMinutes(5);

    @Autowired
    private FindByIndexNameSessionRepository<? extends Session> sessionRepository;

    @Autowired
    private RedisConnectionFactory redis;

    @BeforeEach
    void setUp() {
        CLOCK.reset();
    }

    /**
     * The Login comes seconds into a 10-minute lifetime, so it leaves the session just under ten
     * minutes, not the 30-minute idle bound.
     */
    @Test
    void theLoginThatKeepsTheTokensBoundsTheSessionByTheLifetimeRemaining() throws Exception {
        Cookie signedIn = signInFromEpic();

        assertThat(idleBoundOf(signedIn))
                .isBetween(Duration.ofMinutes(9), Duration.ofMinutes(10));
    }

    /** Six minutes on, a request may renew the session for the four minutes that remain only. */
    @Test
    void aLaterRequestRenewsTheSessionOnlyUntilTheLifetimesEnd() throws Exception {
        Cookie signedIn = signInFromEpic();
        CLOCK.advanceBy(Duration.ofMinutes(6));

        assertSignedIn(signedIn);

        assertThat(idleBoundOf(signedIn))
                .isBetween(Duration.ofMinutes(3), Duration.ofMinutes(4));
    }

    /**
     * In Redis itself: the session's key, the tokens in it, expires within ten minutes of the
     * Login and Spring Session's grace — fifteen minutes — where the idle bound would have kept
     * it thirty-five.
     */
    @Test
    void redisDropsTheTokensNoLaterThanTheSpringSessionGracePastTheLifetimesEnd()
            throws Exception {
        Cookie signedIn = signInFromEpic();

        assertThat(timeToLiveInRedisOf(signedIn))
                .isBetween(Duration.ofMinutes(14), Duration.ofMinutes(10).plus(SPRING_SESSION_GRACE));
    }

    /** And a request after the Login does not renew the key past that either. */
    @Test
    void aLaterRequestDoesNotKeepTheTokensInRedisPastTheGraceEither() throws Exception {
        Cookie signedIn = signInFromEpic();
        CLOCK.advanceBy(Duration.ofMinutes(6));

        assertSignedIn(signedIn);

        assertThat(timeToLiveInRedisOf(signedIn))
                .isBetween(Duration.ofMinutes(8), Duration.ofMinutes(4).plus(SPRING_SESSION_GRACE));
    }

    // ---- helpers ------------------------------------------------------------------------------

    /** A freshly provisioned clinician's whole Epic Login; the signed-in session cookie. */
    private static Cookie signInFromEpic() throws Exception {
        Landing landing = EPIC.signInFromEpic(EPIC.practitioners().provision(), null);
        assertThat(landing.callback().getResponse().getRedirectedUrl()).as("signed in")
                .isEqualTo("/");
        return landing.signedIn();
    }

    private static void assertSignedIn(Cookie session) throws Exception {
        assertThat(EPIC.mvc().perform(get("/api/auth/me").cookie(session)).andReturn()
                .getResponse().getStatus()).as("still signed in").isEqualTo(200);
    }

    /** The idle bound the store holds the session under, as its repository reads it back. */
    private Duration idleBoundOf(Cookie session) {
        return sessionRepository.findById(sessionId(session)).getMaxInactiveInterval();
    }

    /**
     * How much longer Redis keeps the session's own key — the hash holding every attribute, the
     * tokens among them — as its time to live says.
     */
    private Duration timeToLiveInRedisOf(Cookie session) {
        String suffix = ":sessions:" + sessionId(session);
        List<byte[]> keys = new ArrayList<>();
        try (RedisConnection connection = redis.getConnection()) {
            try (Cursor<byte[]> scan = connection.keyCommands()
                    .scan(ScanOptions.scanOptions().match("*" + suffix).build())) {
                scan.forEachRemaining(keys::add);
            }
            assertThat(keys).as("the session's one key").hasSize(1);
            assertThat(new String(keys.getFirst(), StandardCharsets.UTF_8)).endsWith(suffix);
            return Duration.ofMillis(connection.keyCommands().pTtl(keys.getFirst()));
        }
    }
}

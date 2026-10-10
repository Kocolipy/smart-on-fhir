package com.example.backend.auth.epic;

import static com.example.backend.auth.epic.EpicMeters.series;
import static com.example.backend.auth.epic.EpicPractitioners.unprovisioned;
import static com.example.backend.auth.epic.EpicTestEnvironment.FHIR_BASE;
import static org.assertj.core.api.Assertions.assertThat;

import com.example.backend.ContainerTestConfiguration;
import com.example.backend.auth.epic.EpicMeters.Ending;
import com.example.backend.observability.EcsLogCapture;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.convention.TestBean;

/**
 * ADR 0013's D22, end to end, as an ADR-0003 redaction test: an Epic Login that succeeds, one
 * refused for each reason the audit trail knows, and one for each way Epic can be unavailable
 * past discovery, each searched for every D22 value it handled — the {@code launch}, the
 * {@code state} and {@code nonce}, the authorization {@code code}, the PKCE verifier, the client
 * assertion, the {@code id_token}, the access token, the refresh token, and the private key
 * material of both signing keys — in the whole log stream it produced, the whole audit trail after
 * it, and every answer our callback gave the browser. Epic's three tokens are kept server-side for
 * a signed-in session (ADR 0013, D29), and none of them leaves it this way. The
 * same paths hold the {@code login} counter's Epic series and the {@code epic.outbound} meters to the tags each should
 * move.
 *
 * <p>The log is {@link EcsLogCapture} on the root logger, encoded by the production ECS encoder,
 * as {@code JdbcErrorLogRedactionTests} captures it: a value in a message, a field, a stack trace
 * or the logging context fails the search alike, whichever logger wrote it. The values come from
 * both ends of each Login ({@link D22Values}): what the browser sent and what {@link FakeEpic} was
 * sent and answered with, so each is the one this Login actually carried.
 *
 * <p>Discovery failing needs a context whose discovery has never succeeded, and is
 * {@link EpicDiscoveryIntegrationTests}' to hold to the same two properties.
 */
@SpringBootTest
@ActiveProfiles("dev")
@Import(ContainerTestConfiguration.class)
class EpicLoginRedactionIntegrationTests {

    /** A next key published beside the active one, so its material is searched for too. */
    @RegisterExtension
    static final EpicTestEnvironment EPIC = EpicTestEnvironment.epicLoginOn().withNextKey();

    @DynamicPropertySource
    static void epicLoginOn(DynamicPropertyRegistry registry) {
        EPIC.register(registry);
    }

    @Autowired
    private Environment environment;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private MeterRegistry meters;

    /** No wait before a D26 JWKS refetch: this class is not about them. */
    @TestBean(methodName = EpicTestFixtures.NO_RETRY_PAUSE)
    private EpicRetryPause epicRetryPause;

    private EcsLogCapture logs;

    /** Every answer the browser got from our callback, as text: status, headers and body. */
    private final StringBuilder callbackAnswers = new StringBuilder();

    @BeforeEach
    void setUp() {
        // Epic issues a refresh token on every path here, so it is searched for as a D22 value
        // beside the access token and the id_token (ADR 0013, D29).
        EPIC.fake().issuingRefreshTokens();
        logs = EcsLogCapture.attach(environment);
    }

    @AfterEach
    void tearDown() {
        logs.close();
    }

    // ---- every path -------------------------------------------------------------------------

    /** What the path's Login handled before it ended, as kinds of D22 value. */
    private static final Set<String> KEYS = Set.of("active signing key", "next signing key");

    private static final Set<String> LAUNCHED = with(KEYS, "launch");

    private static final Set<String> AUTHORIZED = with(LAUNCHED, "state", "nonce");

    private static final Set<String> CALLED_BACK = with(AUTHORIZED, "code");

    private static final Set<String> REDEEMED =
            with(CALLED_BACK, "code_verifier", "client_assertion");

    private static final Set<String> TOKENS =
            with(REDEEMED, "id_token", "access_token", "refresh_token");

    /** One way an Epic Login can go, played against this test's browser and fake Epic. */
    @FunctionalInterface
    private interface LoginPath {
        void take(EpicLoginRedactionIntegrationTests test, D22Values seen) throws Exception;
    }

    static Stream<Arguments> everyPath() {
        return Stream.of(
                Arguments.of("a successful Login", TOKENS, Ending.success("token"),
                        (LoginPath) (test, seen) -> test.signIn(EPIC.provisionedFhirUser(), seen)),
                Arguments.of("an iss that is not the FHIR base", LAUNCHED,
                        Ending.refused("ISS_MISMATCH"),
                        (LoginPath) (test, seen) -> EPIC.browser().open(
                                "https://fhir.example.com/api/FHIR/R4", seen.launch(), null)),
                Arguments.of("a launch carrying a space", LAUNCHED,
                        Ending.refused("INVALID_LAUNCH"),
                        (LoginPath) (test, seen) -> EPIC.browser().open(
                                FHIR_BASE, "launch " + seen.launch(), null)),
                Arguments.of("a forged state", CALLED_BACK,
                        Ending.refused("INVALID_STATE"),
                        (LoginPath) (test, seen) -> test.callBack(EPIC.provisionedFhirUser(), seen,
                                callback -> callback.put("state", "forged-" + UUID.randomUUID()))),
                Arguments.of("a code carrying a space", CALLED_BACK,
                        Ending.refused("INVALID_CODE"),
                        (LoginPath) (test, seen) -> test.callBack(EPIC.provisionedFhirUser(), seen,
                                callback -> callback.put("code", "bad " + UUID.randomUUID()))),
                Arguments.of("an OAuth error from Epic", AUTHORIZED,
                        Ending.refused("IDP_ERROR"),
                        (LoginPath) (test, seen) -> {
                            EPIC.fake().answeringAuthorizeWithError("access_denied");
                            test.signIn(EPIC.provisionedFhirUser(), seen);
                        }),
                Arguments.of("Epic refusing our client assertion", REDEEMED,
                        Ending.refusedByCall("TOKEN_EXCHANGE_FAILED", "token", "cert/auth", "token"),
                        (LoginPath) (test, seen) -> {
                            EPIC.fake().rejectingOurAssertion();
                            test.signIn(EPIC.provisionedFhirUser(), seen);
                        }),
                Arguments.of("a forged id_token signature", TOKENS,
                        Ending.refused("INVALID_SIGNATURE", "token"),
                        (LoginPath) (test, seen) -> {
                            EPIC.fake().forgingIdTokenSignatures();
                            test.signIn(EPIC.provisionedFhirUser(), seen);
                        }),
                Arguments.of("an id_token for another nonce", TOKENS,
                        Ending.refused("INVALID_CLAIMS", "token"),
                        (LoginPath) (test, seen) -> {
                            String forged = "forged-nonce-" + UUID.randomUUID();
                            seen.add("nonce", forged);
                            EPIC.fake().mintingIdTokensWith(
                                    claims -> claims.claim("nonce", forged));
                            test.signIn(EPIC.provisionedFhirUser(), seen);
                        }),
                Arguments.of("a fhirUser naming a Patient", TOKENS,
                        Ending.refused("INVALID_FHIR_USER", "token"),
                        (LoginPath) (test, seen) -> test.signIn(
                                FHIR_BASE + "/Patient/" + unprovisioned(),
                                seen)),
                Arguments.of("an unprovisioned Practitioner", TOKENS,
                        Ending.refused("UNKNOWN_ACCOUNT", "token"),
                        (LoginPath) (test, seen) -> test.signIn(
                                EPIC.browser().practitioner(unprovisioned()), seen)),
                Arguments.of("a deactivated User", TOKENS,
                        Ending.refused("ACCOUNT_DISABLED", "token"),
                        (LoginPath) (test, seen) -> test.signIn(EPIC.deactivatedFhirUser(), seen)),
                Arguments.of("a locked User", TOKENS,
                        Ending.refused("ACCOUNT_LOCKED", "token"),
                        (LoginPath) (test, seen) -> test.signIn(EPIC.lockedFhirUser(), seen)),
                Arguments.of("a token endpoint 5xx", REDEEMED,
                        Ending.unavailable("token", "server", "token"),
                        (LoginPath) (test, seen) -> test.signInWhile(
                                FakeEpic.Endpoint.TOKEN, FakeEpic.Failure.SERVER_ERROR, seen)),
                Arguments.of("a token endpoint timeout", REDEEMED,
                        Ending.unavailable("token", "network", "token"),
                        (LoginPath) (test, seen) -> test.signInWhile(
                                FakeEpic.Endpoint.TOKEN, FakeEpic.Failure.STALL, seen)),
                Arguments.of("a JWKS 5xx", TOKENS, Ending.unavailable("jwks", "server", "token"),
                        (LoginPath) (test, seen) -> test.signInWhile(
                                FakeEpic.Endpoint.JWKS, FakeEpic.Failure.SERVER_ERROR, seen)),
                Arguments.of("a JWKS timeout", TOKENS, Ending.unavailable("jwks", "network", "token"),
                        (LoginPath) (test, seen) -> test.signInWhile(
                                FakeEpic.Endpoint.JWKS, FakeEpic.Failure.STALL, seen)));
    }

    @ParameterizedTest(name = "{0} puts no D22 value in the log or the audit trail")
    @MethodSource("everyPath")
    void noD22ValueReachesTheLogOrTheAuditTrail(String which, Set<String> handled,
            Ending ending, LoginPath path) throws Exception {
        D22Values seen = new D22Values()
                .signingKey("active signing key", EPIC.activeKey())
                .signingKey("next signing key", EPIC.nextKey());

        path.take(this, seen);
        EPIC.fake().handled(seen);

        // The search is only worth its absences if the path handled what it was meant to, and
        // the capture saw the records the path wrote.
        assertThat(seen.kinds()).as("the D22 values %s handled", which)
                .containsExactlyInAnyOrderElementsOf(handled);
        assertThat(logs.records()).as("the records %s wrote", which).isNotEmpty();
        seen.assertNoneIn("the log", logs.lines());
        seen.assertNoneInTheAuditTrail(jdbc);
        seen.assertNoneIn("the callback's answer", callbackAnswers.toString());
    }

    @ParameterizedTest(name = "{0} moves the Epic Login meters under the expected tags")
    @MethodSource("everyPath")
    void theEpicLoginMetersMoveUnderTheExpectedTags(String which, Set<String> handled,
            Ending ending, LoginPath path) throws Exception {
        Map<String, Double> before = EpicMeters.read(meters);

        path.take(this, new D22Values());

        assertThat(cacheIndependent(EpicMeters.change(before, EpicMeters.read(meters))))
                .isEqualTo(ending.expected());
    }

    /**
     * The series a path moves by itself: every Epic {@code login} series and
     * {@code epic.outbound.errors} series, and the token call's timer. Discovery and the JWKS are
     * read on a Login's first use and whenever Epic's key is new to the kept JWKS — each test's
     * fake signs with a key of its own — so how often their timers move depends on the tests
     * before it, not on the path.
     */
    private static Map<String, Double> cacheIndependent(Map<String, Double> moved) {
        Map<String, Double> kept = new TreeMap<>(moved);
        kept.remove(series("epic.outbound", "call", "discovery"));
        kept.remove(series("epic.outbound", "call", "jwks"));
        return kept;
    }

    // ---- the browser ----------------------------------------------------------------------------

    /** A whole Login, its launch and Epic's redirect back recorded as handled. */
    private void signIn(String fhirUser, D22Values seen) throws Exception {
        callBack(fhirUser, seen, callback -> { });
    }

    /** {@link #signIn}, with {@code endpoint} failing as {@code failure} throughout. */
    private void signInWhile(FakeEpic.Endpoint endpoint, FakeEpic.Failure failure, D22Values seen)
            throws Exception {
        EPIC.fake().failing(endpoint, failure);
        signIn(EPIC.provisionedFhirUser(), seen);
    }

    /**
     * A Login whose callback query is {@code forged} after Epic's redirect, before the browser
     * follows it.
     */
    private void callBack(String fhirUser, D22Values seen, Consumer<Map<String, String>> forged)
            throws Exception {
        EpicBrowser browser = EPIC.browser();
        EpicBrowser.Launched launched = browser.launch(seen.launch(), null);
        Map<String, String> callback = browser.authorizeAtEpic(fhirUser, launched);
        seen.callback(callback);
        forged.accept(callback);
        seen.callback(callback);
        MockHttpServletResponse answer =
                browser.callback(callback, launched.session()).callback().getResponse();
        callbackAnswers.append(answer.getStatus()).append('\n');
        answer.getHeaderNames().forEach(name -> callbackAnswers.append(name).append(": ")
                .append(answer.getHeaders(name)).append('\n'));
        callbackAnswers.append(answer.getContentAsString()).append('\n');
    }

    // ---- helpers ------------------------------------------------------------------------------

    private static Set<String> with(Set<String> kinds, String... more) {
        Set<String> all = new TreeSet<>(kinds);
        all.addAll(List.of(more));
        return Set.copyOf(all);
    }
}

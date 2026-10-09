package com.example.backend.auth.epic;

import static com.example.backend.auth.epic.EpicTestEnvironment.sessionId;
import static com.example.backend.auth.epic.EpicTestEnvironment.CLIENT_ID;
import static com.example.backend.auth.epic.EpicTestEnvironment.FHIR_BASE;
import static com.example.backend.auth.epic.EpicTestEnvironment.REDIRECT_URI;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import ch.qos.logback.classic.Level;
import com.example.backend.ContainerTestConfiguration;
import com.example.backend.audit.CapturedLog;
import com.example.backend.observability.LogEvent;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jwt.JWTClaimsSet;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.http.Cookie;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.connection.DataType;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.session.FindByIndexNameSessionRepository;
import org.springframework.session.Session;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.convention.TestBean;
import org.springframework.test.web.servlet.MvcResult;

/**
 * Epic Login's protocol hardening (ADR 0013, D10, D17, D18, D27, flow steps 1–5
 * and 8): every malformed, forged, replayed or out-of-policy launch, callback and {@code id_token}
 * lands at {@code /?signin=refused} with its session ended, and is audited once as a
 * {@code LOGIN_FAILURE} under method {@code sso} with its exact reason (ADR 0013, "Audit").
 *
 * <p>Epic is a {@link FakeEpic} started for each test by the {@link EpicTestEnvironment}, told to
 * forge whatever Epic or an attacker could send; the browser is an {@link EpicBrowser} over the
 * real filter chain and the real, Redis-backed session store. The happy path and Epic being
 * unavailable are {@link EpicLoginIntegrationTests}'s.
 */
@SpringBootTest
@ActiveProfiles("dev")
@Import(ContainerTestConfiguration.class)
class EpicProtocolIntegrationTests {

    private static final String REFUSED = "/?signin=refused";

    @RegisterExtension
    static final EpicTestEnvironment EPIC = EpicTestEnvironment.epicLoginOn();

    @DynamicPropertySource
    static void epicLoginOn(DynamicPropertyRegistry registry) {
        EPIC.register(registry);
    }

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private FindByIndexNameSessionRepository<? extends Session> sessionRepository;

    @Autowired
    private RedisConnectionFactory redis;

    @Autowired
    private MeterRegistry meters;

    /** No wait before a D26 JWKS refetch: this class is not about them. */
    @TestBean(methodName = EpicTestFixtures.NO_RETRY_PAUSE)
    private EpicRetryPause epicRetryPause;

    // ---- the launch (flow step 1, D10, D18) ---------------------------------------------------

    /** Each launch {@code iss} that is not exactly {@code APP_EPIC_FHIR_BASE}, and its rule. */
    static Stream<Arguments> wrongIss() {
        return Stream.of(
                Arguments.of("missing", null, "missing"),
                Arguments.of("another FHIR base", "https://fhir.example.com/api/FHIR/R4", "mismatch"),
                Arguments.of("a case variant", "https://fhir.example.org/api/fhir/R4", "mismatch"),
                Arguments.of("a trailing slash", FHIR_BASE + "/", "mismatch"),
                Arguments.of("empty", "", "missing"));
    }

    /** Each {@code launch} outside D18's bounds, and its rule. */
    static Stream<Arguments> invalidLaunch() {
        return Stream.of(
                Arguments.of("missing", null, "missing"),
                Arguments.of("empty", "", "missing"),
                Arguments.of("8193 characters long", "x".repeat(8193), "length"),
                Arguments.of("carrying a space", "launch context", "charset"),
                Arguments.of("carrying a tab", "launch\tcontext", "charset"),
                Arguments.of("carrying non-ASCII", "launch-contéxt", "charset"));
    }

    @ParameterizedTest(name = "an iss {0} lands at the refused notice")
    @MethodSource("wrongIss")
    void aWrongIssLandsAtTheRefusedNotice(String which, String iss, String rule) throws Exception {
        MvcResult launch = EPIC.browser().open(iss, EpicBrowser.LAUNCH, null);

        assertThat(launch.getResponse().getStatus()).isEqualTo(302);
        assertThat(launch.getResponse().getRedirectedUrl()).isEqualTo(REFUSED);
    }

    @ParameterizedTest(name = "an iss {0} is audited as ISS_MISMATCH")
    @MethodSource("wrongIss")
    void aWrongIssIsAuditedAsIssMismatch(String which, String iss, String rule) throws Exception {
        int before = refusalsAudited("ISS_MISMATCH");

        EPIC.browser().open(iss, EpicBrowser.LAUNCH, null);

        assertThat(refusalsAudited("ISS_MISMATCH")).isEqualTo(before + 1);
    }

    @ParameterizedTest(name = "an iss {0} is one WARN naming the field and the rule {2}")
    @MethodSource("wrongIss")
    void aWrongIssIsOneWarningNamingTheFieldAndTheRule(String which, String iss, String rule)
            throws Exception {
        List<Map<String, Object>> warnings = refusalWarnings(() -> EPIC.browser().open(
                iss, EpicBrowser.LAUNCH, null));

        assertThat(warnings).singleElement().satisfies(fields -> assertThat(fields)
                .containsEntry("app.epic.input.field", "iss")
                .containsEntry("app.epic.input.rule", rule));
    }

    @ParameterizedTest(name = "a launch {0} lands at the refused notice")
    @MethodSource("invalidLaunch")
    void anInvalidLaunchLandsAtTheRefusedNotice(String which, String launch, String rule)
            throws Exception {
        MvcResult opened = EPIC.browser().open(FHIR_BASE, launch, null);

        assertThat(opened.getResponse().getRedirectedUrl()).isEqualTo(REFUSED);
    }

    @ParameterizedTest(name = "a launch {0} is audited as INVALID_LAUNCH")
    @MethodSource("invalidLaunch")
    void anInvalidLaunchIsAuditedAsInvalidLaunch(String which, String launch, String rule)
            throws Exception {
        int before = refusalsAudited("INVALID_LAUNCH");

        EPIC.browser().open(FHIR_BASE, launch, null);

        assertThat(refusalsAudited("INVALID_LAUNCH")).isEqualTo(before + 1);
    }

    @ParameterizedTest(name = "a launch {0} is one WARN naming the field and the rule {2}")
    @MethodSource("invalidLaunch")
    void anInvalidLaunchIsOneWarningNamingTheFieldAndTheRule(String which, String launch,
            String rule) throws Exception {
        List<Map<String, Object>> warnings =
                refusalWarnings(() -> EPIC.browser().open(FHIR_BASE, launch, null));

        assertThat(warnings).singleElement().satisfies(fields -> assertThat(fields)
                .containsEntry("app.epic.input.field", "launch")
                .containsEntry("app.epic.input.rule", rule));
    }

    /** D22: the log names the field and the rule, never the value that broke it. */
    @Test
    void aRefusedLaunchLogsNeitherValue() throws Exception {
        String launch = "secret-launch " + UUID.randomUUID();
        String iss = "https://forged-" + UUID.randomUUID() + ".example.org/fhir";

        String everything;
        try (CapturedLog captured = CapturedLog.attach()) {
            EPIC.browser().open(FHIR_BASE, launch, null);
            EPIC.browser().open(iss, "launch-" + launch.substring(14), null);
            everything = captured.withAction(Level.TRACE, LogEvent.KIND, "event").stream()
                    .map(record -> record.getFormattedMessage() + CapturedLog.fields(record))
                    .collect(Collectors.joining("\n"));
        }

        assertThat(everything).isNotEmpty()
                .doesNotContain(launch.substring(14))
                .doesNotContain(iss);
    }

    /** D24: a refused launch ends whatever session the browser held. */
    @Test
    void aRefusedLaunchEndsTheSessionTheBrowserHeld() throws Exception {
        Cookie held = EPIC.browser().launch(null).session();

        EPIC.browser().open(FHIR_BASE + "/", EpicBrowser.LAUNCH, held);

        assertThat(sessionRepository.findById(sessionId(held))).isNull();
    }

    @Test
    void theEpicLoginCounterRecordsARefusedLaunchWithItsReason() throws Exception {
        double before = refusalsCounted("ISS_MISMATCH");

        EPIC.browser().open(null, EpicBrowser.LAUNCH, null);

        assertThat(refusalsCounted("ISS_MISMATCH")).isEqualTo(before + 1);
    }

    /** The authorize hop with no launch pending: nothing to send to Epic, and refused. */
    @Test
    void theAuthorizeHopWithNoLaunchPendingIsAuditedAsInvalidLaunch() throws Exception {
        int before = refusalsAudited("INVALID_LAUNCH");

        MvcResult hop = EPIC.mvc().perform(get("/api/auth/epic/authorize")).andReturn();

        assertThat(hop.getResponse().getRedirectedUrl()).isEqualTo(REFUSED);
        assertThat(refusalsAudited("INVALID_LAUNCH")).isEqualTo(before + 1);
    }

    // ---- the authorize redirect and the token call (flow steps 2 and 3) ----------------------

    @Test
    void theTokenCallSendsExactlyTheRegisteredRedirectUri() throws Exception {
        EPIC.browser().signIn(EPIC.provisionedFhirUser(), null);

        assertThat(EPIC.fake().tokenRequests()).singleElement()
                .satisfies(form -> assertThat(form).containsEntry("redirect_uri", REDIRECT_URI));
    }

    /** At least 43 characters, and at least 256 bits: 32 random bytes or more, Base64URL. */
    @Test
    void theVerifierIsAtLeast43CharactersCarryingAtLeast256Bits() throws Exception {
        EPIC.browser().signIn(EPIC.provisionedFhirUser(), null);

        String verifier = EPIC.fake().tokenRequests().getFirst().get("code_verifier");
        assertThat(verifier).hasSizeGreaterThanOrEqualTo(43).matches("[A-Za-z0-9_-]+");
        assertThat(Base64.getUrlDecoder().decode(verifier)).hasSizeGreaterThanOrEqualTo(32);
    }

    @Test
    void theChallengeIsTheBase64UrlOfTheSha256OfTheVerifier() throws Exception {
        EPIC.browser().signIn(EPIC.provisionedFhirUser(), null);

        String verifier = EPIC.fake().tokenRequests().getFirst().get("code_verifier");
        byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest(verifier.getBytes(StandardCharsets.US_ASCII));
        assertThat(EPIC.fake().authorizeRequests().getFirst())
                .containsEntry("code_challenge",
                        Base64.getUrlEncoder().withoutPadding().encodeToString(digest))
                .containsEntry("code_challenge_method", "S256");
    }

    @Test
    void stateAndNonceAreUniqueOnEveryCall() throws Exception {
        List<Map<String, String>> sent = new ArrayList<>();
        for (int launch = 0; launch < 3; launch++) {
            sent.add(FakeEpic.queryOf(EPIC.browser().launch(null).epicAuthorize()));
        }

        assertThat(sent.stream().map(query -> query.get("state")).distinct()).hasSize(3);
        assertThat(sent.stream().map(query -> query.get("nonce")).distinct()).hasSize(3);
    }

    /** The {@code launch} already carries the clinician's Hyperspace context. */
    @Test
    void noLoginHintIsSent() throws Exception {
        Map<String, String> sent = FakeEpic.queryOf(EPIC.browser().launch(null).epicAuthorize());

        assertThat(sent).containsKey("launch").doesNotContainKey("login_hint");
    }

    // ---- the callback: the pending request, single use (D27) ---------------------------------

    /** A browser that never launched holds no pending request, and Epic is not called. */
    @Test
    void aCallbackWithNoPendingRequestIsRefusedWithoutATokenCall() throws Exception {
        Map<String, String> callback = callbackFromEpic(EPIC.practitioners().provision());
        int before = refusalsAudited("INVALID_STATE");

        EpicBrowser.Landing landing = EPIC.browser().callback(callback, null);

        assertThat(landing.callback().getResponse().getRedirectedUrl()).isEqualTo(REFUSED);
        assertThat(refusalsAudited("INVALID_STATE")).isEqualTo(before + 1);
        assertThat(EPIC.fake().requests(FakeEpic.Endpoint.TOKEN)).isZero();
    }

    @Test
    void aForgedStateIsRefusedAsInvalidStateWithoutATokenCall() throws Exception {
        EpicBrowser.Launched launched = EPIC.browser().launch(null);
        Map<String, String> callback =
                EPIC.browser().authorizeAtEpic(EPIC.provisionedFhirUser(), launched);
        callback.put("state", "forged-" + UUID.randomUUID());
        int before = refusalsAudited("INVALID_STATE");

        EpicBrowser.Landing landing = EPIC.browser().callback(callback, launched.session());

        assertThat(landing.callback().getResponse().getRedirectedUrl()).isEqualTo(REFUSED);
        assertThat(refusalsAudited("INVALID_STATE")).isEqualTo(before + 1);
        assertThat(EPIC.fake().requests(FakeEpic.Endpoint.TOKEN)).isZero();
    }

    @Test
    void aCallbackWithNoStateIsRefusedAsInvalidState() throws Exception {
        EpicBrowser.Launched launched = EPIC.browser().launch(null);
        Map<String, String> callback =
                EPIC.browser().authorizeAtEpic(EPIC.provisionedFhirUser(), launched);
        callback.remove("state");
        int before = refusalsAudited("INVALID_STATE");

        EpicBrowser.Landing landing = EPIC.browser().callback(callback, launched.session());

        assertThat(landing.callback().getResponse().getRedirectedUrl()).isEqualTo(REFUSED);
        assertThat(refusalsAudited("INVALID_STATE")).isEqualTo(before + 1);
    }

    /** A reused {@code state}: the same callback twice signs in once, and Epic is called once. */
    @Test
    void theSameCallbackTwiceIsRefusedTheSecondTimeWithoutATokenCall() throws Exception {
        EpicBrowser.Launched launched = EPIC.browser().launch(null);
        Map<String, String> callback =
                EPIC.browser().authorizeAtEpic(EPIC.provisionedFhirUser(), launched);
        EpicBrowser.Landing first = EPIC.browser().callback(callback, launched.session());
        int before = refusalsAudited("INVALID_STATE");

        EpicBrowser.Landing replayed = EPIC.browser().callback(callback, launched.session());

        assertThat(first.callback().getResponse().getRedirectedUrl()).isEqualTo("/");
        assertThat(replayed.callback().getResponse().getRedirectedUrl()).isEqualTo(REFUSED);
        assertThat(refusalsAudited("INVALID_STATE")).isEqualTo(before + 1);
        assertThat(EPIC.fake().requests(FakeEpic.Endpoint.TOKEN)).isEqualTo(1);
    }

    /**
     * D27 across concurrent requests: both callbacks load the same Redis-backed session at once,
     * and the slow token endpoint keeps the first in flight while the second runs. Only one may
     * take the pending request, so Epic is called once, and at most one Login succeeds.
     */
    @Test
    void ofTwoConcurrentIdenticalCallbacksAtMostOneSucceedsAndEpicIsCalledOnce() throws Exception {
        EpicBrowser.Launched launched = EPIC.browser().launch(null);
        Map<String, String> callback =
                EPIC.browser().authorizeAtEpic(EPIC.provisionedFhirUser(), launched);
        EPIC.fake().slowingTokenBy(Duration.ofMillis(500));
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService browsers = Executors.newFixedThreadPool(2);
        List<Future<EpicBrowser.Landing>> landings;
        try {
            Callable<EpicBrowser.Landing> sameCallback = () -> {
                start.await();
                return EPIC.browser().callback(callback, launched.session());
            };
            landings = List.of(browsers.submit(sameCallback), browsers.submit(sameCallback));
            start.countDown();
            for (Future<EpicBrowser.Landing> landing : landings) {
                landing.get(30, TimeUnit.SECONDS);
            }
        } finally {
            browsers.shutdownNow();
        }

        long signedIn = 0;
        for (Future<EpicBrowser.Landing> landing : landings) {
            if ("/".equals(landing.get().callback().getResponse().getRedirectedUrl())) {
                signedIn++;
            }
        }
        assertThat(signedIn).isLessThanOrEqualTo(1);
        assertThat(EPIC.fake().requests(FakeEpic.Endpoint.TOKEN)).isEqualTo(1);
    }

    /** The pending request — the verifier with it — is gone from the store once it is used. */
    @Test
    void theVerifierIsDiscardedAfterTheExchange() throws Exception {
        EPIC.browser().signIn(EPIC.provisionedFhirUser(), null);

        String verifier = EPIC.fake().tokenRequests().getFirst().get("code_verifier");
        assertThat(everythingInRedis()).doesNotContain(verifier);
    }

    // ---- the callback: Epic's answer (flow step 3, D18) --------------------------------------

    @Test
    void anOAuthErrorFromEpicIsRefusedAsIdpErrorWithoutATokenCall() throws Exception {
        EPIC.fake().answeringAuthorizeWithError("access_denied");
        int before = refusalsAudited("IDP_ERROR");

        EpicBrowser.Landing landing =
                EPIC.browser().signIn(EPIC.provisionedFhirUser(), null);

        assertThat(landing.callback().getResponse().getRedirectedUrl()).isEqualTo(REFUSED);
        assertThat(refusalsAudited("IDP_ERROR")).isEqualTo(before + 1);
        assertThat(EPIC.fake().requests(FakeEpic.Endpoint.TOKEN)).isZero();
    }

    /** Each {@code code} outside D18's bounds, and its rule. */
    static Stream<Arguments> invalidCode() {
        return Stream.of(
                Arguments.of("missing", null, "missing"),
                Arguments.of("empty", "", "missing"),
                Arguments.of("8193 characters long", "c".repeat(8193), "length"),
                Arguments.of("carrying a space", "code value", "charset"),
                Arguments.of("carrying non-ASCII", "cödé", "charset"));
    }

    @ParameterizedTest(name = "a code {0} is refused as INVALID_CODE without a token call")
    @MethodSource("invalidCode")
    void anInvalidCodeIsRefusedAsInvalidCodeWithoutATokenCall(String which, String code,
            String rule) throws Exception {
        EpicBrowser.Launched launched = EPIC.browser().launch(null);
        Map<String, String> callback =
                EPIC.browser().authorizeAtEpic(EPIC.provisionedFhirUser(), launched);
        if (code == null) {
            callback.remove("code");
        } else {
            callback.put("code", code);
        }
        int before = refusalsAudited("INVALID_CODE");

        EpicBrowser.Landing landing = EPIC.browser().callback(callback, launched.session());

        assertThat(landing.callback().getResponse().getRedirectedUrl()).isEqualTo(REFUSED);
        assertThat(refusalsAudited("INVALID_CODE")).isEqualTo(before + 1);
        assertThat(EPIC.fake().requests(FakeEpic.Endpoint.TOKEN)).isZero();
    }

    @ParameterizedTest(name = "a code {0} is one WARN naming the field and the rule {2}")
    @MethodSource("invalidCode")
    void anInvalidCodeIsOneWarningNamingTheFieldAndTheRule(String which, String code,
            String rule) throws Exception {
        EpicBrowser.Launched launched = EPIC.browser().launch(null);
        Map<String, String> callback =
                EPIC.browser().authorizeAtEpic(EPIC.provisionedFhirUser(), launched);
        if (code == null) {
            callback.remove("code");
        } else {
            callback.put("code", code);
        }

        List<Map<String, Object>> warnings =
                refusalWarnings(() -> EPIC.browser().callback(callback, launched.session()));

        assertThat(warnings).singleElement().satisfies(fields -> assertThat(fields)
                .containsEntry("app.epic.input.field", "code")
                .containsEntry("app.epic.input.rule", rule));
    }

    /** D22: a refused callback's log names neither its code nor its state. */
    @Test
    void aRefusedCallbackLogsNeitherTheCodeNorTheState() throws Exception {
        String forgedState = "forged-state-" + UUID.randomUUID();
        String badCode = "bad code " + UUID.randomUUID();
        EpicBrowser.Launched first = EPIC.browser().launch(null);
        Map<String, String> forged =
                EPIC.browser().authorizeAtEpic(EPIC.provisionedFhirUser(), first);
        forged.put("state", forgedState);
        EpicBrowser.Launched second = EPIC.browser().launch(null);
        Map<String, String> malformed =
                EPIC.browser().authorizeAtEpic(EPIC.provisionedFhirUser(), second);
        malformed.put("code", badCode);

        String everything;
        try (CapturedLog captured = CapturedLog.attach()) {
            EPIC.browser().callback(forged, first.session());
            EPIC.browser().callback(malformed, second.session());
            everything = captured.withAction(Level.TRACE, LogEvent.KIND, "event").stream()
                    .map(record -> record.getFormattedMessage() + CapturedLog.fields(record))
                    .collect(Collectors.joining("\n"));
        }

        assertThat(everything).isNotEmpty()
                .doesNotContain(forgedState)
                .doesNotContain(badCode.substring(9))
                .doesNotContain(forged.get("code"))
                .doesNotContain(malformed.get("state"));
    }

    /** D24: a refused callback ends the launch's session. */
    @Test
    void aRefusedCallbackEndsTheSessionTheBrowserHeld() throws Exception {
        EpicBrowser.Launched launched = EPIC.browser().launch(null);
        Map<String, String> callback =
                EPIC.browser().authorizeAtEpic(EPIC.provisionedFhirUser(), launched);
        callback.put("state", "forged");

        EPIC.browser().callback(callback, launched.session());

        assertThat(sessionRepository.findById(sessionId(launched.session()))).isNull();
    }

    // ---- the token exchange (flow step 3) ------------------------------------------------------

    /** Epic's own record of the redirect URI is not the one our token call sends. */
    @Test
    void theTokenEndpointRefusingTheRedirectUriIsRefusedAsTokenExchangeFailed() throws Exception {
        EPIC.fake().rememberingAtAuthorize(
                "redirect_uri", "https://elsewhere.example.org/callback");

        assertRefusedAs("TOKEN_EXCHANGE_FAILED",
                () -> EPIC.browser().signIn(EPIC.provisionedFhirUser(), null));
        assertThat(EPIC.fake().tokenRefusals()).containsExactly("redirect_uri");
    }

    /** Epic's own record of the challenge is not the hash of the verifier our call sends. */
    @Test
    void theTokenEndpointRefusingTheVerifierIsRefusedAsTokenExchangeFailed() throws Exception {
        EPIC.fake().rememberingAtAuthorize("code_challenge", "a-challenge-no-verifier-hashes-to");

        assertRefusedAs("TOKEN_EXCHANGE_FAILED",
                () -> EPIC.browser().signIn(EPIC.provisionedFhirUser(), null));
        assertThat(EPIC.fake().tokenRefusals()).containsExactly("code_verifier");
    }

    /**
     * A code Epic already redeemed, carried into a second launch's callback under that launch's
     * own {@code state}: our checks pass, and Epic's single-use code is what refuses it.
     */
    @Test
    void theTokenEndpointRefusingAReplayedCodeIsRefusedAsTokenExchangeFailed() throws Exception {
        String practitioner = EPIC.provisionedFhirUser();
        EpicBrowser.Launched first = EPIC.browser().launch(null);
        Map<String, String> firstCallback = EPIC.browser().authorizeAtEpic(practitioner, first);
        EPIC.browser().callback(firstCallback, first.session());
        EpicBrowser.Launched second = EPIC.browser().launch(null);
        Map<String, String> secondCallback = EPIC.browser().authorizeAtEpic(practitioner, second);
        secondCallback.put("code", firstCallback.get("code"));

        assertRefusedAs("TOKEN_EXCHANGE_FAILED",
                () -> EPIC.browser().callback(secondCallback, second.session()));
        assertThat(EPIC.fake().tokenRefusals()).containsExactly("code");
    }

    @Test
    void theTokenEndpointRefusingOurAssertionIsRefusedAsTokenExchangeFailed() throws Exception {
        EPIC.fake().rejectingOurAssertion();

        assertRefusedAs("TOKEN_EXCHANGE_FAILED",
                () -> EPIC.browser().signIn(EPIC.provisionedFhirUser(), null));
    }

    // ---- the id_token's signature (flow step 4) -----------------------------------------------

    @Test
    void anIdTokenSignedWithAnotherAlgorithmIsRefusedAsInvalidSignature() throws Exception {
        EPIC.fake().signingIdTokensWith(JWSAlgorithm.RS512);

        assertRefusedAs("INVALID_SIGNATURE",
                () -> EPIC.browser().signIn(EPIC.provisionedFhirUser(), null));
    }

    @Test
    void anIdTokenWithAForgedSignatureIsRefusedAsInvalidSignature() throws Exception {
        EPIC.fake().forgingIdTokenSignatures();

        assertRefusedAs("INVALID_SIGNATURE",
                () -> EPIC.browser().signIn(EPIC.provisionedFhirUser(), null));
    }

    /** D26: a {@code kid} still unknown after the refetches, as ADR 0013 records it. */
    @Test
    void anIdTokenWhoseKidStaysUnknownIsRefusedAsInvalidSignature() throws Exception {
        EPIC.browser().signIn(EPIC.provisionedFhirUser(), null);
        EPIC.fake().rotateSigningKey(Integer.MAX_VALUE);

        assertRefusedAs("INVALID_SIGNATURE",
                () -> EPIC.browser().signIn(EPIC.provisionedFhirUser(), null));
    }

    /** Epic's keys arrived but could not be read, so no signature could be checked. */
    @Test
    void anUnreadableJwksIsRefusedAsInvalidSignature() throws Exception {
        EPIC.fake().failing(FakeEpic.Endpoint.JWKS, FakeEpic.Failure.MALFORMED);

        assertRefusedAs("INVALID_SIGNATURE",
                () -> EPIC.browser().signIn(EPIC.provisionedFhirUser(), null));
    }

    // ---- the id_token's claims (flow step 4) --------------------------------------------------

    /** Each {@code id_token} whose claims fail flow step 4, as the fake mints it. */
    static Stream<Arguments> failingClaims() {
        Instant now = Instant.now();
        return Stream.of(
                Arguments.of("another issuer", (Consumer<JWTClaimsSet.Builder>) claims ->
                        claims.issuer("https://elsewhere.example.org/oauth2")),
                Arguments.of("another audience", (Consumer<JWTClaimsSet.Builder>) claims ->
                        claims.audience("someone-else")),
                Arguments.of("several audiences and no azp",
                        (Consumer<JWTClaimsSet.Builder>) claims ->
                                claims.audience(List.of(CLIENT_ID, "someone-else"))),
                Arguments.of("several audiences and another azp",
                        (Consumer<JWTClaimsSet.Builder>) claims -> claims
                                .audience(List.of(CLIENT_ID, "someone-else"))
                                .claim("azp", "someone-else")),
                Arguments.of("an expiry 31 seconds past", (Consumer<JWTClaimsSet.Builder>) claims ->
                        claims.issueTime(Date.from(now.minusSeconds(600)))
                                .expirationTime(Date.from(now.minusSeconds(31)))),
                Arguments.of("an iat 31 seconds ahead", (Consumer<JWTClaimsSet.Builder>) claims ->
                        claims.issueTime(Date.from(Instant.now().plusSeconds(31)))),
                Arguments.of("no iat", (Consumer<JWTClaimsSet.Builder>) claims ->
                        claims.issueTime(null)),
                Arguments.of("another nonce", (Consumer<JWTClaimsSet.Builder>) claims ->
                        claims.claim("nonce", "a-nonce-we-never-sent")),
                Arguments.of("no nonce", (Consumer<JWTClaimsSet.Builder>) claims ->
                        claims.claim("nonce", null)));
    }

    @ParameterizedTest(name = "an id_token with {0} is refused as INVALID_CLAIMS")
    @MethodSource("failingClaims")
    void anIdTokenWithFailingClaimsIsRefusedAsInvalidClaims(String which,
            Consumer<JWTClaimsSet.Builder> claims) throws Exception {
        EPIC.fake().mintingIdTokensWith(claims);

        assertRefusedAs("INVALID_CLAIMS",
                () -> EPIC.browser().signIn(EPIC.provisionedFhirUser(), null));
    }

    /** The 30-second skew is applied, not merely bounded: 20 seconds ahead is accepted. */
    @Test
    void anIatWithinTheClockSkewIsAccepted() throws Exception {
        EPIC.fake().mintingIdTokensWith(
                claims -> claims.issueTime(Date.from(Instant.now().plusSeconds(20))));

        EpicBrowser.Landing landing =
                EPIC.browser().signIn(EPIC.provisionedFhirUser(), null);

        assertThat(landing.callback().getResponse().getRedirectedUrl()).isEqualTo("/");
    }

    @Test
    void severalAudiencesWithOurClientAsAzpAreAccepted() throws Exception {
        EPIC.fake().mintingIdTokensWith(claims -> claims
                .audience(List.of(CLIENT_ID, "someone-else")).claim("azp", CLIENT_ID));

        EpicBrowser.Landing landing =
                EPIC.browser().signIn(EPIC.provisionedFhirUser(), null);

        assertThat(landing.callback().getResponse().getRedirectedUrl()).isEqualTo("/");
    }

    // ---- the identity (flow step 5) -----------------------------------------------------------

    static Stream<Arguments> foreignFhirUser() {
        return Stream.of(
                Arguments.of("a Patient", FHIR_BASE + "/Patient/eABC123"),
                Arguments.of("another FHIR base", "https://fhir.example.com/api/FHIR/R4/Practitioner/eABC"),
                Arguments.of("a blank id", FHIR_BASE + "/Practitioner/"),
                Arguments.of("absent", null));
    }

    @ParameterizedTest(name = "a fhirUser naming {0} is refused as INVALID_FHIR_USER")
    @MethodSource("foreignFhirUser")
    void aForeignFhirUserIsRefusedAsInvalidFhirUser(String which, String fhirUser)
            throws Exception {
        assertRefusedAs("INVALID_FHIR_USER", () -> EPIC.browser().signIn(fhirUser, null));
    }

    // ---- MFA (D17), with its switch off: attested by the Epic organisation --------------------

    /** Until Epic confirms the claim, the factor is the organisation's attestation. */
    @Test
    void anEpicLoginSuccessRecordsTheFactorAsIdpAttested() throws Exception {
        String practitioner = EPIC.practitioners().provision();

        EPIC.signIn(practitioner, null);

        assertThat(mfaFactorsOfLoginSuccess(practitioner)).containsExactly("idp-attested");
    }

    /** With the switch off, whatever {@code amr} says is not what the factor is taken from. */
    @Test
    void anAmrIsNotReadWhileTheSwitchIsOff() throws Exception {
        String practitioner = EPIC.practitioners().provision();
        EPIC.fake().mintingIdTokensWith(claims -> claims.claim("amr", List.of("pwd", "otp")));

        EPIC.signIn(practitioner, null);

        assertThat(mfaFactorsOfLoginSuccess(practitioner)).containsExactly("idp-attested");
    }

    /** With the switch off, a token with no MFA evidence at all is not refused for it. */
    @Test
    void noMfaEvidenceIsRequiredWhileTheSwitchIsOff() throws Exception {
        EpicBrowser.Landing landing =
                EPIC.browser().signIn(EPIC.provisionedFhirUser(), null);

        assertThat(landing.callback().getResponse().getRedirectedUrl()).isEqualTo("/");
    }

    // ---- every refusal ------------------------------------------------------------------------

    /**
     * Holds {@code step} to a refusal for {@code reason}: the refused notice, exactly one
     * {@code LOGIN_FAILURE} with that reason under method {@code sso} and no subject, the browser's
     * session ended, and the {@code epic.login} counter's reason.
     */
    private void assertRefusedAs(String reason, LandingStep step) throws Exception {
        int audited = refusalsAudited(reason);
        int allFailures = allLoginFailures();
        double counted = refusalsCounted(reason);

        EpicBrowser.Landing landing = step.take();

        assertThat(landing.callback().getResponse().getStatus()).isEqualTo(302);
        assertThat(landing.callback().getResponse().getRedirectedUrl()).isEqualTo(REFUSED);
        assertThat(refusalsAudited(reason)).as("audited as " + reason).isEqualTo(audited + 1);
        assertThat(allLoginFailures()).as("audited once").isEqualTo(allFailures + 1);
        assertThat(refusalsCounted(reason)).isEqualTo(counted + 1);
        if (landing.launched() != null) {
            assertThat(sessionRepository.findById(sessionId(landing.launched()))).isNull();
        }
    }

    /** Something a browser does that ends at our callback. */
    @FunctionalInterface
    private interface LandingStep {
        EpicBrowser.Landing take() throws Exception;
    }

    /** The MFA factor of each {@code LOGIN_SUCCESS} of the User named {@code userName}. */
    private List<String> mfaFactorsOfLoginSuccess(String userName) {
        return jdbc.queryForList("""
                SELECT e.mfa_factor FROM audit_events e
                JOIN scim_users u ON u.resource_id = e.subject_id
                WHERE e.operation = 'LOGIN_SUCCESS' AND u.user_name = ?""",
                String.class, userName);
    }

    private int allLoginFailures() {
        return jdbc.queryForObject(
                "SELECT count(*) FROM audit_events WHERE operation = 'LOGIN_FAILURE'",
                Integer.class);
    }

    // ---- helpers ------------------------------------------------------------------------------

    /** Epic's redirect back to our callback for a fresh launch, as {@code practitioner}. */
    private Map<String, String> callbackFromEpic(String practitioner) throws Exception {
        EpicBrowser browser = EPIC.browser();
        return browser.authorizeAtEpic(browser.practitioner(practitioner), browser.launch(null));
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
                }
            }
        }
        return all.toString();
    }

    /** Every Epic {@code LOGIN_FAILURE} the trail holds for {@code reason}, each naming nobody. */
    private int refusalsAudited(String reason) {
        return jdbc.queryForObject("""
                SELECT count(*) FROM audit_events
                WHERE operation = 'LOGIN_FAILURE' AND error_code = ?
                AND login_method = 'sso' AND subject_id IS NULL""", Integer.class, reason);
    }

    private double refusalsCounted(String reason) {
        Counter counter = meters.find("epic.login").tag("outcome", "refused")
                .tag("reason", reason).counter();
        return counter == null ? 0 : counter.count();
    }

    /** Something a browser does. */
    @FunctionalInterface
    private interface Step {
        void take() throws Exception;
    }

    /** The fields of each "Epic sign-in refused" {@code WARN} that {@code step} caused. */
    private static List<Map<String, Object>> refusalWarnings(Step step) throws Exception {
        try (CapturedLog captured = CapturedLog.attach()) {
            step.take();
            return captured.withAction(Level.WARN, LogEvent.LOCAL_ACTION, "epic.login").stream()
                    .filter(record -> record.getLevel() == Level.WARN)
                    .map(CapturedLog::fields)
                    .toList();
        }
    }
}

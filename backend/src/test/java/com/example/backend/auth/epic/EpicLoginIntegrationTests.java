package com.example.backend.auth.epic;

import static com.example.backend.auth.epic.EpicPractitioners.unprovisioned;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import com.example.backend.ContainerTestConfiguration;
import com.example.backend.SessionCsrf;
import com.example.backend.audit.CapturedLog;
import com.example.backend.auth.domain.RoleMappingSessions;
import com.example.backend.authorization.domain.RoleMapping;
import com.example.backend.observability.LogEvent;
import com.example.backend.observability.RequestIdFilter;
import com.example.backend.observability.SessionHash;
import com.example.backend.scim.domain.ScimUserRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.servlet.Filter;
import jakarta.servlet.http.Cookie;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
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
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.session.FindByIndexNameSessionRepository;
import org.springframework.session.Session;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.convention.TestBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.web.context.WebApplicationContext;

/**
 * Epic Login's happy path end to end (ADR 0013, flow steps 1–7): a clinician
 * whose Practitioner FHIR ID is the {@code userName} of a provisioned User opens the application
 * from Epic and is signed in exactly as password Login would sign them in.
 *
 * <p>Epic is a {@link FakeEpic} started for each test, which verifies our client assertion against
 * our own published JWKS and mints the {@code id_token} the test names. The browser is played by
 * the test: each redirect is followed by hand, our routes through MockMvc over the real filter
 * chain and a real, indexed Redis session store, and Epic's {@code /authorize} over HTTP.
 *
 * <p>The {@code dev} profile lets the fake be plain {@code http} on the loopback interface (D21);
 * nothing else in this context depends on it.
 *
 * <p>Epic on a bad day is the same fake told to stall, answer {@code 5xx} or rotate its key
 * (D23, D24, D26). The outbound timeouts are 1 second here, well short of the fake's stall; the
 * waits before D26's JWKS refetches are recorded rather than slept. Discovery and Epic's keys
 * are kept across tests, as they are across Logins: each test's fake signs with a key of its
 * own, so a test's first Login finds its key by a refetch, and a test about the JWKS counts from
 * a Login that has already found it. Discovery failing is {@link EpicDiscoveryIntegrationTests}'
 * subject, in a context whose discovery has never succeeded.
 */
@SpringBootTest
@ActiveProfiles("dev")
@Import(ContainerTestConfiguration.class)
class EpicLoginIntegrationTests {

    private static final String FHIR_BASE = "https://fhir.example.org/api/FHIR/R4";

    private static final String CLIENT_ID = "epic-client-id";

    private static final String REDIRECT_URI = "https://app.example.org/api/auth/epic/callback";

    private static final String ACTIVE_KID = "active-2026-04";

    private static final String PASSWORD = EpicPractitioners.PASSWORD;

    /** The Admin group, which the test role mapping maps to the Superuser Role. */
    private static final UUID ADMIN_GROUP = UUID.fromString("00000000-0000-4000-8000-00000000a001");

    /** Every Permission the Superuser Role holds, sorted as {@code /api/auth/me} reports them. */
    private static final List<String> SUPERUSER_PERMISSIONS = List.of(
            "audit:read", "connector:read", "connector:token", "connector:write", "counter:read",
            "counter:write", "group:read", "group:write", "ops:read", "user:read", "user:write");

    private static final KeyPair ACTIVE_KEY = EpicTestKeys.p384KeyPair();

    /** One port for the class, so the issuer the context is configured with names each fake. */
    private static final int EPIC_PORT = EpicTestFixtures.freePort();

    @DynamicPropertySource
    static void epicLoginOn(DynamicPropertyRegistry registry) {
        registry.add("app.epic.enabled", () -> "true");
        registry.add("app.epic.fhir-base", () -> FHIR_BASE);
        registry.add("app.epic.oauth-issuer", () -> "http://localhost:" + EPIC_PORT + "/oauth2");
        registry.add("app.epic.client-id", () -> CLIENT_ID);
        registry.add("app.epic.redirect-uri", () -> REDIRECT_URI);
        registry.add("app.epic.client-key", () -> EpicTestKeys.pem(ACTIVE_KEY));
        registry.add("app.epic.client-key-id", () -> ACTIVE_KID);
        // Short, so a stalled fake Epic times out quickly: and well short of the stall, which
        // the 5-second default would not be.
        registry.add("app.epic.connect-timeout", () -> "1s");
        registry.add("app.epic.read-timeout", () -> "1s");
    }

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private ScimUserRepository users;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private RequestIdFilter requestIdFilter;

    @Autowired
    @Qualifier("springSecurityFilterChain")
    private Filter springSecurityFilterChain;

    @Autowired
    @Qualifier("springSessionRepositoryFilter")
    private Filter springSessionRepositoryFilter;

    @Autowired
    private FindByIndexNameSessionRepository<? extends Session> sessionRepository;

    @Autowired
    private RedisConnectionFactory redis;

    @Autowired
    private RoleMapping roleMapping;

    @Autowired
    private MeterRegistry meters;

    @Autowired
    private EpicJwks ourJwks;

    @Value("${server.servlet.session.cookie.name:SESSION}")
    private String sessionCookieName;

    /** Every wait before a D26 JWKS refetch, in order, since the test began. */
    private static final List<Duration> PAUSES = Collections.synchronizedList(new ArrayList<>());

    @TestBean
    private EpicRetryPause epicRetryPause;

    static EpicRetryPause epicRetryPause() {
        return PAUSES::add;
    }

    private MockMvc mvc;

    private FakeEpic epic;

    private EpicPractitioners practitioners;

    @BeforeEach
    void setUp() throws IOException {
        mvc = MockMvcBuilders.webAppContextSetup(context)
                .addFilters(requestIdFilter, springSessionRepositoryFilter, springSecurityFilterChain)
                .build();
        epic = FakeEpic.start(EPIC_PORT, CLIENT_ID, ACTIVE_KID,
                () -> EpicTestFixtures.publishedJwks(ourJwks));
        practitioners = new EpicPractitioners(users, passwordEncoder, transactionManager, jdbc);
        PAUSES.clear();
    }

    @AfterEach
    void tearDown() {
        epic.close();
        practitioners.removeAll();
    }

    // ---- the launch and the authorize redirect ------------------------------------------------

    /** Flow step 2: everything Epic's authorization endpoint is sent, and nothing to spare. */
    @Test
    void theAuthorizeRedirectCarriesTheLaunchTheAudienceTheScopeAndPkce() throws Exception {
        URI authorize = launch(null).epicAuthorize();

        Map<String, String> sent = FakeEpic.queryOf(authorize);
        assertThat(authorize.toString()).startsWith(epic.issuer() + "/authorize?");
        assertThat(sent).containsEntry("response_type", "code")
                .containsEntry("client_id", CLIENT_ID)
                .containsEntry("redirect_uri", REDIRECT_URI)
                .containsEntry("scope", "launch openid fhirUser")
                .containsEntry("launch", "launch-context-from-hyperspace")
                .containsEntry("aud", FHIR_BASE)
                .containsEntry("code_challenge_method", "S256")
                .containsKeys("state", "nonce", "code_challenge");
    }

    // ---- the happy path -----------------------------------------------------------------------

    /** Flow step 7: the browser lands at {@code /}, and the SPA's {@code /me} path takes over. */
    @Test
    void theCallbackLandsAtTheApplicationRoot() throws Exception {
        String practitioner = practitioners.provision();

        Landing landing = signInFromEpic(practitioner, null);

        assertThat(landing.callback().getResponse().getStatus()).isEqualTo(302);
        assertThat(landing.callback().getResponse().getRedirectedUrl()).isEqualTo("/");
    }

    /**
     * The dev profile alone also accepts the relative {@code Practitioner/{id}} the local SMART
     * launcher issues as {@code fhirUser}; this context runs in it.
     */
    @Test
    void inTheDevProfileARelativeFhirUserSignsThePractitionerIn() throws Exception {
        String practitioner = practitioners.provision();

        Landing landing = completeAs("Practitioner/" + practitioner, launch(null));

        assertThat(landing.callback().getResponse().getRedirectedUrl()).isEqualTo("/");
    }

    @Test
    void theSignedInSessionIsTheUsersWithThePermissionsItsGroupsConfer() throws Exception {
        String practitioner = provisionInAdminGroup();

        Landing landing = signInFromEpic(practitioner, null);

        assertThat(me(landing.signedIn()))
                .isEqualTo(new Me(practitioner, SUPERUSER_PERMISSIONS));
    }

    /**
     * The authorities themselves — role, baseline and mapped Permissions — not only /me's view.
     * Spring Security's {@code FACTOR_*} authorities are set aside: they record how a credential
     * was proven, which is exactly what differs between the two paths (D15), and an Epic Login
     * must not claim the password factor it never presented.
     */
    @Test
    void anEpicLoginGivesTheSameAuthoritiesAsPasswordLoginForTheSameUser() throws Exception {
        String practitioner = provisionInAdminGroup();
        Set<String> byPassword = grantsOf(logIn(practitioner, null));

        Set<String> byEpic = grantsOf(signInFromEpic(practitioner, null).signedIn());

        assertThat(byEpic).isEqualTo(byPassword);
    }

    @Test
    void theSessionIdIsRotatedBySigningIn() throws Exception {
        String practitioner = practitioners.provision();

        Landing landing = signInFromEpic(practitioner, null);

        assertThat(landing.signedIn().getValue()).isNotEqualTo(landing.launched().getValue());
    }

    @Test
    void theSessionIsIndexedByTheUsersStableId() throws Exception {
        String practitioner = practitioners.provision();

        Landing landing = signInFromEpic(practitioner, null);

        assertThat(sessionRepository.findByPrincipalName(
                practitioners.idOf(practitioner).toString()))
                .containsOnlyKeys(sessionId(landing.signedIn()));
    }

    @Test
    void theSessionRecordsTheRoleMappingItsPermissionsWereResolvedUnder() throws Exception {
        String practitioner = practitioners.provision();

        Landing landing = signInFromEpic(practitioner, null);

        Object recorded = stored(landing.signedIn()).getAttribute(RoleMappingSessions.HASH_ATTRIBUTE);
        assertThat(recorded).isEqualTo(roleMapping.hash());
    }

    /** A token fetched while the launch was pending is the pre-login one, and is refused after. */
    @Test
    void aCsrfTokenFetchedBeforeTheCallbackIsRefusedAfterIt() throws Exception {
        String practitioner = practitioners.provision();
        Launched launched = launch(null);
        Map<String, String> preLoginToken = csrfToken(launched.session());

        Landing landing = complete(practitioner, launched);

        int status = mvc.perform(delete("/api/auth/logout").cookie(landing.signedIn())
                        .header(preLoginToken.get("headerName"), preLoginToken.get("token")))
                .andReturn().getResponse().getStatus();
        assertThat(status).isEqualTo(403);
    }

    // ---- the login decision, recorded exactly as password Login records it -------------------

    @Test
    void aSuccessfulEpicLoginMovesTheDormancyBasis() throws Exception {
        String practitioner = practitioners.provision();
        Instant before = Instant.now();

        signInFromEpic(practitioner, null);

        assertThat(users.findById(practitioners.idOf(practitioner)).orElseThrow()
                .login().lastAuthenticatedAt())
                .isAfterOrEqualTo(before);
    }

    @Test
    void aSuccessfulEpicLoginIsAuditedAsALoginSuccessByMethodSso() throws Exception {
        String practitioner = practitioners.provision();

        signInFromEpic(practitioner, null);

        assertThat(jdbc.queryForList(
                "SELECT login_method FROM audit_events WHERE operation = 'LOGIN_SUCCESS'"
                        + " AND subject_id = ?", String.class, practitioners.idOf(practitioner)))
                .containsExactly("sso");
    }

    /**
     * One session per User across both Login paths: a password Login's session ends when the same
     * User signs in from Epic, and only the Epic session remains.
     */
    @Test
    void anEpicLoginAfterAPasswordLoginEndsThePasswordSession() throws Exception {
        String practitioner = practitioners.provision();
        Cookie byPassword = logIn(practitioner, null);

        Cookie byEpic = signInFromEpic(practitioner, null).signedIn();

        assertThat(status(get("/api/auth/me"), byPassword)).isEqualTo(401);
        assertThat(sessionRepository.findByPrincipalName(
                practitioners.idOf(practitioner).toString()))
                .containsOnlyKeys(sessionId(byEpic));
    }

    /** D9: every launch is a fresh Login, whoever the browser was signed in as. */
    @Test
    void aLaunchWhileAnotherUsersSessionIsPresentReplacesIt() throws Exception {
        String clinician = practitioners.provision();
        String colleague = practitioners.provision();
        Cookie colleaguesSession = logIn(colleague, null);

        Landing landing = signInFromEpic(clinician, colleaguesSession);

        assertThat(status(get("/api/auth/me"), colleaguesSession)).isEqualTo(401);
        assertThat(sessionRepository.findByPrincipalName(
                practitioners.idOf(colleague).toString())).isEmpty();
        assertThat(me(landing.signedIn()).username()).isEqualTo(clinician);
    }

    @Test
    void theEpicLoginCounterRecordsASuccess() throws Exception {
        String practitioner = practitioners.provision();
        double before = successes();

        signInFromEpic(practitioner, null);

        assertThat(successes()).isEqualTo(before + 1);
    }

    /**
     * The accepted record is written once the session is signed in, so it names that session —
     * the one the clinician goes on to use — by its hash, and carries the MFA factor (Logging
     * §2.2, SSO §3.4).
     */
    @Test
    void theAcceptedRecordNamesTheSignedInSessionByHashAndItsMfaFactor() throws Exception {
        String practitioner = practitioners.provision();

        Map<String, Object> accepted;
        try (CapturedLog captured = CapturedLog.attach()) {
            signInFromEpic(practitioner, null);
            accepted = captured.withAction(Level.INFO, LogEvent.ACTION, "user-authentication")
                    .stream()
                    .filter(record -> record.getLevel() == Level.INFO)
                    .map(CapturedLog::fields)
                    .filter(fields -> "sso".equals(fields.get(LogEvent.LOGIN_METHOD)))
                    .collect(Collectors.collectingAndThen(Collectors.toList(),
                            records -> {
                                assertThat(records).as("one accepted record").hasSize(1);
                                return records.getFirst();
                            }));
        }

        Set<String> signedIn = sessionRepository.findByPrincipalName(
                practitioners.idOf(practitioner).toString()).keySet();
        assertThat(signedIn).hasSize(1);
        assertThat(accepted)
                .containsEntry(LogEvent.SESSION_HASH, SessionHash.of(signedIn.iterator().next()))
                .containsEntry(LogEvent.MFA_FACTOR, "idp-attested");
    }

    /** D8: the access token, the id_token and the launch context are used and dropped. */
    @Test
    void nothingFromEpicsTokenResponseIsStored() throws Exception {
        String practitioner = practitioners.provision();

        signInFromEpic(practitioner, null);

        assertThat(epic.idTokens).hasSize(1);
        String everythingInRedis = everythingInRedis();
        assertThat(List.of(epic.accessToken, epic.idTokens.getFirst(), epic.patient,
                        epic.encounter))
                .allSatisfy(value -> assertThat(everythingInRedis).doesNotContain(value));
    }

    // ---- account refusals (flow steps 6 and 8) ------------------------------------------------

    @Test
    void anUnprovisionedPractitionerLandsAtTheRefusedNotice() throws Exception {
        Landing landing = signInFromEpic(unprovisioned(), null);

        assertThat(landing.callback().getResponse().getRedirectedUrl())
                .isEqualTo("/?signin=refused");
    }

    @Test
    void aDeactivatedUserLandsAtTheRefusedNotice() throws Exception {
        String practitioner = practitioners.provisionDeactivated();

        Landing landing = signInFromEpic(practitioner, null);

        assertThat(landing.callback().getResponse().getRedirectedUrl())
                .isEqualTo("/?signin=refused");
    }

    @Test
    void aLockedUserLandsAtTheRefusedNotice() throws Exception {
        String practitioner = practitioners.provisionLocked();

        Landing landing = signInFromEpic(practitioner, null);

        assertThat(landing.callback().getResponse().getRedirectedUrl())
                .isEqualTo("/?signin=refused");
    }

    /** D24: the refused browser is left signed in as nobody — its session is gone. */
    @Test
    void aRefusedLaunchEndsTheSessionTheBrowserHeld() throws Exception {
        Landing landing = signInFromEpic(unprovisioned(), null);

        assertThat(sessionRepository.findById(sessionId(landing.launched()))).isNull();
    }

    /** D24, whoever the session belonged to: a colleague's is not left signed in either. */
    @Test
    void aRefusedLaunchLeavesNoColleagueSignedIn() throws Exception {
        String colleague = practitioners.provision();
        Cookie colleaguesSession = logIn(colleague, null);

        signInFromEpic(unprovisioned(), colleaguesSession);

        assertThat(status(get("/api/auth/me"), colleaguesSession)).isEqualTo(401);
    }

    /** The refusal outlives the login decision's rolled-back transaction. */
    @Test
    void aRefusedDeactivatedUserIsAuditedAsALoginFailureBySsoWithItsReason() throws Exception {
        String practitioner = practitioners.provisionDeactivated();

        signInFromEpic(practitioner, null);

        assertThat(jdbc.queryForList(
                "SELECT error_code || '/' || login_method FROM audit_events"
                        + " WHERE operation = 'LOGIN_FAILURE' AND subject_id = ?",
                String.class, practitioners.idOf(practitioner)))
                .containsExactly("ACCOUNT_DISABLED/sso");
    }

    /** An unknown ID is not recorded: the audit trail holds no trace of the Practitioner ID. */
    @Test
    void anUnknownAccountRefusalRecordsNothingOfThePractitionerId() throws Exception {
        String practitioner = unprovisioned();

        signInFromEpic(practitioner, null);

        assertThat(jdbc.queryForList("SELECT * FROM audit_events").toString())
                .doesNotContain(practitioner);
    }

    @Test
    void theEpicLoginCounterRecordsARefusalWithItsReason() throws Exception {
        String practitioner = practitioners.provisionLocked();
        double before = refusals("ACCOUNT_LOCKED");

        signInFromEpic(practitioner, null);

        assertThat(refusals("ACCOUNT_LOCKED")).isEqualTo(before + 1);
    }

    // ---- Epic unavailable (D23, D24, D26) ------------------------------------------------------

    @Test
    void aTokenEndpoint5xxLandsAtTheUnavailableNotice() throws Exception {
        String practitioner = practitioners.provision();
        epic.failing(FakeEpic.Endpoint.TOKEN, FakeEpic.Failure.SERVER_ERROR);

        Landing landing = signInFromEpic(practitioner, null);

        assertThat(landing.callback().getResponse().getRedirectedUrl())
                .isEqualTo("/?signin=unavailable");
    }

    @Test
    void aTokenEndpointTimeoutLandsAtTheUnavailableNotice() throws Exception {
        String practitioner = practitioners.provision();
        epic.failing(FakeEpic.Endpoint.TOKEN, FakeEpic.Failure.STALL);

        Landing landing = signInFromEpic(practitioner, null);

        assertThat(landing.callback().getResponse().getRedirectedUrl())
                .isEqualTo("/?signin=unavailable");
    }

    /** D26: the code is single-use, so a token call that timed out is not sent again. */
    @Test
    void aTokenEndpointTimeoutMakesExactlyOneTokenRequest() throws Exception {
        String practitioner = practitioners.provision();
        epic.failing(FakeEpic.Endpoint.TOKEN, FakeEpic.Failure.STALL);

        signInFromEpic(practitioner, null);

        assertThat(epic.requests(FakeEpic.Endpoint.TOKEN)).isEqualTo(1);
    }

    @Test
    void aTokenEndpoint5xxMakesExactlyOneTokenRequest() throws Exception {
        String practitioner = practitioners.provision();
        epic.failing(FakeEpic.Endpoint.TOKEN, FakeEpic.Failure.SERVER_ERROR);

        signInFromEpic(practitioner, null);

        assertThat(epic.requests(FakeEpic.Endpoint.TOKEN)).isEqualTo(1);
    }

    /** The fake's new key is not in the kept JWKS, so the id_token sends us to fetch it. */
    @Test
    void aJwks5xxLandsAtTheUnavailableNotice() throws Exception {
        String practitioner = practitioners.provision();
        epic.failing(FakeEpic.Endpoint.JWKS, FakeEpic.Failure.SERVER_ERROR);

        Landing landing = signInFromEpic(practitioner, null);

        assertThat(landing.callback().getResponse().getRedirectedUrl())
                .isEqualTo("/?signin=unavailable");
    }

    @Test
    void aJwksTimeoutLandsAtTheUnavailableNotice() throws Exception {
        String practitioner = practitioners.provision();
        epic.failing(FakeEpic.Endpoint.JWKS, FakeEpic.Failure.STALL);

        Landing landing = signInFromEpic(practitioner, null);

        assertThat(landing.callback().getResponse().getRedirectedUrl())
                .isEqualTo("/?signin=unavailable");
    }

    /** A fetch that failed is Epic unavailable, not a reason to try again within the Login. */
    @Test
    void aJwksFetchThatFailedIsNotRetried() throws Exception {
        String practitioner = practitioners.provision();
        epic.failing(FakeEpic.Endpoint.JWKS, FakeEpic.Failure.SERVER_ERROR);

        signInFromEpic(practitioner, null);

        assertThat(epic.requests(FakeEpic.Endpoint.JWKS)).isEqualTo(1);
    }

    @Test
    void anUnavailableEpicIsAuditedAsALoginFailureBySsoNamingNobody() throws Exception {
        String practitioner = practitioners.provision();
        epic.failing(FakeEpic.Endpoint.TOKEN, FakeEpic.Failure.SERVER_ERROR);
        int before = unavailableFailures();

        signInFromEpic(practitioner, null);

        assertThat(unavailableFailures()).isEqualTo(before + 1);
    }

    /** D24: the launch's session does not outlive a Login Epic could not complete. */
    @Test
    void anUnavailableLaunchEndsTheSessionTheBrowserHeld() throws Exception {
        String practitioner = practitioners.provision();
        epic.failing(FakeEpic.Endpoint.TOKEN, FakeEpic.Failure.STALL);

        Landing landing = signInFromEpic(practitioner, null);

        assertThat(sessionRepository.findById(sessionId(landing.launched()))).isNull();
    }

    @Test
    void anUnavailableLaunchSignsNobodyIn() throws Exception {
        String practitioner = practitioners.provision();
        epic.failing(FakeEpic.Endpoint.TOKEN, FakeEpic.Failure.SERVER_ERROR);

        signInFromEpic(practitioner, null);

        assertThat(sessionRepository.findByPrincipalName(
                practitioners.idOf(practitioner).toString())).isEmpty();
    }

    @Test
    void theEpicLoginCounterRecordsAnUnavailableLaunch() throws Exception {
        String practitioner = practitioners.provision();
        epic.failing(FakeEpic.Endpoint.TOKEN, FakeEpic.Failure.SERVER_ERROR);
        double before = unavailables();

        signInFromEpic(practitioner, null);

        assertThat(unavailables()).isEqualTo(before + 1);
    }

    /**
     * ADR 0013's error categories: a timeout is {@code network}, and Epic being down needs no
     * follow-up. Logging §3.3: it is one {@code ERROR} in all — the outbound call's, which saw
     * it fail — and not a second one when the Login ends for it.
     */
    @Test
    void aTokenEndpointTimeoutIsOneErrorUnderTheNetworkCategory() throws Exception {
        String practitioner = practitioners.provision();
        epic.failing(FakeEpic.Endpoint.TOKEN, FakeEpic.Failure.STALL);

        List<ILoggingEvent> errors;
        try (CapturedLog captured = CapturedLog.attach()) {
            signInFromEpic(practitioner, null);
            errors = captured.withAction(Level.ERROR, LogEvent.ACTION, "user-authentication");
        }

        assertThat(errors).singleElement().satisfies(record -> assertThat(
                        CapturedLog.fields(record))
                .containsEntry(LogEvent.LOCAL_ACTION, "epic.outbound")
                .containsEntry(LogEvent.ERROR_CATEGORY, "network")
                .containsEntry(LogEvent.ERROR_FOLLOW_UP_ACTION, false)
                .containsEntry(LogEvent.EPIC_CALL, "token"));
    }

    @Test
    void aTokenEndpoint5xxIsOneErrorUnderTheServerCategoryWithEpicsStatus() throws Exception {
        String practitioner = practitioners.provision();
        epic.failing(FakeEpic.Endpoint.TOKEN, FakeEpic.Failure.SERVER_ERROR);

        List<Map<String, Object>> errors = signInFailureErrors(practitioner);

        assertThat(errors).singleElement().satisfies(fields -> assertThat(fields)
                .containsEntry(LogEvent.ERROR_CATEGORY, "server")
                .containsEntry(LogEvent.ERROR_CODE, 503)
                .containsEntry(LogEvent.ERROR_FOLLOW_UP_ACTION, false)
                .containsEntry(LogEvent.EPIC_CALL, "token"));
    }

    @Test
    void aJwks5xxIsOneErrorNamingTheJwksCall() throws Exception {
        String practitioner = practitioners.provision();
        epic.failing(FakeEpic.Endpoint.JWKS, FakeEpic.Failure.SERVER_ERROR);

        List<Map<String, Object>> errors = signInFailureErrors(practitioner);

        assertThat(errors).singleElement().satisfies(fields -> assertThat(fields)
                .containsEntry(LogEvent.ERROR_CATEGORY, "server")
                .containsEntry(LogEvent.EPIC_CALL, "jwks"));
    }

    /**
     * ADR 0013's error categories: Epic refusing our assertion is a key or registration
     * problem.
     */
    @Test
    void epicRefusingOurAssertionIsRefused() throws Exception {
        String practitioner = practitioners.provision();
        epic.rejectingOurAssertion();

        Landing landing = completeAllowingRefusal(practitioner, launch(null));

        assertThat(landing.callback().getResponse().getRedirectedUrl())
                .isEqualTo("/?signin=refused");
    }

    @Test
    void epicRefusingOurAssertionIsOneErrorUnderCertAuthNeedingFollowUp() throws Exception {
        String practitioner = practitioners.provision();
        epic.rejectingOurAssertion();

        List<Map<String, Object>> errors;
        try (CapturedLog captured = CapturedLog.attach()) {
            completeAllowingRefusal(practitioner, launch(null));
            errors = signInFailureErrors(captured);
        }

        assertThat(errors).singleElement().satisfies(fields -> assertThat(fields)
                .containsEntry(LogEvent.ERROR_CATEGORY, "cert/auth")
                .containsEntry(LogEvent.ERROR_FOLLOW_UP_ACTION, true)
                .containsEntry(LogEvent.EPIC_CALL, "token"));
    }

    @Test
    void aMalformedJwksIsRefused() throws Exception {
        String practitioner = practitioners.provision();
        epic.failing(FakeEpic.Endpoint.JWKS, FakeEpic.Failure.MALFORMED);

        Landing landing = signInFromEpic(practitioner, null);

        assertThat(landing.callback().getResponse().getRedirectedUrl())
                .isEqualTo("/?signin=refused");
    }

    @Test
    void aMalformedJwksIsOneErrorUnderTheDataCategoryNeedingFollowUp() throws Exception {
        String practitioner = practitioners.provision();
        epic.failing(FakeEpic.Endpoint.JWKS, FakeEpic.Failure.MALFORMED);

        List<Map<String, Object>> errors = signInFailureErrors(practitioner);

        assertThat(errors).singleElement().satisfies(fields -> assertThat(fields)
                .containsEntry(LogEvent.ERROR_CATEGORY, "data")
                .containsEntry(LogEvent.ERROR_FOLLOW_UP_ACTION, true)
                .containsEntry(LogEvent.EPIC_CALL, "jwks"));
    }

    // ---- the outbound client (ADR 0013, D25) ----------------------------------------------------

    @Test
    void eachOutboundCallIsLoggedStartedThenCompletedWithItsName() throws Exception {
        String practitioner = practitioners.provision();

        List<String> logged;
        try (CapturedLog captured = CapturedLog.attach()) {
            signInFromEpic(practitioner, null);
            logged = outbound(captured).stream()
                    .map(record -> CapturedLog.fields(record).get(LogEvent.EPIC_CALL) + " "
                            + record.getMessage())
                    .toList();
        }

        // The JWKS twice: this test's fake signs with a key the kept JWKS has never held.
        assertThat(logged).containsSubsequence(
                "token Epic outbound call started", "token Epic outbound call completed",
                "jwks Epic outbound call started", "jwks Epic outbound call completed");
    }

    @Test
    void aCompletedCallRecordsTheMethodTheUrlWithNoQueryTheStatusAndTheDuration()
            throws Exception {
        String practitioner = practitioners.provision();

        Map<String, Object> jwks;
        try (CapturedLog captured = CapturedLog.attach()) {
            signInFromEpic(practitioner, null);
            jwks = outbound(captured).stream()
                    .filter(record -> "Epic outbound call completed".equals(record.getMessage()))
                    .map(CapturedLog::fields)
                    .filter(fields -> "jwks".equals(fields.get(LogEvent.EPIC_CALL)))
                    .findFirst().orElseThrow();
        }

        assertThat(jwks).containsEntry(LogEvent.HTTP_METHOD, "GET")
                .containsEntry(LogEvent.URL_FULL, epic.issuer() + "/jwks")
                .containsEntry(LogEvent.HTTP_STATUS_CODE, 200)
                .containsKey(LogEvent.DURATION_MS);
    }

    /** The JWKS URI discovery names carries a query, which no record repeats. */
    @Test
    void noOutboundRecordCarriesAQuery() throws Exception {
        String practitioner = practitioners.provision();

        String everything;
        try (CapturedLog captured = CapturedLog.attach()) {
            signInFromEpic(practitioner, null);
            everything = outbound(captured).stream()
                    .map(record -> CapturedLog.fields(record).toString())
                    .collect(Collectors.joining("\n"));
        }

        assertThat(everything).isNotEmpty().doesNotContain(FakeEpic.JWKS_QUERY);
    }

    @Test
    void aCallThatTimedOutIsLoggedAsFailedUnderTheNetworkCategory() throws Exception {
        String practitioner = practitioners.provision();
        epic.failing(FakeEpic.Endpoint.TOKEN, FakeEpic.Failure.STALL);

        Map<String, Object> failed;
        try (CapturedLog captured = CapturedLog.attach()) {
            signInFromEpic(practitioner, null);
            failed = outbound(captured).stream()
                    .filter(record -> record.getLevel() == Level.ERROR)
                    .map(CapturedLog::fields)
                    .findFirst().orElseThrow();
        }

        assertThat(failed).containsEntry(LogEvent.EPIC_CALL, "token")
                .containsEntry(LogEvent.ERROR_CATEGORY, "network")
                .containsKey(LogEvent.DURATION_MS);
    }

    /** Neither a body nor a header reaches the outbound log: not Epic's tokens, not our assertion. */
    @Test
    void noOutboundRecordCarriesWhatEpicSentBack() throws Exception {
        String practitioner = practitioners.provision();

        String everything;
        try (CapturedLog captured = CapturedLog.attach()) {
            signInFromEpic(practitioner, null);
            everything = outbound(captured).stream()
                    .map(record -> record.getFormattedMessage() + CapturedLog.fields(record))
                    .collect(Collectors.joining("\n"));
        }

        assertThat(List.of(epic.accessToken, epic.idTokens.getFirst(), epic.patient))
                .allSatisfy(value -> assertThat(everything).doesNotContain(value));
    }

    /** D25: Epic's side of the call can be joined to ours. */
    @Test
    void theTokenCallCarriesAW3cTraceparent() throws Exception {
        String practitioner = practitioners.provision();

        signInFromEpic(practitioner, null);

        assertThat(epic.tokenRequestHeaders()).singleElement()
                .satisfies(headers -> assertThat(headers.get("traceparent")).singleElement()
                        .asString().matches("00-[0-9a-f]{32}-[0-9a-f]{16}-0[01]"));
    }

    @Test
    void theOutboundTimerCountsTheTokenCall() throws Exception {
        String practitioner = practitioners.provision();
        long before = outboundCalls("token");

        signInFromEpic(practitioner, null);

        assertThat(outboundCalls("token")).isEqualTo(before + 1);
    }

    @Test
    void theOutboundErrorCounterCountsAToken5xxUnderItsCall() throws Exception {
        String practitioner = practitioners.provision();
        epic.failing(FakeEpic.Endpoint.TOKEN, FakeEpic.Failure.SERVER_ERROR);
        double before = outboundErrors("token");

        signInFromEpic(practitioner, null);

        assertThat(outboundErrors("token")).isEqualTo(before + 1);
    }

    @Test
    void theOutboundErrorCounterCountsAJwksTimeoutUnderItsCall() throws Exception {
        String practitioner = practitioners.provision();
        epic.failing(FakeEpic.Endpoint.JWKS, FakeEpic.Failure.STALL);
        double before = outboundErrors("jwks");

        signInFromEpic(practitioner, null);

        assertThat(outboundErrors("jwks")).isEqualTo(before + 1);
    }

    @Test
    void aSuccessfulLoginCountsNoOutboundError() throws Exception {
        String practitioner = practitioners.provision();
        double before = outboundErrors("token") + outboundErrors("jwks");

        signInFromEpic(practitioner, null);

        assertThat(outboundErrors("token") + outboundErrors("jwks")).isEqualTo(before);
    }

    // ---- discovery and Epic's keys, kept (D26) ------------------------------------------------

    @Test
    void aSecondLaunchReadsDiscoveryFromWhatWasKept() throws Exception {
        signInFromEpic(practitioners.provision(), null);
        int before = epic.requests(FakeEpic.Endpoint.DISCOVERY);

        signInFromEpic(practitioners.provision(), null);

        assertThat(epic.requests(FakeEpic.Endpoint.DISCOVERY)).isEqualTo(before);
    }

    @Test
    void aKnownKidIsVerifiedWithTheKeptKeysWithNoFetch() throws Exception {
        signInFromEpic(practitioners.provision(), null);
        int before = epic.requests(FakeEpic.Endpoint.JWKS);

        signInFromEpic(practitioners.provision(), null);

        assertThat(epic.requests(FakeEpic.Endpoint.JWKS)).isEqualTo(before);
    }

    /** D26: a key Epic never publishes is refetched for three times, and then refused. */
    @Test
    void anUnknownKidIsRefetchedThreeTimesBeforeTheRefusal() throws Exception {
        signInFromEpic(practitioners.provision(), null);
        epic.rotateSigningKey(Integer.MAX_VALUE);
        int before = epic.requests(FakeEpic.Endpoint.JWKS);

        Landing landing = signInFromEpic(practitioners.provision(), null);

        assertThat(epic.requests(FakeEpic.Endpoint.JWKS)).isEqualTo(before + 3);
        assertThat(landing.callback().getResponse().getRedirectedUrl())
                .isEqualTo("/?signin=refused");
    }

    @Test
    void theRefetchesWaitOneThenTwoThenFourSeconds() throws Exception {
        signInFromEpic(practitioners.provision(), null);
        epic.rotateSigningKey(Integer.MAX_VALUE);
        PAUSES.clear();

        signInFromEpic(practitioners.provision(), null);

        assertThat(PAUSES).containsExactly(
                Duration.ofSeconds(1), Duration.ofSeconds(2), Duration.ofSeconds(4));
    }

    @Test
    void eachRefetchIsAWarningWithItsAttemptAndTheLastFailureAnError() throws Exception {
        signInFromEpic(practitioners.provision(), null);
        epic.rotateSigningKey(Integer.MAX_VALUE);

        List<String> logged;
        try (CapturedLog captured = CapturedLog.attach()) {
            signInFromEpic(practitioners.provision(), null);
            logged = captured.withAction(Level.WARN, LogEvent.LOCAL_ACTION, "epic.jwks_refetch")
                    .stream()
                    .map(record -> record.getLevel() + " "
                            + CapturedLog.fields(record).get(LogEvent.RETRY_ATTEMPT))
                    .toList();
        }

        assertThat(logged).containsExactly("WARN 1", "WARN 2", "WARN 3", "ERROR null");
    }

    /** D26: still unknown, Epic may have moved its keys, so discovery is read again. */
    @Test
    void aKidStillUnknownAfterTheRefetchesRefetchesDiscovery() throws Exception {
        signInFromEpic(practitioners.provision(), null);
        epic.rotateSigningKey(Integer.MAX_VALUE);
        int before = epic.requests(FakeEpic.Endpoint.DISCOVERY);

        signInFromEpic(practitioners.provision(), null);

        assertThat(epic.requests(FakeEpic.Endpoint.DISCOVERY)).isEqualTo(before + 1);
    }

    /** A rotation Epic publishes a moment late is found by the refetches, and accepted. */
    @Test
    void aRotatedKeyPublishedOnTheSecondRefetchIsAccepted() throws Exception {
        signInFromEpic(practitioners.provision(), null);
        epic.rotateSigningKey(2);
        int before = epic.requests(FakeEpic.Endpoint.JWKS);

        Landing landing = signInFromEpic(practitioners.provision(), null);

        assertThat(landing.callback().getResponse().getRedirectedUrl()).isEqualTo("/");
        assertThat(epic.requests(FakeEpic.Endpoint.JWKS)).isEqualTo(before + 2);
    }

    // ---- the browser --------------------------------------------------------------------------

    /** The launch session, and Epic's authorization URL the backend redirected it to. */
    private record Launched(Cookie session, URI epicAuthorize) {
    }

    /** The callback's answer, the session it signed in, and the launch session before it. */
    private record Landing(MvcResult callback, Cookie signedIn, Cookie launched) {
    }

    /** {@code /api/auth/me}'s account and Permissions. */
    private record Me(String username, List<String> permissions) {
    }

    /** Epic opens the launch URL, from {@code jar} when the browser already holds a session. */
    private Launched launch(Cookie jar) throws Exception {
        MockHttpServletRequestBuilder opened = get("/api/auth/epic/launch")
                .param("iss", FHIR_BASE)
                .param("launch", "launch-context-from-hyperspace");
        if (jar != null) {
            opened.cookie(jar);
        }
        MvcResult launch = mvc.perform(opened).andReturn();
        assertThat(launch.getResponse().getStatus()).as("the launch redirects").isEqualTo(302);
        Cookie session = issuedCookie(launch);
        MvcResult authorize = mvc.perform(
                get(launch.getResponse().getRedirectedUrl()).cookie(session)).andReturn();
        assertThat(authorize.getResponse().getStatus()).as("the authorize hop redirects")
                .isEqualTo(302);
        return new Launched(session, URI.create(authorize.getResponse().getRedirectedUrl()));
    }

    /** The clinician authorizes at Epic as {@code practitioner}, and Epic calls us back. */
    private Landing complete(String practitioner, Launched launched) throws Exception {
        return completeAs(FHIR_BASE + "/Practitioner/" + practitioner, launched);
    }

    /** {@link #complete}, with Epic's {@code id_token} naming the clinician as {@code fhirUser}. */
    private Landing completeAs(String fhirUser, Launched launched) throws Exception {
        Landing landing = completeAllowingRefusalAs(fhirUser, launched);
        assertThat(epic.tokenRefusals()).as("Epic accepted our token request").isEmpty();
        return landing;
    }

    /** {@link #complete}, whether or not Epic's {@code /token} accepts the request. */
    private Landing completeAllowingRefusal(String practitioner, Launched launched)
            throws Exception {
        return completeAllowingRefusalAs(FHIR_BASE + "/Practitioner/" + practitioner, launched);
    }

    private Landing completeAllowingRefusalAs(String fhirUser, Launched launched)
            throws Exception {
        epic.signInAs(fhirUser);
        HttpResponse<Void> atEpic = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(launched.epicAuthorize()).GET().build(),
                HttpResponse.BodyHandlers.discarding());
        URI callback = URI.create(atEpic.headers().firstValue("Location").orElseThrow());
        assertThat(callback.getPath()).isEqualTo("/api/auth/epic/callback");
        // Each parameter by itself: a raw query handed to MockMvc as a URI template would be
        // encoded a second time.
        MockHttpServletRequestBuilder redirected =
                get(callback.getPath()).cookie(launched.session());
        FakeEpic.queryOf(callback).forEach(redirected::param);
        MvcResult result = mvc.perform(redirected).andReturn();
        Cookie signedIn = result.getResponse().getCookie(sessionCookieName);
        return new Landing(result,
                signedIn == null ? null : new Cookie(signedIn.getName(), signedIn.getValue()),
                launched.session());
    }

    private Landing signInFromEpic(String practitioner, Cookie jar) throws Exception {
        return complete(practitioner, launch(jar));
    }

    // ---- helpers ------------------------------------------------------------------------------

    /**
     * {@link EpicPractitioners#provision()}, and a member of the Admin group, so its
     * Permissions are mapped.
     */
    private String provisionInAdminGroup() {
        String practitioner = practitioners.provision();
        jdbc.update("INSERT INTO scim_group_members (group_id, user_id) VALUES (?, ?)",
                ADMIN_GROUP, practitioners.idOf(practitioner));
        return practitioner;
    }

    /** A password Login, from {@code jar} when there is one; the signed-in session cookie. */
    private Cookie logIn(String userName, Cookie jar) throws Exception {
        MockHttpServletRequestBuilder request = SessionCsrf.withCsrf(mvc, post("/api/auth/login"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"%s\",\"password\":\"%s\"}".formatted(userName, PASSWORD));
        if (jar != null) {
            request.cookie(jar);
        }
        MvcResult login = mvc.perform(request).andReturn();
        assertThat(login.getResponse().getStatus()).isEqualTo(200);
        return issuedCookie(login);
    }

    private Cookie issuedCookie(MvcResult result) {
        Cookie issued = result.getResponse().getCookie(sessionCookieName);
        assertThat(issued).as("a session cookie was issued").isNotNull();
        return new Cookie(issued.getName(), issued.getValue());
    }

    private Me me(Cookie session) throws Exception {
        MvcResult me = mvc.perform(get("/api/auth/me").cookie(session)).andReturn();
        assertThat(me.getResponse().getStatus()).isEqualTo(200);
        tools.jackson.databind.JsonNode body = tools.jackson.databind.json.JsonMapper.builder()
                .build().readTree(me.getResponse().getContentAsString());
        List<String> permissions = new ArrayList<>();
        body.get("permissions").forEach(permission -> permissions.add(permission.asText()));
        return new Me(body.get("username").asText(), permissions);
    }

    private Map<String, String> csrfToken(Cookie session) throws Exception {
        String body = mvc.perform(get(SessionCsrf.PATH).cookie(session)).andReturn()
                .getResponse().getContentAsString();
        tools.jackson.databind.JsonNode token =
                tools.jackson.databind.json.JsonMapper.builder().build().readTree(body);
        return Map.of("headerName", token.get("headerName").asText(),
                "token", token.get("token").asText());
    }

    private int status(MockHttpServletRequestBuilder request, Cookie session) throws Exception {
        return mvc.perform(request.cookie(session)).andReturn().getResponse().getStatus();
    }

    private Session stored(Cookie session) {
        Session stored = sessionRepository.findById(sessionId(session));
        assertThat(stored).as("the session is in the store").isNotNull();
        return stored;
    }

    /** The session's authorities that grant something: every one but the {@code FACTOR_*}. */
    private Set<String> grantsOf(Cookie session) {
        SecurityContext security = stored(session).getAttribute("SPRING_SECURITY_CONTEXT");
        return security.getAuthentication().getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .filter(authority -> !authority.startsWith("FACTOR_"))
                .collect(Collectors.toSet());
    }

    private double successes() {
        Counter counter = meters.find("epic.login").tag("outcome", "success").counter();
        return counter == null ? 0 : counter.count();
    }

    private double unavailables() {
        Counter counter = meters.find("epic.login").tag("outcome", "unavailable")
                .tag("reason", "EPIC_UNAVAILABLE").counter();
        return counter == null ? 0 : counter.count();
    }

    private long outboundCalls(String call) {
        Timer timer = meters.find("epic.outbound").tag("call", call).timer();
        return timer == null ? 0 : timer.count();
    }

    private double outboundErrors(String call) {
        Counter counter = meters.find("epic.outbound.errors").tag("call", call).counter();
        return counter == null ? 0 : counter.count();
    }

    /** Every Epic {@code EPIC_UNAVAILABLE} failure the audit trail holds, each naming nobody. */
    private int unavailableFailures() {
        return jdbc.queryForObject("""
                SELECT count(*) FROM audit_events
                WHERE operation = 'LOGIN_FAILURE' AND error_code = 'EPIC_UNAVAILABLE'
                AND login_method = 'sso' AND subject_id IS NULL""", Integer.class);
    }

    /** The fields of each {@code ERROR} the Epic sign-in failure wrote while signing in. */
    private List<Map<String, Object>> signInFailureErrors(String practitioner) throws Exception {
        try (CapturedLog captured = CapturedLog.attach()) {
            signInFromEpic(practitioner, null);
            return signInFailureErrors(captured);
        }
    }

    private static List<Map<String, Object>> signInFailureErrors(CapturedLog captured) {
        return captured.withAction(Level.ERROR, LogEvent.LOCAL_ACTION, "epic.login").stream()
                .map(CapturedLog::fields)
                .toList();
    }

    /** Every outbound call record, in order. */
    private static List<ILoggingEvent> outbound(CapturedLog captured) {
        return captured.withAction(Level.INFO, LogEvent.LOCAL_ACTION, "epic.outbound");
    }

    private double refusals(String reason) {
        Counter counter = meters.find("epic.login").tag("outcome", "refused")
                .tag("reason", reason).counter();
        return counter == null ? 0 : counter.count();
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

    /** The id a session cookie names in the store: Spring Session writes it Base64-encoded. */
    private static String sessionId(Cookie session) {
        return new String(Base64.getDecoder().decode(session.getValue()), StandardCharsets.UTF_8);
    }
}

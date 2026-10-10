package com.example.backend.auth.epic;

import static com.example.backend.auth.epic.EpicTestEnvironment.sessionId;
import static com.example.backend.auth.epic.EpicPractitioners.unprovisioned;
import static com.example.backend.auth.epic.EpicTestEnvironment.CLIENT_ID;
import static com.example.backend.auth.epic.EpicTestEnvironment.FHIR_BASE;
import static com.example.backend.auth.epic.EpicTestEnvironment.REDIRECT_URI;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import com.example.backend.ContainerTestConfiguration;
import com.example.backend.SessionCsrf;
import com.example.backend.audit.CapturedLog;
import com.example.backend.auth.application.IdentityAdministrationService;
import com.example.backend.auth.domain.RoleMappingSessions;
import com.example.backend.auth.epic.EpicBrowser.Landing;
import com.example.backend.auth.epic.EpicBrowser.Launched;
import com.example.backend.authorization.domain.RoleMapping;
import com.example.backend.observability.LogEvent;
import com.example.backend.observability.SessionHash;
import com.example.backend.scim.domain.ScimLoginState;
import com.example.backend.scim.domain.ScimUserRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.servlet.http.Cookie;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.session.FindByIndexNameSessionRepository;
import org.springframework.session.Session;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.convention.TestBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * Epic Login's happy path end to end (ADR 0013, flow steps 1–7): a clinician
 * whose Practitioner FHIR ID is the {@code userName} of a provisioned User opens the application
 * from Epic and is signed in exactly as password Login would sign them in.
 *
 * <p>Epic is a {@link FakeEpic} the {@link EpicTestEnvironment} starts for each test, which
 * verifies our client assertion against our own published JWKS and mints the {@code id_token} the
 * test names. The browser is an {@link EpicBrowser}: each redirect is followed by hand, our routes
 * through MockMvc over the real filter chain and a real, indexed Redis session store, and Epic's
 * {@code /authorize} over HTTP. Every Login here holds Epic to having accepted our token request
 * unless the test is about Epic refusing it, so no outcome can pass on a refusal of ours.
 *
 * <p>The {@code dev} profile lets the fake be plain {@code http} on the loopback interface (D21);
 * nothing else in this context depends on it.
 *
 * <p>Epic on a bad day is the same fake told to stall, answer {@code 5xx} or rotate its key
 * (D23, D24, D26). The outbound timeouts are 3 seconds here, well short of the fake's 8-second
 * stall; the waits before D26's JWKS refetches are recorded rather than slept. Discovery and
 * Epic's keys are kept across tests, as they are across Logins: each test's fake signs with a key
 * of its own, so a test's first Login finds its key by a refetch, and a test about the JWKS counts
 * from a Login that has already found it. Discovery failing is
 * {@link EpicDiscoveryIntegrationTests}' subject, in a context whose discovery has never
 * succeeded.
 */
@SpringBootTest
@ActiveProfiles("dev")
@Import(ContainerTestConfiguration.class)
class EpicLoginIntegrationTests {

    private static final String PASSWORD = EpicPractitioners.PASSWORD;

    /** The Admin group, which the test role mapping maps to the Superuser Role. */
    private static final UUID ADMIN_GROUP = UUID.fromString("00000000-0000-4000-8000-00000000a001");

    /** Every Permission the Superuser Role holds, sorted as {@code /api/auth/me} reports them. */
    private static final List<String> SUPERUSER_PERMISSIONS = List.of(
            "audit:read", "connector:read", "connector:token", "connector:write", "counter:read",
            "counter:write", "group:read", "group:write", "ops:read", "user:read", "user:write");

    /** When the change-required flag of a User provisioned flagged was set. */
    private static final Instant FLAGGED_SINCE = Instant.parse("2026-10-01T09:00:00Z");

    /** The test profile's Bootstrap Admin, acting as the administrator of a forced change. */
    private static final String BOOTSTRAP_ADMIN = "test-admin";

    /** The test profile's {@code app.lockout.max-attempts}. */
    private static final int LOCKOUT_THRESHOLD = 3;

    /** One for the class, so the issuer its context is configured with names each test's fake. */
    @RegisterExtension
    static final EpicTestEnvironment EPIC = EpicTestEnvironment.epicLoginOn();

    @DynamicPropertySource
    static void epicLoginOn(DynamicPropertyRegistry registry) {
        EPIC.register(registry);
    }

    @Autowired
    private ScimUserRepository users;

    @Autowired
    private IdentityAdministrationService administration;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private FindByIndexNameSessionRepository<? extends Session> sessionRepository;

    @Autowired
    private RedisConnectionFactory redis;

    @Autowired
    private RoleMapping roleMapping;

    @Autowired
    private MeterRegistry meters;

    @Value("${server.servlet.session.cookie.name:SESSION}")
    private String sessionCookieName;

    /** Every wait before a D26 JWKS refetch, in order, since the test began. */
    private static final List<Duration> PAUSES = Collections.synchronizedList(new ArrayList<>());

    @TestBean
    private EpicRetryPause epicRetryPause;

    static EpicRetryPause epicRetryPause() {
        return PAUSES::add;
    }

    @BeforeEach
    void setUp() {
        PAUSES.clear();
    }

    // ---- the launch and the authorize redirect ------------------------------------------------

    /** Flow step 2: everything Epic's authorization endpoint is sent, and nothing to spare. */
    @Test
    void theAuthorizeRedirectCarriesTheLaunchTheAudienceTheScopeAndPkce() throws Exception {
        URI authorize = EPIC.browser().launch(null).epicAuthorize();

        Map<String, String> sent = FakeEpic.queryOf(authorize);
        assertThat(authorize.toString()).startsWith(EPIC.fake().issuer() + "/authorize?");
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
        String practitioner = EPIC.practitioners().provision();

        Landing landing = EPIC.signInFromEpic(practitioner, null);

        assertThat(landing.callback().getResponse().getStatus()).isEqualTo(302);
        assertThat(landing.callback().getResponse().getRedirectedUrl()).isEqualTo("/");
    }

    /**
     * The dev profile alone also accepts the relative {@code Practitioner/{id}} the local SMART
     * launcher issues as {@code fhirUser}; this context runs in it.
     */
    @Test
    void inTheDevProfileARelativeFhirUserSignsThePractitionerIn() throws Exception {
        String practitioner = EPIC.practitioners().provision();

        Landing landing = EPIC.browser().completeAccepted(
                "Practitioner/" + practitioner, EPIC.browser().launch(null));

        assertThat(landing.callback().getResponse().getRedirectedUrl()).isEqualTo("/");
    }

    @Test
    void theSignedInSessionIsTheUsersWithThePermissionsItsGroupsConfer() throws Exception {
        String practitioner = provisionInAdminGroup();

        Landing landing = EPIC.signInFromEpic(practitioner, null);

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

        Set<String> byEpic = grantsOf(EPIC.signInFromEpic(practitioner, null).signedIn());

        assertThat(byEpic).isEqualTo(byPassword);
    }

    @Test
    void theSessionIdIsRotatedBySigningIn() throws Exception {
        String practitioner = EPIC.practitioners().provision();

        Landing landing = EPIC.signInFromEpic(practitioner, null);

        assertThat(landing.signedIn().getValue()).isNotEqualTo(landing.launched().getValue());
    }

    @Test
    void theSessionIsIndexedByTheUsersStableId() throws Exception {
        String practitioner = EPIC.practitioners().provision();

        Landing landing = EPIC.signInFromEpic(practitioner, null);

        assertThat(sessionRepository.findByPrincipalName(
                EPIC.practitioners().idOf(practitioner).toString()))
                .containsOnlyKeys(sessionId(landing.signedIn()));
    }

    @Test
    void theSessionRecordsTheRoleMappingItsPermissionsWereResolvedUnder() throws Exception {
        String practitioner = EPIC.practitioners().provision();

        Landing landing = EPIC.signInFromEpic(practitioner, null);

        Object recorded = stored(landing.signedIn()).getAttribute(RoleMappingSessions.HASH_ATTRIBUTE);
        assertThat(recorded).isEqualTo(roleMapping.hash());
    }

    /** A token fetched while the launch was pending is the pre-login one, and is refused after. */
    @Test
    void aCsrfTokenFetchedBeforeTheCallbackIsRefusedAfterIt() throws Exception {
        String practitioner = EPIC.practitioners().provision();
        Launched launched = EPIC.browser().launch(null);
        Map<String, String> preLoginToken = csrfToken(launched.session());

        Landing landing = EPIC.completeFromEpic(practitioner, launched);

        int status = EPIC.mvc().perform(delete("/api/auth/logout").cookie(landing.signedIn())
                        .header(preLoginToken.get("headerName"), preLoginToken.get("token")))
                .andReturn().getResponse().getStatus();
        assertThat(status).isEqualTo(403);
    }

    // ---- the login decision, recorded exactly as password Login records it -------------------

    @Test
    void aSuccessfulEpicLoginMovesTheDormancyBasis() throws Exception {
        String practitioner = EPIC.practitioners().provision();
        Instant before = Instant.now();

        EPIC.signInFromEpic(practitioner, null);

        assertThat(loginStateOf(practitioner).lastAuthenticatedAt())
                .isAfterOrEqualTo(before);
    }

    @Test
    void aSuccessfulEpicLoginIsAuditedAsALoginSuccessByMethodSso() throws Exception {
        String practitioner = EPIC.practitioners().provision();

        EPIC.signInFromEpic(practitioner, null);

        assertThat(jdbc.queryForList(
                "SELECT login_method FROM audit_events WHERE operation = 'LOGIN_SUCCESS'"
                        + " AND subject_id = ?",
                String.class, EPIC.practitioners().idOf(practitioner)))
                .containsExactly("sso");
    }

    /**
     * One session per User across both Login paths: a password Login's session ends when the same
     * User signs in from Epic, and only the Epic session remains.
     */
    @Test
    void anEpicLoginAfterAPasswordLoginEndsThePasswordSession() throws Exception {
        String practitioner = EPIC.practitioners().provision();
        Cookie byPassword = logIn(practitioner, null);

        Cookie byEpic = EPIC.signInFromEpic(practitioner, null).signedIn();

        assertThat(status(get("/api/auth/me"), byPassword)).isEqualTo(401);
        assertThat(sessionRepository.findByPrincipalName(
                EPIC.practitioners().idOf(practitioner).toString()))
                .containsOnlyKeys(sessionId(byEpic));
    }

    // ---- the change-required flag confines only a password Login ------------------------------

    /**
     * An Epic Login presents no password of ours (D20), so a flagged User signed in through Epic
     * holds what an unflagged one does: {@code ROLE_USER}, the baseline and its Groups'
     * Permissions.
     */
    @Test
    void anEpicLoginOfAUserWithTheChangeRequiredFlagHoldsItsGroupsPermissions() throws Exception {
        String practitioner = provisionFlaggedInAdminGroup();

        Landing landing = EPIC.signInFromEpic(practitioner, null);

        assertThat(me(landing.signedIn()))
                .isEqualTo(new Me(practitioner, SUPERUSER_PERMISSIONS));
    }

    /** {@code /me} reports the session's confinement, and an Epic session is not confined. */
    @Test
    void anEpicLoginOfAUserWithTheChangeRequiredFlagIsNotAConfinedSession() throws Exception {
        String practitioner = EPIC.practitioners().provisionRequiredToChangePassword(FLAGGED_SINCE);

        Landing landing = EPIC.signInFromEpic(practitioner, null);

        assertThat(meBody(landing.signedIn()).get("passwordChangeRequired").asBoolean()).isFalse();
    }

    /** The same flagged User's password Login is still confined to the change and logout. */
    @Test
    void aPasswordLoginOfAUserWithTheChangeRequiredFlagIsStillConfined() throws Exception {
        String practitioner = provisionFlaggedInAdminGroup();
        EPIC.signInFromEpic(practitioner, null);

        Cookie byPassword = logIn(practitioner, null);

        assertThat(status(get("/api/admin/roles"), byPassword)).isEqualTo(403);
    }

    /** An Epic Login neither reads the flag into the session nor clears it. */
    @Test
    void anEpicLoginLeavesTheChangeRequiredFlagSet() throws Exception {
        String practitioner = EPIC.practitioners().provisionRequiredToChangePassword(FLAGGED_SINCE);

        EPIC.signInFromEpic(practitioner, null);

        assertThat(loginStateOf(practitioner).passwordChangeRequiredSince())
                .isEqualTo(FLAGGED_SINCE);
    }

    /** An unconfined Epic Login is real use of the account, so it moves the dormancy basis. */
    @Test
    void anEpicLoginOfAUserWithTheChangeRequiredFlagMovesTheDormancyBasis() throws Exception {
        String practitioner = EPIC.practitioners().provisionRequiredToChangePassword(FLAGGED_SINCE);
        Instant before = Instant.now();

        EPIC.signInFromEpic(practitioner, null);

        assertThat(loginStateOf(practitioner).lastAuthenticatedAt())
                .isAfterOrEqualTo(before);
    }

    /** A forced password change ends every session the User holds, an Epic one included. */
    @Test
    void aForcedPasswordChangeEndsTheUsersEpicSession() throws Exception {
        String practitioner = EPIC.practitioners().provision();
        Cookie byEpic = EPIC.signInFromEpic(practitioner, null).signedIn();

        administration.forcePasswordChange(
                EPIC.practitioners().idOf(practitioner), BOOTSTRAP_ADMIN);

        assertThat(status(get("/api/auth/me"), byEpic)).isEqualTo(401);
    }

    /**
     * A lockout imposed by password failures ends a flagged User's Epic session, as it ends any
     * session — the revocation an Unlock then relies on, since a locked User holds none.
     */
    @Test
    void aLockoutEndsAFlaggedUsersEpicSession() throws Exception {
        String practitioner = EPIC.practitioners().provisionRequiredToChangePassword(FLAGGED_SINCE);
        Cookie byEpic = EPIC.signInFromEpic(practitioner, null).signedIn();

        for (int attempt = 0; attempt < LOCKOUT_THRESHOLD; attempt++) {
            assertThat(failedLogIn(practitioner)).isEqualTo(401);
        }

        assertThat(status(get("/api/auth/me"), byEpic)).isEqualTo(401);
    }

    /** D9: every launch is a fresh Login, whoever the browser was signed in as. */
    @Test
    void aLaunchWhileAnotherUsersSessionIsPresentReplacesIt() throws Exception {
        String clinician = EPIC.practitioners().provision();
        String colleague = EPIC.practitioners().provision();
        Cookie colleaguesSession = logIn(colleague, null);

        Landing landing = EPIC.signInFromEpic(clinician, colleaguesSession);

        assertThat(status(get("/api/auth/me"), colleaguesSession)).isEqualTo(401);
        assertThat(sessionRepository.findByPrincipalName(
                EPIC.practitioners().idOf(colleague).toString())).isEmpty();
        assertThat(me(landing.signedIn()).username()).isEqualTo(clinician);
    }

    @Test
    void theEpicLoginCounterRecordsASuccess() throws Exception {
        String practitioner = EPIC.practitioners().provision();
        double before = successes();

        EPIC.signInFromEpic(practitioner, null);

        assertThat(successes()).isEqualTo(before + 1);
    }

    /**
     * The accepted record is written once the session is signed in, so it names that session —
     * the one the clinician goes on to use — by its hash, and carries the MFA factor (Logging
     * §2.2, SSO §3.4).
     */
    @Test
    void theAcceptedRecordNamesTheSignedInSessionByHashAndItsMfaFactor() throws Exception {
        String practitioner = EPIC.practitioners().provision();

        Map<String, Object> accepted;
        try (CapturedLog captured = CapturedLog.attach()) {
            EPIC.signInFromEpic(practitioner, null);
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
                EPIC.practitioners().idOf(practitioner).toString()).keySet();
        assertThat(signedIn).hasSize(1);
        assertThat(accepted)
                .containsEntry(LogEvent.SESSION_HASH, SessionHash.of(signedIn.iterator().next()))
                .containsEntry(LogEvent.MFA_FACTOR, "idp-attested");
    }

    /**
     * What D8 still drops: the token response's patient and encounter launch context. Epic's
     * three tokens are kept for the signed-in session (ADR 0013, D29), which
     * {@link EpicTokensIntegrationTests} holds.
     */
    @Test
    void theLaunchContextFromEpicsTokenResponseIsNotStored() throws Exception {
        String practitioner = EPIC.practitioners().provision();

        EPIC.signInFromEpic(practitioner, null);

        FakeEpic epic = EPIC.fake();
        assertThat(epic.idTokens).hasSize(1);
        String everythingInRedis = everythingInRedis();
        assertThat(List.of(epic.patient, epic.encounter))
                .allSatisfy(value -> assertThat(everythingInRedis).doesNotContain(value));
    }

    // ---- account refusals (flow steps 6 and 8) ------------------------------------------------

    @Test
    void anUnprovisionedPractitionerLandsAtTheRefusedNotice() throws Exception {
        Landing landing = EPIC.signInFromEpic(unprovisioned(), null);

        assertThat(landing.callback().getResponse().getRedirectedUrl())
                .isEqualTo("/?signin=refused");
    }

    @Test
    void aDeactivatedUserLandsAtTheRefusedNotice() throws Exception {
        String practitioner = EPIC.practitioners().provisionDeactivated();

        Landing landing = EPIC.signInFromEpic(practitioner, null);

        assertThat(landing.callback().getResponse().getRedirectedUrl())
                .isEqualTo("/?signin=refused");
    }

    @Test
    void aLockedUserLandsAtTheRefusedNotice() throws Exception {
        String practitioner = EPIC.practitioners().provisionLocked();

        Landing landing = EPIC.signInFromEpic(practitioner, null);

        assertThat(landing.callback().getResponse().getRedirectedUrl())
                .isEqualTo("/?signin=refused");
    }

    /** D24: the refused browser is left signed in as nobody — its session is gone. */
    @Test
    void aRefusedLaunchEndsTheSessionTheBrowserHeld() throws Exception {
        Landing landing = EPIC.signInFromEpic(unprovisioned(), null);

        assertThat(sessionRepository.findById(sessionId(landing.launched()))).isNull();
    }

    /** D24, whoever the session belonged to: a colleague's is not left signed in either. */
    @Test
    void aRefusedLaunchLeavesNoColleagueSignedIn() throws Exception {
        String colleague = EPIC.practitioners().provision();
        Cookie colleaguesSession = logIn(colleague, null);

        EPIC.signInFromEpic(unprovisioned(), colleaguesSession);

        assertThat(status(get("/api/auth/me"), colleaguesSession)).isEqualTo(401);
    }

    /** The refusal outlives the login decision's rolled-back transaction. */
    @Test
    void aRefusedDeactivatedUserIsAuditedAsALoginFailureBySsoWithItsReason() throws Exception {
        String practitioner = EPIC.practitioners().provisionDeactivated();

        EPIC.signInFromEpic(practitioner, null);

        assertThat(jdbc.queryForList(
                "SELECT error_code || '/' || login_method FROM audit_events"
                        + " WHERE operation = 'LOGIN_FAILURE' AND subject_id = ?",
                String.class, EPIC.practitioners().idOf(practitioner)))
                .containsExactly("ACCOUNT_DISABLED/sso");
    }

    /** An unknown ID is not recorded: the audit trail holds no trace of the Practitioner ID. */
    @Test
    void anUnknownAccountRefusalRecordsNothingOfThePractitionerId() throws Exception {
        String practitioner = unprovisioned();

        EPIC.signInFromEpic(practitioner, null);

        assertThat(jdbc.queryForList("SELECT * FROM audit_events").toString())
                .doesNotContain(practitioner);
    }

    @Test
    void theEpicLoginCounterRecordsARefusalWithItsReason() throws Exception {
        String practitioner = EPIC.practitioners().provisionLocked();
        double before = refusals("ACCOUNT_LOCKED");

        EPIC.signInFromEpic(practitioner, null);

        assertThat(refusals("ACCOUNT_LOCKED")).isEqualTo(before + 1);
    }

    // ---- Epic unavailable (D23, D24, D26) ------------------------------------------------------

    @Test
    void aTokenEndpoint5xxLandsAtTheUnavailableNotice() throws Exception {
        String practitioner = EPIC.practitioners().provision();
        EPIC.fake().failing(FakeEpic.Endpoint.TOKEN, FakeEpic.Failure.SERVER_ERROR);

        Landing landing = EPIC.signInFromEpic(practitioner, null);

        assertThat(landing.callback().getResponse().getRedirectedUrl())
                .isEqualTo("/?signin=unavailable");
    }

    @Test
    void aTokenEndpointTimeoutLandsAtTheUnavailableNotice() throws Exception {
        String practitioner = EPIC.practitioners().provision();
        EPIC.fake().failing(FakeEpic.Endpoint.TOKEN, FakeEpic.Failure.STALL);

        Landing landing = EPIC.signInFromEpic(practitioner, null);

        assertThat(landing.callback().getResponse().getRedirectedUrl())
                .isEqualTo("/?signin=unavailable");
    }

    /** D26: the code is single-use, so a token call that timed out is not sent again. */
    @Test
    void aTokenEndpointTimeoutMakesExactlyOneTokenRequest() throws Exception {
        String practitioner = EPIC.practitioners().provision();
        EPIC.fake().failing(FakeEpic.Endpoint.TOKEN, FakeEpic.Failure.STALL);

        EPIC.signInFromEpic(practitioner, null);

        assertThat(EPIC.fake().requests(FakeEpic.Endpoint.TOKEN)).isEqualTo(1);
    }

    @Test
    void aTokenEndpoint5xxMakesExactlyOneTokenRequest() throws Exception {
        String practitioner = EPIC.practitioners().provision();
        EPIC.fake().failing(FakeEpic.Endpoint.TOKEN, FakeEpic.Failure.SERVER_ERROR);

        EPIC.signInFromEpic(practitioner, null);

        assertThat(EPIC.fake().requests(FakeEpic.Endpoint.TOKEN)).isEqualTo(1);
    }

    /** The fake's new key is not in the kept JWKS, so the id_token sends us to fetch it. */
    @Test
    void aJwks5xxLandsAtTheUnavailableNotice() throws Exception {
        String practitioner = EPIC.practitioners().provision();
        EPIC.fake().failing(FakeEpic.Endpoint.JWKS, FakeEpic.Failure.SERVER_ERROR);

        Landing landing = EPIC.signInFromEpic(practitioner, null);

        assertThat(landing.callback().getResponse().getRedirectedUrl())
                .isEqualTo("/?signin=unavailable");
    }

    @Test
    void aJwksTimeoutLandsAtTheUnavailableNotice() throws Exception {
        String practitioner = EPIC.practitioners().provision();
        EPIC.fake().failing(FakeEpic.Endpoint.JWKS, FakeEpic.Failure.STALL);

        Landing landing = EPIC.signInFromEpic(practitioner, null);

        assertThat(landing.callback().getResponse().getRedirectedUrl())
                .isEqualTo("/?signin=unavailable");
    }

    /** A fetch that failed is Epic unavailable, not a reason to try again within the Login. */
    @Test
    void aJwksFetchThatFailedIsNotRetried() throws Exception {
        String practitioner = EPIC.practitioners().provision();
        EPIC.fake().failing(FakeEpic.Endpoint.JWKS, FakeEpic.Failure.SERVER_ERROR);

        EPIC.signInFromEpic(practitioner, null);

        assertThat(EPIC.fake().requests(FakeEpic.Endpoint.JWKS)).isEqualTo(1);
    }

    @Test
    void anUnavailableEpicIsAuditedAsALoginFailureBySsoNamingNobody() throws Exception {
        String practitioner = EPIC.practitioners().provision();
        EPIC.fake().failing(FakeEpic.Endpoint.TOKEN, FakeEpic.Failure.SERVER_ERROR);
        int before = unavailableFailures();

        EPIC.signInFromEpic(practitioner, null);

        assertThat(unavailableFailures()).isEqualTo(before + 1);
    }

    /** D24: the launch's session does not outlive a Login Epic could not complete. */
    @Test
    void anUnavailableLaunchEndsTheSessionTheBrowserHeld() throws Exception {
        String practitioner = EPIC.practitioners().provision();
        EPIC.fake().failing(FakeEpic.Endpoint.TOKEN, FakeEpic.Failure.STALL);

        Landing landing = EPIC.signInFromEpic(practitioner, null);

        assertThat(sessionRepository.findById(sessionId(landing.launched()))).isNull();
    }

    @Test
    void anUnavailableLaunchSignsNobodyIn() throws Exception {
        String practitioner = EPIC.practitioners().provision();
        EPIC.fake().failing(FakeEpic.Endpoint.TOKEN, FakeEpic.Failure.SERVER_ERROR);

        EPIC.signInFromEpic(practitioner, null);

        assertThat(sessionRepository.findByPrincipalName(
                EPIC.practitioners().idOf(practitioner).toString())).isEmpty();
    }

    @Test
    void theEpicLoginCounterRecordsAnUnavailableLaunch() throws Exception {
        String practitioner = EPIC.practitioners().provision();
        EPIC.fake().failing(FakeEpic.Endpoint.TOKEN, FakeEpic.Failure.SERVER_ERROR);
        double before = unavailables();

        EPIC.signInFromEpic(practitioner, null);

        assertThat(unavailables()).isEqualTo(before + 1);
    }

    /**
     * ADR 0013's error categories: a timeout is {@code network}, and Epic being down needs no
     * follow-up. Logging §3.3: it is one {@code ERROR} in all — the outbound call's, which saw
     * it fail — and not a second one when the Login ends for it.
     */
    @Test
    void aTokenEndpointTimeoutIsOneErrorUnderTheNetworkCategory() throws Exception {
        String practitioner = EPIC.practitioners().provision();
        EPIC.fake().failing(FakeEpic.Endpoint.TOKEN, FakeEpic.Failure.STALL);

        List<ILoggingEvent> errors;
        try (CapturedLog captured = CapturedLog.attach()) {
            EPIC.signInFromEpic(practitioner, null);
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
        String practitioner = EPIC.practitioners().provision();
        EPIC.fake().failing(FakeEpic.Endpoint.TOKEN, FakeEpic.Failure.SERVER_ERROR);

        List<Map<String, Object>> errors = signInFailureErrors(practitioner);

        assertThat(errors).singleElement().satisfies(fields -> assertThat(fields)
                .containsEntry(LogEvent.ERROR_CATEGORY, "server")
                .containsEntry(LogEvent.ERROR_CODE, 503)
                .containsEntry(LogEvent.ERROR_FOLLOW_UP_ACTION, false)
                .containsEntry(LogEvent.EPIC_CALL, "token"));
    }

    @Test
    void aJwks5xxIsOneErrorNamingTheJwksCall() throws Exception {
        String practitioner = EPIC.practitioners().provision();
        EPIC.fake().failing(FakeEpic.Endpoint.JWKS, FakeEpic.Failure.SERVER_ERROR);

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
        String practitioner = EPIC.practitioners().provision();
        EPIC.fake().rejectingOurAssertion();

        Landing landing = EPIC.signIn(practitioner, null);

        assertThat(landing.callback().getResponse().getRedirectedUrl())
                .isEqualTo("/?signin=refused");
    }

    @Test
    void epicRefusingOurAssertionIsOneErrorUnderCertAuthNeedingFollowUp() throws Exception {
        String practitioner = EPIC.practitioners().provision();
        EPIC.fake().rejectingOurAssertion();

        List<Map<String, Object>> errors;
        try (CapturedLog captured = CapturedLog.attach()) {
            EPIC.signIn(practitioner, null);
            errors = signInFailureErrors(captured);
        }

        assertThat(errors).singleElement().satisfies(fields -> assertThat(fields)
                .containsEntry(LogEvent.ERROR_CATEGORY, "cert/auth")
                .containsEntry(LogEvent.ERROR_FOLLOW_UP_ACTION, true)
                .containsEntry(LogEvent.EPIC_CALL, "token"));
    }

    @Test
    void aMalformedJwksIsRefused() throws Exception {
        String practitioner = EPIC.practitioners().provision();
        EPIC.fake().failing(FakeEpic.Endpoint.JWKS, FakeEpic.Failure.MALFORMED);

        Landing landing = EPIC.signInFromEpic(practitioner, null);

        assertThat(landing.callback().getResponse().getRedirectedUrl())
                .isEqualTo("/?signin=refused");
    }

    @Test
    void aMalformedJwksIsOneErrorUnderTheDataCategoryNeedingFollowUp() throws Exception {
        String practitioner = EPIC.practitioners().provision();
        EPIC.fake().failing(FakeEpic.Endpoint.JWKS, FakeEpic.Failure.MALFORMED);

        List<Map<String, Object>> errors = signInFailureErrors(practitioner);

        assertThat(errors).singleElement().satisfies(fields -> assertThat(fields)
                .containsEntry(LogEvent.ERROR_CATEGORY, "data")
                .containsEntry(LogEvent.ERROR_FOLLOW_UP_ACTION, true)
                .containsEntry(LogEvent.EPIC_CALL, "jwks"));
    }

    // ---- the outbound client (ADR 0013, D25) ----------------------------------------------------

    @Test
    void eachOutboundCallIsLoggedStartedThenCompletedWithItsName() throws Exception {
        String practitioner = EPIC.practitioners().provision();

        List<String> logged;
        try (CapturedLog captured = CapturedLog.attach()) {
            EPIC.signInFromEpic(practitioner, null);
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
        String practitioner = EPIC.practitioners().provision();

        Map<String, Object> jwks;
        try (CapturedLog captured = CapturedLog.attach()) {
            EPIC.signInFromEpic(practitioner, null);
            jwks = outbound(captured).stream()
                    .filter(record -> "Epic outbound call completed".equals(record.getMessage()))
                    .map(CapturedLog::fields)
                    .filter(fields -> "jwks".equals(fields.get(LogEvent.EPIC_CALL)))
                    .findFirst().orElseThrow();
        }

        assertThat(jwks).containsEntry(LogEvent.HTTP_METHOD, "GET")
                .containsEntry(LogEvent.URL_FULL, EPIC.fake().issuer() + "/jwks")
                .containsEntry(LogEvent.HTTP_STATUS_CODE, 200)
                .containsKey(LogEvent.DURATION_MS);
    }

    /** The JWKS URI discovery names carries a query, which no record repeats. */
    @Test
    void noOutboundRecordCarriesAQuery() throws Exception {
        String practitioner = EPIC.practitioners().provision();

        String everything;
        try (CapturedLog captured = CapturedLog.attach()) {
            EPIC.signInFromEpic(practitioner, null);
            everything = outbound(captured).stream()
                    .map(record -> CapturedLog.fields(record).toString())
                    .collect(Collectors.joining("\n"));
        }

        assertThat(everything).isNotEmpty().doesNotContain(FakeEpic.JWKS_QUERY);
    }

    @Test
    void aCallThatTimedOutIsLoggedAsFailedUnderTheNetworkCategory() throws Exception {
        String practitioner = EPIC.practitioners().provision();
        EPIC.fake().failing(FakeEpic.Endpoint.TOKEN, FakeEpic.Failure.STALL);

        Map<String, Object> failed;
        try (CapturedLog captured = CapturedLog.attach()) {
            EPIC.signInFromEpic(practitioner, null);
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
        String practitioner = EPIC.practitioners().provision();

        String everything;
        try (CapturedLog captured = CapturedLog.attach()) {
            EPIC.signInFromEpic(practitioner, null);
            everything = outbound(captured).stream()
                    .map(record -> record.getFormattedMessage() + CapturedLog.fields(record))
                    .collect(Collectors.joining("\n"));
        }

        FakeEpic epic = EPIC.fake();
        assertThat(List.of(epic.accessToken, epic.idTokens.getFirst(), epic.patient))
                .allSatisfy(value -> assertThat(everything).doesNotContain(value));
    }

    /** D25: Epic's side of the call can be joined to ours. */
    @Test
    void theTokenCallCarriesAW3cTraceparent() throws Exception {
        String practitioner = EPIC.practitioners().provision();

        EPIC.signInFromEpic(practitioner, null);

        assertThat(EPIC.fake().tokenRequestHeaders()).singleElement()
                .satisfies(headers -> assertThat(headers.get("traceparent")).singleElement()
                        .asString().matches("00-[0-9a-f]{32}-[0-9a-f]{16}-0[01]"));
    }

    @Test
    void theOutboundTimerCountsTheTokenCall() throws Exception {
        String practitioner = EPIC.practitioners().provision();
        long before = outboundCalls("token");

        EPIC.signInFromEpic(practitioner, null);

        assertThat(outboundCalls("token")).isEqualTo(before + 1);
    }

    @Test
    void theOutboundErrorCounterCountsAToken5xxUnderItsCall() throws Exception {
        String practitioner = EPIC.practitioners().provision();
        EPIC.fake().failing(FakeEpic.Endpoint.TOKEN, FakeEpic.Failure.SERVER_ERROR);
        double before = outboundErrors("token");

        EPIC.signInFromEpic(practitioner, null);

        assertThat(outboundErrors("token")).isEqualTo(before + 1);
    }

    @Test
    void theOutboundErrorCounterCountsAJwksTimeoutUnderItsCall() throws Exception {
        String practitioner = EPIC.practitioners().provision();
        EPIC.fake().failing(FakeEpic.Endpoint.JWKS, FakeEpic.Failure.STALL);
        double before = outboundErrors("jwks");

        EPIC.signInFromEpic(practitioner, null);

        assertThat(outboundErrors("jwks")).isEqualTo(before + 1);
    }

    @Test
    void aSuccessfulLoginCountsNoOutboundError() throws Exception {
        String practitioner = EPIC.practitioners().provision();
        double before = outboundErrors("token") + outboundErrors("jwks");

        EPIC.signInFromEpic(practitioner, null);

        assertThat(outboundErrors("token") + outboundErrors("jwks")).isEqualTo(before);
    }

    // ---- discovery and Epic's keys, kept (D26) ------------------------------------------------

    @Test
    void aSecondLaunchReadsDiscoveryFromWhatWasKept() throws Exception {
        EPIC.signInFromEpic(EPIC.practitioners().provision(), null);
        int before = EPIC.fake().requests(FakeEpic.Endpoint.DISCOVERY);

        EPIC.signInFromEpic(EPIC.practitioners().provision(), null);

        assertThat(EPIC.fake().requests(FakeEpic.Endpoint.DISCOVERY)).isEqualTo(before);
    }

    @Test
    void aKnownKidIsVerifiedWithTheKeptKeysWithNoFetch() throws Exception {
        EPIC.signInFromEpic(EPIC.practitioners().provision(), null);
        int before = EPIC.fake().requests(FakeEpic.Endpoint.JWKS);

        EPIC.signInFromEpic(EPIC.practitioners().provision(), null);

        assertThat(EPIC.fake().requests(FakeEpic.Endpoint.JWKS)).isEqualTo(before);
    }

    /** D26: a key Epic never publishes is refetched for three times, and then refused. */
    @Test
    void anUnknownKidIsRefetchedThreeTimesBeforeTheRefusal() throws Exception {
        EPIC.signInFromEpic(EPIC.practitioners().provision(), null);
        EPIC.fake().rotateSigningKey(Integer.MAX_VALUE);
        int before = EPIC.fake().requests(FakeEpic.Endpoint.JWKS);

        Landing landing = EPIC.signInFromEpic(EPIC.practitioners().provision(), null);

        assertThat(EPIC.fake().requests(FakeEpic.Endpoint.JWKS)).isEqualTo(before + 3);
        assertThat(landing.callback().getResponse().getRedirectedUrl())
                .isEqualTo("/?signin=refused");
    }

    @Test
    void theRefetchesWaitOneThenTwoThenFourSeconds() throws Exception {
        EPIC.signInFromEpic(EPIC.practitioners().provision(), null);
        EPIC.fake().rotateSigningKey(Integer.MAX_VALUE);
        PAUSES.clear();

        EPIC.signInFromEpic(EPIC.practitioners().provision(), null);

        assertThat(PAUSES).containsExactly(
                Duration.ofSeconds(1), Duration.ofSeconds(2), Duration.ofSeconds(4));
    }

    @Test
    void eachRefetchIsAWarningWithItsAttemptAndTheLastFailureAnError() throws Exception {
        EPIC.signInFromEpic(EPIC.practitioners().provision(), null);
        EPIC.fake().rotateSigningKey(Integer.MAX_VALUE);

        List<String> logged;
        try (CapturedLog captured = CapturedLog.attach()) {
            EPIC.signInFromEpic(EPIC.practitioners().provision(), null);
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
        EPIC.signInFromEpic(EPIC.practitioners().provision(), null);
        EPIC.fake().rotateSigningKey(Integer.MAX_VALUE);
        int before = EPIC.fake().requests(FakeEpic.Endpoint.DISCOVERY);

        EPIC.signInFromEpic(EPIC.practitioners().provision(), null);

        assertThat(EPIC.fake().requests(FakeEpic.Endpoint.DISCOVERY)).isEqualTo(before + 1);
    }

    /** A rotation Epic publishes a moment late is found by the refetches, and accepted. */
    @Test
    void aRotatedKeyPublishedOnTheSecondRefetchIsAccepted() throws Exception {
        EPIC.signInFromEpic(EPIC.practitioners().provision(), null);
        EPIC.fake().rotateSigningKey(2);
        int before = EPIC.fake().requests(FakeEpic.Endpoint.JWKS);

        Landing landing = EPIC.signInFromEpic(EPIC.practitioners().provision(), null);

        assertThat(landing.callback().getResponse().getRedirectedUrl()).isEqualTo("/");
        assertThat(EPIC.fake().requests(FakeEpic.Endpoint.JWKS)).isEqualTo(before + 2);
    }

    // ---- helpers ------------------------------------------------------------------------------

    /** {@code /api/auth/me}'s account and Permissions. */
    private record Me(String username, List<String> permissions) {
    }

    /**
     * {@link EpicPractitioners#provision()}, and a member of the Admin group, so its
     * Permissions are mapped.
     */
    private String provisionInAdminGroup() {
        String practitioner = EPIC.practitioners().provision();
        jdbc.update("INSERT INTO scim_group_members (group_id, user_id) VALUES (?, ?)",
                ADMIN_GROUP, EPIC.practitioners().idOf(practitioner));
        return practitioner;
    }

    /** A password Login, from {@code jar} when there is one; the signed-in session cookie. */
    private Cookie logIn(String userName, Cookie jar) throws Exception {
        MockHttpServletRequestBuilder request = loginRequest(userName, PASSWORD);
        if (jar != null) {
            request.cookie(jar);
        }
        MvcResult login = EPIC.mvc().perform(request).andReturn();
        assertThat(login.getResponse().getStatus()).isEqualTo(200);
        return issuedCookie(login);
    }

    private Cookie issuedCookie(MvcResult result) {
        Cookie issued = result.getResponse().getCookie(sessionCookieName);
        assertThat(issued).as("a session cookie was issued").isNotNull();
        return new Cookie(issued.getName(), issued.getValue());
    }

    /**
     * {@link #provisionInAdminGroup()}, with the change-required flag set since
     * {@link #FLAGGED_SINCE}.
     */
    private String provisionFlaggedInAdminGroup() {
        String practitioner = EPIC.practitioners().provisionRequiredToChangePassword(FLAGGED_SINCE);
        jdbc.update("INSERT INTO scim_group_members (group_id, user_id) VALUES (?, ?)",
                ADMIN_GROUP, EPIC.practitioners().idOf(practitioner));
        return practitioner;
    }

    /** A password Login with a wrong password; its status. */
    private int failedLogIn(String userName) throws Exception {
        return EPIC.mvc().perform(loginRequest(userName, "not-" + PASSWORD))
                .andReturn().getResponse().getStatus();
    }

    /** A CSRF-carrying {@code POST /api/auth/login} for {@code userName} and {@code password}. */
    private MockHttpServletRequestBuilder loginRequest(String userName, String password)
            throws Exception {
        return SessionCsrf.withCsrf(EPIC.mvc(), post("/api/auth/login"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"%s\",\"password\":\"%s\"}"
                        .formatted(userName, password));
    }

    /** The stored login state of the User {@code practitioner} links to. */
    private ScimLoginState loginStateOf(String practitioner) {
        return users.findById(EPIC.practitioners().idOf(practitioner)).orElseThrow().login();
    }

    private Me me(Cookie session) throws Exception {
        tools.jackson.databind.JsonNode body = meBody(session);
        List<String> permissions = new ArrayList<>();
        body.get("permissions").forEach(permission -> permissions.add(permission.asText()));
        return new Me(body.get("username").asText(), permissions);
    }

    /** {@code /api/auth/me}'s body, from a session it answers {@code 200} for. */
    private tools.jackson.databind.JsonNode meBody(Cookie session) throws Exception {
        MvcResult me = EPIC.mvc().perform(get("/api/auth/me").cookie(session)).andReturn();
        assertThat(me.getResponse().getStatus()).isEqualTo(200);
        return tools.jackson.databind.json.JsonMapper.builder()
                .build().readTree(me.getResponse().getContentAsString());
    }

    private Map<String, String> csrfToken(Cookie session) throws Exception {
        String body = EPIC.mvc().perform(get(SessionCsrf.PATH).cookie(session)).andReturn()
                .getResponse().getContentAsString();
        tools.jackson.databind.JsonNode token =
                tools.jackson.databind.json.JsonMapper.builder().build().readTree(body);
        return Map.of("headerName", token.get("headerName").asText(),
                "token", token.get("token").asText());
    }

    private int status(MockHttpServletRequestBuilder request, Cookie session) throws Exception {
        return EPIC.mvc().perform(request.cookie(session)).andReturn().getResponse().getStatus();
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
        Counter counter = meters.find("login").tag("method", "sso").tag("outcome", "success").counter();
        return counter == null ? 0 : counter.count();
    }

    private double unavailables() {
        Counter counter = meters.find("login").tag("method", "sso").tag("outcome", "unavailable")
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
            EPIC.signInFromEpic(practitioner, null);
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
        Counter counter = meters.find("login").tag("method", "sso").tag("outcome", "refused")
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
}

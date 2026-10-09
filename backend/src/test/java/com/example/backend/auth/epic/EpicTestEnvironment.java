package com.example.backend.auth.epic;

import com.example.backend.observability.RequestIdFilter;
import com.example.backend.scim.domain.ScimUserRepository;
import jakarta.servlet.Filter;
import jakarta.servlet.http.Cookie;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.util.Base64;
import java.util.Objects;
import java.util.function.Supplier;
import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.web.context.WebApplicationContext;

/**
 * Where an Epic Login integration test runs: Epic Login switched on against a {@link FakeEpic}
 * started for each test, our routes behind MockMvc over the real filter chain and the real,
 * Redis-backed session store, and the {@link EpicBrowser} and {@link EpicPractitioners} that drive
 * them. A suite states its scenario and nothing of this wiring: it holds one, built with whatever
 * options its scenario needs, in a static {@code @RegisterExtension} field named {@code EPIC}, and
 * hands it the registry in a {@code @DynamicPropertySource} method that calls
 * {@link #register(DynamicPropertyRegistry)} and nothing else.
 *
 * <p>That {@code @DynamicPropertySource} stays a one-line method on each suite, and that is what
 * keeps each suite in a Spring context of its own: the context cache keys on the declaring method,
 * so a suite's caches — discovery, Epic's keys, the meters — are never another suite's. An
 * inherited one would put every suite in one context, and {@link EpicDiscoveryIntegrationTests}
 * needs a context whose discovery has never succeeded. Each environment is the suite's own too:
 * its fake's port, which the context's issuer names, and the private keys it signs our client
 * assertions with are drawn when the suite's class is loaded.
 *
 * <p>Registered as a JUnit extension, so it runs inside the {@code SpringExtension}'s test: each
 * test's fake, MockMvc and drivers are built from the suite's context before the suite's own
 * {@code @BeforeEach}, and taken down after its {@code @AfterEach} — a suite's log capture closes
 * while the fake that answered it is still there.
 *
 * <p>Requires the {@code dev} profile, which lets the fake be plain {@code http} on the loopback
 * interface (D21).
 */
final class EpicTestEnvironment implements BeforeEachCallback, AfterEachCallback {

    /** {@code APP_EPIC_FHIR_BASE}: the {@code iss} a launch must name, and Epic's audience. */
    static final String FHIR_BASE = "https://fhir.example.org/api/FHIR/R4";

    /** {@code APP_EPIC_CLIENT_ID}, our registration at Epic. */
    static final String CLIENT_ID = "epic-client-id";

    /** {@code APP_EPIC_REDIRECT_URI}, the callback Epic sends the browser back to. */
    static final String REDIRECT_URI = "https://app.example.org/api/auth/epic/callback";

    /** {@code APP_EPIC_CLIENT_KEY_ID}, the {@code kid} our client assertion names. */
    static final String ACTIVE_KID = "active-2026-04";

    /** {@code APP_EPIC_CLIENT_NEXT_KEY_ID}, published beside the active key. */
    static final String NEXT_KID = "next-2026-10";

    private final int port;

    private final KeyPair activeKey;

    private final KeyPair nextKey;

    private final boolean mfaEvidenceRequired;

    private FakeEpic fake;

    private MockMvc mvc;

    private EpicBrowser browser;

    private EpicPractitioners practitioners;

    private EpicTestEnvironment(int port, KeyPair activeKey, KeyPair nextKey,
            boolean mfaEvidenceRequired) {
        this.port = port;
        this.activeKey = activeKey;
        this.nextKey = nextKey;
        this.mfaEvidenceRequired = mfaEvidenceRequired;
    }

    /**
     * Epic Login on, against a fake Epic on a free port of its own, signing our client assertions
     * with a fresh active key and publishing no next key; Epic's {@code id_token} needs no MFA
     * evidence (D17's default).
     */
    static EpicTestEnvironment epicLoginOn() {
        return new EpicTestEnvironment(
                EpicTestFixtures.freePort(), EpicTestKeys.p384KeyPair(), null, false);
    }

    /** This environment, with a fresh next key published beside the active one and never used. */
    EpicTestEnvironment withNextKey() {
        return new EpicTestEnvironment(
                port, activeKey, EpicTestKeys.p384KeyPair(), mfaEvidenceRequired);
    }

    /**
     * This environment, with D17's switch on: Epic's {@code id_token} must carry MFA evidence in
     * {@code amr} ({@code APP_EPIC_MFA_EVIDENCE_REQUIRED}).
     */
    EpicTestEnvironment withMfaEvidenceRequired() {
        return new EpicTestEnvironment(port, activeKey, nextKey, true);
    }

    /**
     * Registers this environment's Epic Login settings, the issuer naming this environment's fake.
     * The outbound timeouts are 3 seconds: long enough that a fake Epic answering normally is never
     * cut off on a loaded machine, and well short of the fake's 8-second
     * {@link FakeEpic#STALL_FOR}, so a stalled Epic always times out before it answers.
     */
    void register(DynamicPropertyRegistry registry) {
        epicLoginSettings(registry, "http://localhost:" + port + "/oauth2",
                () -> EpicTestKeys.pem(activeKey));
        if (nextKey != null) {
            nextKeyOn(registry, () -> EpicTestKeys.pem(nextKey));
        }
        registry.add("app.epic.connect-timeout", () -> "3s");
        registry.add("app.epic.read-timeout", () -> "3s");
        if (mfaEvidenceRequired) {
            registry.add("app.epic.mfa-evidence-required", () -> "true");
        }
    }

    /**
     * The settings every Epic Login context has, whether or not a fake Epic stands behind
     * {@code oauthIssuer}: the switch, the FHIR base, the issuer, our registration and our active
     * key. The one place they are written, so a setting every Epic Login needs is added here.
     */
    static void epicLoginSettings(DynamicPropertyRegistry registry, String oauthIssuer,
            Supplier<Object> activePem) {
        registry.add("app.epic.enabled", () -> "true");
        registry.add("app.epic.fhir-base", () -> FHIR_BASE);
        registry.add("app.epic.oauth-issuer", () -> oauthIssuer);
        registry.add("app.epic.client-id", () -> CLIENT_ID);
        registry.add("app.epic.redirect-uri", () -> REDIRECT_URI);
        registry.add("app.epic.client-key", activePem);
        registry.add("app.epic.client-key-id", () -> ACTIVE_KID);
    }

    /** The next key, published beside the active one under {@link #NEXT_KID}. */
    static void nextKeyOn(DynamicPropertyRegistry registry, Supplier<Object> nextPem) {
        registry.add("app.epic.client-next-key", nextPem);
        registry.add("app.epic.client-next-key-id", () -> NEXT_KID);
    }

    @Override
    public void beforeEach(ExtensionContext extension) throws IOException {
        ApplicationContext context = SpringExtension.getApplicationContext(extension);
        mvc = MockMvcBuilders.webAppContextSetup((WebApplicationContext) context)
                .addFilters(context.getBean(RequestIdFilter.class),
                        context.getBean("springSessionRepositoryFilter", Filter.class),
                        context.getBean("springSecurityFilterChain", Filter.class))
                .build();
        EpicJwks ourJwks = context.getBean(EpicJwks.class);
        fake = FakeEpic.start(port, CLIENT_ID, ACTIVE_KID,
                () -> EpicTestFixtures.publishedJwks(ourJwks));
        practitioners = new EpicPractitioners(context.getBean(ScimUserRepository.class),
                context.getBean(PasswordEncoder.class),
                context.getBean(PlatformTransactionManager.class),
                context.getBean(JdbcTemplate.class));
        browser = new EpicBrowser(mvc, fake,
                context.getEnvironment().getProperty("server.servlet.session.cookie.name",
                        "SESSION"),
                FHIR_BASE);
    }

    @Override
    public void afterEach(ExtensionContext extension) {
        try {
            fake.close();
        } finally {
            practitioners.removeAll();
            fake = null;
            mvc = null;
            browser = null;
            practitioners = null;
        }
    }

    /** This test's fake Epic. */
    FakeEpic fake() {
        return during(fake);
    }

    /** MockMvc over the real filter chain and session store, for a request no driver makes. */
    MockMvc mvc() {
        return during(mvc);
    }

    /** This test's browser, against this test's fake. */
    EpicBrowser browser() {
        return during(browser);
    }

    /** The Users this test provisioned, removed again after it. */
    EpicPractitioners practitioners() {
        return during(practitioners);
    }

    /**
     * The {@code fhirUser} Epic names a freshly provisioned, active User by: for a test that needs
     * a Practitioner the directory knows, and never which one.
     */
    String provisionedFhirUser() {
        return browser().practitioner(practitioners().provision());
    }

    /** {@link #provisionedFhirUser()}, the User then deactivated, as the directory would. */
    String deactivatedFhirUser() {
        return browser().practitioner(practitioners().provisionDeactivated());
    }

    /** {@link #provisionedFhirUser()}, the User then locked by a failure run reaching the limit. */
    String lockedFhirUser() {
        return browser().practitioner(practitioners().provisionLocked());
    }

    /**
     * The clinician whose Practitioner ID is {@code practitioner} opens the application from Epic,
     * from {@code jar} when the browser already holds a session, and the whole Login runs, whatever
     * Epic makes of our token request: for a test about Epic refusing it, or one that never gets
     * that far. Any other test signs in with {@link #signInFromEpic(String, Cookie)}.
     */
    EpicBrowser.Landing signIn(String practitioner, Cookie jar) throws Exception {
        EpicBrowser browser = browser();
        return browser.signIn(browser.practitioner(practitioner), jar);
    }

    /**
     * The clinician whose Practitioner ID is {@code practitioner} opens the application from Epic,
     * from {@code jar} when the browser already holds a session, and the whole Login runs: Epic
     * held to having accepted our token request ({@link EpicBrowser#completeAccepted}), so the
     * outcome is the one our side decided.
     */
    EpicBrowser.Landing signInFromEpic(String practitioner, Cookie jar) throws Exception {
        return completeFromEpic(practitioner, browser().launch(jar));
    }

    /**
     * The rest of {@link #signInFromEpic(String, Cookie)}, from a launch the test already made: for
     * a test that does something between the launch and Epic's authorization.
     */
    EpicBrowser.Landing completeFromEpic(String practitioner, EpicBrowser.Launched launched)
            throws Exception {
        EpicBrowser browser = browser();
        return browser.completeAccepted(browser.practitioner(practitioner), launched);
    }

    /** The id a session cookie names in the store: Spring Session writes it Base64-encoded. */
    static String sessionId(Cookie session) {
        return new String(Base64.getDecoder().decode(session.getValue()), StandardCharsets.UTF_8);
    }

    /** The key our client assertions are signed with. */
    KeyPair activeKey() {
        return activeKey;
    }

    /** The next key, published and never used; {@link #withNextKey()} gives this one. */
    KeyPair nextKey() {
        return Objects.requireNonNull(nextKey, "no next key: see withNextKey()");
    }

    private static <T> T during(T perTest) {
        return Objects.requireNonNull(perTest, "only during a test: built per test, before it");
    }
}

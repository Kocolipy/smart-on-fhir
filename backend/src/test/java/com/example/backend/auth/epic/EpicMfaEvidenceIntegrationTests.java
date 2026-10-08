package com.example.backend.auth.epic;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.backend.ContainerTestConfiguration;
import com.example.backend.observability.RequestIdFilter;
import com.example.backend.scim.ScimIdentities;
import com.example.backend.scim.domain.ScimUser;
import com.example.backend.scim.domain.ScimUserRepository;
import com.nimbusds.jose.jwk.JWKSet;
import jakarta.servlet.Filter;
import java.io.IOException;
import java.net.ServerSocket;
import java.security.KeyPair;
import java.text.ParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.convention.TestBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.WebApplicationContext;

/**
 * D17 with its switch on — {@code APP_EPIC_MFA_EVIDENCE_REQUIRED=true}, once Epic confirms it
 * sends {@code amr} on an EHR launch (spec section 8): the {@code id_token} must carry MFA
 * evidence in {@code amr}, a token without it is refused as {@code INVALID_CLAIMS}, and the factor
 * an Epic {@code LOGIN_SUCCESS} records is taken from {@code amr}. The switch off, the default, is
 * {@link EpicProtocolIntegrationTests}'.
 */
@SpringBootTest
@ActiveProfiles("dev")
@Import(ContainerTestConfiguration.class)
class EpicMfaEvidenceIntegrationTests {

    private static final String FHIR_BASE = "https://fhir.example.org/api/FHIR/R4";

    private static final String CLIENT_ID = "epic-client-id";

    private static final String ACTIVE_KID = "active-2026-04";

    private static final KeyPair ACTIVE_KEY = EpicTestKeys.p384KeyPair();

    private static final int EPIC_PORT = freePort();

    @DynamicPropertySource
    static void epicLoginOnWithMfaEvidenceRequired(DynamicPropertyRegistry registry) {
        registry.add("app.epic.enabled", () -> "true");
        registry.add("app.epic.fhir-base", () -> FHIR_BASE);
        registry.add("app.epic.oauth-issuer", () -> "http://localhost:" + EPIC_PORT + "/oauth2");
        registry.add("app.epic.client-id", () -> CLIENT_ID);
        registry.add("app.epic.redirect-uri",
                () -> "https://app.example.org/api/auth/epic/callback");
        registry.add("app.epic.client-key", () -> EpicTestKeys.pem(ACTIVE_KEY));
        registry.add("app.epic.client-key-id", () -> ACTIVE_KID);
        registry.add("app.epic.mfa-evidence-required", () -> "true");
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
    private EpicJwks ourJwks;

    @Value("${server.servlet.session.cookie.name:SESSION}")
    private String sessionCookieName;

    @TestBean
    private EpicRetryPause epicRetryPause;

    static EpicRetryPause epicRetryPause() {
        return wait -> { };
    }

    private FakeEpic epic;

    private EpicBrowser browser;

    private final List<UUID> seeded = new ArrayList<>();

    @BeforeEach
    void setUp() throws IOException {
        MockMvc mvc = MockMvcBuilders.webAppContextSetup(context)
                .addFilters(requestIdFilter, springSessionRepositoryFilter, springSecurityFilterChain)
                .build();
        epic = FakeEpic.start(EPIC_PORT, CLIENT_ID, ACTIVE_KID, this::publishedJwks);
        browser = new EpicBrowser(mvc, epic, sessionCookieName, FHIR_BASE);
    }

    @AfterEach
    void tearDown() {
        epic.close();
        for (UUID id : seeded) {
            jdbc.update("DELETE FROM scim_resources WHERE id = ?", id);
        }
        seeded.clear();
    }

    @Test
    void aSecondFactorInAmrSignsInAndIsTheFactorRecorded() throws Exception {
        String practitioner = provision();
        epic.mintingIdTokensWith(claims -> claims.claim("amr", List.of("pwd", "otp")));

        EpicBrowser.Landing landing = browser.signIn(browser.practitioner(practitioner), null);

        assertThat(landing.callback().getResponse().getRedirectedUrl()).isEqualTo("/");
        assertThat(mfaFactorsOfLoginSuccess(practitioner)).containsExactly("otp");
    }

    /** RFC 8176's {@code mfa}, naming no factor of its own, is recorded as itself. */
    @Test
    void anAmrOfMfaAloneSignsInAndIsRecordedAsMfa() throws Exception {
        String practitioner = provision();
        epic.mintingIdTokensWith(claims -> claims.claim("amr", List.of("mfa")));

        browser.signIn(browser.practitioner(practitioner), null);

        assertThat(mfaFactorsOfLoginSuccess(practitioner)).containsExactly("mfa");
    }

    /** A password alone is one factor: no MFA evidence. */
    @Test
    void anAmrOfAPasswordAloneIsRefusedAsInvalidClaims() throws Exception {
        epic.mintingIdTokensWith(claims -> claims.claim("amr", List.of("pwd")));
        int before = refusalsAudited("INVALID_CLAIMS");

        EpicBrowser.Landing landing = browser.signIn(browser.practitioner(provision()), null);

        assertThat(landing.callback().getResponse().getRedirectedUrl())
                .isEqualTo("/?signin=refused");
        assertThat(refusalsAudited("INVALID_CLAIMS")).isEqualTo(before + 1);
    }

    /** An {@code acr} alone names no factor for the record, so it is no evidence here. */
    @Test
    void aTokenWithNoAmrIsRefusedAsInvalidClaims() throws Exception {
        epic.mintingIdTokensWith(claims -> claims.claim("acr", "urn:epic:loa:2"));
        int before = refusalsAudited("INVALID_CLAIMS");

        EpicBrowser.Landing landing = browser.signIn(browser.practitioner(provision()), null);

        assertThat(landing.callback().getResponse().getRedirectedUrl())
                .isEqualTo("/?signin=refused");
        assertThat(refusalsAudited("INVALID_CLAIMS")).isEqualTo(before + 1);
    }

    private List<String> mfaFactorsOfLoginSuccess(String userName) {
        return jdbc.queryForList("""
                SELECT e.mfa_factor FROM audit_events e
                JOIN scim_users u ON u.resource_id = e.subject_id
                WHERE e.operation = 'LOGIN_SUCCESS' AND u.user_name = ?""",
                String.class, userName);
    }

    private int refusalsAudited(String reason) {
        return jdbc.queryForObject("""
                SELECT count(*) FROM audit_events
                WHERE operation = 'LOGIN_FAILURE' AND error_code = ?
                AND login_method = 'sso' AND subject_id IS NULL""", Integer.class, reason);
    }

    private String provision() {
        String practitioner = "ePract" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        ScimUser created = new TransactionTemplate(transactionManager).execute(status -> users.create(
                ScimUser.created(
                        UUID.randomUUID(),
                        ScimIdentities.profile(practitioner, true),
                        passwordEncoder.encode("a-perfectly-good-passphrase"),
                        ScimIdentities.NOW)));
        seeded.add(created.id());
        return practitioner;
    }

    private JWKSet publishedJwks() {
        try {
            return JWKSet.parse(ourJwks.document());
        } catch (ParseException malformed) {
            throw new IllegalStateException(malformed);
        }
    }

    private static int freePort() {
        // Test-only: binds an ephemeral local port just to learn a free number for the fake
        // Epic, and closes at once. Nothing is ever sent over it, so there is no traffic for
        // TLS to protect.
        // nosemgrep: java.lang.security.audit.crypto.unencrypted-socket.unencrypted-socket
        try (ServerSocket socket = new ServerSocket(0)) {
            socket.setReuseAddress(true);
            return socket.getLocalPort();
        } catch (IOException unavailable) {
            throw new IllegalStateException(unavailable);
        }
    }
}

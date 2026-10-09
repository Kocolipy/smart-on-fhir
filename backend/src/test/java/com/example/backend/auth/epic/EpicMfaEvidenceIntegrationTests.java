package com.example.backend.auth.epic;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.backend.ContainerTestConfiguration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.convention.TestBean;

/**
 * D17 with its switch on — {@code APP_EPIC_MFA_EVIDENCE_REQUIRED=true}, once Epic confirms it
 * sends {@code amr} on an EHR launch (ADR 0013, "Open items"): the {@code id_token} must carry MFA
 * evidence in {@code amr}, a token without it is refused as {@code INVALID_CLAIMS}, and the factor
 * an Epic {@code LOGIN_SUCCESS} records is taken from {@code amr}. The switch off, the default, is
 * {@link EpicProtocolIntegrationTests}'.
 */
@SpringBootTest
@ActiveProfiles("dev")
@Import(ContainerTestConfiguration.class)
class EpicMfaEvidenceIntegrationTests {

    @RegisterExtension
    static final EpicTestEnvironment EPIC =
            EpicTestEnvironment.epicLoginOn().withMfaEvidenceRequired();

    @DynamicPropertySource
    static void epicLoginOnWithMfaEvidenceRequired(DynamicPropertyRegistry registry) {
        EPIC.register(registry);
    }

    @Autowired
    private JdbcTemplate jdbc;

    @TestBean(methodName = EpicTestFixtures.NO_RETRY_PAUSE)
    private EpicRetryPause epicRetryPause;

    @Test
    void aSecondFactorInAmrSignsInAndIsTheFactorRecorded() throws Exception {
        String practitioner = EPIC.practitioners().provision();
        EPIC.fake().mintingIdTokensWith(claims -> claims.claim("amr", List.of("pwd", "otp")));

        EpicBrowser.Landing landing = EPIC.signIn(practitioner, null);

        assertThat(landing.callback().getResponse().getRedirectedUrl()).isEqualTo("/");
        assertThat(mfaFactorsOfLoginSuccess(practitioner)).containsExactly("otp");
    }

    /** RFC 8176's {@code mfa}, naming no factor of its own, is recorded as itself. */
    @Test
    void anAmrOfMfaAloneSignsInAndIsRecordedAsMfa() throws Exception {
        String practitioner = EPIC.practitioners().provision();
        EPIC.fake().mintingIdTokensWith(claims -> claims.claim("amr", List.of("mfa")));

        EPIC.signIn(practitioner, null);

        assertThat(mfaFactorsOfLoginSuccess(practitioner)).containsExactly("mfa");
    }

    /** A password alone is one factor: no MFA evidence. */
    @Test
    void anAmrOfAPasswordAloneIsRefusedAsInvalidClaims() throws Exception {
        EPIC.fake().mintingIdTokensWith(claims -> claims.claim("amr", List.of("pwd")));
        int before = refusalsAudited("INVALID_CLAIMS");

        EpicBrowser.Landing landing = EPIC.browser().signIn(
                EPIC.provisionedFhirUser(), null);

        assertThat(landing.callback().getResponse().getRedirectedUrl())
                .isEqualTo("/?signin=refused");
        assertThat(refusalsAudited("INVALID_CLAIMS")).isEqualTo(before + 1);
    }

    /** An {@code acr} alone names no factor for the record, so it is no evidence here. */
    @Test
    void aTokenWithNoAmrIsRefusedAsInvalidClaims() throws Exception {
        EPIC.fake().mintingIdTokensWith(claims -> claims.claim("acr", "urn:epic:loa:2"));
        int before = refusalsAudited("INVALID_CLAIMS");

        EpicBrowser.Landing landing = EPIC.browser().signIn(
                EPIC.provisionedFhirUser(), null);

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
}

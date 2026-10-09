package com.example.backend.auth.epic;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/**
 * What the bound configuration says about itself. A record's generated {@code toString} prints
 * every component, and a configuration object ends up in debug output, a failure report or a
 * heap of log lines sooner or later; the two private keys must not go with it (D22).
 */
class EpicLoginPropertiesTests {

    @Test
    void toStringNeverPrintsEitherPrivateKey() {
        EpicLoginProperties properties = new EpicLoginProperties(true,
                "https://fhir.example.org/api/FHIR/R4", "https://fhir.example.org/oauth2",
                "epic-client-id", "https://app.example.org/api/auth/epic/callback",
                "ACTIVE-KEY-SENTINEL", "active-kid", "NEXT-KEY-SENTINEL", "next-kid",
                null, null, true);

        assertThat(properties.toString()).isEqualTo("EpicLoginProperties[enabled=true"
                + ", fhirBase=https://fhir.example.org/api/FHIR/R4"
                + ", oauthIssuer=https://fhir.example.org/oauth2"
                + ", clientId=epic-client-id"
                + ", redirectUri=https://app.example.org/api/auth/epic/callback"
                + ", clientKey=[REDACTED], clientKeyId=active-kid"
                + ", clientNextKey=[REDACTED], clientNextKeyId=next-kid"
                + ", connectTimeout=PT2S, readTimeout=PT5S, mfaEvidenceRequired=true]");
    }

    /** An unset key is shown as unset, so the output still says which keys were configured. */
    @Test
    void toStringShowsAnUnsetKeyAsNull() {
        EpicLoginProperties properties = new EpicLoginProperties(
                null, null, null, null, null, null, null, null, null, null, null, null);

        assertThat(properties.toString()).isEqualTo("EpicLoginProperties[enabled=false"
                + ", fhirBase=null, oauthIssuer=null, clientId=null, redirectUri=null"
                + ", clientKey=null, clientKeyId=null, clientNextKey=null, clientNextKeyId=null"
                + ", connectTimeout=PT2S, readTimeout=PT5S, mfaEvidenceRequired=false]");
    }

    /**
     * D17: until Epic confirms it sends {@code amr} on an EHR launch, an unset MFA switch leaves
     * the Epic organisation's MFA an attestation.
     */
    @Test
    void anUnsetMfaEvidenceSwitchIsOff() {
        EpicLoginProperties properties = new EpicLoginProperties(
                null, null, null, null, null, null, null, null, null, null, null, null);

        assertThat(properties.mfaEvidenceRequired()).isFalse();
    }

    /** The switch, set, reaches the accepted configuration. */
    @Test
    void aSetMfaEvidenceSwitchReachesTheSettings() {
        EpicLoginProperties properties = new EpicLoginProperties(true,
                "https://fhir.example.org/api/FHIR/R4", "https://fhir.example.org/oauth2",
                "epic-client-id", "https://app.example.org/api/auth/epic/callback",
                EpicTestKeys.pem(EpicTestKeys.p384KeyPair()), "active-kid", null, null,
                null, null, true);

        assertThat(properties.validate(EpicDevAllowances.NONE).mfaEvidenceRequired()).isTrue();
    }

    /** The {@code http} allowance (D21) admits {@code http} URLs and grants nothing else. */
    @Test
    void theHttpAllowanceAloneLeavesARelativeFhirUserRefused() {
        EpicLoginSettings settings = withUrls("http://localhost:8099/v/r4/fhir")
                .validate(new EpicDevAllowances(true, false));

        assertThat(settings.fhirBase()).hasScheme("http");
        assertThat(settings.relativeFhirUserAllowed()).isFalse();
    }

    /** The relative {@code fhirUser} allowance grants that and leaves D21 in force. */
    @Test
    void theRelativeFhirUserAllowanceAloneLeavesHttpRefused() {
        assertThat(withUrls("https://fhir.example.org/api/FHIR/R4")
                .validate(new EpicDevAllowances(false, true))
                .relativeFhirUserAllowed())
                .isTrue();
        assertThatThrownBy(() -> withUrls("http://localhost:8099/v/r4/fhir")
                .validate(new EpicDevAllowances(false, true)))
                .isInstanceOf(InvalidEpicConfigurationException.class)
                .hasMessageContaining("must be an absolute https URL");
    }

    private static EpicLoginProperties withUrls(String url) {
        return new EpicLoginProperties(true, url, url, "epic-client-id", url,
                EpicTestKeys.pem(EpicTestKeys.p384KeyPair()), "active-kid", null, null,
                null, null, false);
    }

    /** An unset switch is off. */
    @Test
    void anUnsetSwitchIsOff() {
        EpicLoginProperties properties = new EpicLoginProperties(
                null, null, null, null, null, null, null, null, null, null, null, null);

        assertThat(properties.enabled()).isFalse();
    }

    /** A switch set to false is off, as {@code APP_EPIC_ENABLED=false} sets it. */
    @Test
    void aSwitchSetToFalseIsOff() {
        EpicLoginProperties properties = new EpicLoginProperties(
                false, null, null, null, null, null, null, null, null, null, null, null);

        assertThat(properties.enabled()).isFalse();
    }

    /**
     * An MFA switch set to false is off, as {@code APP_EPIC_MFA_EVIDENCE_REQUIRED=false} sets
     * it.
     */
    @Test
    void anMfaEvidenceSwitchSetToFalseIsOff() {
        EpicLoginProperties properties = new EpicLoginProperties(
                null, null, null, null, null, null, null, null, null, null, null, false);

        assertThat(properties.mfaEvidenceRequired()).isFalse();
    }
}

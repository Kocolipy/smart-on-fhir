package com.example.backend.auth.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.backend.audit.domain.AuditMfaFactor;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * D17's MFA evidence in {@code amr}: a second factor beside Epic's password, or {@code mfa}; the
 * factor recorded is the first second factor listed.
 */
class EpicMfaEvidenceTests {

    @Test
    void noAmrIsNoEvidence() {
        assertThat(EpicMfaEvidence.factorIn(null)).isEmpty();
    }

    @Test
    void anEmptyAmrIsNoEvidence() {
        assertThat(EpicMfaEvidence.factorIn(List.of())).isEmpty();
    }

    @Test
    void aPasswordAloneIsNoEvidence() {
        assertThat(EpicMfaEvidence.factorIn(List.of("pwd"))).isEmpty();
    }

    @Test
    void knowledgeFactorsAloneAreNoEvidence() {
        assertThat(EpicMfaEvidence.factorIn(List.of("pwd", "pin", "kba"))).isEmpty();
    }

    @Test
    void aOneTimePasswordBesideThePasswordIsTheFactor() {
        assertThat(EpicMfaEvidence.factorIn(List.of("pwd", "otp"))).contains(AuditMfaFactor.OTP);
    }

    /** Each RFC 8176 possession or inherence method is a second factor, recorded as itself. */
    @Test
    void eachSecondFactorIsRecordedAsItself() {
        Map<String, AuditMfaFactor> rfc8176 = Map.ofEntries(
                Map.entry("otp", AuditMfaFactor.OTP), Map.entry("hwk", AuditMfaFactor.HWK),
                Map.entry("swk", AuditMfaFactor.SWK), Map.entry("sms", AuditMfaFactor.SMS),
                Map.entry("tel", AuditMfaFactor.TEL), Map.entry("sc", AuditMfaFactor.SC),
                Map.entry("fpt", AuditMfaFactor.FPT), Map.entry("face", AuditMfaFactor.FACE),
                Map.entry("iris", AuditMfaFactor.IRIS), Map.entry("retina", AuditMfaFactor.RETINA),
                Map.entry("vbm", AuditMfaFactor.VBM));
        rfc8176.forEach((method, factor) -> assertThat(
                EpicMfaEvidence.factorIn(List.of("pwd", method))).as(method).contains(factor));
    }

    /** D17's switch off: the Epic organisation's MFA is attested, whatever {@code amr} says. */
    @Test
    void withEvidenceNotRequiredTheFactorIsIdpAttested() {
        assertThat(EpicMfaEvidence.factorOf(false, () -> List.of("pwd")))
                .contains(AuditMfaFactor.IDP_ATTESTED);
    }

    /** With the switch off {@code amr} is never read, so a malformed one cannot refuse a Login. */
    @Test
    void withEvidenceNotRequiredAmrIsNotRead() {
        assertThat(EpicMfaEvidence.factorOf(false, () -> {
            throw new IllegalArgumentException("amr read");
        })).contains(AuditMfaFactor.IDP_ATTESTED);
    }

    @Test
    void withEvidenceRequiredTheFactorIsTheOneAmrNames() {
        assertThat(EpicMfaEvidence.factorOf(true, () -> List.of("pwd", "fpt")))
                .contains(AuditMfaFactor.FPT);
    }

    @Test
    void withEvidenceRequiredAPasswordAloneHasNoFactor() {
        assertThat(EpicMfaEvidence.factorOf(true, () -> List.of("pwd"))).isEmpty();
    }

    @Test
    void mfaAloneIsEvidenceRecordedAsMfa() {
        assertThat(EpicMfaEvidence.factorIn(List.of("mfa"))).contains(AuditMfaFactor.MFA);
    }

    /** A named factor says more than {@code mfa}, wherever {@code mfa} is listed. */
    @Test
    void aNamedFactorIsPreferredToMfa() {
        assertThat(EpicMfaEvidence.factorIn(List.of("mfa", "pwd", "hwk")))
                .contains(AuditMfaFactor.HWK);
    }

    @Test
    void theFirstSecondFactorListedIsTheOneRecorded() {
        assertThat(EpicMfaEvidence.factorIn(List.of("pwd", "sms", "otp")))
                .contains(AuditMfaFactor.SMS);
    }

    /** An attestation is not something {@code amr} can claim. */
    @Test
    void idpAttestedInAmrIsNoEvidence() {
        assertThat(EpicMfaEvidence.factorIn(List.of("idp-attested"))).isEmpty();
    }

    @Test
    void aNullMethodIsSkipped() {
        assertThat(EpicMfaEvidence.factorIn(Arrays.asList(null, "otp")))
                .contains(AuditMfaFactor.OTP);
    }

    /** Matching is exact: RFC 8176 values are lower case. */
    @Test
    void anUpperCaseMethodIsNoEvidence() {
        assertThat(EpicMfaEvidence.factorIn(List.of("OTP", "MFA"))).isEmpty();
    }
}

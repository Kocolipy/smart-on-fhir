package com.example.backend.auth.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.backend.audit.domain.AuditRefusalReason;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.CredentialsExpiredException;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.authentication.LockedException;
import org.springframework.security.core.AuthenticationException;

/**
 * The one closed set of password Login refusal reasons: each reason says which Spring Security
 * refusal it is reported for — or that it is settled by looking the name up — and which audit
 * trail reason it is recorded under, so a reason added to it reaches every place a refusal is
 * mapped, recorded and counted in one edit.
 */
class PasswordRefusalReasonTests {

    /**
     * How each reason is reached: the Spring Security refusal it is reported for, or none for the
     * one settled by the lookup. A reason added to the set without a row here fails
     * {@link #everyReasonIsReachedEitherByARefusalOrByTheUnknownAccountSettlement}.
     */
    private static final Map<PasswordRefusalReason, AuthenticationException> REPORTED_FOR = Map.of(
            PasswordRefusalReason.BAD_CREDENTIALS, new BadCredentialsException("wrong"),
            PasswordRefusalReason.ACCOUNT_LOCKED, new LockedException("locked"),
            PasswordRefusalReason.ACCOUNT_DISABLED, new DisabledException("disabled"),
            PasswordRefusalReason.OTHER, new CredentialsExpiredException("expired"));

    @ParameterizedTest(name = "{0}")
    @EnumSource(PasswordRefusalReason.class)
    void everyReasonIsReachedEitherByARefusalOrByTheUnknownAccountSettlement(
            PasswordRefusalReason reason) {
        PasswordRefusalReason reached = REPORTED_FOR.containsKey(reason)
                ? PasswordRefusalReason.reportedFor(REPORTED_FOR.get(reason))
                : PasswordRefusalReason.BAD_CREDENTIALS.settled(false);

        assertThat(reached).isEqualTo(reason);
    }

    @Test
    void aLockedAccountIsReportedAsLocked() {
        assertThat(PasswordRefusalReason.reportedFor(new LockedException("locked")))
                .isEqualTo(PasswordRefusalReason.ACCOUNT_LOCKED);
    }

    @Test
    void aDisabledAccountIsReportedAsDisabled() {
        assertThat(PasswordRefusalReason.reportedFor(new DisabledException("disabled")))
                .isEqualTo(PasswordRefusalReason.ACCOUNT_DISABLED);
    }

    /** Spring Security hides an unknown name behind a wrong password; the lookup settles it. */
    @Test
    void aWrongPasswordIsReportedAsBadCredentials() {
        assertThat(PasswordRefusalReason.reportedFor(new BadCredentialsException("wrong")))
                .isEqualTo(PasswordRefusalReason.BAD_CREDENTIALS);
    }

    /** A refusal no other reason names is {@code OTHER}, never an unmapped failure. */
    @Test
    void aRefusalNoReasonNamesIsReportedAsOther() {
        assertThat(PasswordRefusalReason.reportedFor(new CredentialsExpiredException("expired")))
                .isEqualTo(PasswordRefusalReason.OTHER);
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(PasswordRefusalReason.class)
    void aRefusalOfANameThatMatchedNoUserIsSettledAsAnUnknownAccount(
            PasswordRefusalReason reported) {
        assertThat(reported.settled(false)).isEqualTo(PasswordRefusalReason.UNKNOWN_ACCOUNT);
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(PasswordRefusalReason.class)
    void aRefusalOfANameThatMatchedAUserKeepsTheReasonItWasReportedFor(
            PasswordRefusalReason reported) {
        assertThat(reported.settled(true)).isEqualTo(reported);
    }

    /** Spring Security never reports an unknown account; only the settlement reaches it. */
    @Test
    void onlyTheUnknownAccountIsNeverReported() {
        assertThat(PasswordRefusalReason.values())
                .filteredOn(reason -> !reason.reported())
                .containsExactly(PasswordRefusalReason.UNKNOWN_ACCOUNT);
    }

    /** The audit trail's vocabulary, which Epic Login's refusals share, under the same name. */
    @Test
    void everyReasonIsAuditedUnderTheTrailsReasonOfTheSameName() {
        assertThat(PasswordRefusalReason.values())
                .extracting(PasswordRefusalReason::audited)
                .containsExactly(
                        AuditRefusalReason.BAD_CREDENTIALS,
                        AuditRefusalReason.UNKNOWN_ACCOUNT,
                        AuditRefusalReason.ACCOUNT_LOCKED,
                        AuditRefusalReason.ACCOUNT_DISABLED,
                        AuditRefusalReason.OTHER);
    }
}

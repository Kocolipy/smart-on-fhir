package com.example.backend.auth.epic.controller;

import com.example.backend.audit.domain.AuditLoginMethod;
import com.example.backend.audit.domain.AuditMfaFactor;
import com.example.backend.auth.application.EpicSignInRefusedException;
import com.example.backend.auth.application.EpicLoginOutcomeService;
import com.example.backend.auth.application.LoginService;
import com.example.backend.auth.application.LoginService.EpicLoginDecision;
import com.example.backend.auth.application.LoginService.LoginOutcome;
import com.example.backend.auth.controller.SessionEstablishment;
import com.example.backend.auth.domain.AbsoluteSessionLifetimePolicy;
import com.example.backend.auth.domain.EpicLoginFailureReason;
import com.example.backend.auth.domain.EpicTokenSet;
import com.example.backend.auth.domain.EpicTokens;
import com.example.backend.auth.domain.EpicMfaEvidence;
import com.example.backend.auth.epic.EpicLoginSettings;
import com.example.backend.auth.epic.EpicSignIn;
import com.example.backend.auth.epic.EpicSignInFailure;
import com.example.backend.auth.epic.EpicTokenHandOff;
import com.example.backend.auth.epic.FhirUserReference;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.stereotype.Component;

/**
 * Where an Epic Login becomes a Login (flow steps 5–7), once Spring Security's OAuth 2.0 login
 * filter has redeemed the code and validated the {@code id_token}.
 *
 * <p>What it is handed is Epic's verified identity, not a session: the filter is configured to
 * neither rotate the session nor save a security context of its own, so nothing Epic sent is
 * written to the pre-login session. Here the
 * {@code fhirUser} claim is read as a Practitioner ID ({@link FhirUserReference}), the login
 * decision is {@link LoginService#logInFromEpic}'s, and the session is established by
 * {@link SessionEstablishment}, the step password Login ends in too, so an Epic session is
 * signed in exactly as a password one is: rotated id, saved context, principal index, role
 * mapping hash, pre-login CSRF token dropped, and a {@code session-start} naming method
 * {@code sso}. Only once the session is signed in are Epic's tokens — the access token, any
 * refresh token and the {@code id_token} — kept on it, under {@link EpicTokens#SESSION_ATTRIBUTE},
 * taken from the token response the filter handed over ({@link EpicTokenHandOff}): so they live
 * under the rotated id alone, for exactly as long as the session does, and the session is stored
 * no longer than its remaining absolute lifetime (ADR 0013, addendum 2026-10-09). The browser
 * lands at {@code /}.
 *
 * <p>Anything short of that — a {@code fhirUser} of another form, or no acceptable User — ends
 * the session, whoever it belonged to (D24), and lands at {@code /?signin=refused} with no detail.
 * A {@code fhirUser} of another form is a protocol refusal, {@code INVALID_FHIR_USER}, handed to
 * the one Epic failure handler, which records it. A User the login decision refused is recorded
 * there; one it accepted is recorded here, through {@code EpicLoginOutcomeService}, once its
 * session is signed in. Either way the browser lands where {@link EpicLoginLanding} sends it.
 *
 * <p>A web adapter, because the session work is one, and a component rather than a bean of the
 * Epic security configuration so that the configuration need not depend on a web adapter: it is
 * found by its type, {@link EpicSignIn}.
 */
@Component
public class EpicLoginSuccessHandler implements EpicSignIn {

    /** The {@code id_token} claim naming the signed-in FHIR user (SMART App Launch). */
    static final String FHIR_USER_CLAIM = "fhirUser";

    private final LoginService login;

    private final SessionEstablishment sessionEstablishment;

    private final ObjectProvider<EpicLoginSettings> settings;

    private final EpicSignInFailure signInFailure;

    private final EpicLoginOutcomeService outcomes;

    private final AbsoluteSessionLifetimePolicy absoluteLifetime;

    private final Clock clock;

    public EpicLoginSuccessHandler(
            LoginService login,
            SessionEstablishment sessionEstablishment,
            ObjectProvider<EpicLoginSettings> settings,
            EpicSignInFailure signInFailure,
            EpicLoginOutcomeService outcomes,
            AbsoluteSessionLifetimePolicy absoluteLifetime,
            Clock clock) {
        this.login = login;
        this.sessionEstablishment = sessionEstablishment;
        this.settings = settings;
        this.signInFailure = signInFailure;
        this.outcomes = outcomes;
        this.absoluteLifetime = absoluteLifetime;
        this.clock = clock;
    }

    @Override
    public void onAuthenticationSuccess(
            HttpServletRequest request, HttpServletResponse response, Authentication epic)
            throws IOException, ServletException {
        Optional<String> practitionerId = practitionerIdOf(epic);
        if (practitionerId.isEmpty()) {
            // A protocol refusal like any other, so the one failure handler records it.
            signInFailure.onAuthenticationFailure(request, response,
                    new EpicSignInRefusedException(EpicLoginFailureReason.INVALID_FHIR_USER));
            return;
        }
        Optional<AuditMfaFactor> factor = mfaFactorOf(epic);
        if (factor.isEmpty()) {
            // The id_token checks refuse a token without a factor before it gets here, by the same
            // decision; should one ever arrive, it is refused rather than recorded without one.
            signInFailure.onAuthenticationFailure(request, response,
                    new EpicSignInRefusedException(EpicLoginFailureReason.INVALID_CLAIMS));
            return;
        }
        // The session the launch began, which the Login continues in and so is the one session
        // of the User's that it keeps.
        HttpSession existing = request.getSession(false);
        EpicLoginDecision decision = login.logInFromEpic(practitionerId.get(),
                existing == null ? null : existing.getId(), factor.get());
        // A refusal is recorded by the login decision already. An acceptance is recorded here,
        // once the session is signed in: after the decision's commit, so a Login that rolled back
        // or never got its session is not counted a success, and naming the session the User
        // goes on to use.
        if (decision.accepted().isPresent()) {
            LoginOutcome accepted = decision.accepted().get();
            HttpSession signedIn = sessionEstablishment.establish(accepted.authentication(),
                    accepted.userId(), accepted.roleMappingHash(), AuditLoginMethod.SSO,
                    request, response);
            // After establish: kept under the signed-in (rotated) id, never the pre-login one.
            EpicTokenCapture.take(request, epic, clock.instant())
                    .ifPresent(tokens -> keep(signedIn, tokens));
            outcomes.record(decision.outcome(), signedIn.getId());
        }
        EpicLoginLanding.after(decision.outcome(), request, response);
    }

    /**
     * Keeps Epic's tokens on the signed-in session, and bounds how long the store keeps the
     * session by what remains of its absolute lifetime, once that is shorter than its idle bound:
     * so the tokens are never stored past the lifetime's end. Each later request is bounded the
     * same way by {@code AbsoluteSessionLifetimeFilter}, so its renewal cannot undo this.
     */
    private void keep(HttpSession signedIn, EpicTokenSet tokens) {
        signedIn.setAttribute(EpicTokens.SESSION_ATTRIBUTE, tokens);
        signedIn.setMaxInactiveInterval((int) absoluteLifetime.idleBoundAt(
                Duration.ofSeconds(signedIn.getMaxInactiveInterval()),
                Instant.ofEpochMilli(signedIn.getCreationTime()), clock.instant()).toSeconds());
    }

    /** The MFA factor the Login was made with, as {@link EpicMfaEvidence#factorOf} decides it. */
    private Optional<AuditMfaFactor> mfaFactorOf(Authentication epic) {
        EpicLoginSettings epicSettings = settings.getIfAvailable();
        return EpicMfaEvidence.factorOf(
                epicSettings != null && epicSettings.mfaEvidenceRequired(),
                () -> epic.getPrincipal() instanceof OidcUser user
                        ? user.getIdToken().getClaimAsStringList(EpicMfaEvidence.AMR) : null);
    }

    /** The Practitioner ID the validated {@code id_token} names, if it names one here. */
    private Optional<String> practitionerIdOf(Authentication epic) {
        EpicLoginSettings epicSettings = settings.getIfAvailable();
        if (epicSettings == null || !(epic.getPrincipal() instanceof OidcUser user)) {
            return Optional.empty();
        }
        return FhirUserReference.practitionerId(
                user.getIdToken().getClaimAsString(FHIR_USER_CLAIM), epicSettings.fhirBase(),
                epicSettings.relativeFhirUserAllowed());
    }
}

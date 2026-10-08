package com.example.backend.auth.epic.controller;

import com.example.backend.audit.domain.AuditLoginMethod;
import com.example.backend.audit.domain.AuditMfaFactor;
import com.example.backend.auth.application.EpicLoginRefusedException;
import com.example.backend.auth.application.EpicSignInRefusedException;
import com.example.backend.auth.application.LoginService;
import com.example.backend.auth.application.LoginService.LoginOutcome;
import com.example.backend.auth.controller.SessionEstablishment;
import com.example.backend.auth.domain.EpicLoginFailureReason;
import com.example.backend.auth.domain.EpicMfaEvidence;
import com.example.backend.auth.epic.EpicLoginMetrics;
import com.example.backend.auth.epic.EpicLoginSettings;
import com.example.backend.auth.epic.EpicSignIn;
import com.example.backend.auth.epic.EpicSignInFailure;
import com.example.backend.auth.epic.EpicSignInRedirect;
import com.example.backend.auth.epic.FhirUserReference;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.io.IOException;
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
 * neither rotate the session nor save a security context of its own, so nothing Epic sent —
 * the {@code id_token} with the rest — is ever written to the session store (D8). Here the
 * {@code fhirUser} claim is read as a Practitioner ID ({@link FhirUserReference}), the login
 * decision is {@link LoginService#logInFromEpic}'s, and the session is established by
 * {@link SessionEstablishment}, the step password Login ends in too, so an Epic session is
 * signed in exactly as a password one is: rotated id, saved context, principal index, role
 * mapping hash, pre-login CSRF token dropped, and a {@code session-start} naming method
 * {@code sso}. The browser lands at {@code /}.
 *
 * <p>Anything short of that — a {@code fhirUser} of another form, or no acceptable User — ends
 * the session, whoever it belonged to (D24), and lands at {@code /?signin=refused} with no detail.
 * A {@code fhirUser} of another form is a protocol refusal, {@code INVALID_FHIR_USER}, handed to
 * the one Epic failure handler, which records it. A User the login decision refused is audited
 * and logged there, once, and counted here under its reason; the counter is the web adapter's,
 * as the success count is.
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

    private final EpicLoginMetrics metrics;

    private final EpicSignInFailure signInFailure;

    public EpicLoginSuccessHandler(
            LoginService login,
            SessionEstablishment sessionEstablishment,
            ObjectProvider<EpicLoginSettings> settings,
            EpicLoginMetrics metrics,
            EpicSignInFailure signInFailure) {
        this.login = login;
        this.sessionEstablishment = sessionEstablishment;
        this.settings = settings;
        this.metrics = metrics;
        this.signInFailure = signInFailure;
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
        LoginOutcome outcome;
        try {
            outcome = login.logInFromEpic(practitionerId.get(),
                    existing == null ? null : existing.getId(), factor.get());
        } catch (EpicLoginRefusedException refused) {
            // Audited and logged by the login decision already; counted here, and the browser
            // signed out and sent to the refused notice with no detail (D23, D24).
            metrics.refused(refused.reason().name());
            EpicSignInRedirect.refused(request, response);
            return;
        }
        sessionEstablishment.establish(outcome.authentication(), outcome.userId(),
                outcome.roleMappingHash(), AuditLoginMethod.SSO, request, response);
        metrics.success();
        EpicSignInRedirect.signedIn(request, response);
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

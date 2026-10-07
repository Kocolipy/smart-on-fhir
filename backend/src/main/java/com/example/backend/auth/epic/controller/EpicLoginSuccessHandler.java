package com.example.backend.auth.epic.controller;

import com.example.backend.audit.domain.AuditLoginMethod;
import com.example.backend.auth.application.LoginService;
import com.example.backend.auth.application.LoginService.LoginOutcome;
import com.example.backend.auth.controller.SessionEstablishment;
import com.example.backend.auth.epic.EpicLoginMetrics;
import com.example.backend.auth.epic.EpicLoginSettings;
import com.example.backend.auth.epic.EpicSignIn;
import com.example.backend.auth.epic.EpicSignInRedirect;
import com.example.backend.auth.epic.FhirUserReference;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.io.IOException;
import java.util.Optional;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
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
 * the session and lands at {@code /?signin=refused}.
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

    public EpicLoginSuccessHandler(
            LoginService login,
            SessionEstablishment sessionEstablishment,
            ObjectProvider<EpicLoginSettings> settings,
            EpicLoginMetrics metrics) {
        this.login = login;
        this.sessionEstablishment = sessionEstablishment;
        this.settings = settings;
        this.metrics = metrics;
    }

    @Override
    public void onAuthenticationSuccess(
            HttpServletRequest request, HttpServletResponse response, Authentication epic)
            throws IOException {
        Optional<String> practitionerId = practitionerIdOf(epic);
        if (practitionerId.isEmpty()) {
            EpicSignInRedirect.refused(request, response);
            return;
        }
        // The session the launch began, which the Login continues in and so is the one session
        // of the User's that it keeps.
        HttpSession existing = request.getSession(false);
        LoginOutcome outcome;
        try {
            outcome = login.logInFromEpic(
                    practitionerId.get(), existing == null ? null : existing.getId());
        } catch (AuthenticationException refused) {
            EpicSignInRedirect.refused(request, response);
            return;
        }
        sessionEstablishment.establish(outcome.authentication(), outcome.userId(),
                outcome.roleMappingHash(), AuditLoginMethod.SSO, request, response);
        metrics.success();
        EpicSignInRedirect.signedIn(request, response);
    }

    /** The Practitioner ID the validated {@code id_token} names, if it names one here. */
    private Optional<String> practitionerIdOf(Authentication epic) {
        EpicLoginSettings epicSettings = settings.getIfAvailable();
        if (epicSettings == null || !(epic.getPrincipal() instanceof OidcUser user)) {
            return Optional.empty();
        }
        return FhirUserReference.practitionerId(
                user.getIdToken().getClaimAsString(FHIR_USER_CLAIM), epicSettings.fhirBase());
    }
}

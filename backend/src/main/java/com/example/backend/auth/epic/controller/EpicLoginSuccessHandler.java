package com.example.backend.auth.epic.controller;

import com.example.backend.audit.domain.AuditMfaFactor;
import com.example.backend.auth.application.EpicSignInRefusedException;
import com.example.backend.auth.application.LoginService;
import com.example.backend.auth.controller.HttpSessionAttributes;
import com.example.backend.auth.controller.LoginCompletion;
import com.example.backend.auth.domain.AbsoluteSessionLifetimePolicy;
import com.example.backend.auth.domain.EpicLoginFailureReason;
import com.example.backend.auth.domain.EpicMfaEvidence;
import com.example.backend.auth.domain.SignedInSession;
import com.example.backend.auth.epic.EpicLogin;
import com.example.backend.auth.epic.EpicLoginSettings;
import com.example.backend.auth.epic.EpicTokenHandOff;
import com.example.backend.auth.epic.FhirUserReference;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Clock;
import java.util.Optional;
import org.springframework.context.annotation.Conditional;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.stereotype.Component;

/**
 * Where an Epic Login becomes a Login (flow steps 5–7), once Spring Security's OAuth 2.0 login
 * filter has redeemed the code and validated the {@code id_token}.
 *
 * <p>What it is handed is Epic's verified identity, not a session: the filter is configured to
 * neither rotate the session nor save a security context of its own, so nothing Epic sent is
 * written to the pre-login session. Here the
 * {@code fhirUser} claim is read as a Practitioner ID ({@link FhirUserReference}), and the Login
 * is completed by {@link LoginCompletion}, the steps password Login ends in too: the login
 * decision ({@link LoginService#logInFromEpic}), then the session signed in exactly as a password
 * one is — rotated id, saved context, principal index, role mapping hash, pre-login CSRF token
 * dropped, and a {@code session-start} naming method {@code sso} — then the ending recorded,
 * naming that session. Between the last two, on the signed-in session alone, Epic's tokens — the
 * access token, any refresh token and the {@code id_token} — are kept there
 * ({@link SignedInSession#keepEpicTokens}), taken from the token response the filter handed over
 * ({@link EpicTokenHandOff}): so they live under the rotated id alone, for exactly as long as the
 * session does, and the session is stored no longer than its remaining absolute lifetime (ADR
 * 0013, D29). The browser lands at {@code /}. Each later request is bounded the same way by
 * {@code AbsoluteSessionLifetimeFilter}, so its renewal cannot undo this.
 *
 * <p>Anything short of that — a {@code fhirUser} of another form, or no acceptable User — ends
 * the session, whoever it belonged to (D24), and lands at {@code /?signin=refused} with no detail.
 * A {@code fhirUser} of another form is a protocol refusal, {@code INVALID_FHIR_USER}, handed to
 * the one Epic failure handler, which ends it. A User the login decision refused is recorded by
 * the decision and its session ended by {@link LoginCompletion}; this handler only redirects.
 *
 * <p>A web adapter, because the session work is one, and a component rather than a bean of the
 * Epic security configuration so that the configuration need not depend on a web adapter: it is
 * the one {@link AuthenticationSuccessHandler} in the application, and found by that type. Exists
 * only while Epic Login is on, so it is handed the settings themselves.
 */
@Component
@Conditional(EpicLogin.WhenOn.class)
public class EpicLoginSuccessHandler implements AuthenticationSuccessHandler {

    /** The {@code id_token} claim naming the signed-in FHIR user (SMART App Launch). */
    static final String FHIR_USER_CLAIM = "fhirUser";

    private final LoginService login;

    private final LoginCompletion loginCompletion;

    private final EpicLoginSettings settings;

    private final EpicLoginFailureHandler signInFailure;

    private final AbsoluteSessionLifetimePolicy absoluteLifetime;

    private final Clock clock;

    public EpicLoginSuccessHandler(
            LoginService login,
            LoginCompletion loginCompletion,
            EpicLoginSettings settings,
            EpicLoginFailureHandler signInFailure,
            AbsoluteSessionLifetimePolicy absoluteLifetime,
            Clock clock) {
        this.login = login;
        this.loginCompletion = loginCompletion;
        this.settings = settings;
        this.signInFailure = signInFailure;
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
        boolean signedIn = loginCompletion.complete(
                retained -> login.logInFromEpic(practitionerId.get(), retained, factor.get()),
                // On the signed-in (rotated) session, before the ending is recorded.
                session -> EpicTokenCapture.take(request, epic, clock.instant())
                        .ifPresent(tokens -> HttpSessionAttributes.signedIn(session)
                                .keepEpicTokens(tokens, absoluteLifetime, clock.instant())),
                request, response).isPresent();
        (signedIn ? EpicLanding.SIGNED_IN : EpicLanding.REFUSED).sendTo(request, response);
    }

    /** The MFA factor the Login was made with, as {@link EpicMfaEvidence#factorOf} decides it. */
    private Optional<AuditMfaFactor> mfaFactorOf(Authentication epic) {
        return EpicMfaEvidence.factorOf(settings.mfaEvidenceRequired(),
                () -> epic.getPrincipal() instanceof OidcUser user
                        ? user.getIdToken().getClaimAsStringList(EpicMfaEvidence.AMR) : null);
    }

    /** The Practitioner ID the validated {@code id_token} names, if it names one here. */
    private Optional<String> practitionerIdOf(Authentication epic) {
        if (!(epic.getPrincipal() instanceof OidcUser user)) {
            return Optional.empty();
        }
        return FhirUserReference.practitionerId(
                user.getIdToken().getClaimAsString(FHIR_USER_CLAIM), settings.fhirBase(),
                settings.relativeFhirUserAllowed());
    }
}

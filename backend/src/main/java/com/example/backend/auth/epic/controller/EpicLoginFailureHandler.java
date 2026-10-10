package com.example.backend.auth.epic.controller;

import com.example.backend.auth.application.LoginOutcome;
import com.example.backend.auth.application.LoginOutcome.FailedCall;
import com.example.backend.auth.application.LoginOutcome.EpicRefused;
import com.example.backend.auth.application.LoginOutcome.Unavailable;
import com.example.backend.auth.application.LoginOutcomeService;
import com.example.backend.auth.application.EpicSignInRefusedException;
import com.example.backend.auth.domain.EpicLoginFailureReason;
import com.example.backend.auth.epic.CauseChain;
import com.example.backend.auth.epic.EpicLogin;
import com.example.backend.auth.epic.EpicOutboundException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.io.IOException;
import java.util.Optional;
import org.springframework.context.annotation.Conditional;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.JwtValidationException;
import org.springframework.security.web.authentication.AuthenticationFailureHandler;
import org.springframework.stereotype.Component;

/**
 * Where an Epic Login that failed before any login decision ends (flow step 8, D23, D24): at the
 * launch, when its {@code iss} or {@code launch} was refused; at the authorize hop, when no launch
 * was pending or discovery failed; or at the callback, when anything from the pending request to
 * the {@code id_token} and its {@code fhirUser} did.
 *
 * <p>What failed is told apart here, from what Spring Security raised, and nowhere else: Epic
 * being unavailable — no answer in time, or a {@code 5xx}, from discovery, the JWKS or the token
 * endpoint — or a refusal with its exact reason (ADR 0013, "Audit"), read from what failed and
 * never from a message, which can quote what Epic sent. {@link LoginOutcomeService} records
 * the outcome — audit, log and counts — and the browser lands at {@code /?signin=unavailable} or
 * {@code /?signin=refused}, the session it held ended first, whoever it belonged to, and told
 * nothing more (D23, D24).
 *
 * <p>The one {@link AuthenticationFailureHandler} in the application, which is how the Epic
 * security configuration finds it without depending on this web adapter. Exists only while Epic
 * Login is on.
 */
@Component
@Conditional(EpicLogin.WhenOn.class)
public class EpicLoginFailureHandler implements AuthenticationFailureHandler {

    /**
     * Spring Security's error code for an {@code id_token} nonce that is not the one sent, raised by
     * its OIDC provider's own nonce check after decoding. Spring keeps it private, so it is spelled
     * here. Epic Login's own nonce check fails in the decoder, as {@code INVALID_CLAIMS}, and
     * reports no code of its own that this reads.
     */
    private static final String INVALID_NONCE = "invalid_nonce";

    /** Spring Security's error code for a callback {@code state} that is not the pending one. */
    private static final String INVALID_STATE_PARAMETER = "invalid_state_parameter";

    /** Spring Security's error code for a callback with no pending authorization request. */
    private static final String AUTHORIZATION_REQUEST_NOT_FOUND = "authorization_request_not_found";

    private final LoginOutcomeService outcomes;

    public EpicLoginFailureHandler(LoginOutcomeService outcomes) {
        this.outcomes = outcomes;
    }

    @Override
    public void onAuthenticationFailure(
            HttpServletRequest request, HttpServletResponse response,
            AuthenticationException failure) throws IOException {
        LoginOutcome outcome = outcomeOf(failure);
        HttpSession session = request.getSession(false);
        outcomes.record(outcome, session == null ? null : session.getId());
        EpicLoginLanding.after(outcome, request, response);
    }

    /** The outcome {@code failure} ended the Login in. */
    private static LoginOutcome outcomeOf(AuthenticationException failure) {
        if (failure instanceof EpicSignInRefusedException ours) {
            return ours.field().isPresent() && ours.rule().isPresent()
                    ? EpicRefused.input(ours.reason(), ours.field().get(), ours.rule().get())
                    : EpicRefused.because(ours.reason());
        }
        Optional<EpicOutboundException> outbound = EpicOutboundException.in(failure);
        if (outbound.isEmpty()) {
            return EpicRefused.because(reasonFor(failure, outbound));
        }
        EpicOutboundException failed = outbound.get();
        FailedCall call = new FailedCall(failed.call().tag(), failed.category(), failed.code());
        return failed.unavailable()
                ? new Unavailable(call)
                : EpicRefused.call(reasonFor(failure, outbound), call);
    }

    /**
     * The reason (ADR 0013, "Audit") for a refusal Spring Security's OAuth 2.0 login raised, read from what
     * failed — never from a message, which can quote what Epic sent.
     *
     * <ul>
     *   <li>An Epic call that answered with something unusable: the token call's is the exchange
     *       failing, the JWKS's leaves no signature checkable, and discovery's is Epic's own
     *       error.
     *   <li>The {@code id_token} decoder: a claim validator's failure is {@code INVALID_CLAIMS},
     *       and any other — a signature that does not verify, an algorithm other than RS256, a
     *       {@code kid} still unknown after D26's refetches, a token that does not parse — is
     *       {@code INVALID_SIGNATURE}.
     *   <li>Spring Security's own nonce check, run after decoding, is a claim too, though Epic
     *       Login's in the decoder fails first; the {@code state} and the pending
     *       request, already checked ahead of the login filter, are {@code INVALID_STATE} if they
     *       ever reach here.
     *   <li>Anything else happened in the exchange: the token endpoint's OAuth error, or a token
     *       response without an {@code id_token}.
     * </ul>
     */
    private static EpicLoginFailureReason reasonFor(
            AuthenticationException failure, Optional<EpicOutboundException> outbound) {
        if (outbound.isPresent()) {
            return switch (outbound.get().call()) {
                case TOKEN -> EpicLoginFailureReason.TOKEN_EXCHANGE_FAILED;
                case JWKS -> EpicLoginFailureReason.INVALID_SIGNATURE;
                case DISCOVERY -> EpicLoginFailureReason.IDP_ERROR;
            };
        }
        if (CauseChain.firstOf(failure, JwtValidationException.class).isPresent()) {
            return EpicLoginFailureReason.INVALID_CLAIMS;
        }
        if (CauseChain.firstOf(failure, JwtException.class).isPresent()) {
            return EpicLoginFailureReason.INVALID_SIGNATURE;
        }
        if (failure instanceof OAuth2AuthenticationException oauth) {
            String code = oauth.getError().getErrorCode();
            if (INVALID_NONCE.equals(code)) {
                return EpicLoginFailureReason.INVALID_CLAIMS;
            }
            if (INVALID_STATE_PARAMETER.equals(code)
                    || AUTHORIZATION_REQUEST_NOT_FOUND.equals(code)) {
                return EpicLoginFailureReason.INVALID_STATE;
            }
        }
        return EpicLoginFailureReason.TOKEN_EXCHANGE_FAILED;
    }
}

package com.example.backend.auth.epic.controller;

import com.example.backend.audit.domain.AuditLoginMethod;
import com.example.backend.auth.application.EpicSignInRefusedException;
import com.example.backend.auth.application.LoginService;
import com.example.backend.auth.domain.EpicLoginFailureReason;
import com.example.backend.auth.epic.CauseChain;
import com.example.backend.auth.epic.EpicLoginMetrics;
import com.example.backend.auth.epic.EpicOutboundException;
import com.example.backend.auth.epic.EpicSignInFailure;
import com.example.backend.auth.epic.EpicSignInRedirect;
import com.example.backend.observability.LogContext;
import com.example.backend.observability.LogEvent;
import com.example.backend.observability.LogEvent.Category;
import com.example.backend.observability.LogEvent.Operation;
import com.example.backend.observability.LogEvent.Type;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.spi.LoggingEventBuilder;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.JwtValidationException;
import org.springframework.stereotype.Component;

/**
 * Where an Epic Login that failed before any login decision ends (flow step 8, D23, D24): at the
 * launch, when its {@code iss} or {@code launch} was refused; at the authorize hop, when no launch
 * was pending or discovery failed; or at the callback, when anything from the pending request to
 * the {@code id_token} and its {@code fhirUser} did. It is the one place such a failure is told
 * apart, logged and recorded, once.
 *
 * <p>Epic being unavailable — no answer in time, or a {@code 5xx}, from discovery, the JWKS or
 * the token endpoint — is recorded as a {@code LOGIN_FAILURE} under method {@code sso} with
 * {@code EPIC_UNAVAILABLE} and no subject, the {@code epic.login} count under
 * {@code outcome=unavailable}, and one {@code ERROR} naming the call and its error category,
 * with no follow-up — Epic being down is not ours to fix. The browser lands at
 * {@code /?signin=unavailable}.
 *
 * <p>Anything else is a refusal with its exact reason (ADR 0013, "Audit"): a
 * {@code LOGIN_FAILURE} under method {@code sso} naming nobody — no User was looked up — counted
 * toward no failure run (D12), the {@code epic.login} count under {@code outcome=refused} and the
 * reason, and one {@code WARN} saying only "Epic sign-in refused", with the field and rule of a
 * refused input but never its value and never the reason, which is the audit trail's alone
 * (ADR 0013, "the account reasons are audit-only"). Epic refusing our own credential
 * ({@code cert/auth}) or answering with something unusable ({@code data}) is also an
 * {@code ERROR} — one that needs a person, since a key or a registration is likely wrong. The
 * browser lands at {@code /?signin=refused}.
 *
 * <p>Either way the session the browser held is ended first, whoever it belonged to, and the
 * browser told nothing more (D23, D24).
 */
@Component
public class EpicLoginFailureHandler implements EpicSignInFailure {

    private static final Logger log = LoggerFactory.getLogger(EpicLoginFailureHandler.class);

    /** Spring Security's error code for an {@code id_token} nonce that is not the one sent. */
    private static final String INVALID_NONCE = "invalid_nonce";

    /** Spring Security's error code for a callback {@code state} that is not the pending one. */
    private static final String INVALID_STATE_PARAMETER = "invalid_state_parameter";

    /** Spring Security's error code for a callback with no pending authorization request. */
    private static final String AUTHORIZATION_REQUEST_NOT_FOUND = "authorization_request_not_found";

    private final LoginService login;

    private final EpicLoginMetrics metrics;

    public EpicLoginFailureHandler(LoginService login, EpicLoginMetrics metrics) {
        this.login = login;
        this.metrics = metrics;
    }

    @Override
    public void onAuthenticationFailure(
            HttpServletRequest request, HttpServletResponse response,
            AuthenticationException failure) throws IOException {
        Optional<EpicOutboundException> outbound = EpicOutboundException.in(failure);
        outbound.ifPresent(EpicLoginFailureHandler::logFailedCall);
        if (outbound.isPresent() && outbound.get().unavailable()) {
            login.recordEpicFailure(EpicLoginFailureReason.EPIC_UNAVAILABLE);
            metrics.unavailable();
            EpicSignInRedirect.unavailable(request, response);
            return;
        }
        EpicSignInRefusedException refused = failure instanceof EpicSignInRefusedException ours
                ? ours : new EpicSignInRefusedException(reasonFor(failure, outbound));
        login.recordEpicFailure(refused.reason());
        metrics.refused(refused.reason().name());
        logRefusal(refused);
        EpicSignInRedirect.refused(request, response);
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
     *   <li>The nonce, checked after decoding, is a claim; the {@code state} and the pending
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

    /**
     * The one {@code WARN} of a refusal: generic, with the field and rule of a refused input and
     * nothing else — no reason, no value, no user. The browser's session, if it had one, is not
     * whom the launch was for.
     */
    private static void logRefusal(EpicSignInRefusedException refused) {
        try (LogContext.Scope unresolved = LogContext.userId(null)) {
            LoggingEventBuilder warning = LogEvent.refused(
                            log, Operation.EPIC_LOGIN, Category.PROCESS, Type.USER, Type.DENIED)
                    .addKeyValue(LogEvent.LOGIN_METHOD, AuditLoginMethod.SSO.value());
            refused.field().ifPresent(
                    field -> warning.addKeyValue(LogEvent.EPIC_INPUT_FIELD, field.value()));
            refused.rule().ifPresent(
                    rule -> warning.addKeyValue(LogEvent.EPIC_INPUT_RULE, rule.value()));
            warning.log();
        }
    }

    /**
     * The one {@code ERROR} of a failed Epic call: which call, and its {@code error.category}
     * (ADR 0013, "Log") and code, followed up unless Epic was merely unavailable. No user:
     * the browser's session, if it had one, is not whom the launch was for.
     */
    private static void logFailedCall(EpicOutboundException failed) {
        try (LogContext.Scope unresolved = LogContext.userId(null)) {
            // be-log-sensitive-value matches any value named "code", for Epic's authorization
            // code (ADR 0013, D22). This one is the failed call's error.code -- the HTTP status
            // the failure maps to, never a value Epic sent -- so it is suppressed on this one
            // record and nowhere else.
            LogEvent.error(log, Operation.EPIC_LOGIN, failed.code(), failed.category(), // nosemgrep: be-log-sensitive-value
                            !failed.unavailable(), Category.NETWORK, Type.ERROR)
                    .addKeyValue(LogEvent.EPIC_CALL, failed.call().tag())
                    .addKeyValue(LogEvent.LOGIN_METHOD, AuditLoginMethod.SSO.value())
                    .log();
        }
    }
}

package com.example.backend.auth.epic.controller;

import com.example.backend.audit.domain.AuditLoginMethod;
import com.example.backend.auth.application.LoginService;
import com.example.backend.auth.domain.EpicLoginFailureReason;
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
import org.springframework.security.core.AuthenticationException;
import org.springframework.stereotype.Component;

/**
 * Where an Epic Login that failed before any login decision ends (flow step 8, D23, D24): at the
 * authorize hop, when discovery failed, or at the callback, when anything from the pending
 * request to the {@code id_token} did.
 *
 * <p>Epic being unavailable — no answer in time, or a {@code 5xx}, from discovery, the JWKS or
 * the token endpoint — is told apart from a refusal, and recorded once, here: a
 * {@code LOGIN_FAILURE} under method {@code sso} with {@code EPIC_UNAVAILABLE} and no subject,
 * the {@code epic.login} count under {@code outcome=unavailable}, and one {@code ERROR} naming
 * the call and its section 5 category, with no follow-up — Epic being down is not ours to fix.
 * The browser lands at {@code /?signin=unavailable}.
 *
 * <p>Anything else is a refusal, which lands at {@code /?signin=refused}. Epic refusing our own
 * credential ({@code cert/auth}) or answering with something unusable ({@code data}) is also an
 * {@code ERROR} — one that needs a person, since a key or a registration is likely wrong.
 *
 * <p>Either way the session the browser held is ended first, whoever it belonged to, and the
 * browser told nothing more (D23, D24).
 */
@Component
public class EpicLoginFailureHandler implements EpicSignInFailure {

    private static final Logger log = LoggerFactory.getLogger(EpicLoginFailureHandler.class);

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
        EpicSignInRedirect.refused(request, response);
    }

    /**
     * The one {@code ERROR} of a failed Epic call: which call, and its section 5
     * {@code error.category} and code, followed up unless Epic was merely unavailable. No user:
     * the browser's session, if it had one, is not whom the launch was for.
     */
    private static void logFailedCall(EpicOutboundException failed) {
        try (LogContext.Scope unresolved = LogContext.userId(null)) {
            LogEvent.error(log, Operation.EPIC_LOGIN, failed.code(), failed.category(),
                            !failed.unavailable(), Category.NETWORK, Type.ERROR)
                    .addKeyValue(LogEvent.EPIC_CALL, failed.call().tag())
                    .addKeyValue(LogEvent.LOGIN_METHOD, AuditLoginMethod.SSO.value())
                    .log();
        }
    }
}

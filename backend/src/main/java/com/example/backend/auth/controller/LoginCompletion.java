package com.example.backend.auth.controller;

import com.example.backend.auth.application.LoginOutcome;
import com.example.backend.auth.application.LoginOutcome.SignedIn;
import com.example.backend.auth.application.LoginOutcomeService;
import com.example.backend.auth.application.LoginService;
import com.example.backend.auth.application.LoginService.AcceptedLogin;
import com.example.backend.auth.application.LoginService.LoginDecision;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Function;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

/**
 * How every Login ends, by either login method: the one place the steps that end a Login run, in
 * one order (ADR 0013, its addendum of 2026-10-10).
 *
 * <ol>
 *   <li><b>Decide.</b> The login decision is {@link LoginService}'s, handed the id of the session
 *       the browser holds, which the Login continues in. It counts the attempt (ADR 0001), writes
 *       an accepted Login's fail-closed {@code LOGIN_SUCCESS} in its own transaction (ADR 0004),
 *       and records a refusal it reached, so no caller can refuse a Login without the record.
 *   <li><b>Establish.</b> An accepted Login's session is signed in by {@link SessionEstablishment}:
 *       rotated, its security context saved, indexed, the pre-login CSRF token dropped.
 *   <li>The login method's own work on the signed-in session, if it has any — an Epic Login keeps
 *       Epic's tokens there — so it lands under the rotated id alone.
 *   <li><b>Record</b> the ending, once, through {@link LoginOutcomeService}: the
 *       {@code user-authentication} record and the {@code login} success count, naming the session
 *       the User goes on to use. Only now, so a Login that never got its session — the session
 *       step or the method's work failing — is not logged or counted a success.
 *   <li><b>On a refusal, end the browser's session</b>, whoever it belonged to, and clear the
 *       security context (ADR 0013, D24, and its password analogue), so a shared browser is never
 *       left signed in as the previous User after a Login that signed nobody in. A refused Login
 *       that arrived without a session creates none.
 * </ol>
 *
 * <p>An Epic Login can also end before any login decision — at the launch, the authorize hop or
 * the callback ({@code EpicLoginFailureHandler}) — and {@link #endUndecided} ends it the same way:
 * its outcome recorded, once, naming the session it ran in, and that session ended.
 *
 * <p>What the browser is answered is left to each login method's web adapter, as a function of
 * the ending alone: a password Login's {@code UserResponse} or bare {@code 401}, an Epic Login's
 * redirect. A web adapter rather than an application service, because establishing and ending a
 * session are servlet work; it depends on no Epic bean, since password Login exists whether Epic
 * Login is on or not.
 */
@Component
public class LoginCompletion {

    private final SessionEstablishment sessionEstablishment;

    private final LoginOutcomeService outcomes;

    public LoginCompletion(SessionEstablishment sessionEstablishment, LoginOutcomeService outcomes) {
        this.sessionEstablishment = sessionEstablishment;
        this.outcomes = outcomes;
    }

    /**
     * {@link #complete(Function, Consumer, HttpServletRequest, HttpServletResponse)} for a login
     * method with no work of its own on the signed-in session.
     */
    public Optional<SignedInSession> complete(Function<String, LoginDecision> decide,
            HttpServletRequest request, HttpServletResponse response) {
        return complete(decide, signedIn -> { }, request, response);
    }

    /**
     * Completes a Login: decides it, then signs its session in and records it, or ends the
     * browser's session.
     *
     * @param decide     the login decision, handed the id of the session the browser holds — the
     *                   one the Login continues in — or {@code null} when it holds none
     * @param onSignedIn the login method's own work on the signed-in session, run before the
     *                   ending is recorded; run only for an accepted Login
     * @return the session the Login signed in, or empty when it was refused — a refusal its
     *     decision has recorded already
     */
    public Optional<SignedInSession> complete(Function<String, LoginDecision> decide,
            Consumer<HttpSession> onSignedIn, HttpServletRequest request,
            HttpServletResponse response) {
        HttpSession existing = request.getSession(false);
        Optional<AcceptedLogin> decided =
                decide.apply(existing == null ? null : existing.getId()).accepted();
        if (decided.isEmpty()) {
            // Recorded by the decision already, which counted it; ended here.
            endBrowserSession(request);
            return Optional.empty();
        }
        AcceptedLogin accepted = decided.get();
        HttpSession session = sessionEstablishment.establish(accepted, request, response);
        onSignedIn.accept(session);
        outcomes.record(accepted.signedIn(), session.getId());
        return Optional.of(new SignedInSession(accepted.authentication(), session));
    }

    /**
     * Ends a Login that failed before any login decision was made: records {@code outcome},
     * naming the session the Login ran in, then ends that session.
     *
     * @throws IllegalArgumentException if {@code outcome} is a signed-in one, which only a
     *     decision reaches
     */
    public void endUndecided(LoginOutcome outcome, HttpServletRequest request) {
        if (outcome instanceof SignedIn) {
            throw new IllegalArgumentException("a Login signs in only by a login decision");
        }
        HttpSession session = request.getSession(false);
        outcomes.record(outcome, session == null ? null : session.getId());
        endBrowserSession(request);
    }

    /** Ends whatever session the browser held, whoever it belonged to, and clears the context. */
    private static void endBrowserSession(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session != null) {
            session.invalidate();
        }
        SecurityContextHolder.clearContext();
    }

    /**
     * The session an accepted Login signed in, under its rotated id, and the authentication it was
     * signed in as.
     */
    public record SignedInSession(Authentication authentication, HttpSession session) {
    }
}

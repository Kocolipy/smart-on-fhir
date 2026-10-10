package com.example.backend.auth.epic.controller;

import com.example.backend.audit.domain.AuditLoginMethod;
import com.example.backend.auth.application.LoginOutcome;
import com.example.backend.auth.application.LoginOutcome.Refused;
import com.example.backend.auth.application.LoginOutcome.SignedIn;
import com.example.backend.auth.application.LoginOutcome.Unavailable;
import com.example.backend.auth.epic.EpicSignInRedirect;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;

/**
 * Where the browser lands once an Epic Login ended, as a function of how it ended and nothing
 * else: signed in, at {@code /}; refused, at {@code /?signin=refused}; Epic unavailable, at
 * {@code /?signin=unavailable} — the last two with the browser's session ended first (D23, D24).
 *
 * <p>The redirect is the one effect of an ending {@code LoginOutcomeService} cannot have, the
 * application layer knowing no servlet; this is that effect's one mapping, which both Epic
 * handlers end in. A password Login's is {@code AuthController}'s: the bare {@code 401}, after
 * the same end of the browser's session. A password Login's outcome handed here is therefore a
 * caller's bug, refused rather than redirected: it shares the {@link LoginOutcome} type, so only
 * its login method tells it apart.
 */
final class EpicLoginLanding {

    private EpicLoginLanding() {
    }

    /**
     * Sends the browser where an Epic Login that ended in {@code outcome} lands.
     *
     * @throws IllegalArgumentException if {@code outcome} is a password Login's
     */
    static void after(
            LoginOutcome outcome, HttpServletRequest request, HttpServletResponse response)
            throws IOException {
        if (!isEpic(outcome)) {
            throw new IllegalArgumentException(
                    "not an Epic Login's outcome: a password Login lands as a 401");
        }
        switch (outcome) {
            case SignedIn signedIn -> EpicSignInRedirect.signedIn(request, response);
            case Refused refused -> EpicSignInRedirect.refused(request, response);
            case Unavailable unavailable -> EpicSignInRedirect.unavailable(request, response);
        }
    }

    private static boolean isEpic(LoginOutcome outcome) {
        return switch (outcome) {
            case SignedIn signedIn -> signedIn.method() == AuditLoginMethod.SSO;
            case Refused refused -> refused.method() == AuditLoginMethod.SSO;
            case Unavailable unavailable -> true;
        };
    }
}

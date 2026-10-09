package com.example.backend.auth.epic.controller;

import com.example.backend.auth.application.EpicLoginOutcome;
import com.example.backend.auth.application.EpicLoginOutcome.Refused;
import com.example.backend.auth.application.EpicLoginOutcome.SignedIn;
import com.example.backend.auth.application.EpicLoginOutcome.Unavailable;
import com.example.backend.auth.epic.EpicSignInRedirect;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;

/**
 * Where the browser lands once an Epic Login ended, as a function of how it ended and nothing
 * else: signed in, at {@code /}; refused, at {@code /?signin=refused}; Epic unavailable, at
 * {@code /?signin=unavailable} — the last two with the browser's session ended first (D23, D24).
 *
 * <p>The redirect is the one effect of an ending {@code EpicLoginOutcomeService} cannot have, the
 * application layer knowing no servlet; this is that effect's one mapping, which both Epic
 * handlers end in.
 */
final class EpicLoginLanding {

    private EpicLoginLanding() {
    }

    /** Sends the browser where a Login that ended in {@code outcome} lands. */
    static void after(
            EpicLoginOutcome outcome, HttpServletRequest request, HttpServletResponse response)
            throws IOException {
        switch (outcome) {
            case SignedIn signedIn -> EpicSignInRedirect.signedIn(request, response);
            case Refused refused -> EpicSignInRedirect.refused(request, response);
            case Unavailable unavailable -> EpicSignInRedirect.unavailable(request, response);
        }
    }
}

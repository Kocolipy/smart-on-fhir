package com.example.backend.auth.epic;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.io.IOException;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * Where an Epic Login sends the browser when it ends: the application root, signed in; the root
 * marked {@code ?signin=refused}; or the root marked {@code ?signin=unavailable} when Epic could
 * not be reached. Neither mark carries any further detail (D23).
 *
 * <p>Every launch is a fresh Login (D9), so a refused or unavailable launch also ends whatever
 * session the browser held, whoever it belonged to (D24): a shared workstation is never left
 * signed in as the previous User after a launch that did not sign anyone in.
 *
 * <p>Redirects are relative to the application's context path, so the SPA's own routing takes
 * over from {@code /}: {@code /api/auth/me} answers {@code authenticated} and the guest route
 * sends the clinician on to {@code /showcase} (D11).
 */
public final class EpicSignInRedirect {

    /** The SPA root, where a signed-in browser lands. */
    static final String SIGNED_IN = "/";

    /** The SPA root, marked so the login page can say a sign-in from Epic was refused. */
    static final String REFUSED = "/?signin=refused";

    /** The SPA root, marked so the login page can say to try Epic again shortly. */
    static final String UNAVAILABLE = "/?signin=unavailable";

    private EpicSignInRedirect() {
    }

    /** Lands the browser at the application root, signed in. */
    public static void signedIn(HttpServletRequest request, HttpServletResponse response)
            throws IOException {
        response.sendRedirect(request.getContextPath() + SIGNED_IN);
    }

    /** Ends any session the browser holds and lands it at the root, marked refused. */
    public static void refused(HttpServletRequest request, HttpServletResponse response)
            throws IOException {
        signedOutTo(REFUSED, request, response);
    }

    /** Ends any session the browser holds and lands it at the root, marked unavailable. */
    public static void unavailable(HttpServletRequest request, HttpServletResponse response)
            throws IOException {
        signedOutTo(UNAVAILABLE, request, response);
    }

    private static void signedOutTo(
            String landing, HttpServletRequest request, HttpServletResponse response)
            throws IOException {
        HttpSession session = request.getSession(false);
        if (session != null) {
            session.invalidate();
        }
        SecurityContextHolder.clearContext();
        response.sendRedirect(request.getContextPath() + landing);
    }
}

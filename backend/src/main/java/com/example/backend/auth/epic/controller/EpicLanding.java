package com.example.backend.auth.epic.controller;

import com.example.backend.auth.epic.LaunchContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;

/**
 * Where the browser lands once an Epic Login has ended: an SPA route, relative to the context
 * path so the SPA's own routing takes over — Home when signed in, otherwise the login page,
 * marked with how the Login ended and nothing more (D23). The one place both Epic handlers'
 * redirects are spelled; the session work behind them is {@code LoginCompletion}'s.
 */
enum EpicLanding {

    /** Refused, so the login page can say a sign-in from Epic was refused, with no detail. */
    REFUSED("/login?signin=refused"),

    /** Epic unavailable, so the login page can say to try Epic again shortly. */
    UNAVAILABLE("/login?signin=unavailable");

    /** Home, the SPA root. */
    private static final String HOME = "/";

    private final String path;

    EpicLanding(String path) {
        this.path = path;
    }

    /** Redirects the browser here. */
    void sendTo(HttpServletRequest request, HttpServletResponse response) throws IOException {
        response.sendRedirect(request.getContextPath() + path);
    }

    /**
     * Signed in: redirects the browser to Home, the SPA root, where {@code /api/auth/me} answers
     * {@code authenticated} and the protected route renders for the clinician (D11) — the launch
     * context in the fragment, {@code /#patient=<id>&encounter=<id>} or the bare {@code /#launch},
     * which the SPA reads and strips (D8). A fragment is never sent to a server, so the launch
     * context reaches no request line and no access log.
     */
    static void signedIn(LaunchContext launchContext, HttpServletRequest request,
            HttpServletResponse response) throws IOException {
        response.sendRedirect(request.getContextPath() + HOME + "#" + launchContext.fragment());
    }
}

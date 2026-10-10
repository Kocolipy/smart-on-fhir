package com.example.backend.auth.epic.controller;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;

/**
 * Where the browser lands once an Epic Login has ended: the SPA root, relative to the context
 * path so the SPA's own routing takes over, marked with how the Login ended and nothing more
 * (D23). The one place both Epic handlers' redirects are spelled; the session work behind them is
 * {@code LoginCompletion}'s.
 */
enum EpicLanding {

    /**
     * Signed in: {@code /api/auth/me} answers {@code authenticated} and the guest route sends the
     * clinician on to {@code /showcase} (D11).
     */
    SIGNED_IN("/"),

    /** Refused, so the login page can say a sign-in from Epic was refused, with no detail. */
    REFUSED("/?signin=refused"),

    /** Epic unavailable, so the login page can say to try Epic again shortly. */
    UNAVAILABLE("/?signin=unavailable");

    private final String path;

    EpicLanding(String path) {
        this.path = path;
    }

    /** Redirects the browser here. */
    void sendTo(HttpServletRequest request, HttpServletResponse response) throws IOException {
        response.sendRedirect(request.getContextPath() + path);
    }
}

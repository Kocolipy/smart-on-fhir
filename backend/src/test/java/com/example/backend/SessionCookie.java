package com.example.backend;

/**
 * The session cookie's name, as the deployed service issues it — the one name every test that
 * names the session cookie uses.
 *
 * <p>It is configured once, as {@code server.servlet.session.cookie.name} in
 * {@code src/main/resources/session.yaml}, which both the main and the test resources'
 * {@code application.yaml} import, so every Spring test context issues it too.
 * {@code LoginSessionHardeningIntegrationTests} holds this constant to the shipped configuration
 * and to the cookie a real login is issued; the servlet-mock tests, which run no context, and
 * the log-leak check use it as it stands.
 */
public final class SessionCookie {

    /** The name the shipped configuration gives the session cookie. */
    public static final String NAME = "JSESSIONID";

    private SessionCookie() {
    }
}

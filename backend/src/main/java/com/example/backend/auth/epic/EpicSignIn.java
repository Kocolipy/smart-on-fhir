package com.example.backend.auth.epic;

import org.springframework.security.web.authentication.AuthenticationSuccessHandler;

/**
 * Where an Epic Login's verified identity becomes a Login (flow steps 5–7): the success handler
 * the OAuth 2.0 login filter hands Epic's validated {@code id_token} to.
 *
 * <p>A type of its own so the Epic security configuration finds the one implementation — a web
 * adapter — by type rather than by a bean name both sides would have to spell alike, and without
 * depending on the web adapter itself.
 */
public interface EpicSignIn extends AuthenticationSuccessHandler {
}

package com.example.backend.auth.epic;

import org.springframework.security.web.authentication.AuthenticationFailureHandler;

/**
 * Where an Epic Login that failed before a login decision ends (flow step 8): the failure handler
 * of both the authorize hop and the callback, handed whatever failed — an OAuth error, a failed
 * check, or an Epic call that failed ({@link EpicOutboundException}).
 *
 * <p>A type of its own for the reason {@link EpicSignIn} is one: the Epic security configuration
 * finds the one implementation, a web adapter, by type, without depending on the web adapter.
 */
public interface EpicSignInFailure extends AuthenticationFailureHandler {
}

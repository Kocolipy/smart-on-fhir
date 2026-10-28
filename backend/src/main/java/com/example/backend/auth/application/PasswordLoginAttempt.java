package com.example.backend.auth.application;

import java.util.UUID;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;

/**
 * A password Login's request to Spring Security, which also keeps what the authentication's one
 * read of the User found: the User it resolved, by stable id, and the credential it compared the
 * submitted password against.
 *
 * <p>Spring Security's {@code DaoAuthenticationProvider} refuses by throwing, and hands back no
 * User with the refusal, so a refusal could otherwise only be counted by looking the submitted
 * name up again — and a directory rename landing in between would move the failure, a Lockout
 * and its revocation onto whichever User holds the name by then. {@link PasswordLoginProvider}
 * records the User here during the provider's own read, so nothing is looked up twice and no
 * lookup or comparison is added to any refusal.
 *
 * <p>The request only: Spring Security builds a new authentication for an accepted Login, so this
 * object, and the credential it holds, never reach the security context or the session. Its
 * fields are transient for the same reason, and it is never serialized.
 */
final class PasswordLoginAttempt extends UsernamePasswordAuthenticationToken {

    private static final long serialVersionUID = 1L;

    /** The User the authentication's read resolved, or {@code null} when it found none. */
    private transient UUID resolvedUserId;

    /** The stored credential the submitted password was compared against, if one was. */
    private transient String comparedPasswordHash;

    PasswordLoginAttempt(String username, String password) {
        super(username, password);
    }

    /**
     * The read found {@code user}, with a password the submitted one is then compared against.
     */
    void resolved(LoginPrincipal user) {
        resolvedUserId = user.userId();
        comparedPasswordHash = user.getPassword();
    }

    /**
     * The read found the User {@code userId}, which has no password and so no password Login: it
     * is refused as an unknown name is, but the refusal still names it.
     */
    void resolvedWithoutPassword(UUID userId) {
        resolvedUserId = userId;
    }

    /** The User the authentication's read resolved, or {@code null} when it found none. */
    UUID resolvedUserId() {
        return resolvedUserId;
    }

    /**
     * The credential an accepted authentication verified, for the login-state module to confirm
     * under the User's lock that it is still the stored one.
     *
     * @throws IllegalStateException if the authentication compared no stored credential: an
     *     authentication that did not come from {@link PasswordLoginProvider} verified nothing
     *     this Login can stand on, so it fails closed rather than skip the check
     */
    LoginStateService.VerifiedPassword verifiedCredential() {
        if (comparedPasswordHash == null) {
            throw new IllegalStateException("the authentication verified no stored credential");
        }
        return LoginStateService.VerifiedCredential.passwordHash(comparedPasswordHash);
    }
}

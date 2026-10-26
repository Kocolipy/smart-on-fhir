package com.example.backend.auth.application;

import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;

/**
 * Password Login's {@link DaoAuthenticationProvider}, unchanged in what it compares and refuses,
 * which also tells the {@link PasswordLoginAttempt} it was handed which User its one read found.
 *
 * <p>Two places see that User, and between them every outcome but an unknown name:
 *
 * <ul>
 *   <li>{@link #additionalAuthenticationChecks}, the comparison itself, which the provider makes
 *       for every User it found with a password — a wrong password, the right one, and a locked or
 *       deactivated User alike, since {@code alwaysPerformAdditionalChecksOnUser} compares before
 *       the account-status refusal is rethrown ({@code SecurityConfig#authenticationManager}). It
 *       is handed the User the read returned, credential included, before anything erases it.
 *   <li>{@link #authenticate}'s refusal of a User with no password, which
 *       {@link LoginIdentityService#loadUserByUsername} reports as no User: the provider hides that
 *       behind a wrong password, keeping the report as the refusal's cause, and the report names
 *       the User it found.
 * </ul>
 *
 * <p>Neither adds a lookup or a comparison: each reads what the provider already holds, so every
 * refusal still costs the one Argon2id verification it did ({@code RefusalTimingEquivalenceTests}).
 */
public class PasswordLoginProvider extends DaoAuthenticationProvider {

    public PasswordLoginProvider(UserDetailsService users) {
        super(users);
    }

    @Override
    public Authentication authenticate(Authentication request) throws AuthenticationException {
        try {
            return super.authenticate(request);
        } catch (BadCredentialsException refused) {
            if (request instanceof PasswordLoginAttempt attempt
                    && refused.getCause() instanceof LoginIdentityService.NoPasswordLoginException
                            passwordless) {
                attempt.resolvedWithoutPassword(passwordless.userId());
            }
            throw refused;
        }
    }

    @Override
    protected void additionalAuthenticationChecks(
            UserDetails user, UsernamePasswordAuthenticationToken request)
            throws AuthenticationException {
        if (request instanceof PasswordLoginAttempt attempt
                && user instanceof LoginPrincipal principal) {
            attempt.resolved(principal);
        }
        super.additionalAuthenticationChecks(user, request);
    }
}

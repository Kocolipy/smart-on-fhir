package com.example.backend.auth.application;

import java.util.Collection;
import java.util.Objects;
import java.util.UUID;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;

/**
 * The User a Login authenticated, as Spring Security holds it, carrying the User's stable id
 * beside the {@code userName} Spring Security names it by.
 *
 * <p>{@link LoginIdentityService} builds it from the one read of the User a Login makes, and
 * everything the Login does after authenticating — recording the success, the One session per
 * User sweep, the session index, the outcome the Login completes — works from {@link #userId()}.
 * Looking the User up again by {@code userName} would follow whichever User holds the name by
 * then: a directory rename can land between the two reads.
 *
 * <p>A {@link User} still, so Spring Security's own handling of it — the account-status checks,
 * erasing the credential once the Login is accepted, and naming it by {@link #getUsername()} — is
 * unchanged. Serializable, as the security context stored in the session must be. Equality is
 * {@link User}'s own, by {@code userName}.
 */
public final class LoginPrincipal extends User {

    private static final long serialVersionUID = 1L;

    /**
     * What a principal issued with no credential is constructed with, before it is erased:
     * Spring Security's {@code User} refuses a {@code null}. Not a credential: nothing compares
     * against it.
     */
    private static final String NO_CREDENTIAL = "";

    private final UUID userId;

    LoginPrincipal(UUID userId, UserDetails details) {
        super(details.getUsername(), details.getPassword(), details.isEnabled(),
                details.isAccountNonExpired(), details.isCredentialsNonExpired(),
                details.isAccountNonLocked(), details.getAuthorities());
        this.userId = Objects.requireNonNull(userId, "an authenticated User has a stable id");
    }

    /**
     * The User {@code userId}, named {@code username}, holding {@code authorities} and no
     * credential: a principal issued from an authentication rather than read from the directory,
     * so it carries no password, and its account standing is the accepted Login's.
     */
    private LoginPrincipal(
            UUID userId, String username, Collection<? extends GrantedAuthority> authorities) {
        super(username, NO_CREDENTIAL, authorities);
        this.userId = Objects.requireNonNull(userId, "an authenticated User has a stable id");
        eraseCredentials();
    }

    /**
     * This principal holding {@code authorities} in place of its own, and no credential: the
     * same User, by the same stable id and {@code userName}.
     */
    LoginPrincipal holding(Collection<? extends GrantedAuthority> authorities) {
        return new LoginPrincipal(userId, getUsername(), authorities);
    }

    /** The authenticated User's stable id: its SCIM resource id. */
    public UUID userId() {
        return userId;
    }

    /**
     * The stable id of the User {@code authentication} authenticated.
     *
     * @throws IllegalStateException if its principal is not a {@code LoginPrincipal}: such an
     *     authentication names a User only by {@code userName}, and a Login refuses to guess
     *     whom that name means now
     */
    static UUID userIdOf(Authentication authentication) {
        if (authentication.getPrincipal() instanceof LoginPrincipal principal) {
            return principal.userId();
        }
        throw new IllegalStateException("the authentication carries no User's stable id");
    }
}

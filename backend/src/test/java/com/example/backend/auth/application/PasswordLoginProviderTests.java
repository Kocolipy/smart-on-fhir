package com.example.backend.auth.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import com.example.backend.auth.config.SecurityConfig;
import com.example.backend.authorization.TestGroupPermissions;
import com.example.backend.scim.InMemoryScimGroupRepository;
import com.example.backend.scim.InMemoryScimUserRepository;
import com.example.backend.scim.ScimIdentities;
import com.example.backend.scim.domain.ScimUser;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * Password Login's provider, through the {@code AuthenticationProvider} interface Spring Security
 * calls it by: it tells a {@link PasswordLoginAttempt} which User its read found, and is otherwise
 * Spring Security's own {@code DaoAuthenticationProvider} — a request that is not a Login attempt,
 * or a User the directory did not report, is authenticated and refused exactly as that provider
 * would, with nothing recorded.
 */
class PasswordLoginProviderTests {

    private static final String PASSWORD = "correct-password";

    private final InMemoryScimUserRepository users = new InMemoryScimUserRepository();

    private PasswordEncoder passwordEncoder;

    private PasswordLoginProvider provider;

    @BeforeEach
    void setUp() {
        passwordEncoder = new SecurityConfig().passwordEncoder();
        users.given(ScimUser.created(UUID.randomUUID(), ScimIdentities.profile("ada", true),
                passwordEncoder.encode(PASSWORD), ScimIdentities.NOW));
        provider = providerOver(new LoginIdentityService(users,
                new InMemoryScimGroupRepository(users), TestGroupPermissions.superuserOnly()));
    }

    @Test
    void aPlainRequestForADirectoryUserIsAuthenticatedAsSpringSecurityWould() {
        assertThat(provider.authenticate(
                        UsernamePasswordAuthenticationToken.unauthenticated("ada", PASSWORD))
                .isAuthenticated())
                .isTrue();
    }

    @Test
    void aPlainRequestWithTheWrongPasswordIsRefusedAsSpringSecurityWould() {
        assertThatExceptionOfType(BadCredentialsException.class).isThrownBy(() ->
                provider.authenticate(
                        UsernamePasswordAuthenticationToken.unauthenticated("ada", "wrong")));
    }

    /** A User reported by some other {@code UserDetailsService} names no stable id to record. */
    @Test
    void anAttemptForAUserNotFromTheDirectoryIsAuthenticatedAsSpringSecurityWould() {
        PasswordLoginProvider elsewhere = providerOver(name -> User.withUsername(name)
                .password(passwordEncoder.encode(PASSWORD)).authorities("ROLE_USER").build());

        assertThat(elsewhere.authenticate(new PasswordLoginAttempt("ada", PASSWORD))
                .isAuthenticated())
                .isTrue();
    }

    @Test
    void anAttemptForADirectoryUserRecordsTheUserItsReadFound() {
        PasswordLoginAttempt attempt = new PasswordLoginAttempt("ada", "wrong");

        assertThatExceptionOfType(BadCredentialsException.class)
                .isThrownBy(() -> provider.authenticate(attempt));

        assertThat(attempt.resolvedUserId()).isEqualTo(users.require("ada").id());
    }

    private PasswordLoginProvider providerOver(
            org.springframework.security.core.userdetails.UserDetailsService service) {
        PasswordLoginProvider built = new PasswordLoginProvider(service);
        built.setPasswordEncoder(passwordEncoder);
        built.setAlwaysPerformAdditionalChecksOnUser(true);
        return built;
    }
}

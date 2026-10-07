package com.example.backend.auth.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import com.example.backend.audit.CapturedLog;
import com.example.backend.audit.RecordingAuditTrail;
import com.example.backend.auth.InMemoryAccountSessions;
import com.example.backend.auth.MutableClock;
import com.example.backend.auth.PendingCommit;
import com.example.backend.auth.config.SecurityConfig;
import com.example.backend.authorization.TestRoleMappings;
import com.example.backend.observability.LogEvent;
import com.example.backend.scim.InMemoryScimGroupRepository;
import com.example.backend.scim.InMemoryScimUserRepository;
import com.example.backend.scim.ScimIdentities;
import com.example.backend.scim.domain.LockoutPolicy;
import com.example.backend.scim.domain.ScimLoginState;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.authentication.LockedException;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * The login decision for an Epic Login ({@link LoginService#logInFromEpic}), over the real
 * login path wired by hand as {@code LoginLockoutTests} wires it.
 *
 * <p>Epic proves who the clinician is, but Lockout and deactivation are this service's own, and
 * apply to an Epic Login exactly as to a password one (D12): Epic's word does not unlock or
 * reactivate anybody.
 */
class EpicLoginDecisionTests {

    private static final Instant NOW = Instant.parse("2026-09-24T07:00:00Z");

    private final InMemoryScimUserRepository users = new InMemoryScimUserRepository();
    private final InMemoryScimGroupRepository groups = new InMemoryScimGroupRepository(users);
    private final RecordingAuditTrail audit = new RecordingAuditTrail();

    private LoginService login;

    @BeforeEach
    void setUp() {
        SecurityConfig config = new SecurityConfig();
        PasswordEncoder passwordEncoder = config.passwordEncoder();
        LoginIdentityService identities = new LoginIdentityService(
                users, groups, passwordEncoder, TestRoleMappings.superuserOnly());
        login = new LoginService(
                config.authenticationManager(identities, passwordEncoder),
                new LoginAttemptService(
                        users,
                        new InMemoryAccountSessions(),
                        new PendingCommit(),
                        new LockoutPolicy(5),
                        audit,
                        new MutableClock(NOW)),
                identities);
    }

    @Test
    void aPractitionerIdLinkedToNoUserIsRefused() {
        assertThatThrownBy(() -> login.logInFromEpic("eNOBODY", null))
                .isInstanceOf(UsernameNotFoundException.class);
    }

    @Test
    void anAcceptedEpicLoginCarriesTheAuthoritiesOfAUserInNoMappedGroup() {
        users.given(ScimIdentities.user("eACTIVE"));

        assertThat(login.logInFromEpic("eACTIVE", null).authentication().getAuthorities())
                .extracting(GrantedAuthority::getAuthority)
                .containsExactlyInAnyOrder("ROLE_USER", "counter:read", "counter:write");
    }

    /** As for a password Login, the session's authentication never carries the stored hash. */
    @Test
    void anAcceptedEpicLoginCarriesNoPasswordHash() {
        users.given(ScimIdentities.user("eACTIVE"));

        Object principal = login.logInFromEpic("eACTIVE", null).authentication().getPrincipal();

        assertThat(((UserDetails) principal).getPassword()).isNull();
    }

    @Test
    void anAcceptedEpicLoginNamesTheRoleMappingItsAuthoritiesWereResolvedUnder() {
        users.given(ScimIdentities.user("eACTIVE"));

        assertThat(login.logInFromEpic("eACTIVE", null).roleMappingHash())
                .isEqualTo(TestRoleMappings.superuserOnly().hash());
    }

    @Test
    void aLockedUserIsRefusedAnEpicLogin() {
        users.given(ScimIdentities.userWithLoginState(
                "eLOCKED", new ScimLoginState("hash", 5, NOW)));

        assertThatThrownBy(() -> login.logInFromEpic("eLOCKED", null))
                .isInstanceOf(LockedException.class);
    }

    @Test
    void aDeactivatedUserIsRefusedAnEpicLogin() {
        users.given(ScimIdentities.inactiveUser("eRETIRED"));

        assertThatThrownBy(() -> login.logInFromEpic("eRETIRED", null))
                .isInstanceOf(DisabledException.class);
    }

    /** The accepted record says how the Login was made (D15), as the password one does. */
    @Test
    void theAcceptedEpicLoginRecordNamesTheSsoMethod() {
        users.given(ScimIdentities.user("eACTIVE"));

        try (CapturedLog captured = CapturedLog.attach()) {
            login.logInFromEpic("eACTIVE", null);

            List<ILoggingEvent> records =
                    captured.withAction(Level.INFO, LogEvent.ACTION, "user-authentication");
            assertThat(records).singleElement()
                    .satisfies(record -> assertThat(CapturedLog.fields(record))
                            .containsEntry(LogEvent.LOGIN_METHOD, "sso"));
        }
    }
}

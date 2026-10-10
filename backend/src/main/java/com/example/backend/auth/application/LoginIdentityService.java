package com.example.backend.auth.application;

import com.example.backend.authorization.domain.Permission;
import com.example.backend.authorization.domain.RoleMapping;
import com.example.backend.scim.domain.NormalizedUserName;
import com.example.backend.scim.domain.ReservedResourceName;
import com.example.backend.scim.domain.ScimGroupReference;
import com.example.backend.scim.domain.ScimGroupRepository;
import com.example.backend.scim.domain.ScimUser;
import com.example.backend.scim.domain.ScimUserRepository;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

/**
 * Reports a SCIM User to Spring Security, with the authority its Group membership confers.
 *
 * <p>This is the login path's whole view of the directory. It replaces the account aggregate's
 * {@code AccountService}, which read a table of its own; there is no second identity to read any
 * more, because a SCIM User carries the profile, the credential and the authentication state
 * together.
 *
 * <p>It lives in this slice rather than in {@code scim} because it serves the password-login
 * surface, not the SCIM protocol: it reaches the directory through the SCIM ports and adds
 * Spring Security's vocabulary on top. The dependency runs one way only — {@code auth} knows
 * about {@code scim}, and nothing in {@code scim} knows this class exists, which is what keeps
 * the two slices free of a cycle.
 *
 * <p>It reads and never writes. Administrative changes are {@link IdentityAdministrationService}'s,
 * so nothing the authentication path depends on is also able to mutate an identity.
 *
 * <h2>Authority is derived, not stored</h2>
 *
 * <p>There is no role column. Every active User gets {@code ROLE_USER} — baseline access is what
 * being an active identity means, and a redundant "Users" Group would be a second place for the
 * same fact — and nothing else is a role.
 *
 * <p>Derived HERE, which is once per login, and that is the specified behaviour rather than a
 * limitation: a session carries the authorities it was issued with, so adding a User to a mapped
 * Group grants its Role's Permissions at its next login and never mid-session. Recomputing
 * per request would make an authority change take effect at an unpredictable moment and would
 * put a database read on every authenticated request.
 *
 * <h2>Permissions come from the role mapping</h2>
 *
 * <p>Beside the roles, a User holds the {@link #BASELINE_PERMISSIONS} — the counter's, which every
 * active User may read and change — and the Permissions of every Role the {@link RoleMapping}
 * assigns to a Group it is a direct member of, derived here too, once per login, for the same
 * reasons. A session confined by a required password change holds none, as it holds no role.
 */
@Service
public class LoginIdentityService implements UserDetailsService {

    /** Spring Security's prefix on every role-derived authority. */
    private static final String ROLE_PREFIX = "ROLE_";

    /** Baseline access, which every active User has by being one. */
    private static final String USER_ROLE = "USER";

    /**
     * The Permissions every active User holds whatever its Groups: the counter's. Granted here, at
     * login, beside {@code ROLE_USER} rather than through a Role, because a Role is conferred by a
     * Group and a redundant "every User" Group would be a second place for the same fact. A session
     * confined by a required password change does not receive them.
     */
    public static final Set<Permission> BASELINE_PERMISSIONS =
            Set.copyOf(EnumSet.of(Permission.COUNTER_READ, Permission.COUNTER_WRITE));

    /**
     * The only authority a password Login by a User with a pending required password change
     * receives: it may read its own requirement, submit the change and log out, and nothing else.
     * An Epic Login is not confined by the flag ({@link #loadEpicLinkedUser}). Deliberately not a role and
     * not combined with {@code ROLE_USER} or any Permission — a flagged Superuser holds no
     * administrative authority until the credential is replaced, and the filter chain, which grants
     * every other application endpoint to {@code ROLE_USER} or a Permission only, refuses it
     * everywhere else.
     *
     * <p>Decided here, at authentication, rather than per request: the flag is not enumerable before
     * login (the User authenticates normally), and a session carries the authority it was issued
     * with, so clearing the flag takes effect at the next login — which the change forces, by
     * revoking every session.
     */
    public static final String PASSWORD_CHANGE_REQUIRED_AUTHORITY = "PASSWORD_CHANGE_REQUIRED";

    /**
     * A fixed passphrase encoded with the same {@link PasswordEncoder} this service is
     * configured with, standing in for a credentialless User's absent hash. Computed once, on
     * first use, from whatever encoder is injected — mirroring how
     * {@code DaoAuthenticationProvider} builds its own dummy hash for an unknown username —
     * rather than a literal encoded string fixed at compile time, which would silently stop
     * matching the encoder's parameters the moment they changed.
     *
     * <p>No password verifies against it — the encoded value matches nothing a caller can submit
     * — so {@code DaoAuthenticationProvider} still runs one real Argon2id comparison, at the
     * same cost as a genuine hash, before refusing. That uniformity, not the string's content,
     * is why one is needed at all: passing {@code null} through to
     * {@code User.withUsername(...).password(...)} would throw before any comparison happened,
     * which is a different and distinguishable failure mode from a wrong password.
     */
    private volatile String noPasswordSetMarker;

    private final ScimUserRepository users;
    private final ScimGroupRepository groups;
    private final PasswordEncoder passwordEncoder;
    private final RoleMapping roleMapping;

    public LoginIdentityService(
            ScimUserRepository users,
            ScimGroupRepository groups,
            PasswordEncoder passwordEncoder,
            RoleMapping roleMapping) {
        this.users = users;
        this.groups = groups;
        this.passwordEncoder = passwordEncoder;
        this.roleMapping = roleMapping;
    }

    /**
     * Reports the User to Spring Security, including whether it is currently locked and whether
     * an administrator has deactivated it. Carrying both here is what rejects such a User with
     * its correct password: {@code DaoAuthenticationProvider} refuses on account status whatever
     * the password, and compares the password first all the same, so the refusal costs what a
     * wrong password's does ({@code SecurityConfig#authenticationManager}).
     *
     * <p>A credentialless User — one with no password hash set — reports
     * {@link #noPasswordSetMarker()} rather than {@code null}: the User exists and may be active
     * and unlocked, but nothing submitted can match a hash nobody wrote, so it is refused on the
     * password check like any other wrong password, in the same amount of work.
     *
     * <p>The lookup is by the NORMALIZED userName, which is what uniqueness is decided on, so a
     * correct password is not refused because of how the name was typed. The returned
     * {@code UserDetails} still names the User by its stored {@code userName} — that stays Spring
     * Security's own vocabulary, and the login and {@code /me} responses continue to report it.
     */
    @Override
    public UserDetails loadUserByUsername(String username) {
        ScimUser user = users.findByNormalizedUserName(normalized(username))
                .orElseThrow(() -> new UsernameNotFoundException("User not found"));
        return userDetailsOf(user, Confinement.BY_CHANGE_REQUIRED_FLAG);
    }

    /**
     * The User an Epic Login's Practitioner ID links to (D2), reported to the caller as
     * {@link #loadUserByUsername} reports it to Spring Security — the same locked and disabled
     * flags, and the authorities a password Login gives the same User with the change-required flag
     * clear — or empty when it links to none.
     *
     * <p>The flag does not confine an Epic Login: an Epic Login presents no password of ours (D20),
     * so the imposed credential the flag marks is not what it used, and confining the session would
     * protect nothing. The flag itself is neither read into the session nor cleared, so a password
     * Login by the same User is still confined until it replaces the password (ADR 0008 addendum).
     *
     * <p>Found through the normal {@link NormalizedUserName} lookup, and then accepted only when
     * the stored {@code userName} equals the Practitioner ID character for character (D3):
     * normalization lowercases, and Epic IDs are case-sensitive, so {@code eabc} never signs in
     * the User provisioned as {@code eABC}. The Bootstrap Admin, recognised by its reservation
     * marker, links to nothing whatever its name (D6): password Login is its recovery path.
     */
    public Optional<UserDetails> loadEpicLinkedUser(String practitionerId) {
        if (practitionerId == null || practitionerId.isBlank()) {
            return Optional.empty();
        }
        return users.findByNormalizedUserName(NormalizedUserName.of(practitionerId))
                .filter(user -> user.profile().userName().equals(practitionerId))
                .filter(user -> user.reservedName() != ReservedResourceName.BOOTSTRAP_ADMIN)
                .map(user -> userDetailsOf(user, Confinement.NONE));
    }

    private UserDetails userDetailsOf(ScimUser user, Confinement confinement) {
        User.UserBuilder builder = User.withUsername(user.profile().userName())
                .password(user.login().hasPassword()
                        ? user.login().passwordHash()
                        : noPasswordSetMarker());
        if (confinement.confines(user.login().isPasswordChangeRequired())) {
            builder.authorities(PASSWORD_CHANGE_REQUIRED_AUTHORITY);
        } else {
            builder.authorities(authoritiesOf(user));
        }
        return builder
                .accountLocked(user.login().isLocked())
                // `active` is what the administrative listing reports, so authentication has to
                // honour it: an inactive User that could still log in would make the listing a
                // lie.
                .disabled(!user.profile().active())
                .build();
    }

    /**
     * The stable id behind a userName, for a caller that has just authenticated it and needs to
     * key application-owned state — the session index, the counter feature — by that id rather
     * than by the userName Spring Security itself keeps using.
     */
    public UUID resolveUserId(String username) {
        return users.findByNormalizedUserName(normalized(username))
                .map(ScimUser::id)
                .orElseThrow(() -> new UsernameNotFoundException("User not found"));
    }

    /**
     * The hash of the role mapping every Permission this service reports was resolved under. A
     * session records it beside the Permissions, so a session issued under another mapping can be
     * recognised as such.
     */
    public String roleMappingHash() {
        return roleMapping.hash();
    }

    /**
     * Baseline access, and the Permissions of every Role the User's direct Group memberships
     * confer.
     *
     * <p>Permissions are the UNION over the mapped Groups the User is a direct member of, so
     * holding an extra Role never takes a power away, and a User in no mapped Group holds none and
     * keeps {@code ROLE_USER}. Each is an authority spelled as its {@link Permission#value()}
     * ({@code user:read}), which carries no {@code ROLE_} prefix and so can never be mistaken for a
     * role. Their order here is immaterial: Spring Security's {@code User} keeps authorities sorted
     * by name, and {@code /api/auth/me} sorts the Permissions it reports itself.
     *
     * <p>There is no administrative role. Every protected operation requires its own Permission
     * (ADR 0010), so membership of the Admin group confers authority only through the Role the
     * mapping assigns it — the Superuser Role, which holds every Permission.
     */
    private List<GrantedAuthority> authoritiesOf(ScimUser user) {
        List<GrantedAuthority> authorities = new ArrayList<>();
        authorities.add(new SimpleGrantedAuthority(ROLE_PREFIX + USER_ROLE));
        List<UUID> memberOf = groups.findGroupsOfUser(user.id()).stream()
                .map(ScimGroupReference::id)
                .toList();
        Set<Permission> permissions = EnumSet.copyOf(BASELINE_PERMISSIONS);
        permissions.addAll(roleMapping.permissionsOf(memberOf));
        permissions.stream()
                .map(permission -> new SimpleGrantedAuthority(permission.value()))
                .forEach(authorities::add);
        return authorities;
    }

    /**
     * The submitted name in the form uniqueness is decided on, or a refusal.
     *
     * <p>A blank submission cannot normalize, and normalization throws on one. It is turned into
     * the same {@code UsernameNotFoundException} an unknown name produces, because that is what it
     * is: no User holds a blank userName, and a distinguishable failure here would tell a caller
     * that its input was rejected for a different reason than being wrong.
     */
    private static NormalizedUserName normalized(String username) {
        try {
            return NormalizedUserName.of(username);
        } catch (IllegalArgumentException blank) {
            throw new UsernameNotFoundException("User not found", blank);
        }
    }

    /**
     * Lazily computed and cached: encoding is the expensive Argon2id step this marker exists to
     * force on the refusal path, so it must happen once per process, not on every credentialless
     * login attempt.
     */
    private String noPasswordSetMarker() {
        String cached = noPasswordSetMarker;
        if (cached == null) {
            synchronized (this) {
                cached = noPasswordSetMarker;
                if (cached == null) {
                    cached = passwordEncoder.encode("no-password-set");
                    noPasswordSetMarker = cached;
                }
            }
        }
        return cached;
    }
}

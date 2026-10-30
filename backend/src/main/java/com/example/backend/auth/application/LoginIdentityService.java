package com.example.backend.auth.application;

import com.example.backend.authorization.domain.ConferredPower;
import com.example.backend.authorization.domain.GroupPermissions;
import com.example.backend.authorization.domain.Permission;
import com.example.backend.scim.domain.EdgeWhitespace;
import com.example.backend.scim.domain.NormalizedUserName;
import com.example.backend.scim.domain.ReservedResourceName;
import com.example.backend.scim.domain.ScimGroupReference;
import com.example.backend.scim.domain.ScimGroupRepository;
import com.example.backend.scim.domain.ScimUser;
import com.example.backend.scim.domain.ScimUserRepository;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.FactorGrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

/**
 * Reports a SCIM User to Spring Security, and to the Login's decision the authority its Group
 * membership confers.
 *
 * <p>This is the login path's whole view of the directory. There is no second identity to read,
 * because a SCIM User carries the profile, the credential and the authentication state together.
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
 * <p>Derived HERE ({@link #sessionAuthorities}), which is once per login, and that is the
 * specified behaviour rather than a limitation: a session carries the authorities it was issued
 * with, so adding a User to a Group grants the Group's Permissions at its next login and never
 * mid-session. Recomputing per request would make an authority change take effect at an
 * unpredictable moment and would put a database read on every authenticated request.
 *
 * <p>Derived under the User's lock, in the Login's decision: the login-state module asks for them
 * once it has read the User's standing there ({@link LoginStateService#passwordLogin},
 * {@link LoginStateService#epicLogin}), so they come from the same moment that accepted the
 * Login, after the password comparison. The User read before the lock — Spring Security's
 * {@link #loadUserByUsername}, and the Epic link's {@link #loadEpicLinkedUser} — carries none: a
 * Group membership removed, or a Group's Permissions reduced, while the password was compared
 * commits a revocation that finds no session of this Login yet, so authorities taken from that
 * earlier read would sign the removed power in (ADR 0002).
 *
 * <h2>Permissions come from the Groups</h2>
 *
 * <p>Beside the roles, a User holds the {@link #BASELINE_PERMISSIONS} — {@code patient:read},
 * which every active User holds — and the Permissions every Group it is a direct member of
 * carries ({@link GroupPermissions}, ADR 0015), derived here too, once per login under the
 * User's lock, for the same reasons. A session confined by a required password change holds
 * none, as it holds no role.
 */
@Service
public class LoginIdentityService implements UserDetailsService {

    /** Spring Security's prefix on every role-derived authority. */
    private static final String ROLE_PREFIX = "ROLE_";

    /** Baseline access, which every active User has by being one. */
    private static final String USER_ROLE = "USER";

    /**
     * The Superuser authority: the one session authority that is not a Permission, and so can
     * never be put on a Group. Held by a direct member of the Admin group; method security spells
     * it {@code hasRole('SUPERUSER')}, and {@code /api/auth/me} reports it as {@code superuser}.
     */
    public static final String SUPERUSER_AUTHORITY = ROLE_PREFIX + "SUPERUSER";

    /**
     * The Permissions every active User holds whatever its Groups: reading a patient. Granted
     * here, at login, beside {@code ROLE_USER} rather than through a Group, because a redundant
     * "every User" Group would be a second place for the same fact. A session confined by a required password change does not receive them.
     */
    public static final Set<Permission> BASELINE_PERMISSIONS =
            Set.copyOf(EnumSet.of(Permission.PATIENT_READ));

    /**
     * The only authority a password Login by a User with a pending required password change
     * receives: it may read its own requirement, submit the change and log out, and nothing else.
     * An Epic Login is not confined by the flag ({@link LoginStateService#epicLogin}). Deliberately
     * not a role and not combined with {@code ROLE_USER} or any Permission — a flagged Superuser holds no
     * administrative authority until the credential is replaced, and the filter chain, which grants
     * every other application endpoint to {@code ROLE_USER} or a Permission only, refuses it
     * everywhere else.
     *
     * <p>Decided at login, on the state the login-state module reads under the User's lock
     * ({@link #sessionAuthorities}), rather than per request: the flag is not enumerable before login (the User
     * authenticates normally), and a session carries the authority it was issued with, so
     * clearing the flag takes effect at the next login — which the change forces, by revoking
     * every session.
     */
    public static final String PASSWORD_CHANGE_REQUIRED_AUTHORITY = "PASSWORD_CHANGE_REQUIRED";

    /**
     * The password an Epic Login's principal carries: none. An Epic Login presents no password of
     * this service's (D20), so nothing compares against it, and Spring Security's {@code User}
     * refuses a {@code null}.
     */
    private static final String NO_PASSWORD = "";

    private final ScimUserRepository users;
    private final ScimGroupRepository groups;
    private final GroupPermissions groupPermissions;

    public LoginIdentityService(
            ScimUserRepository users,
            ScimGroupRepository groups,
            GroupPermissions groupPermissions) {
        this.users = users;
        this.groups = groups;
        this.groupPermissions = groupPermissions;
    }

    /**
     * Reports the User to Spring Security, including whether it is currently locked and whether
     * an administrator has deactivated it. Carrying both here is what rejects such a User with
     * its correct password: {@code DaoAuthenticationProvider} refuses on account status whatever
     * the password, and compares the password first all the same, so the refusal costs what a
     * wrong password's does ({@code SecurityConfig#authenticationManager}).
     *
     * <p>A credentialless User — one with no password hash set — has no password Login, whatever
     * is submitted: it is reported as no User at all. {@code DaoAuthenticationProvider} then
     * refuses it as it refuses an unknown name, never by authenticating, after one comparison
     * against its own dummy hash, so the refusal costs what a wrong password's does; and it hides
     * the refusal behind a wrong password, as which the Login records it, naming the User — the
     * report ({@link NoPasswordLoginException}) carries the User's stable id, which
     * {@link PasswordLoginProvider} hands the Login, so the refusal is recorded against the User
     * this read found without the name being looked up again.
     *
     * <p>The lookup is by the NORMALIZED userName, which is what uniqueness is decided on, so a
     * correct password is not refused because of how the name was typed. The returned
     * {@code UserDetails} still names the User by its stored {@code userName} — that stays Spring
     * Security's own vocabulary, and the login and {@code /me} responses continue to report it.
     */
    @Override
    public LoginPrincipal loadUserByUsername(String username) {
        ScimUser user = users.findByNormalizedUserName(normalized(username))
                .orElseThrow(() -> new UsernameNotFoundException("User not found"));
        if (!user.login().hasPassword()) {
            throw new NoPasswordLoginException(user.id());
        }
        return userDetailsOf(user, user.login().passwordHash());
    }

    /**
     * The report of a User found with no password: to Spring Security, no User at all — the same
     * {@link UsernameNotFoundException}, with the same message, an unknown name gets — carrying the
     * stable id of the User found, so the refusal is recorded against it
     * ({@link PasswordLoginProvider}). The id is not a secret, and the exception goes no further
     * than the login decision.
     */
    static final class NoPasswordLoginException extends UsernameNotFoundException {

        private static final long serialVersionUID = 1L;

        private final UUID userId;

        NoPasswordLoginException(UUID userId) {
            super("User not found");
            this.userId = userId;
        }

        /** The stable id of the User found, which has no password. */
        UUID userId() {
            return userId;
        }
    }

    /**
     * The User an Epic Login's Practitioner ID links to (D2), reported to the caller as
     * {@link #loadUserByUsername} reports it to Spring Security — the same locked and disabled
     * flags, and no authority, which the login decision reads under the User's lock — or empty
     * when it links to none.
     *
     * <p>The change-required flag does not confine an Epic Login ({@link LoginStateService#epicLogin}):
     * an Epic Login presents no password of ours (D20), so the imposed credential the flag marks is
     * not what it used, and confining the session would protect nothing. The flag itself is
     * neither read into the session nor cleared, so a password Login by the same User is still
     * confined until it replaces the password (ADR 0008).
     *
     * <p>Found through the normal {@link NormalizedUserName} lookup, and then accepted only when
     * the stored {@code userName} equals the Practitioner ID character for character (D3):
     * normalization lowercases, and Epic IDs are case-sensitive, so {@code eabc} never signs in
     * the User provisioned as {@code eABC}. The Bootstrap Admin, recognised by its reservation
     * marker, links to nothing whatever its name (D6): password Login is its recovery path.
     */
    public Optional<LoginPrincipal> loadEpicLinkedUser(String practitionerId) {
        if (practitionerId == null || practitionerId.isBlank()) {
            return Optional.empty();
        }
        return users.findByNormalizedUserName(NormalizedUserName.of(practitionerId))
                .filter(user -> user.profile().userName().equals(practitionerId))
                .filter(user -> user.reservedName() != ReservedResourceName.BOOTSTRAP_ADMIN)
                .map(user -> userDetailsOf(user, NO_PASSWORD));
    }

    /**
     * The User as a {@link LoginPrincipal}: Spring Security's view of it, carrying the stable id
     * the rest of the Login works from, so nothing after authentication reads the User by name.
     *
     * <p>It holds no authority. What the session may do is decided once, under the User's lock, in
     * the Login's decision, from the state the login-state module reads there
     * ({@link LoginStateService#passwordLogin} for a password Login,
     * {@link LoginStateService#epicLogin} for an Epic one; {@link #sessionAuthorities}), and
     * applied by {@link #issued} — not from this read, which a change committed while the
     * password was compared would leave behind: a change required, a Group membership removed, a
     * Group's Permissions reduced. Deriving it here too would only leave a second, staler answer
     * for some later path to issue by mistake.
     */
    private LoginPrincipal userDetailsOf(ScimUser user, String password) {
        return new LoginPrincipal(user.id(), User.withUsername(user.profile().userName())
                .password(password)
                .accountLocked(user.login().isLocked())
                // `active` is what the administrative listing reports, so authentication has to
                // honour it: an inactive User that could still log in would make the listing a
                // lie.
                .disabled(!user.profile().active())
                .build());
    }

    /**
     * The authorities an accepted Login's session is issued with, read by the login-state module
     * under the User {@code userId}'s lock, in the Login's decision transaction: for a
     * {@code confined} Login {@link #PASSWORD_CHANGE_REQUIRED_AUTHORITY} alone, with no Group
     * read — no role, no Permission, and in the Admin group not the Superuser either — and
     * otherwise baseline access and what the User's direct Groups confer ({@link #authoritiesOf}).
     */
    List<GrantedAuthority> sessionAuthorities(UUID userId, boolean confined) {
        if (confined) {
            return List.of(new SimpleGrantedAuthority(PASSWORD_CHANGE_REQUIRED_AUTHORITY));
        }
        return authoritiesOf(userId);
    }

    /**
     * The authentication an accepted Login's session is issued with: {@code authenticated}'s User,
     * holding {@code authorities} — the ones the login decision read under the User's lock
     * ({@link LoginStateService.LoginVerdict.Accepted#authorities}) — principal and token alike,
     * so the two can never disagree. Only the authentication factor Spring Security recorded
     * beside the authorities ({@link FactorGrantedAuthority}) is kept from {@code authenticated},
     * since it says how the User authenticated, not what it may do.
     *
     * @param authenticated the authentication the Login's credential was verified with, whose
     *     principal is a {@link LoginPrincipal} with its credential already erased
     */
    Authentication issued(
            Authentication authenticated, Collection<? extends GrantedAuthority> authorities) {
        return issued((LoginPrincipal) authenticated.getPrincipal(), authorities,
                authenticated.getAuthorities().stream()
                        .filter(FactorGrantedAuthority.class::isInstance)
                        .toList());
    }

    /**
     * The authentication an accepted Epic Login's session is issued with: {@code user}, holding
     * {@code authorities} as {@link #issued(Authentication, Collection)} has it, and no credential.
     * There is no authentication factor of this service's to keep: Epic checked the credential.
     */
    Authentication issued(LoginPrincipal user, Collection<? extends GrantedAuthority> authorities) {
        return issued(user, authorities, List.of());
    }

    private static Authentication issued(LoginPrincipal user,
            Collection<? extends GrantedAuthority> authorities,
            Collection<? extends GrantedAuthority> factors) {
        LoginPrincipal holding = user.holding(authorities);
        List<GrantedAuthority> issued = new ArrayList<>(holding.getAuthorities());
        issued.addAll(factors);
        return UsernamePasswordAuthenticationToken.authenticated(holding, null, issued);
    }

    /**
     * Baseline access, and the Permissions the User's direct Groups carry.
     *
     * <p>Permissions are the UNION over the Groups the User is a direct member of, so belonging to
     * an extra Group never takes a power away, and a User whose Groups carry none holds only the
     * baseline and keeps {@code ROLE_USER}. A Group with no Permissions confers nothing. Each is an
     * authority spelled as its {@link Permission#value()} ({@code user:read}), which carries no
     * {@code ROLE_} prefix and so can never be mistaken for a role. Their order here is
     * immaterial: Spring Security's {@code User} keeps authorities sorted by name, and
     * {@code /api/auth/me} sorts the Permissions it reports itself.
     *
     * <p>A direct member of the Admin group — the Group with the {@code admin-group} reservation
     * marker — is the Superuser (ADR 0015): {@code ROLE_SUPERUSER} and every
     * {@linkplain Permission#delegatable() delegatable} Permission, expanded in code and answered
     * with the Permissions in one question ({@link GroupPermissions#powerOf}), never from a
     * stored list or a role hierarchy. The Admin group carries no Permissions of its own. Every other protected
     * operation requires its own Permission, and connector administration requires the
     * Superuser. Membership is direct only: a Group's members are Users, never Groups.
     */
    private List<GrantedAuthority> authoritiesOf(UUID userId) {
        List<GrantedAuthority> authorities = new ArrayList<>();
        authorities.add(new SimpleGrantedAuthority(ROLE_PREFIX + USER_ROLE));
        List<UUID> memberOf = groups.findGroupsOfUser(userId).stream()
                .map(ScimGroupReference::id)
                .toList();
        ConferredPower conferred = groupPermissions.powerOf(memberOf);
        Set<Permission> permissions = EnumSet.copyOf(BASELINE_PERMISSIONS);
        permissions.addAll(conferred.permissions());
        if (conferred.superuser()) {
            authorities.add(new SimpleGrantedAuthority(SUPERUSER_AUTHORITY));
        }
        permissions.stream()
                .map(permission -> new SimpleGrantedAuthority(permission.value()))
                .forEach(authorities::add);
        return authorities;
    }

    /**
     * The submitted name in the form uniqueness is decided on, or a refusal.
     *
     * <p>A name that can name no User ({@link #lookupKeyOf}) is turned into the same
     * {@code UsernameNotFoundException} an unknown name produces, because that is what it is, and
     * a distinguishable failure here would tell a caller that its input was rejected for a
     * different reason than being wrong. {@code DaoAuthenticationProvider} still runs its dummy-hash
     * comparison on it, as on any unknown name, so it costs what a wrong name costs.
     */
    private static NormalizedUserName normalized(String username) {
        return lookupKeyOf(username)
                .orElseThrow(() -> new UsernameNotFoundException("User not found"));
    }

    /**
     * The key a submitted login name is looked up by, or empty when it can name no User — decided
     * before any lookup, so whatever the database would match it against never comes into it.
     *
     * <p>Two kinds of name can name nobody. A blank one cannot normalize: no User holds a blank
     * {@code userName}. One beginning or ending with whitespace ({@link EdgeWhitespace}) cannot
     * either, since the directory refuses such a {@code userName}; and on an engine that ignores
     * trailing spaces ({@link EdgeWhitespace} says which and why) {@code alice } would find
     * {@code alice}, sign in as her and lengthen her failure run. Refused here, it is an unknown
     * account on every engine. The password Login's one lookup takes its key from this rule, and
     * its failure is counted against the User that lookup found, so the two cannot disagree
     * about who an attempt was against.
     */
    private static Optional<NormalizedUserName> lookupKeyOf(String username) {
        if (username == null || username.isBlank() || EdgeWhitespace.borders(username)) {
            return Optional.empty();
        }
        return Optional.of(NormalizedUserName.of(username));
    }
}

package com.example.backend.auth.application;

import com.example.backend.audit.domain.AuditAdministrativeRefusal;
import com.example.backend.audit.domain.AuditLockCause;
import com.example.backend.audit.domain.AuditLoginStateAttribute;
import com.example.backend.audit.domain.AuditTrail;
import com.example.backend.audit.domain.Audited;
import com.example.backend.auth.domain.SessionRevocationCause;
import com.example.backend.observability.LogEvent;
import com.example.backend.observability.LogEvent.Category;
import com.example.backend.observability.LogEvent.Operation;
import com.example.backend.observability.LogEvent.Type;
import com.example.backend.observability.RetriedOnDeadlock;
import com.example.backend.scim.domain.LockCause;
import com.example.backend.scim.domain.LoginStateChange;
import com.example.backend.scim.domain.ReservedResourceName;
import com.example.backend.scim.domain.ScimGroup;
import com.example.backend.scim.domain.ScimGroupMember;
import com.example.backend.scim.domain.ScimGroupRepository;
import com.example.backend.scim.domain.ScimLoginState;
import com.example.backend.scim.domain.ScimUser;
import com.example.backend.scim.domain.ScimUserRepository;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The identity use cases an administrator drives: reviewing the directory, and the two operations
 * on application-owned state that the directory cannot perform — Unlock and the forced password
 * change.
 *
 * <p>Separate from {@link LoginIdentityService}, which serves the login path. Keeping them apart
 * means the authentication path does not depend on a class that also mutates identities, and the
 * administrative guards below live beside nothing the login path could accidentally bypass.
 *
 * <p>Nothing here writes a directory-owned attribute. {@code userName}, {@code active} and Group
 * membership are a connector's to set over SCIM; the Deactivate and Activate operations that used
 * to live here were removed because an administrator overriding {@code active} from the browser
 * would only be overwritten by the next synchronization. Every operation that remains addresses
 * its User by the stable resource id, never by the mutable {@code userName}.
 *
 * <h2>Why this is here and not in the scim slice</h2>
 *
 * <p>The forced change has to reach the sessions the identity is already holding, and the module
 * that ends them ({@link SessionRevocationService}) is this slice's: a session is a fact about the
 * login surface, not about the directory. The revocation is audited under
 * {@code FORCED_PASSWORD_CHANGE}, naming the administrator as its actor. So the use case that
 * needs both the directory and the
 * sessions lives on the side that owns the sessions and reaches the directory through its ports,
 * which is also the direction that keeps the two slices acyclic.
 */
@Service
public class IdentityAdministrationService {

    private static final Logger log = LoggerFactory.getLogger(IdentityAdministrationService.class);

    private static final String ACTOR_REQUIRED =
            "an administrative operation names its acting administrator by stable id";

    private final ScimUserRepository users;
    private final ScimGroupRepository groups;
    private final LoginStateService loginState;
    private final SessionRevocationService sessions;
    private final AuditTrail audit;

    public IdentityAdministrationService(
            ScimUserRepository users,
            ScimGroupRepository groups,
            LoginStateService loginState,
            SessionRevocationService sessions,
            AuditTrail audit) {
        this.users = users;
        this.groups = groups;
        this.loginState = loginState;
        this.sessions = sessions;
        this.audit = audit;
    }

    /**
     * Every identity, for administrative review. Never carries a password hash.
     *
     * <p>Every Group is read ONCE, memberships included, and both derived columns are answered
     * from it in memory — the Admin flag from the reserved Group's members, and each User's
     * direct Groups by inverting every Group's. The per-identity question is one statement each,
     * and a listing of a thousand identities would be a thousand of them to compute a column.
     */
    @Transactional(readOnly = true)
    public List<IdentitySummary> listIdentities() {
        List<ScimGroup> directory = groups.findAllOrderedByNormalizedDisplayName();
        Optional<ScimGroup> adminGroup = directory.stream()
                .filter(group -> group.reservedName() == ReservedResourceName.ADMIN_GROUP)
                .findFirst();
        Map<UUID, List<IdentitySummary.DirectGroup>> groupsByMember = new HashMap<>();
        for (ScimGroup group : directory) {
            IdentitySummary.DirectGroup reference =
                    new IdentitySummary.DirectGroup(group.id(), group.displayName());
            for (ScimGroupMember member : group.members()) {
                groupsByMember.computeIfAbsent(member.userId(), id -> new ArrayList<>())
                        .add(reference);
            }
        }
        return users.findAllOrderedByNormalizedUserName().stream()
                .map(user -> summarize(
                        user,
                        user.login(),
                        isAdmin(adminGroup, user),
                        groupsByMember.get(user.id())))
                .toList();
    }

    /**
     * Every Group, for the read-only Groups projection: its name, how many direct members it has,
     * and whether it is the protected Admin group — decided by the reservation marker, so a Group
     * a connector names "Admins" is not reported as one.
     */
    @Transactional(readOnly = true)
    public List<GroupSummary> listGroups() {
        return groups.findAllOrderedByNormalizedDisplayName().stream()
                .map(group -> new GroupSummary(
                        group.id(),
                        group.displayName(),
                        group.members().size(),
                        group.reservedName() == ReservedResourceName.ADMIN_GROUP))
                .toList();
    }

    /**
     * Ends a lockout, clearing the failure run with it, and requires a password change. Says nothing
     * about whether the identity is active — a deactivated identity can be unlocked, and stays
     * deactivated.
     *
     * <p>The only way a lockout ends, whatever its cause — a failure run or dormancy. Nothing
     * expires it and no other operation lifts it, so an identity that locked itself out, or that
     * the dormancy job locked, stays locked until an administrator performs exactly this.
     *
     * <p>What the Unlock does to the login state is decided and written by
     * {@link LoginStateService#unlocked}, and the response is built from that decision. Lifting a
     * lock also restarts the dormancy window, so the next dormancy run does not lock again a User
     * that has not yet had the chance to sign in. The audit event records the cause of the lock it
     * lifted and the paths the decision actually changed: the failure run when it held attempts,
     * and the lock with the dormancy basis when one stood.
     *
     * <p>Lifting a lock always requires a change of password, because the credential that reached
     * the threshold may be the one an attacker was guessing: after Unlock the User authenticates
     * and is confined to the change flow until it sets a new one. A credentialless User is unlocked
     * without the requirement — it has no password to replace. The flag is not a SCIM attribute,
     * so the version does not advance. No session is revoked: a locked User holds none, since
     * imposing the lock ended them.
     *
     * <p>No administrator may unlock their own account, whatever Permissions it holds; recovering
     * from a self-inflicted state takes a second administrator. The refusal is checked before
     * anything is written.
     *
     * <p>Idempotent on an identity serving no lockout: it is returned unchanged, no change is
     * required and the dormancy window is not restarted — there was no lockout for the credential
     * to have reached, and an Unlock is not a way to keep an unused account from going dormant.
     *
     * @param actorId the acting administrator's stable id, as its signed-in session holds it
     */
    @Transactional
    @RetriedOnDeadlock(Operation.UNLOCK)
    public IdentitySummary unlock(UUID userId, UUID actorId) {
        Objects.requireNonNull(actorId, ACTOR_REQUIRED);
        ScimUser user = require(userId);
        if (isSelf(user, actorId)) {
            refused(Operation.UNLOCK, user.id(), AuditAdministrativeRefusal.SELF_TARGET);
            audit.append(new Audited.UnlockRefused(
                    actorId, user.id(), AuditAdministrativeRefusal.SELF_TARGET));
            throw new ForbiddenIdentityChangeException("An Admin cannot unlock their own account");
        }
        LoginStateChange unlocked = loginState.unlocked(user);
        audit.append(new Audited.LockoutLiftedByUnlock(actorId, user.id(),
                auditCause(unlocked.before().lockCause()), unlocked.moved()));
        if (unlocked.hasMoved(AuditLoginStateAttribute.PASSWORD_CHANGE_REQUIRED_SINCE)) {
            audit.append(new Audited.PasswordChangeRequired(actorId, user.id(), unlocked.moved()));
        }
        succeeded(Operation.UNLOCK, user.id());
        return summarize(user, unlocked.after());
    }

    /** The lifted lock's cause in the audit trail's own vocabulary, or none when none stood. */
    private static AuditLockCause auditCause(LockCause cause) {
        if (cause == null) {
            return null;
        }
        return switch (cause) {
            case FAILURES -> AuditLockCause.FAILURES;
            case DORMANCY -> AuditLockCause.DORMANCY;
        };
    }

    /**
     * Requires the User to replace its password before it may do anything but submit that change
     * or log out, and ends every session it holds so the requirement applies from its next request.
     * The Admin never sees, chooses or transports the password: this invalidates the credential's
     * standing, it does not disclose or replace it.
     *
     * <p>Refused, before anything is written:
     *
     * <ul>
     *   <li>on the caller's own account, whatever Permissions it holds — the Bootstrap Admin's
     *       included, so no administrator acts on itself through the admin flow; a User replaces
     *       its own password through the self-service change instead;
     *   <li>on the Bootstrap Admin, by anyone else — it is the deployment's recovery identity;
     *   <li>on a credentialless User, which already cannot log in and has nothing to replace.
     * </ul>
     *
     * <p>What the forced change does to the login state is decided and written by
     * {@link LoginStateService#passwordChangeForced}, and the response is built from that
     * decision. It is idempotent there: a User already required to change is returned unchanged,
     * with the instant the change was first required kept, and nothing is audited or revoked.
     *
     * @param actorId the acting administrator's stable id, as its signed-in session holds it
     */
    @Transactional
    @RetriedOnDeadlock(Operation.FORCE_PASSWORD_CHANGE)
    public IdentitySummary forcePasswordChange(UUID userId, UUID actorId) {
        Objects.requireNonNull(actorId, ACTOR_REQUIRED);
        ScimUser user = require(userId);
        if (isSelf(user, actorId)) {
            throw refuseForcedChange(actorId, user.id(),
                    AuditAdministrativeRefusal.SELF_TARGET,
                    new ForbiddenIdentityChangeException(
                            "An administrator cannot force a password change on their own account"));
        }
        if (user.reservedName() == ReservedResourceName.BOOTSTRAP_ADMIN) {
            throw refuseForcedChange(actorId, user.id(),
                    AuditAdministrativeRefusal.PROTECTED_RESOURCE,
                    new ForbiddenIdentityChangeException(
                            "The bootstrap administrator's password change cannot be forced"));
        }
        if (!user.login().hasPassword()) {
            throw refuseForcedChange(actorId, user.id(),
                    AuditAdministrativeRefusal.CREDENTIALLESS_TARGET,
                    new UnsafeIdentityChangeException(
                            "A User with no password has no credential to replace"));
        }
        LoginStateChange forced = loginState.passwordChangeForced(user);
        if (!forced.hasMoved(AuditLoginStateAttribute.PASSWORD_CHANGE_REQUIRED_SINCE)) {
            return summarize(user, forced.after());
        }
        audit.append(new Audited.PasswordChangeRequired(actorId, user.id(), forced.moved()));
        sessions.revokeAllAfterCommit(
                user.id(), SessionRevocationCause.FORCED_PASSWORD_CHANGE, actorId);
        succeeded(Operation.FORCE_PASSWORD_CHANGE, user.id());
        return summarize(user, forced.after());
    }

    private RuntimeException refuseForcedChange(
            UUID actorId, UUID subjectId, AuditAdministrativeRefusal reason, RuntimeException refusal) {
        refused(Operation.FORCE_PASSWORD_CHANGE, subjectId, reason);
        audit.append(new Audited.PasswordChangeRequirementRefused(actorId, subjectId, reason));
        return refusal;
    }

    /**
     * Whether the caller is the identity they are acting on, compared by stable id.
     *
     * <p>The acting administrator is named by the stable id its signed-in session holds, never by
     * {@code userName}: a directory rename between signing in and acting would otherwise let an
     * administrator act on itself past the guard, or refuse it acting on the User that took its
     * former name.
     */
    private static boolean isSelf(ScimUser user, UUID actorId) {
        return user.id().equals(actorId);
    }

    private static boolean isAdmin(Optional<ScimGroup> adminGroup, ScimUser user) {
        return adminGroup.map(group -> group.hasMember(user.id())).orElse(false);
    }

    /**
     * The User behind a stable id. The id is what every operation addresses — a {@code userName}
     * a connector may rename between the Admin reading the row and acting on it, and an id it
     * cannot.
     */
    private ScimUser require(UUID userId) {
        return users.findById(userId).orElseThrow(UnknownIdentityException::new);
    }

    /**
     * Records an administrative write that went through.
     *
     * <p>The record names the action, and the identity acted on by its stable id as
     * {@code user.target.id}; the administrator who acted is {@code user.id}, which the request's
     * logging context already carries. Neither is ever a {@code userName}: that is not something
     * this service writes to a log. The audit trail remains the authoritative record of who changed
     * what; the log line is what an operator watching for unexpected activity reads.
     */
    private static void succeeded(Operation operation, UUID subjectId) {
        LogEvent.success(log, operation, Category.PROCESS, Type.ADMIN, Type.USER, Type.CHANGE)
                .addKeyValue(LogEvent.USER_TARGET_ID, subjectId.toString())
                .log();
    }

    /** Records an administrative write refused for {@code reason}, before anything was written. */
    private static void refused(
            Operation operation, UUID subjectId, AuditAdministrativeRefusal reason) {
        LogEvent.refused(log, operation, Category.PROCESS, Type.ADMIN, Type.USER, Type.DENIED)
                .addKeyValue(LogEvent.USER_TARGET_ID, subjectId.toString())
                .addKeyValue(LogEvent.REASON, reason.name())
                .log();
    }

    /**
     * One User's row, for an operation's response: its direct Groups read for it alone, and the
     * Admin flag from the reserved Group's membership — the same two answers the listing computes
     * for everyone at once. {@code login} is the state as the operation left it, which the stored
     * {@code user} it was read from predates.
     */
    private IdentitySummary summarize(ScimUser user, ScimLoginState login) {
        List<IdentitySummary.DirectGroup> direct = groups.findGroupsOfUser(user.id()).stream()
                .map(group -> new IdentitySummary.DirectGroup(group.id(), group.displayName()))
                .toList();
        return summarize(
                user,
                login,
                groups.isMemberOfReservedGroup(user.id(), ReservedResourceName.ADMIN_GROUP),
                direct);
    }

    /**
     * {@code direct} may be {@code null} for a User in no Group; the summary's own constructor
     * reads that as none, so the listing need not allocate an empty list per User.
     */
    private static IdentitySummary summarize(
            ScimUser user,
            ScimLoginState login,
            boolean admin,
            List<IdentitySummary.DirectGroup> direct) {
        return new IdentitySummary(
                user.id(),
                user.profile().userName(),
                user.profile().displayName(),
                admin,
                user.reservedName() == ReservedResourceName.BOOTSTRAP_ADMIN,
                user.profile().active(),
                login.isLocked(),
                login.lockCause(),
                login.hasPassword(),
                login.isPasswordChangeRequired(),
                login.lastAuthenticatedAt(),
                user.createdAt(),
                direct);
    }
}

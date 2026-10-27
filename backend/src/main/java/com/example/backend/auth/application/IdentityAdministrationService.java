package com.example.backend.auth.application;

import com.example.backend.audit.domain.AuditLockCause;
import com.example.backend.audit.domain.AuditLoginStateAttribute;
import com.example.backend.audit.domain.AuditTrail;
import com.example.backend.audit.domain.Audited;
import com.example.backend.authorization.domain.ConferredPower;
import com.example.backend.authorization.domain.GroupPermissions;
import com.example.backend.authorization.domain.Permission;
import com.example.backend.observability.LogEvent;
import com.example.backend.observability.LogEvent.Category;
import com.example.backend.observability.LogEvent.Operation;
import com.example.backend.observability.LogEvent.Type;
import com.example.backend.observability.RetriedOnDeadlock;
import com.example.backend.scim.domain.AdministrativeQueryRepository;
import com.example.backend.scim.domain.LockCause;
import com.example.backend.scim.domain.LoginStateChange;
import com.example.backend.scim.domain.ReservedResourceName;
import com.example.backend.scim.domain.ScimFilter;
import com.example.backend.scim.domain.ScimGroup;
import com.example.backend.scim.domain.ScimGroupHeadcount;
import com.example.backend.scim.domain.ScimGroupMember;
import com.example.backend.scim.domain.ScimGroupRepository;
import com.example.backend.scim.domain.ScimLoginState;
import com.example.backend.scim.domain.ScimPageRequest;
import com.example.backend.scim.domain.ScimQuery;
import com.example.backend.scim.domain.ScimResourceType;
import com.example.backend.scim.domain.ScimUser;
import com.example.backend.scim.domain.ScimUserRepository;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The identity use cases an administrator drives: reviewing the directory, and the one operation on
 * application-owned state that the directory cannot perform — Unlock, which acts only on a locked
 * User, so the helpdesk Permission cannot disturb a higher-privileged User that is working (ADR
 * 0015).
 *
 * <p>Separate from {@link LoginIdentityService}, which serves the login path. Keeping them apart
 * means the authentication path does not depend on a class that also mutates identities, and the
 * administrative guards below live beside nothing the login path could accidentally bypass.
 *
 * <p>Nothing here writes a directory-owned attribute. {@code userName}, {@code active} and Group
 * membership are a connector's to set over SCIM: an administrator overriding {@code active} from
 * the browser would only be overwritten by the next synchronization. Every operation that remains addresses
 * its User by the stable resource id, never by the mutable {@code userName}.
 *
 * <h2>Why this is here and not in the scim slice</h2>
 *
 * <p>An Unlock is a fact about the login surface — it lifts the refusal of a Login and decides
 * what the next Login may do — and the module that writes it ({@link LoginStateService}) is this
 * slice's. So the use case lives on the side that owns the login state and reaches the directory
 * through its ports, which is also the direction that keeps the two slices acyclic.
 */
@Service
public class IdentityAdministrationService {

    private static final Logger log = LoggerFactory.getLogger(IdentityAdministrationService.class);

    private static final String ACTOR_REQUIRED =
            "an administrative operation names its acting administrator by stable id";

    private final ScimUserRepository users;
    private final ScimGroupRepository groups;
    private final LoginStateService loginState;
    private final AuditTrail audit;
    private final GroupPermissions groupPermissions;
    private final AdministrativeQueryRepository queries;

    public IdentityAdministrationService(
            ScimUserRepository users,
            ScimGroupRepository groups,
            LoginStateService loginState,
            AuditTrail audit,
            GroupPermissions groupPermissions,
            AdministrativeQueryRepository queries) {
        this.users = users;
        this.groups = groups;
        this.loginState = loginState;
        this.audit = audit;
        this.groupPermissions = groupPermissions;
        this.queries = queries;
    }

    /**
     * Every identity, for administrative review. Never carries a password hash.
     *
     * <p>Every Group is read ONCE, memberships included, and each User's direct Groups are
     * answered from it in memory by inverting every Group's. The Superuser flag is the answer the
     * session gets at sign-in ({@link GroupPermissions#powersOf}), asked for every User at
     * once from those memberships — one read of the Groups, not one statement per identity, which
     * for a thousand identities would be a thousand of them to compute a column.
     */
    @Transactional(readOnly = true)
    public List<IdentitySummary> listIdentities() {
        Map<UUID, List<IdentitySummary.DirectGroup>> groupsByMember = new HashMap<>();
        for (ScimGroup group : groups.findAllOrderedByNormalizedDisplayName()) {
            IdentitySummary.DirectGroup reference =
                    new IdentitySummary.DirectGroup(group.id(), group.displayName());
            for (ScimGroupMember member : group.members()) {
                groupsByMember.computeIfAbsent(member.userId(), id -> new ArrayList<>())
                        .add(reference);
            }
        }
        Map<UUID, ConferredPower> power = groupPermissions.powersOf(
                groupsByMember.entrySet().stream().collect(Collectors.toMap(
                        Map.Entry::getKey, member -> idsOf(member.getValue()))));
        return users.findAllOrderedByNormalizedUserName().stream()
                .map(user -> summarize(
                        user,
                        user.login(),
                        power.getOrDefault(user.id(), ConferredPower.NONE).superuser(),
                        groupsByMember.get(user.id())))
                .toList();
    }

    /**
     * Every Group, for the read-only Groups projection: its name, how many direct members it has,
     * whether it is the protected Admin group — decided by the reservation marker, so a Group a
     * connector names "Admins" is not reported as one — and the Permissions it carries, sorted by
     * name. The members are counted where they are stored, never read to be counted; one read for
     * every Group's Permissions, not one per Group.
     */
    @Transactional(readOnly = true)
    public List<GroupSummary> listGroups() {
        List<ScimGroupHeadcount> all = groups.findAllHeadcountsOrderedByNormalizedDisplayName();
        Map<UUID, Set<Permission>> carried =
                groupPermissions.carriedBy(all.stream().map(ScimGroupHeadcount::id).toList());
        return all.stream()
                .map(group -> GroupSummary.of(group, carried.getOrDefault(group.id(), Set.of())))
                .toList();
    }

    /**
     * One page of a Group's direct members, for the Groups projection's member read: the
     * directory's collection query, asked without a connector — the Users who are direct members
     * of the Group, in the Users' default order (normalized {@code userName}), paged and totalled
     * as SCIM pages and totals them — and the page's Users loaded in one read. Two statements for
     * the query and one for the page, however many members there are; never one per member.
     *
     * <p>Empty when no live Group has the id — it never existed, a connector deleted it, or it is
     * a User's. A Group with no members is a page of none with a total of zero.
     *
     * <p>A member's row is {@link GroupMemberPage.Member}, which has no field a credential could go
     * in. Nothing is written, logged or audited: this is a read.
     */
    @Transactional(readOnly = true)
    public Optional<GroupMemberPage> groupMembers(UUID groupId, ScimPageRequest page) {
        if (!groups.existsById(groupId)) {
            return Optional.empty();
        }
        ScimQuery.Result result = queries.query(new ScimQuery(
                Set.of(ScimResourceType.USER), ScimFilter.directMemberOf(groupId), null, page));
        List<GroupMemberPage.Member> members =
                users.findAllById(result.idsOf(ScimResourceType.USER)).stream()
                        .map(GroupMemberPage.Member::of)
                        .toList();
        return Optional.of(new GroupMemberPage(result.totalResults(), page.startIndex(), members));
    }

    /**
     * Ends a lockout, clearing the failure run with it, and requires a password change. Says
     * nothing about whether the identity is active — a deactivated identity can be unlocked, and
     * stays deactivated. Has no effect on an identity serving no lockout: that Unlock is refused.
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
     * <p>Refused before anything is written, in this order, by throwing an
     * {@link UnlockRefusedException} naming the administrator, the User addressed and why — which
     * the administrative edge answers, logs and audits as a refused {@code LOCKOUT_LIFT}
     * ({@code auth.controller.AdminRefusals}); nothing here records it:
     *
     * <ul>
     *   <li>on an id no User has ({@code UNKNOWN_IDENTITY}, {@code 404});
     *   <li>on the caller's own account, whatever Permissions it holds and whatever its state —
     *       recovering from a self-inflicted state takes a second administrator; checked before
     *       anything is read under the User's lock ({@code SELF_TARGET}, {@code 403});
     *   <li>on an identity serving no lockout — the Bootstrap Admin, which can never be locked,
     *       always among them — decided by {@link LoginStateService#unlocked} on the state read
     *       under the User's lock, which then writes nothing, the failure run included
     *       ({@code NOT_LOCKED}, {@code 409}). There was no lockout for the credential to have
     *       reached, so nothing is required of it; an Unlock is not a way to keep an unused account
     *       from going dormant, nor to clear a failure run short of the limit; and no session is
     *       revoked. The usual cause is another administrator's Unlock landing first.
     * </ul>
     *
     * @param actorId the acting administrator's stable id, as its signed-in session holds it
     */
    @Transactional
    @RetriedOnDeadlock(Operation.UNLOCK)
    public IdentitySummary unlock(UUID userId, UUID actorId) {
        Objects.requireNonNull(actorId, ACTOR_REQUIRED);
        ScimUser user = require(userId, actorId);
        if (isSelf(user, actorId)) {
            throw new UnlockRefusedException(
                    UnlockRefusedException.Reason.SELF_TARGET, actorId, user.id());
        }
        LoginStateChange decision = loginState.unlocked(user);
        if (!decision.before().isLocked()) {
            throw new UnlockRefusedException(
                    UnlockRefusedException.Reason.NOT_LOCKED, actorId, user.id());
        }
        audit.append(new Audited.LockoutLiftedByUnlock(actorId, user.id(),
                auditCause(decision.before().lockCause()), decision.moved()));
        if (decision.hasMoved(AuditLoginStateAttribute.PASSWORD_CHANGE_REQUIRED_SINCE)) {
            audit.append(new Audited.PasswordChangeRequired(actorId, user.id(), decision.moved()));
        }
        succeeded(Operation.UNLOCK, user.id());
        return summarize(user, decision.after());
    }

    /** The lifted lock's cause in the audit trail's own vocabulary. */
    private static AuditLockCause auditCause(LockCause cause) {
        return switch (cause) {
            case FAILURES -> AuditLockCause.FAILURES;
            case DORMANCY -> AuditLockCause.DORMANCY;
        };
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

    private static List<UUID> idsOf(List<IdentitySummary.DirectGroup> direct) {
        return direct.stream().map(IdentitySummary.DirectGroup::id).toList();
    }

    /**
     * The User behind a stable id. The id is what every operation addresses — a {@code userName}
     * a connector may rename between the Admin reading the row and acting on it, and an id it
     * cannot.
     */
    private ScimUser require(UUID userId, UUID actorId) {
        return users.findById(userId).orElseThrow(() -> new UnlockRefusedException(
                UnlockRefusedException.Reason.UNKNOWN_IDENTITY, actorId, userId));
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

    /**
     * One User's row, for an operation's response: its direct Groups read for it alone, and the
     * Superuser flag from those Groups as sign-in answers it — the same two answers the listing
     * computes for everyone at once. {@code login} is the state as the operation left it, which
     * the stored {@code user} it was read from predates.
     */
    private IdentitySummary summarize(ScimUser user, ScimLoginState login) {
        List<IdentitySummary.DirectGroup> direct = groups.findGroupsOfUser(user.id()).stream()
                .map(group -> new IdentitySummary.DirectGroup(group.id(), group.displayName()))
                .toList();
        return summarize(
                user, login, groupPermissions.powerOf(idsOf(direct)).superuser(), direct);
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

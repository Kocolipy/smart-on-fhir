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
import com.example.backend.scim.domain.InvalidScimQueryException;
import com.example.backend.scim.domain.LockCause;
import com.example.backend.scim.domain.LoginStateChange;
import com.example.backend.scim.domain.ReservedResourceName;
import com.example.backend.scim.domain.ScimFilter;
import com.example.backend.scim.domain.ScimGroupHeadcount;
import com.example.backend.scim.domain.ScimGroupRepository;
import com.example.backend.scim.domain.ScimLoginState;
import com.example.backend.scim.domain.ScimPageRequest;
import com.example.backend.scim.domain.ScimQuery;
import com.example.backend.scim.domain.ScimResourceType;
import com.example.backend.scim.domain.ScimSort;
import com.example.backend.scim.domain.ScimUser;
import com.example.backend.scim.domain.ScimUserRepository;
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
     * One page of the Users projection, for administrative review: the Users whose
     * {@code userName} or display name contains {@code search}, ignoring case — every User when
     * there is no search — in the order {@code sortBy} and {@code sortOrder} name, normalized
     * {@code userName} order without a {@code sortBy}, with how many matched in all. Never carries
     * a password hash.
     *
     * <p>A page of the directory, not the whole of it: the directory's collection query, asked
     * without a connector, selects and totals the page, and the search is a filter value whose
     * text is bound as a statement parameter ({@link ScimFilter#nameContains}). The page's Users
     * are loaded in one read, and their direct Groups in one more for the page's Users alone —
     * never one per User, and never every Group's membership. The Superuser flag is the answer the
     * session gets at sign-in ({@link GroupPermissions#powersOf}), asked for the page's Users at
     * once from those memberships.
     *
     * @param search    the text a name must contain, or {@code null}; stripped, and no search
     *                  when nothing is left
     * @param sortBy    a User attribute path to sort the whole directory by, as SCIM sorts, or
     *                  {@code null} for the default order
     * @param sortOrder {@code ascending} (the default) or {@code descending}, as SCIM reads it
     * @throws InvalidScimQueryException when the sort is not one SCIM sorts Users by, or names a
     *                                   path only a connector has a value for; nothing is run
     */
    @Transactional(readOnly = true)
    public IdentityPage listIdentities(
            String search, String sortBy, String sortOrder, ScimPageRequest page) {
        ScimQuery.Result result =
                matching(ScimResourceType.USER, search, sortBy, sortOrder, page);
        List<ScimUser> found = users.findAllById(result.idsOf(ScimResourceType.USER));
        Map<UUID, List<IdentitySummary.DirectGroup>> direct = new HashMap<>();
        groups.findGroupsOfUsers(found.stream().map(ScimUser::id).toList())
                .forEach((userId, references) -> direct.put(userId, references.stream()
                        .map(group -> new IdentitySummary.DirectGroup(
                                group.id(), group.displayName()))
                        .toList()));
        Map<UUID, ConferredPower> power = groupPermissions.powersOf(direct.entrySet().stream()
                .collect(Collectors.toMap(Map.Entry::getKey, member -> idsOf(member.getValue()))));
        List<IdentitySummary> rows = found.stream()
                .map(user -> summarize(
                        user,
                        user.login(),
                        power.getOrDefault(user.id(), ConferredPower.NONE).superuser(),
                        direct.get(user.id())))
                .toList();
        return new IdentityPage(result.totalResults(), page.startIndex(), rows);
    }

    /**
     * One page of the read-only Groups projection: the Groups whose display name contains
     * {@code search}, ignoring case — every Group when there is no search — in the order
     * {@code sortBy} and {@code sortOrder} name, normalized {@code displayName} order without a
     * {@code sortBy}, with how many matched in all. Each row is the Group's name, how
     * many direct members it has, whether it is the protected Admin group — decided by the
     * reservation marker, so a Group a connector names "Admins" is not reported as one — and the
     * Permissions it carries, sorted by name.
     *
     * <p>Selected and totalled by the directory's collection query, as {@link #listIdentities}
     * is; the page's members are counted where they are stored, never read to be counted, and
     * its Groups' Permissions are one read, not one per Group.
     *
     * @param search    the text a display name must contain, or {@code null}; stripped, and no
     *                  search when nothing is left
     * @param sortBy    a Group attribute path to sort the whole directory by, as SCIM sorts, or
     *                  {@code null} for the default order
     * @param sortOrder {@code ascending} (the default) or {@code descending}, as SCIM reads it
     * @throws InvalidScimQueryException when the sort is not one SCIM sorts Groups by, or names a
     *                                   path only a connector has a value for; nothing is run
     */
    @Transactional(readOnly = true)
    public GroupPage listGroups(
            String search, String sortBy, String sortOrder, ScimPageRequest page) {
        ScimQuery.Result result =
                matching(ScimResourceType.GROUP, search, sortBy, sortOrder, page);
        List<ScimGroupHeadcount> found =
                groups.findHeadcountsById(result.idsOf(ScimResourceType.GROUP));
        Map<UUID, Set<Permission>> carried =
                groupPermissions.carriedBy(found.stream().map(ScimGroupHeadcount::id).toList());
        List<GroupSummary> rows = found.stream()
                .map(group -> GroupSummary.of(group, carried.getOrDefault(group.id(), Set.of())))
                .toList();
        return new GroupPage(result.totalResults(), page.startIndex(), rows);
    }

    /**
     * The page of one resource type a name search selects, in the order asked for, and its
     * total: the administrative collection query, filtered only when the search has text left
     * once stripped, and sorted by SCIM's own sort ({@link ScimSort#of}) — the type's default
     * order when there is no {@code sortBy}.
     *
     * <p>A sort SCIM accepts but only a connector can evaluate — by {@code externalId}, a
     * {@code $ref} or {@code meta.location} — is refused here as the invalid sort it is for a
     * caller that is no connector, rather than reaching the query, which would refuse it as a
     * defect ({@link AdministrativeQueryRepository}).
     */
    private ScimQuery.Result matching(ScimResourceType type, String search, String sortBy,
            String sortOrder, ScimPageRequest page) {
        Set<ScimResourceType> types = Set.of(type);
        String text = search == null ? "" : search.strip();
        ScimFilter filter = text.isEmpty() ? null : ScimFilter.nameContains(type, text);
        ScimQuery query = new ScimQuery(types, filter, ScimSort.of(sortBy, sortOrder, types), page);
        if (query.needsConnector()) {
            throw new InvalidScimQueryException(
                    "sortBy names an attribute only a connector has a value for.");
        }
        return queries.query(query);
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
     * {@link LoginStateService#unlocked}, which reads the User under its lock, and the response
     * is built from that decision. Lifting a lock also restarts the dormancy window, so the next
     * dormancy run does not lock again a User that has not yet had the chance to sign in. The
     * audit event records the cause of the lock it lifted and the paths the decision actually
     * changed: the failure run when it held attempts, and the lock with the dormancy basis when
     * one stood.
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
     *   <li>on the caller's own account, whatever Permissions it holds and whatever its state —
     *       recovering from a self-inflicted state takes a second administrator; checked on the
     *       ids alone, before anything is read ({@code SELF_TARGET}, {@code 403});
     *   <li>on an id no User has, found so under the User's lock — never there, or deleted before
     *       the Unlock took it ({@code UNKNOWN_IDENTITY}, {@code 404});
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
        if (isSelf(userId, actorId)) {
            throw new UnlockRefusedException(
                    UnlockRefusedException.Reason.SELF_TARGET, actorId, userId);
        }
        LoginStateChange decision = loginState.unlocked(userId).orElseThrow(() ->
                new UnlockRefusedException(
                        UnlockRefusedException.Reason.UNKNOWN_IDENTITY, actorId, userId));
        if (!decision.before().isLocked()) {
            throw new UnlockRefusedException(
                    UnlockRefusedException.Reason.NOT_LOCKED, actorId, userId);
        }
        audit.append(new Audited.LockoutLiftedByUnlock(actorId, userId,
                auditCause(decision.before().lockCause()), decision.moved()));
        if (decision.hasMoved(AuditLoginStateAttribute.PASSWORD_CHANGE_REQUIRED_SINCE)) {
            audit.append(new Audited.PasswordChangeRequired(actorId, userId, decision.moved()));
        }
        succeeded(Operation.UNLOCK, userId);
        // Read under the lock the Unlock holds since the module's read, so the User it answers
        // with is the one it lifted, and is still there.
        return summarize(users.findById(userId).orElseThrow(), decision.after());
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
    private static boolean isSelf(UUID userId, UUID actorId) {
        return userId.equals(actorId);
    }

    private static List<UUID> idsOf(List<IdentitySummary.DirectGroup> direct) {
        return direct.stream().map(IdentitySummary.DirectGroup::id).toList();
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
     * computes for a page of Users at once. {@code login} is the state as the operation left it,
     * which the stored {@code user} it was read from predates.
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

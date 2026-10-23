package com.example.backend.scim.application;

import com.example.backend.audit.domain.AuditGroupAttribute;
import com.example.backend.audit.domain.AuditUserAttribute;
import com.example.backend.scim.domain.ScimResourceType;
import com.example.backend.scim.domain.ScimUserProfile;
import com.example.backend.scim.domain.ScimUserSessions;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Everything a SCIM write implies, decided from its before and after state alone: the attributes
 * to audit, the Session revocations it causes, and the membership changes of power it makes.
 *
 * <p>This is the one place that says which edits end Sessions and which change a User's
 * Permissions. The User and Group write use cases, and the dormancy job's membership revocation,
 * keep their locking,
 * preconditions and persistence; they hand the stored state and the written state here, and hand
 * what comes back to the one apply step, {@link ScimWriteEffectsApplier}, which carries it out
 * over the ports. Nothing here performs an effect: a revocation is requested through
 * {@link ScimUserSessions#revokeAfterCommit} (ADR 0002), and an audit append is the fail-open kind
 * (ADR 0004), both by the apply step.
 *
 * <p>The audit attribute set and the Session cause set stay two types, derived side by side. The
 * audit slice owns its vocabulary ({@link AuditUserAttribute}, {@link AuditGroupAttribute}), the
 * session port owns its own ({@link ScimUserSessions.Cause}), and an edit that is audited is not
 * thereby one a live Session must not outlast.
 *
 * <p>What the write must store is a fourth effect, decided beside the other three rather than
 * read back out of the audit set: the audit vocabulary is the audit slice's to change, and a use
 * case that steered its writes by it would start persisting differently the day an attribute
 * was renamed, split or stopped being audited.
 *
 * @param written     the resource the write was made to — the one whose version an alias written
 *                    alone advances
 * @param audited     the attributes the write moved, for its audit event; empty when it moved
 *                    nothing a client can read
 * @param revocations the Users whose Sessions end, and why, in the order to request them
 * @param membershipChanges the memberships of power the write granted and revoked, revocations
 *                    first
 * @param stored      what the write changed in storage: the resource's own columns, the calling
 *                    connector's alias, both, or neither
 * @param <A>         the audit slice's attribute vocabulary for the resource written
 */
record ScimWriteEffects<A extends Enum<A>>(
        Resource written,
        Set<A> audited,
        List<SessionRevocation> revocations,
        List<MembershipChange> membershipChanges,
        Stored stored) {

    ScimWriteEffects {
        Objects.requireNonNull(written, "written");
        audited = Collections.unmodifiableSet(new LinkedHashSet<>(audited));
        revocations = List.copyOf(revocations);
        membershipChanges = List.copyOf(membershipChanges);
        Objects.requireNonNull(stored, "stored");
    }

    /**
     * What a write changed in storage. The alias is not a column of the User or Group: it lives
     * per connector beside the resource, so it is written separately, and a write that moved only
     * the alias advanced no version through the resource's own replacement.
     *
     * @param columns whether any of the resource's own stored attributes changed
     * @param alias   whether the calling connector's {@code externalId} changed
     */
    record Stored(boolean columns, boolean alias) {

        static final Stored NOTHING = new Stored(false, false);

        /** Whether the write moved nothing at all: nothing to store and no version to advance. */
        boolean nothing() {
            return !columns && !alias;
        }

        /** Whether the alias is all that moved, so the version must be advanced on its own. */
        boolean aliasOnly() {
            return alias && !columns;
        }
    }

    /** The resource a write was made to: its type and its id. */
    record Resource(ScimResourceType type, UUID id) {

        Resource {
            Objects.requireNonNull(type, "type");
            Objects.requireNonNull(id, "id");
        }

        static Resource user(UUID id) {
            return new Resource(ScimResourceType.USER, id);
        }

        static Resource group(UUID id) {
            return new Resource(ScimResourceType.GROUP, id);
        }
    }

    /** One User's Sessions to end once the write commits, and every reason the write gave. */
    record SessionRevocation(UUID userId, Set<ScimUserSessions.Cause> causes) {

        SessionRevocation {
            if (causes.isEmpty()) {
                throw new IllegalArgumentException("a revocation has a cause");
            }
            causes = Collections.unmodifiableSet(EnumSet.copyOf(causes));
        }
    }

    /**
     * A User gaining or losing direct membership of a Group that confers Permissions — or of the
     * Admin group — whether by a Group write or by the User's deletion, or, on the dormancy job's
     * revocation, of any Group at all.
     */
    record MembershipChange(Kind kind, UUID userId, UUID groupId) {

        enum Kind {
            GRANTED,
            REVOKED
        }
    }

    /**
     * The state of a User a write compares: its profile, the calling connector's alias, and its
     * credential. The hash, not the request: a write that sets the password it already holds
     * still changed the credential, because each hash is salted afresh, and a removal of a
     * password that was never there changed nothing.
     */
    record UserState(ScimUserProfile profile, String externalId, String passwordHash) {
    }

    /**
     * The state of a Group a write compares: its label, its direct members, and the calling
     * connector's alias — which is not a Group column, so it travels beside the Group.
     */
    record GroupState(String displayName, Set<UUID> memberIds, String externalId) {

        GroupState {
            memberIds = Collections.unmodifiableSet(new LinkedHashSet<>(memberIds));
        }
    }

    /**
     * What a User PUT or PATCH implies.
     *
     * <p>Compared rather than inferred from the request: a PUT resending the stored state moved
     * nothing, and a PATCH whose operations cancel out moved nothing either.
     *
     * <p>Sessions end for the changes a live Session must not outlast (Session revocation in
     * {@code /docs/domain/sessions.md}): {@code active} going from true to false, the credential changing,
     * and {@code userName} changing — once, with every cause that applied. Reactivation, staying
     * inactive, and a profile, email or alias change are not among them: none is something a
     * Session was issued against. A User write changes no Permission: Permissions are conferred
     * by Group membership, and a User write cannot move a membership — only its deletion can.
     */
    static ScimWriteEffects<AuditUserAttribute> ofUserWrite(
            UUID userId, UserState before, UserState after) {
        ScimUserProfile was = before.profile();
        ScimUserProfile is = after.profile();
        boolean userNameChanged = !was.userName().equals(is.userName());
        boolean passwordChanged = !Objects.equals(before.passwordHash(), after.passwordHash());
        boolean aliasChanged = !Objects.equals(before.externalId(), after.externalId());
        Stored stored = new Stored(!was.equals(is) || passwordChanged, aliasChanged);

        Set<AuditUserAttribute> audited = EnumSet.noneOf(AuditUserAttribute.class);
        addIf(audited, AuditUserAttribute.USER_NAME, userNameChanged);
        addIf(audited, AuditUserAttribute.NAME, !was.name().equals(is.name()));
        addIf(audited, AuditUserAttribute.DISPLAY_NAME,
                !Objects.equals(was.displayName(), is.displayName()));
        addIf(audited, AuditUserAttribute.PREFERRED_LANGUAGE,
                !Objects.equals(was.preferredLanguage(), is.preferredLanguage()));
        addIf(audited, AuditUserAttribute.LOCALE, !Objects.equals(was.locale(), is.locale()));
        addIf(audited, AuditUserAttribute.TIMEZONE,
                !Objects.equals(was.timezone(), is.timezone()));
        addIf(audited, AuditUserAttribute.ACTIVE, was.active() != is.active());
        addIf(audited, AuditUserAttribute.EMAILS, !was.emails().equals(is.emails()));
        addIf(audited, AuditUserAttribute.PASSWORD, passwordChanged);
        addIf(audited, AuditUserAttribute.EXTERNAL_ID, aliasChanged);

        Set<ScimUserSessions.Cause> causes = EnumSet.noneOf(ScimUserSessions.Cause.class);
        addIf(causes, ScimUserSessions.Cause.DEACTIVATED, was.active() && !is.active());
        addIf(causes, ScimUserSessions.Cause.PASSWORD_CHANGED, passwordChanged);
        addIf(causes, ScimUserSessions.Cause.USER_NAME_CHANGED, userNameChanged);

        return new ScimWriteEffects<>(
                Resource.user(userId), audited, revocationOf(userId, causes), List.of(), stored);
    }

    /**
     * What deleting a User implies: its Sessions end, once, for the deletion. Its memberships go
     * with it, and each one of a Group that confers Permissions — or of the Admin group — is a
     * change of power, a revocation as removing it from that Group would be; a membership of a
     * Group that confers nothing is none. The deletion's audit event names no attribute.
     *
     * @param groupsOfPower each Group conferring Permissions the User was a direct member of
     */
    static ScimWriteEffects<AuditUserAttribute> ofUserDeletion(
            UUID userId, List<UUID> groupsOfPower) {
        List<MembershipChange> changes = new ArrayList<>();
        groupsOfPower.forEach(groupId -> changes.add(
                new MembershipChange(MembershipChange.Kind.REVOKED, userId, groupId)));
        return new ScimWriteEffects<>(
                Resource.user(userId),
                EnumSet.noneOf(AuditUserAttribute.class),
                revocationOf(userId, EnumSet.of(ScimUserSessions.Cause.DELETED)),
                changes,
                Stored.NOTHING);
    }

    /**
     * What a Group PUT or PATCH implies.
     *
     * <p>The attributes are compared rather than inferred from the verb, so a PUT resending the
     * stored state audits none.
     *
     * <p>A Group that confers Permissions — or the Admin group, which confers the Superuser —
     * confers them on every direct member, so when {@code confersPermissions}, a membership change
     * is a change of power: a grant for each User added, a revocation for each User removed — and
     * each removed User's Sessions end, since a Session carries the Permissions it was issued
     * with. An addition ends no Session: the User holds the Permissions from its next sign-in.
     * Decided from the membership before and after, so PUT and every PATCH shape are one rule, a
     * User removed and added back keeps its Permissions and its Sessions, and a removal naming a
     * non-member changes nothing. A Group that confers nothing moves nobody's Permissions, so its
     * membership changes are none of this.
     */
    static ScimWriteEffects<AuditGroupAttribute> ofGroupWrite(
            UUID groupId, GroupState before, GroupState after, boolean confersPermissions) {
        boolean displayNameChanged = !before.displayName().equals(after.displayName());
        boolean membersChanged = !before.memberIds().equals(after.memberIds());
        boolean aliasChanged = !Objects.equals(before.externalId(), after.externalId());

        Set<AuditGroupAttribute> audited = EnumSet.noneOf(AuditGroupAttribute.class);
        addIf(audited, AuditGroupAttribute.DISPLAY_NAME, displayNameChanged);
        addIf(audited, AuditGroupAttribute.MEMBERS, membersChanged);
        addIf(audited, AuditGroupAttribute.EXTERNAL_ID, aliasChanged);
        return membershipEffects(
                audited, groupId, before.memberIds(), after.memberIds(), confersPermissions,
                new Stored(displayNameChanged || membersChanged, aliasChanged));
    }

    /**
     * What deleting a Group implies: every member leaves it at once, so the members of a Group
     * that confers Permissions each lose them and their Sessions, exactly as removing them one by
     * one would. The deletion's audit event names no attribute.
     */
    static ScimWriteEffects<AuditGroupAttribute> ofGroupDeletion(
            UUID groupId, Set<UUID> memberIds, boolean confersPermissions) {
        return membershipEffects(
                EnumSet.noneOf(AuditGroupAttribute.class), groupId, memberIds, Set.of(),
                confersPermissions, Stored.NOTHING);
    }

    /**
     * What the dormancy job's membership revocation implies (ADR 0007): the User has already been
     * removed from each of these Groups — every direct membership it held, whatever each confers —
     * so each removal is a membership change, in the order given, and its Sessions end — once, with
     * the one cause, however many Groups it left. Nothing is audited as an attribute and nothing
     * more is stored: the removals advanced the versions as they were made.
     *
     * @param removedGroupIds each Group the User was removed from
     */
    static ScimWriteEffects<AuditUserAttribute> ofMembershipRevocation(
            UUID userId, List<UUID> removedGroupIds) {
        List<MembershipChange> changes = new ArrayList<>();
        removedGroupIds.forEach(groupId -> changes.add(
                new MembershipChange(MembershipChange.Kind.REVOKED, userId, groupId)));
        Set<ScimUserSessions.Cause> causes = changes.isEmpty()
                ? EnumSet.noneOf(ScimUserSessions.Cause.class)
                : EnumSet.of(ScimUserSessions.Cause.MEMBERSHIP_REVOKED);
        return new ScimWriteEffects<>(
                Resource.user(userId),
                EnumSet.noneOf(AuditUserAttribute.class),
                revocationOf(userId, causes),
                changes,
                Stored.NOTHING);
    }

    private static ScimWriteEffects<AuditGroupAttribute> membershipEffects(
            Set<AuditGroupAttribute> audited,
            UUID groupId,
            Set<UUID> before,
            Set<UUID> after,
            boolean confersPermissions,
            Stored stored) {
        if (!confersPermissions) {
            return new ScimWriteEffects<>(
                    Resource.group(groupId), audited, List.of(), List.of(), stored);
        }
        List<MembershipChange> changes = new ArrayList<>();
        List<SessionRevocation> revocations = new ArrayList<>();
        for (UUID userId : before) {
            if (!after.contains(userId)) {
                changes.add(new MembershipChange(MembershipChange.Kind.REVOKED, userId, groupId));
                revocations.add(new SessionRevocation(
                        userId, EnumSet.of(ScimUserSessions.Cause.MEMBERSHIP_REVOKED)));
            }
        }
        for (UUID userId : after) {
            if (!before.contains(userId)) {
                changes.add(new MembershipChange(MembershipChange.Kind.GRANTED, userId, groupId));
            }
        }
        return new ScimWriteEffects<>(
                Resource.group(groupId), audited, revocations, changes, stored);
    }

    private static List<SessionRevocation> revocationOf(
            UUID userId, Set<ScimUserSessions.Cause> causes) {
        return causes.isEmpty() ? List.of() : List.of(new SessionRevocation(userId, causes));
    }

    private static <E> void addIf(Set<E> set, E element, boolean applies) {
        if (applies) {
            set.add(element);
        }
    }
}

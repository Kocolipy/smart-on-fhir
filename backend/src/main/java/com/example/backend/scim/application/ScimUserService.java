package com.example.backend.scim.application;

import com.example.backend.audit.domain.AuditScimRefusal;
import com.example.backend.audit.domain.AuditTrail;
import com.example.backend.audit.domain.AuditUserAttribute;
import com.example.backend.scim.domain.AuthenticatedConnector;
import com.example.backend.scim.domain.DuplicateUserNameException;
import com.example.backend.scim.domain.PasswordAcceptance;
import com.example.backend.scim.domain.PasswordPolicy;
import com.example.backend.scim.domain.PasswordPolicyRefusedException;
import com.example.backend.scim.domain.PasswordReusedException;
import com.example.backend.scim.domain.ProtectedResourceException;
import com.example.backend.scim.domain.ReservedResourceName;
import com.example.backend.scim.domain.ScimAttributeLimits;
import com.example.backend.scim.domain.ScimAttributeValueException;
import com.example.backend.scim.domain.ScimExternalIdRepository;
import com.example.backend.scim.domain.ScimGroupRepository;
import com.example.backend.scim.domain.ScimLoginState;
import com.example.backend.scim.domain.ScimQuery;
import com.example.backend.scim.domain.ScimQueryRepository;
import com.example.backend.scim.domain.ScimPasswordChange;
import com.example.backend.scim.domain.ScimPatchRefusedException;
import com.example.backend.scim.domain.ScimResourceType;
import com.example.backend.scim.domain.ScimTombstoneRepository;
import com.example.backend.scim.domain.ScimUser;
import com.example.backend.scim.domain.ScimUserEdit;
import com.example.backend.scim.domain.ScimUserLoginState;
import com.example.backend.scim.domain.ScimUserPatchOperation;
import com.example.backend.scim.domain.ScimUserProfile;
import com.example.backend.scim.domain.ScimUserRepository;
import com.example.backend.scim.domain.ScimUserSessions;
import com.example.backend.scim.domain.ScimVersionPrecondition;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.UnaryOperator;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Creating, reading and changing SCIM Users, as a connector asks for it.
 *
 * <p>Every method takes the {@link AuthenticatedConnector} rather than reading it from
 * a security context, for two reasons that both matter. An {@code externalId} is
 * connector-scoped, so a read that did not know who was asking could not resolve the
 * alias — and the failure mode of getting it wrong is disclosing one connector's
 * namespace to another. And an audit event names the acting connector, so making the
 * actor a parameter means no code path exists that records a SCIM operation with no
 * actor.
 *
 * <p>The returned projections carry no credential: {@link ScimUserResource} has no
 * field a hash could occupy, so the plaintext this service hashes has nowhere to
 * reappear.
 *
 * <h2>Changing a User</h2>
 *
 * <p>PUT and PATCH share one shape ({@link #write}), because everything but computing the
 * desired state is the same and must not be able to differ between the two verbs:
 *
 * <ol>
 *   <li>read the User under its resource lock — absent is a {@code 404}, decided before the
 *       precondition so a malformed or stale header cannot disclose which ids exist;
 *   <li>check the {@code If-Match} precondition, when one was sent, against the locked version,
 *       so two writers racing with the same one produce one success and one {@code 412}. A write
 *       without one is applied unconditionally, still under the lock and in one transaction with
 *       its version advance, so unconditional writers serialize rather than interleave;
 *   <li>refuse the Bootstrap Admin, which no SCIM write may change;
 *   <li>compute the desired state in memory — its login state, a Reactivation's included, is the
 *       login-state module's decision ({@link ScimUserLoginState}), and PUT and PATCH report it
 *       the same way — refuse a value its column cannot hold — too long,
 *       or carrying a forbidden control character ({@link ScimAttributeLimits}) — refuse a
 *       password the {@link PasswordPolicy} does
 *       not accept — its intrinsic rules, then reuse — and write only if something differs — a
 *       write that changed nothing advances no version;
 *   <li>audit what moved, and end the User's sessions after the commit when the change is one
 *       a live session must not outlast. Both are decided by {@link ScimWriteEffects} from the
 *       stored and the desired state; this class carries them out.
 * </ol>
 *
 * <p>Every refusal is raised before anything is written, and each is audited fail-open, as a
 * refused Group write is. A refused precondition is NOT audited: it is the ordinary signal of two
 * writers colliding, carries nothing about the User, and is counted by the operational telemetry
 * instead ({@code ops/prometheus/alerts.yaml}).
 */
@Service
public class ScimUserService {

    private final ScimUserRepository users;
    private final ScimGroupRepository groups;
    private final ScimExternalIdRepository aliases;
    private final PasswordAcceptance passwordAcceptance;
    private final ScimUserSessions sessions;
    private final ScimUserLoginState loginStates;
    private final ScimTombstoneRepository tombstones;
    private final AuditTrail audit;
    private final Clock clock;
    private final ScimQueryRepository queries;

    public ScimUserService(
            ScimUserRepository users,
            ScimGroupRepository groups,
            ScimExternalIdRepository aliases,
            PasswordAcceptance passwordAcceptance,
            ScimUserSessions sessions,
            ScimUserLoginState loginStates,
            ScimTombstoneRepository tombstones,
            AuditTrail audit,
            Clock clock,
            ScimQueryRepository queries) {
        this.queries = queries;
        this.users = users;
        this.groups = groups;
        this.aliases = aliases;
        this.passwordAcceptance = passwordAcceptance;
        this.sessions = sessions;
        this.loginStates = loginStates;
        this.tombstones = tombstones;
        this.audit = audit;
        this.clock = clock;
    }

    /**
     * Creates a User, its connector alias and its audit event in one transaction.
     *
     * <p>The password is hashed before {@link ScimUser} is constructed, so the
     * plaintext exists only as a local in this method and in the command that was
     * handed to it. Nothing that outlives the call holds it — not the domain object,
     * not the row, not the projection returned. Its hash starts the User's password
     * history, so a later change back to it is refused like any other reuse.
     *
     * <p>A value its column cannot hold — see {@link ScimAttributeLimits} — is refused first, as
     * {@code invalidValue} naming the attribute and the rule, and audited fail-open as that.
     * Without it the INSERT would fail on the column and the failure could only be guessed at.
     *
     * <p>A submitted password must satisfy {@link PasswordPolicy} before anything else happens; a
     * refusal names the rule, is audited fail-open as {@code invalidValue}, and writes nothing.
     *
     * <p>A {@code userName} already taken arrives as
     * {@link DuplicateUserNameException} from the failed INSERT rather than from a
     * prior read. The refusal is audited in a transaction of its own — this one is
     * already doomed — and then re-thrown for the adapter to render as a SCIM
     * {@code uniqueness} error.
     */
    @Transactional
    public ScimUserResource create(AuthenticatedConnector connector, NewScimUser command) {
        try {
            ScimAttributeLimits.requireWithin(command.profile());
            ScimAttributeLimits.requireExternalIdWithin(command.externalId());
        } catch (ScimAttributeValueException unacceptable) {
            audit.recordScimUserCreateRejected(connector.connectorId(), AuditScimRefusal.INVALID_VALUE);
            throw unacceptable;
        }
        PasswordAcceptance.Accepted password = null;
        if (command.password() != null) {
            // A User being created has no credential and no history, so nothing can be reused;
            // the policy's own order still decides which rule a refusal names.
            password = acceptedOrRefused(
                    passwordAcceptance.acceptForNewUser(
                            command.password(), command.profile().userName()),
                    () -> audit.recordScimUserCreateRejected(
                            connector.connectorId(), AuditScimRefusal.INVALID_VALUE));
        }
        Instant now = clock.instant();
        String passwordHash = password == null ? null : password.passwordHash();
        // Whether the new credential owes a change is the login-state module's decision.
        ScimUser user = withLogin(
                ScimUser.created(UUID.randomUUID(), command.profile(), passwordHash, now),
                loginStates.created(passwordHash, now));
        ScimUser created;
        try {
            created = users.create(user);
        } catch (DuplicateUserNameException duplicate) {
            audit.recordScimUserCreateRejected(connector.connectorId(), AuditScimRefusal.UNIQUENESS);
            throw duplicate;
        }
        if (password != null) {
            passwordAcceptance.remember(created.id(), password, now);
        }
        if (command.externalId() != null) {
            aliases.put(connector.connectorId(), created.id(), command.externalId());
        }
        audit.recordScimUserCreated(connector.connectorId(), created.id());
        // A freshly created User is in no Group, so the reverse view is empty rather than
        // read: a create cannot put a User in a Group, because `groups` is read-only.
        return ScimUserResource.of(created, List.of(), command.externalId());
    }

    /**
     * One User by its stable id, as this connector sees it.
     *
     * <p>Deliberately not audited. A single-resource retrieval is the ordinary unit of
     * provisioning traffic; recording it would bury the collection reads that indicate
     * an enumeration under millions of reads that indicate nothing. The absence of a call
     * here is where that distinction is enforced.
     */
    @Transactional(readOnly = true)
    public Optional<ScimUserResource> findById(AuthenticatedConnector connector, UUID id) {
        return users.findById(id).map(user -> projection(connector, user));
    }

    /**
     * A query of the User collection — {@code GET /Users} or {@code POST /Users/.search} — and
     * the total it matched.
     *
     * <p>Audited as exactly one bulk read before the page is returned, whatever the page size
     * and whatever comes back — an empty result and a one-User page included — with the number
     * of Users returned and the filter's shape. The audit call is inside the transaction that
     * reads, so a trail that cannot record the read is a read that does not complete.
     *
     * @param query   a query over Users alone
     * @param baseUri the absolute SCIM base URI, against which {@code $ref} and
     *                {@code meta.location} filters are evaluated as rendered
     */
    @Transactional
    public ScimUserListing query(AuthenticatedConnector connector, ScimQuery query, String baseUri) {
        ScimQuery.Result result = queries.query(query, connector.connectorId(), baseUri);
        List<ScimUserResource> resources = resources(connector, result.idsOf(ScimResourceType.USER));
        audit.recordScimUsersQueried(
                connector.connectorId(), resources.size(), ScimAuditFilterShapes.of(query.filter()));
        return new ScimUserListing(resources, result.totalResults(), query.page());
    }

    /**
     * These Users as this connector sees them, in the order given; an id naming no live User is
     * skipped. Not audited: the caller is a query that audits itself.
     */
    List<ScimUserResource> resources(AuthenticatedConnector connector, List<UUID> ids) {
        return users.findAllById(ids).stream().map(user -> projection(connector, user)).toList();
    }

    /**
     * Replaces a User — a PUT.
     *
     * <p>The replacement's profile is already complete, with every omitted optional attribute
     * unassigned. Its password is applied only when one was sent: an omitted password keeps the
     * stored credential usable. An omitted {@code active} keeps the stored value, so a PUT never
     * reactivates a User it did not explicitly set {@code active=true} on.
     *
     * @return empty when no live User has that id
     */
    @Transactional
    public Optional<ScimUserResource> replace(
            AuthenticatedConnector connector,
            UUID id,
            ScimVersionPrecondition precondition,
            ScimUserReplacement replacement) {
        return write(connector, id, precondition, stored -> new ScimUserEdit(
                replacement.profileOver(stored.profile()),
                replacement.externalId(),
                replacement.password() == null
                        ? ScimPasswordChange.UNCHANGED
                        : ScimPasswordChange.set(replacement.password())));
    }

    /**
     * Applies PATCH operations to a User, all or nothing.
     *
     * <p>The operations are folded onto the stored User in memory and the result written once, so
     * a sequence whose last operation is refused leaves the first ones unapplied — there is no
     * intermediate state to roll back, because none was ever written.
     *
     * @return empty when no live User has that id
     */
    @Transactional
    public Optional<ScimUserResource> patch(
            AuthenticatedConnector connector,
            UUID id,
            ScimVersionPrecondition precondition,
            List<ScimUserPatchOperation> operations) {
        return write(connector, id, precondition,
                stored -> ScimUserPatchOperation.fold(stored, operations));
    }

    /**
     * Deletes a User — a DELETE.
     *
     * <p>The same order as a write, for the same reasons: read under the resource lock (absent is
     * a {@code 404}, decided before the precondition), check {@code If-Match} against the locked
     * version, then refuse the Bootstrap Admin, which no SCIM operation may delete.
     *
     * <p>The live rows go — profile, emails, credential, password history, memberships, connector
     * aliases — and a tombstone holding only the id, the type and the time is written in the same
     * transaction, beside the audit event. Nothing about the User is kept for uniqueness, so its
     * former {@code userName} is free for the next create. The User's sessions end after the
     * commit, so a delete that rolls back ends none.
     *
     * @return whether a live User was there to delete
     */
    @Transactional
    public boolean delete(
            AuthenticatedConnector connector, UUID id, ScimVersionPrecondition precondition) {
        Optional<ScimUser> stored = users.findByIdForUpdate(id);
        if (stored.isEmpty()) {
            return false;
        }
        ScimUser current = stored.get();
        precondition.requireSatisfiedBy(current.version());
        UUID connectorId = connector.connectorId();
        if (current.isProtectedFromWrites()) {
            audit.recordScimUserDeleteRejected(connectorId, id, AuditScimRefusal.MUTABILITY);
            throw new ProtectedResourceException(ReservedResourceName.BOOTSTRAP_ADMIN);
        }
        Instant now = clock.instant();
        users.deleteById(id, now);
        tombstones.record(ScimResourceType.USER, id, now);
        audit.recordScimUserDeleted(connectorId, id);
        revokeAfterCommit(connectorId, ScimWriteEffects.ofUserDeletion(id));
        return true;
    }

    /** The shared shape of a User write; see the class documentation for its order. */
    private Optional<ScimUserResource> write(
            AuthenticatedConnector connector,
            UUID id,
            ScimVersionPrecondition precondition,
            UnaryOperator<ScimUserEdit> change) {
        Optional<ScimUser> stored = users.findByIdForUpdate(id);
        if (stored.isEmpty()) {
            return Optional.empty();
        }
        ScimUser current = stored.get();
        precondition.requireSatisfiedBy(current.version());
        UUID connectorId = connector.connectorId();
        if (current.isProtectedFromWrites()) {
            audit.recordScimUserWriteRejected(connectorId, id, AuditScimRefusal.MUTABILITY);
            throw new ProtectedResourceException(ReservedResourceName.BOOTSTRAP_ADMIN);
        }

        ScimUserEdit before =
                ScimUserEdit.of(current.profile(), aliases.find(connectorId, id).orElse(null));
        ScimUserEdit after;
        try {
            after = change.apply(before);
            ScimAttributeLimits.requireWithin(after.profile());
            // A PUT and a PATCH can both set the alias now, so it is bounded on the shared path.
            ScimAttributeLimits.requireExternalIdWithin(after.externalId());
        } catch (ScimPatchRefusedException refused) {
            audit.recordScimUserWriteRejected(connectorId, id, refusal(refused));
            throw refused;
        } catch (ScimAttributeValueException unacceptable) {
            audit.recordScimUserWriteRejected(connectorId, id, AuditScimRefusal.INVALID_VALUE);
            throw unacceptable;
        }

        ScimPasswordChange password = after.password();
        PasswordAcceptance.Accepted accepted = password.kind() == ScimPasswordChange.Kind.SET
                ? acceptedOrRefused(
                        passwordAcceptance.acceptFor(
                                current, password.plaintext(), after.profile().userName()),
                        () -> audit.recordScimUserWriteRejected(
                                connectorId, id, AuditScimRefusal.INVALID_VALUE))
                : null;
        ScimUserLoginState.Credential credential = switch (password.kind()) {
            case UNCHANGED -> ScimUserLoginState.Credential.unchanged(current.login().passwordHash());
            case CLEAR -> ScimUserLoginState.Credential.cleared();
            case SET -> ScimUserLoginState.Credential.set(accepted.passwordHash());
        };
        String passwordHash = credential.passwordHash();
        ScimWriteEffects<AuditUserAttribute> effects = ScimWriteEffects.ofUserWrite(id,
                new ScimWriteEffects.UserState(
                        before.profile(), before.externalId(), current.login().passwordHash()),
                new ScimWriteEffects.UserState(after.profile(), after.externalId(), passwordHash));
        if (effects.stored().nothing()) {
            // Nothing a client can read moved, so nothing is written and no version advances.
            // Still audited, with no changed paths, as a Group write that moves nothing is: the
            // connector did write, and a connector hammering no-op writes is worth seeing.
            audit.recordScimUserReplaced(connectorId, id, effects.audited());
            return Optional.of(projection(connector, current));
        }

        Instant now = clock.instant();
        ScimUser written;
        try {
            written = users.replace(desired(current, after.profile(), loginStates.afterReplacement(
                            current, after.profile(), credential, now)),
                            now)
                    .orElseThrow();
        } catch (DuplicateUserNameException duplicate) {
            audit.recordScimUserWriteRejected(connectorId, id, AuditScimRefusal.UNIQUENESS);
            throw duplicate;
        }
        if (accepted != null) {
            // Remembered only now that the credential is written, in this transaction: a refusal
            // above, or a rollback after this, leaves the history as it was.
            passwordAcceptance.remember(id, accepted, now);
        }
        if (effects.stored().alias()) {
            writeAlias(connectorId, id, after.externalId());
        }
        audit.recordScimUserReplaced(connectorId, id, effects.audited());
        revokeAfterCommit(connectorId, effects);
        return Optional.of(projection(connector, written));
    }

    /**
     * Requests every Session revocation the effects name. After commit, per ADR 0002: the port's
     * contract, so a write that rolls back ends none.
     */
    private void revokeAfterCommit(UUID connectorId, ScimWriteEffects<?> effects) {
        for (ScimWriteEffects.SessionRevocation revocation : effects.revocations()) {
            sessions.revokeAfterCommit(connectorId, revocation.userId(), revocation.causes());
        }
    }

    /**
     * The accepted password, or — after recording the refusal fail-open — the refusal a connector
     * receives for the rule it broke. The decision itself is {@link PasswordAcceptance}'s, in the
     * policy's own order, which is the order the self-service change applies too.
     */
    private static PasswordAcceptance.Accepted acceptedOrRefused(
            PasswordAcceptance.Decision decision, Runnable recordRefusal) {
        return switch (decision) {
            case PasswordAcceptance.Accepted accepted -> accepted;
            case PasswordAcceptance.Refused refused -> {
                recordRefusal.run();
                throw refusal(refused.rule());
            }
        };
    }

    /** The refusal a connector receives for an unmet password rule; it carries no value. */
    private static RuntimeException refusal(PasswordPolicy.Rule rule) {
        return rule == PasswordPolicy.Rule.REUSED
                ? new PasswordReusedException()
                : new PasswordPolicyRefusedException(rule);
    }

    /**
     * Sets or removes the calling connector's alias. Keyed by the calling connector alone, so no
     * write of one connector reaches another's alias for the same User.
     */
    private void writeAlias(UUID connectorId, UUID id, String externalId) {
        if (externalId == null) {
            aliases.remove(connectorId, id);
        } else {
            aliases.put(connectorId, id, externalId);
        }
    }

    /**
     * The User to write: the new profile, and the login state the login-state module decided for
     * the write; everything else as stored. The failure run and the lock ride along unwritten —
     * the port's replacement leaves them to the login path.
     */
    private static ScimUser desired(ScimUser current, ScimUserProfile profile, ScimLoginState login) {
        return new ScimUser(
                current.id(),
                profile,
                login,
                current.reservedName(),
                current.version(),
                current.createdAt(),
                current.lastModifiedAt());
    }

    private static ScimUser withLogin(ScimUser user, ScimLoginState login) {
        return new ScimUser(
                user.id(),
                user.profile(),
                login,
                user.reservedName(),
                user.version(),
                user.createdAt(),
                user.lastModifiedAt());
    }

    private static AuditScimRefusal refusal(ScimPatchRefusedException refused) {
        return switch (refused.reason()) {
            case MUTABILITY -> AuditScimRefusal.MUTABILITY;
            case NO_TARGET -> AuditScimRefusal.NO_TARGET;
        };
    }

    /** The stored User as this connector sees it, alias and computed Group memberships included. */
    private ScimUserResource projection(AuthenticatedConnector connector, ScimUser user) {
        return ScimUserResource.of(
                user,
                groups.findGroupsOfUser(user.id()),
                aliases.find(connector.connectorId(), user.id()).orElse(null));
    }
}

package com.example.backend.scim.infrastructure.persistence;

import com.example.backend.scim.domain.DuplicateUserNameException;
import com.example.backend.scim.domain.LockCause;
import com.example.backend.scim.domain.Lockout;
import com.example.backend.scim.domain.NormalizedUserName;
import com.example.backend.scim.domain.ReservedResourceName;
import com.example.backend.scim.domain.ScimEmail;
import com.example.backend.scim.domain.ScimLoginState;
import com.example.backend.scim.domain.ScimName;
import com.example.backend.scim.domain.ScimResourceType;
import com.example.backend.scim.domain.ScimUser;
import com.example.backend.scim.domain.ScimUserProfile;
import com.example.backend.scim.domain.ScimUserRepository;
import com.example.backend.scim.infrastructure.persistence.entity.ScimLoginStateValue;
import com.example.backend.scim.infrastructure.persistence.entity.ScimResourceEntity;
import com.example.backend.scim.infrastructure.persistence.entity.ScimUserEmailValue;
import com.example.backend.scim.infrastructure.persistence.entity.ScimUserEntity;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Repository;

/** Maps the SCIM User port onto the normalized JPA model. */
@Repository
class ScimUserPersistenceAdapter implements ScimUserRepository {

    /** The order the administrative listing promises: by normalized {@code userName}. */
    private static final Sort BY_NORMALIZED_USER_NAME = Sort.by("normalizedUserName");

    private final ScimUserJpaRepository users;
    private final ScimResourceJpaRepository resources;
    private final ScimGroupMemberJpaRepository memberships;

    ScimUserPersistenceAdapter(
            ScimUserJpaRepository users,
            ScimResourceJpaRepository resources,
            ScimGroupMemberJpaRepository memberships) {
        this.users = users;
        this.resources = resources;
        this.memberships = memberships;
    }

    /**
     * Writes the resource row and the User row, and reports a violation of the live-{@code userName}
     * constraint as a duplicate {@code userName}.
     *
     * <p>Passes a literal {@code null} reservation rather than reading one off the
     * argument. That is what makes "a provisioned resource cannot protect itself from
     * being changed" a property of this adapter instead of a property of whichever use
     * case happens to call it: {@link #createReserved} is the only path to a marker, and
     * it is reachable only from seeding.
     *
     * <p>{@code saveAndFlush} rather than {@code save}: the INSERT has to reach the
     * database inside this call for the violation to be attributable to it. A deferred
     * flush would surface the same violation at commit, by which time the create use
     * case has returned and the refusal could no longer be rendered as a
     * {@code 409 uniqueness} — it would be a 500 from a transaction that failed after
     * the response was decided.
     *
     * <p>The only UNIQUE constraint this INSERT can violate is the normalized
     * {@code userName}: the resource id and the User's primary key are freshly
     * generated UUIDs, and the emails' uniqueness was resolved by
     * {@link ScimEmail#canonical(List)} before the profile existed. But a unique constraint is
     * not the only thing an INSERT can violate — a value too long for its column fails with the
     * same exception type — so the violation is translated only when it names that constraint,
     * and anything else is rethrown as the fault it is.
     */
    @Override
    public ScimUser create(ScimUser user) {
        return insert(user, null);
    }

    /**
     * Writes a reserved User — the Bootstrap Admin.
     *
     * <p>A violation here has one more possible cause than in {@link #create}: the
     * reservation is unique too, so a second attempt to seed the same recovery identity
     * violates it. Seeding never expects either: it looks the reservation up first under
     * its lock, because against Postgres a violation aborts the transaction and cannot be
     * caught and continued past. A taken {@code userName} arrives as the duplicate it is; a
     * taken reservation is rethrown as the integrity violation, and either one fails startup.
     */
    @Override
    public ScimUser createReserved(ScimUser user, ReservedResourceName reservedName) {
        return insert(user, reservedName);
    }

    @Override
    public Optional<ScimUser> findById(UUID id) {
        return users.findById(id).map(ScimUserPersistenceAdapter::toDomain);
    }

    /**
     * Locks the resource row, then reads the User. The lock query is the transaction's first read
     * of the resource, so the version it returns is the one the previous lock holder committed.
     */
    @Override
    public Optional<ScimUser> findByIdForUpdate(UUID id) {
        if (resources.lockById(id).isEmpty()) {
            return Optional.empty();
        }
        return findById(id);
    }

    /**
     * Writes the changed profile columns and the given credential, change-required flag and
     * dormancy basis, then advances the version once. Stores what it is given: which login state a
     * write leaves is decided before it reaches here (see the port).
     *
     * <p>The entity is {@code @DynamicUpdate}, so the UPDATE names only the columns that differ
     * from what was loaded — by the caller's locked read, earlier in the same transaction. That is
     * what keeps this write from touching what the login path writes without the resource lock:
     * the failure run and the lock are not assigned here at all, and a flag or basis given as it
     * was read is not dirty, so it is not written.
     *
     * <p>That comparison is against the locked read only because the entity loaded here IS the
     * one that read loaded: {@link #findByIdForUpdate} locks the resource row, not this one, so a
     * Login can commit a newer basis to this row in between, and an entity reloaded from the
     * database would see it and treat the caller's older value as a change. Nothing between the
     * two may therefore clear the persistence context — no {@code clearAutomatically} statement
     * may run in the SCIM write path before this call.
     *
     * <p>{@code saveAndFlush} so a taken {@code userName} surfaces here, attributable, rather than
     * at commit after the response is decided.
     */
    @Override
    public Optional<ScimUser> replace(ScimUser user, Instant now) {
        Optional<ScimUserEntity> stored = users.findById(user.id());
        if (stored.isEmpty()) {
            return Optional.empty();
        }
        ScimUserEntity entity = stored.get();
        ScimUserProfile profile = user.profile();
        ScimName name = profile.name();
        entity.replaceProfile(
                profile.userName(),
                profile.normalizedUserName().value(),
                profile.active(),
                profile.displayName(),
                name.formatted(),
                name.familyName(),
                name.givenName(),
                name.middleName(),
                name.honorificPrefix(),
                name.honorificSuffix(),
                profile.preferredLanguage(),
                profile.locale(),
                profile.timezone(),
                emailValues(profile.emails()));
        ScimLoginState login = user.login();
        entity.getLogin().storeDirectoryLoginState(
                login.passwordHash(),
                login.passwordChangeRequiredSince(),
                login.lastAuthenticatedAt());
        try {
            users.saveAndFlush(entity);
        } catch (DataIntegrityViolationException violation) {
            throw translated(violation);
        }
        RepresentationChange.userWritten(user.id(), now).advanceIn(resources);
        return findById(user.id());
    }

    @Override
    public Optional<ScimUser> findByNormalizedUserName(NormalizedUserName normalizedUserName) {
        return users.findByNormalizedUserName(normalizedUserName.value())
                .map(ScimUserPersistenceAdapter::toDomain);
    }

    @Override
    public Optional<ScimUser> findByReservedName(ReservedResourceName reservedName) {
        return users.findByResource_ReservedName(reservedName.storedValue())
                .map(ScimUserPersistenceAdapter::toDomain);
    }

    /**
     * Writes the authentication-state columns alone, leaving the resource row untouched.
     *
     * <p>The absence of a version bump here is the behaviour, not an omission: a failure
     * run and a lock instant are invisible to a SCIM client, so advancing the ETag would
     * make every rejected login look like a representation change.
     *
     * <p>A write that matches no row is silently nothing. The login path reads the User
     * and then writes it, so a zero here means the User was deleted in between — in which
     * case there is no failure run left to record and nothing for this to report.
     */
    @Override
    public void updateLoginState(UUID id, ScimLoginState loginState) {
        // The hash is deliberately not passed: see the port. Writing the hash read at the
        // start of a login attempt would revert a password changed while the attempt ran.
        users.updateLoginState(
                id,
                loginState.failedLoginAttempts(),
                loginState.lockedAt(),
                loginState.lockCause() == null ? null : loginState.lockCause().name());
    }

    /** The dormancy basis alone; the resource row and its version are untouched. */
    @Override
    public void recordAuthentication(UUID id, Instant authenticatedAt) {
        users.recordAuthentication(id, authenticatedAt);
    }

    @Override
    public List<UUID> findDormantUnlockedUserIds(Instant cutoff) {
        return users.findDormantUnlockedUserIds(cutoff);
    }

    /** The given lock instant and cause, only where no lock stands; the version is untouched. */
    @Override
    public boolean imposeLock(UUID id, Lockout lockout) {
        return users.imposeLock(id, lockout.at(), lockout.cause().name()) == 1;
    }

    /** The dormancy basis alone; the resource row and its version are untouched. */
    @Override
    public void resetDormancyBasis(UUID id, Instant at) {
        users.recordAuthentication(id, at);
    }

    @Override
    public List<UUID> findAllIds() {
        return users.findAllIds();
    }

    /** The flag alone; the resource row and its version are untouched. */
    @Override
    public void requirePasswordChange(UUID id, Instant since) {
        users.requirePasswordChange(id, since);
    }

    /**
     * The credential and the flag in one statement, then the version once. Re-reads rather than
     * returning a locally-assembled value: the version the caller needs is the one the database
     * just computed. Empty when the statement matched no row — its own row count, rather than a
     * read first, so the answer cannot go stale between the check and the write.
     */
    @Override
    public Optional<ScimUser> completePasswordChange(UUID id, String passwordHash, Instant now) {
        if (users.completePasswordChange(id, passwordHash) == 0) {
            return Optional.empty();
        }
        RepresentationChange.userWritten(id, now).advanceIn(resources);
        return findById(id);
    }

    /**
     * Deletes the User and advances the version of every Group it belonged to.
     *
     * <p>Advanced before the delete, while the membership rows that name those Groups still
     * exist; the cascade from the resource row then removes the memberships with everything else.
     * A User in no Group advances nothing.
     */
    @Override
    public void deleteById(UUID id, Instant now) {
        RepresentationChange.userDeleted(memberships.findGroupIdsOfUser(id), now)
                .advanceIn(resources);
        resources.deleteResource(id);
    }

    @Override
    public List<ScimUser> findAllOrderedByNormalizedUserName() {
        return users.findAll(BY_NORMALIZED_USER_NAME).stream()
                .map(ScimUserPersistenceAdapter::toDomain)
                .toList();
    }

    @Override
    public List<ScimUser> findAllById(List<UUID> ids) {
        if (ids.isEmpty()) {
            return List.of();
        }
        Map<UUID, ScimUser> byId = new HashMap<>();
        for (ScimUserEntity entity : users.findAllById(ids)) {
            byId.put(entity.getResource().getId(), toDomain(entity));
        }
        return ids.stream().map(byId::get).filter(Objects::nonNull).toList();
    }

    private ScimUser insert(ScimUser user, ReservedResourceName reservedName) {
        try {
            return toDomain(users.saveAndFlush(toEntity(user, reservedName)));
        } catch (DataIntegrityViolationException violation) {
            throw translated(violation);
        }
    }

    /**
     * A violation of the live-{@code userName} constraint as the domain's refusal; any other
     * violation unchanged.
     *
     * <p>Only that one constraint means "the name is taken". Anything else reaching here — a value
     * the application layer's length check let through, a NOT NULL or foreign-key violation — is
     * a fault, and reporting it as a conflict would send a connector looking for an account that
     * does not exist. So it is rethrown as the integrity violation it is, which the web adapter
     * renders as a server error.
     */
    private static RuntimeException translated(DataIntegrityViolationException violation) {
        return IntegrityViolations.translated(
                violation, IntegrityViolations.USER_NAME_UNIQUE, DuplicateUserNameException::new);
    }

    private static ScimUserEntity toEntity(ScimUser user, ReservedResourceName reservedName) {
        ScimResourceEntity resource = new ScimResourceEntity(
                user.id(),
                ScimResourceType.USER.resourceTypeName(),
                user.version(),
                user.createdAt(),
                user.lastModifiedAt(),
                reservedName == null ? null : reservedName.storedValue());
        ScimUserProfile profile = user.profile();
        ScimName name = profile.name();
        ScimLoginState login = user.login();
        return new ScimUserEntity(
                resource,
                profile.userName(),
                profile.normalizedUserName().value(),
                new ScimLoginStateValue(
                        login.passwordHash(),
                        login.failedLoginAttempts(),
                        login.lockedAt(),
                        login.lockCause() == null ? null : login.lockCause().name(),
                        login.lastAuthenticatedAt(),
                        login.passwordChangeRequiredSince()),
                profile.active(),
                profile.displayName(),
                name.formatted(),
                name.familyName(),
                name.givenName(),
                name.middleName(),
                name.honorificPrefix(),
                name.honorificSuffix(),
                profile.preferredLanguage(),
                profile.locale(),
                profile.timezone(),
                emailValues(profile.emails()));
    }

    private static List<ScimUserEmailValue> emailValues(List<ScimEmail> emails) {
        return emails.stream()
                .map(email -> new ScimUserEmailValue(email.value(), email.type(), email.primary()))
                .toList();
    }

    private static ScimUser toDomain(ScimUserEntity entity) {
        ScimResourceEntity resource = entity.getResource();
        ScimUserProfile profile = new ScimUserProfile(
                entity.getUserName(),
                new ScimName(
                        entity.getFormattedName(),
                        entity.getFamilyName(),
                        entity.getGivenName(),
                        entity.getMiddleName(),
                        entity.getHonorificPrefix(),
                        entity.getHonorificSuffix()),
                entity.getDisplayName(),
                entity.getPreferredLanguage(),
                entity.getLocale(),
                entity.getTimezone(),
                entity.isActive(),
                entity.getEmails().stream()
                        .map(email -> new ScimEmail(
                                email.getValue(), email.getType(), email.isPrimary()))
                        .toList());
        ScimLoginStateValue login = entity.getLogin();
        return new ScimUser(
                resource.getId(),
                profile,
                new ScimLoginState(
                        login.getPasswordHash(),
                        login.getFailedLoginAttempts(),
                        login.getLockedAt(),
                        login.getLockCause() == null
                                ? null
                                : LockCause.valueOf(login.getLockCause()),
                        login.getLastAuthenticatedAt(),
                        login.getPasswordChangeRequiredSince()),
                ReservedResourceName.ofStoredValue(resource.getReservedName()).orElse(null),
                resource.getVersion(),
                resource.getCreatedAt(),
                resource.getLastModifiedAt());
    }
}

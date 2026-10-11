package com.example.backend.scim;

import com.example.backend.scim.domain.DuplicateUserNameException;
import com.example.backend.scim.domain.Lockout;
import com.example.backend.scim.domain.NormalizedUserName;
import com.example.backend.scim.domain.ReservedResourceName;
import com.example.backend.scim.domain.ScimLoginState;
import com.example.backend.scim.domain.ScimPageRequest;
import com.example.backend.scim.domain.ScimUser;
import com.example.backend.scim.domain.ScimUserRepository;
import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * SCIM User store for tests, standing in for the JPA adapter. Shared so the lockout and the
 * authority derivation are asserted against one persistence behaviour rather than against a
 * slightly different fake per test class.
 *
 * <p>Keyed by the resource's stable id, exactly as the real table is: {@code userName} is a
 * mutable attribute looked up by scanning, not the row's identity.
 *
 * <p>Two behaviours here are load-bearing rather than conveniences, because a fake that got
 * them wrong would hide the defect the real design exists to prevent:
 *
 * <ul>
 *   <li>{@link #updateLoginState} writes the login columns ONLY and does not advance the
 *       version. A fake that replaced the whole row would hide the lost update the narrow
 *       port prevents, and one that bumped the version would hide that a failed login must
 *       not move a connector's ETag.
 *   <li>{@link #create} refuses a reservation and {@link #createReserved} applies one, so a
 *       test cannot accidentally mint a protected resource through the provisioning path —
 *       which is the guarantee the real adapter makes by passing a literal null.
 * </ul>
 */
public final class InMemoryScimUserRepository implements ScimUserRepository {

    private final Map<UUID, ScimUser> stored = new LinkedHashMap<>();

    private int writes;

    private List<UUID> staleDormancyCandidates;

    private Runnable afterNextNameLookup;

    /**
     * Makes the dormancy candidate query answer with these ids, whatever the stored state says.
     *
     * <p>That is the window a dormancy job has to survive: the candidate list is read before each
     * User is locked, so by the locked read a candidate may have logged in, been deactivated, or —
     * were the query's filter ever lost — be the Bootstrap Admin. Against Postgres the window takes
     * two transactions to hit; here it is one call, and the job must decide again on the locked
     * read either way.
     */
    public void answerDormancyCandidatesWith(List<UUID> ids) {
        staleDormancyCandidates = List.copyOf(ids);
    }

    private UUID vanishingAtPasswordChange;

    /**
     * Makes the next {@link #completePasswordChange} for this id find no User, as the row going
     * between the locked read and the write would — the case the caller's own refusal covers.
     */
    public void vanishBeforeNextPasswordChange(UUID id) {
        vanishingAtPasswordChange = id;
    }

    @Override
    public ScimUser create(ScimUser user) {
        return insert(user, null);
    }

    @Override
    public ScimUser createReserved(ScimUser user, ReservedResourceName reservedName) {
        if (findByReservedName(reservedName).isPresent()) {
            throw new DuplicateUserNameException(
                    new IllegalStateException("that reservation is already held"));
        }
        return insert(user, reservedName);
    }

    @Override
    public Optional<ScimUser> findById(UUID id) {
        return Optional.ofNullable(stored.get(id));
    }

    @Override
    public Optional<ScimUser> findByNormalizedUserName(NormalizedUserName normalizedUserName) {
        Optional<ScimUser> found = stored.values().stream()
                .filter(user -> user.profile().normalizedUserName().equals(normalizedUserName))
                .findFirst();
        Runnable hook = afterNextNameLookup;
        afterNextNameLookup = null;
        if (hook != null) {
            hook.run();
        }
        return found;
    }

    /**
     * Runs {@code write} once, right after the next lookup by name has answered — the window a
     * concurrent directory write, such as a rename, lands in for a caller that read by name.
     */
    public void afterNextNameLookup(Runnable write) {
        afterNextNameLookup = write;
    }

    /** Renames the User {@code id} to {@code userName}, as a connector's replace would. */
    public void rename(UUID id, String userName) {
        ScimUser current = stored.get(id);
        replace(new ScimUser(current.id(), ScimIdentities.profile(userName, current.profile().active()),
                current.login(), current.reservedName(), current.version(),
                current.createdAt(), current.lastModifiedAt()), current.lastModifiedAt());
    }

    @Override
    public Optional<ScimUser> findByReservedName(ReservedResourceName reservedName) {
        return stored.values().stream()
                .filter(user -> reservedName == user.reservedName())
                .findFirst();
    }

    @Override
    public void updateLoginState(UUID id, ScimLoginState loginState) {
        ScimUser current = stored.get(id);
        if (current == null) {
            // The real adapter's UPDATE matches no row and reports nothing, because the User
            // was deleted between the read and the write. Silence is the behaviour.
            return;
        }
        stored.put(id, new ScimUser(
                current.id(),
                current.profile(),
                // The stored hash is kept, as the adapter keeps it: this write carries the failure
                // run only, so it cannot revert a password changed while a login attempt ran.
                new ScimLoginState(
                        current.login().passwordHash(),
                        loginState.failedLoginAttempts(),
                        loginState.lockedAt(),
                        loginState.lockCause(),
                        // Kept, as the adapter keeps it: this write is the failure run only.
                        current.login().lastAuthenticatedAt(),
                        current.login().passwordChangeRequiredSince()),
                current.reservedName(),
                // Deliberately unchanged: a failure run is not a SCIM attribute.
                current.version(),
                current.createdAt(),
                current.lastModifiedAt()));
        writes++;
    }

    /** No lock to take in memory; a single-threaded test has no second writer to exclude. */
    @Override
    public Optional<ScimUser> findByIdForUpdate(UUID id) {
        return findById(id);
    }

    /**
     * Replaces the profile and stores the given credential, flag and dormancy basis, keeping the
     * STORED failure run and lock — the port's promise that a replacement never writes those —
     * and advancing the version once. Decides nothing, as the adapter decides nothing: the login
     * state given is the caller's.
     */
    @Override
    public Optional<ScimUser> replace(ScimUser user, Instant now) {
        ScimUser current = stored.get(user.id());
        if (current == null) {
            return Optional.empty();
        }
        if (stored.values().stream().anyMatch(other -> !other.id().equals(user.id())
                && other.profile().normalizedUserName()
                        .equals(user.profile().normalizedUserName()))) {
            throw new com.example.backend.scim.domain.DuplicateUserNameException(null);
        }
        ScimUser updated = new ScimUser(
                current.id(),
                user.profile(),
                new ScimLoginState(
                        user.login().passwordHash(),
                        current.login().failedLoginAttempts(),
                        current.login().lockedAt(),
                        current.login().lockCause(),
                        user.login().lastAuthenticatedAt(),
                        user.login().passwordChangeRequiredSince()),
                current.reservedName(),
                current.version() + 1,
                current.createdAt(),
                now);
        stored.put(user.id(), updated);
        writes++;
        return Optional.of(updated);
    }

    /**
     * Writes the dormancy basis alone, leaving the version where it is. Not counted in
     * {@link #writes()}, which counts profile and failure-run writes: a login that had no failure
     * run to clear still writes nothing a test of that rule is asking about.
     */
    @Override
    public void recordAuthentication(UUID id, Instant authenticatedAt) {
        ScimUser current = stored.get(id);
        if (current == null) {
            return;
        }
        stored.put(id, new ScimUser(
                current.id(),
                current.profile(),
                withDormancyBasis(current.login(), authenticatedAt),
                current.reservedName(),
                current.version(),
                current.createdAt(),
                current.lastModifiedAt()));
    }

    /**
     * Unlocked, unreserved Users whose dormancy basis is strictly before the cutoff, by id — or,
     * once {@link #answerDormancyCandidatesWith} has been called, exactly the ids given.
     */
    @Override
    public List<UUID> findDormantUnlockedUserIds(Instant cutoff) {
        if (staleDormancyCandidates != null) {
            return staleDormancyCandidates;
        }
        return stored.values().stream()
                .filter(user -> !user.login().isLocked())
                .filter(user -> user.reservedName() == null)
                // The candidate query's SQL predicate, basis < cutoff; the decision is the verdict's.
                .filter(user -> user.dormancyBasis().isBefore(cutoff))
                .map(ScimUser::id)
                .sorted()
                .toList();
    }

    /**
     * The given lock and its cause where none stands, as the adapter's conditional statement
     * stores them; the failure run and everything else are kept as stored.
     */
    @Override
    public boolean imposeLock(UUID id, Lockout lockout) {
        ScimUser current = stored.get(id);
        if (current == null || current.login().isLocked()) {
            return false;
        }
        ScimLoginState login = current.login();
        stored.put(id, new ScimUser(
                current.id(),
                current.profile(),
                new ScimLoginState(
                        login.passwordHash(),
                        login.failedLoginAttempts(),
                        lockout.at(),
                        lockout.cause(),
                        login.lastAuthenticatedAt(),
                        login.passwordChangeRequiredSince()),
                current.reservedName(),
                current.version(),
                current.createdAt(),
                current.lastModifiedAt()));
        writes++;
        return true;
    }

    /** The dormancy basis alone, as {@link #recordAuthentication} writes it. */
    @Override
    public void resetDormancyBasis(UUID id, Instant at) {
        recordAuthentication(id, at);
    }

    @Override
    public List<UUID> findAllIds() {
        return stored.values().stream().map(ScimUser::id).sorted().toList();
    }

    /** Removes the User; the Groups it belonged to are the in-memory Group repository's concern. */
    @Override
    public void deleteById(UUID id, Instant now) {
        stored.remove(id);
        writes++;
    }

    @Override
    public List<ScimUser> findAllOrderedByNormalizedUserName() {
        return stored.values().stream()
                .sorted(Comparator.comparing(user -> user.profile().normalizedUserName().value()))
                .toList();
    }

    @Override
    public List<ScimUser> findAllById(List<UUID> ids) {
        return ids.stream().map(stored::get).filter(java.util.Objects::nonNull).toList();
    }

    /** How many Users are stored. */
    public long size() {
        return stored.size();
    }

    /** The stored User, failing the calling test when there is none. */
    public ScimUser require(String userName) {
        return findByNormalizedUserName(NormalizedUserName.of(userName)).orElseThrow(
                () -> new AssertionError("No SCIM User stored for " + userName));
    }

    /** Puts a User in the store directly, for a test arranging state rather than exercising a write. */
    public ScimUser given(ScimUser user) {
        stored.put(user.id(), user);
        return user;
    }

    /**
     * How many writes this store has taken. Lets a test assert that a login with nothing to
     * clear writes nothing, which is the only observable difference between skipping the write
     * and performing a redundant one.
     */
    public int writes() {
        return writes;
    }

    private ScimUser insert(ScimUser user, ReservedResourceName reservedName) {
        ScimUser nonNull = Objects.requireNonNull(user);
        if (findByNormalizedUserName(nonNull.profile().normalizedUserName()).isPresent()) {
            throw new DuplicateUserNameException(
                    new IllegalStateException("that normalized userName is already held"));
        }
        ScimUser toStore = new ScimUser(
                nonNull.id(),
                nonNull.profile(),
                nonNull.login(),
                reservedName,
                nonNull.version(),
                nonNull.createdAt(),
                nonNull.lastModifiedAt());
        stored.put(toStore.id(), toStore);
        writes++;
        return toStore;
    }

    private static ScimLoginState withDormancyBasis(ScimLoginState login, Instant basis) {
        return new ScimLoginState(
                login.passwordHash(),
                login.failedLoginAttempts(),
                login.lockedAt(),
                login.lockCause(),
                basis,
                login.passwordChangeRequiredSince());
    }

    /** The flag alone, as the adapter's narrow statement writes it; the version is untouched. */
    @Override
    public void requirePasswordChange(UUID id, Instant since) {
        ScimUser current = stored.get(id);
        if (current == null) {
            return;
        }
        stored.put(id, new ScimUser(
                current.id(),
                current.profile(),
                current.login().withPasswordChangeRequired(since),
                current.reservedName(),
                current.version(),
                current.createdAt(),
                current.lastModifiedAt()));
        writes++;
    }

    /** The credential and the cleared flag together, advancing the version once. */
    @Override
    public Optional<ScimUser> completePasswordChange(UUID id, String passwordHash, Instant now) {
        ScimUser current = stored.get(id);
        if (current == null || id.equals(vanishingAtPasswordChange)) {
            vanishingAtPasswordChange = null;
            return Optional.empty();
        }
        ScimLoginState login = current.login();
        ScimUser updated = new ScimUser(
                current.id(),
                current.profile(),
                new ScimLoginState(
                        passwordHash,
                        login.failedLoginAttempts(),
                        login.lockedAt(),
                        login.lockCause(),
                        login.lastAuthenticatedAt(),
                        null),
                current.reservedName(),
                current.version() + 1,
                current.createdAt(),
                now);
        stored.put(id, updated);
        writes++;
        return Optional.of(updated);
    }
}

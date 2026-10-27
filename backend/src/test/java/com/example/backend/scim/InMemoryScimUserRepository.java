package com.example.backend.scim;

import com.example.backend.scim.domain.DuplicateUserNameException;
import com.example.backend.scim.domain.Lockout;
import com.example.backend.scim.domain.NormalizedUserName;
import com.example.backend.scim.domain.ReservedResourceName;
import com.example.backend.scim.domain.ResourceVersionAdvance;
import com.example.backend.scim.domain.ScimLoginState;
import com.example.backend.scim.domain.ScimLoginStateChange;
import com.example.backend.scim.domain.ScimLoginStateRepository;
import com.example.backend.scim.domain.ScimPageRequest;
import com.example.backend.scim.domain.ScimUser;
import com.example.backend.scim.domain.ScimUserRepository;
import java.time.Instant;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;

/**
 * SCIM User store for tests, standing in for the JPA adapter. Shared so the lockout and the
 * authority derivation are asserted against one persistence behaviour rather than against a
 * slightly different fake per test class.
 *
 * <p>Keyed by the resource's stable id, exactly as the real table is: {@code userName} is a
 * mutable attribute looked up by scanning, not the row's identity.
 *
 * <p>It implements both User ports, {@link ScimUserRepository} and
 * {@link ScimLoginStateRepository}, over one map, as the JPA adapter implements both over one
 * table. Which columns each write touches and whether it advances the version are load-bearing
 * rather than conveniences — a fake that got them wrong would hide the defect the real design
 * exists to prevent, such as a failed login reverting a profile attribute or moving a connector's
 * ETag — so they are not taken on trust here. {@link ScimUserRepositoryContract} and
 * {@link ScimLoginStateRepositoryContract} state them, and this store and the SQL Server adapter
 * each pass both ({@code InMemory*ContractTests}, {@code SqlServer*ContractTests}): those are the
 * proof of this fake's fidelity, and a rule added to either port belongs in its contract first.
 *
 * <p>What it does NOT model is concurrency. Every call here completes before the next begins, so
 * two writers can never both read a state and then each write a decision made from it: the
 * lost update between parallel failed logins, or between an Unlock and a failure, cannot occur
 * here, and a test against this store proves nothing about it. The login-state module prevents
 * it by taking the User's lock ({@link InMemoryScimWriteLocks}, which takes none) and deciding
 * from {@link #findLoginState}; that is proven against a real database by
 * {@code LoginStateSerializationIntegrationTests}.
 *
 * <p>Which resources a write advances is not decided here: every write that changes a User's
 * rendered document asks {@link ResourceVersionAdvance}, as the adapter does, and applies its
 * answer — to the Users held here and, once a Group store holds this one, to its Groups. A copy of
 * the rule in this fake would keep the old rule after the adapters' changed, and the unit tests
 * would go on passing against it.
 */
public final class InMemoryScimUserRepository
        implements ScimUserRepository, ScimLoginStateRepository {

    private final Map<UUID, ScimUser> stored = new LinkedHashMap<>();

    /**
     * The Group store built over this one, if any: the Groups a User write advances or a User
     * deletion leaves are held there. Set by its constructor; a test with no Group store has a
     * directory in which no User is in any Group.
     */
    private InMemoryScimGroupRepository groups;

    private int writes;

    private List<UUID> staleDormancyCandidates;

    private Runnable afterNextNameLookup;

    /**
     * Makes the dormancy candidate query answer with these ids, whatever the stored state says.
     *
     * <p>That is the window a dormancy job has to survive: the candidate list is read before each
     * User is locked, so by the locked read a candidate may have logged in, been deactivated, or —
     * were the query's filter ever lost — be the Bootstrap Admin. Against SQL Server the window takes
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

    /** Called by the Group store built over this one; see {@link #groups}. */
    void attach(InMemoryScimGroupRepository groupStore) {
        this.groups = groupStore;
    }

    @Override
    public ScimUser create(ScimUser user) {
        return insert(user, null);
    }

    @Override
    public ScimUser createReserved(ScimUser user, ReservedResourceName reservedName) {
        if (findByReservedName(reservedName).isPresent()) {
            throw new DataIntegrityViolationException("that reservation is already held");
        }
        return insert(user, reservedName);
    }

    @Override
    public Optional<ScimUser> findById(UUID id) {
        return Optional.ofNullable(stored.get(id));
    }

    private boolean ignoresTrailingSpaces;

    /**
     * Makes every lookup by name compare as SQL Server's {@code =} does: trailing spaces ignored on
     * both sides, so {@code ada } finds {@code ada} (ADR 0013). A store that compared them exactly
     * would tell the two apart by itself, so a test of a refusal that must come before any lookup
     * needs this stand-in to show it.
     */
    public void ignoreTrailingSpacesInNameLookupsAsSqlServerDoes() {
        ignoresTrailingSpaces = true;
    }

    private String compared(NormalizedUserName name) {
        String value = name.value();
        if (!ignoresTrailingSpaces) {
            return value;
        }
        int end = value.length();
        while (end > 0 && value.charAt(end - 1) == ' ') {
            end--;
        }
        return value.substring(0, end);
    }

    @Override
    public Optional<ScimUser> findByNormalizedUserName(NormalizedUserName normalizedUserName) {
        String sought = compared(normalizedUserName);
        Optional<ScimUser> found = stored.values().stream()
                .filter(user -> compared(user.profile().normalizedUserName()).equals(sought))
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

    /** The stored state: there is no earlier copy here for a re-read to be served from. */
    @Override
    public Optional<ScimLoginState> findLoginState(UUID id) {
        return findById(id).map(ScimUser::login);
    }

    @Override
    public boolean isActive(UUID id) {
        return findById(id).map(user -> user.profile().active()).orElse(false);
    }

    @Override
    public Optional<ScimUser> findByReservedName(ReservedResourceName reservedName) {
        return stored.values().stream()
                .filter(user -> reservedName == user.reservedName())
                .findFirst();
    }

    /**
     * The column sets {@link ScimLoginStateChange} says moved, onto the STORED state — never the
     * credential, never the version. Counted in {@link #writes()} when it moves the run, the lock
     * or the flag; a change of the dormancy basis alone is not counted, so that an accepted login
     * with no failure run to clear writes nothing a test of that rule is asking about.
     */
    @Override
    public void writeChange(UUID id, ScimLoginState before, ScimLoginState after) {
        ScimUser current = stored.get(id);
        if (current == null) {
            // The User was deleted between the read and the write: silence is the behaviour (the
            // contract's missing-User case).
            return;
        }
        ScimLoginStateChange change = new ScimLoginStateChange(before, after);
        stored.put(id, new ScimUser(
                current.id(),
                current.profile(),
                change.appliedTo(current.login()),
                current.reservedName(),
                // Deliberately unchanged: no login-state column is a SCIM attribute.
                current.version(),
                current.createdAt(),
                current.lastModifiedAt()));
        if (change.movesFailureRun() || change.movesLock() || change.movesChangeRequired()) {
            writes++;
        }
    }

    /**
     * Replaces the profile and stores the given credential, flag and dormancy basis, keeping the
     * STORED failure run and lock — the port's promise that a replacement never writes those —
     * then advances what {@link ResourceVersionAdvance#userReplaced} names: the User, and its
     * Groups when the member label moved. Decides nothing: the login state given is the
     * caller's.
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
                current.version(),
                current.createdAt(),
                current.lastModifiedAt());
        stored.put(user.id(), updated);
        writes++;
        apply(ResourceVersionAdvance.userReplaced(
                user.id(),
                current.profile().memberLabel(),
                updated.profile().memberLabel(),
                groupIdsOf(user.id()),
                now));
        return findById(user.id());
    }

    /**
     * The version and {@code lastModified} alone, for an alias written alone. Not counted in
     * {@link #writes()}: no User column is written.
     */
    @Override
    public void advanceVersion(UUID id, Instant now) {
        apply(ResourceVersionAdvance.userWritten(id, now));
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
     * The given lock and its cause where none stands, a compare-and-set; the failure run and
     * everything else are kept as stored.
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

    @Override
    public List<UUID> findAllIds() {
        return stored.values().stream().map(ScimUser::id).sorted().toList();
    }

    /**
     * Advances what {@link ResourceVersionAdvance#userDeleted} names — every Group the User was
     * in — then drops its memberships and the User.
     */
    @Override
    public void deleteById(UUID id, Instant now) {
        apply(ResourceVersionAdvance.userDeleted(groupIdsOf(id), now));
        if (groups != null) {
            groups.dropMembershipsHeldBy(id);
        }
        stored.remove(id);
        writes++;
    }

    /**
     * Every stored User, in the order the persistent query's default order gives them: by
     * normalized {@code userName}. The fake's own, not the port's.
     */
    public List<ScimUser> inQueryOrder() {
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

    /**
     * The credential and the cleared flag together, then what
     * {@link ResourceVersionAdvance#userWritten} names: the User alone.
     */
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
                current.version(),
                current.createdAt(),
                current.lastModifiedAt());
        stored.put(id, updated);
        writes++;
        apply(ResourceVersionAdvance.userWritten(id, now));
        return findById(id);
    }

    /**
     * Carries out a decision made elsewhere: every resource it names, User or Group, advances its
     * version once and records the decision's {@code lastModified}.
     */
    private void apply(ResourceVersionAdvance advance) {
        advanceUsers(advance);
        if (groups != null) {
            groups.advanceGroups(advance);
        }
    }

    /**
     * Advances each User held here that the decision names, and nothing else — the resource row's
     * version and {@code lastModified}, not a User column, so not counted in {@link #writes()}.
     */
    void advanceUsers(ResourceVersionAdvance advance) {
        for (UUID id : advance.advanced()) {
            ScimUser current = stored.get(id);
            if (current != null) {
                stored.put(id, new ScimUser(
                        current.id(),
                        current.profile(),
                        current.login(),
                        current.reservedName(),
                        current.version() + 1,
                        current.createdAt(),
                        advance.at()));
            }
        }
    }

    private Collection<UUID> groupIdsOf(UUID userId) {
        return groups == null ? List.of() : groups.groupIdsOf(userId);
    }
}

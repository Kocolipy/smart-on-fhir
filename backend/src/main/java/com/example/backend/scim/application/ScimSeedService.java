package com.example.backend.scim.application;

import com.example.backend.audit.domain.AuditSeededResource;
import com.example.backend.audit.domain.AuditTrail;
import com.example.backend.audit.domain.Audited;
import com.example.backend.authorization.domain.GroupPermissionValues;
import com.example.backend.authorization.domain.InvalidGroupPermissionsException;
import com.example.backend.authorization.domain.Permission;
import com.example.backend.scim.domain.DuplicateUserNameException;
import com.example.backend.scim.domain.GroupPermissionRefusal;
import com.example.backend.scim.domain.LoginStateChange;
import com.example.backend.scim.domain.NormalizedUserName;
import com.example.backend.scim.domain.ReservedResourceName;
import com.example.backend.scim.domain.ScimGroup;
import com.example.backend.scim.domain.ScimGroupMember;
import com.example.backend.scim.domain.ScimGroupPermissionStore;
import com.example.backend.scim.domain.ScimGroupRepository;
import com.example.backend.scim.domain.ScimSeedLock;
import com.example.backend.scim.domain.ScimUser;
import com.example.backend.scim.domain.ScimUserLoginState;
import com.example.backend.scim.domain.ScimUserProfile;
import com.example.backend.scim.domain.ScimUserRepository;
import com.example.backend.scim.domain.ScimWriteLocks;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Establishes the deployment's recovery path on a database that does not have one: the Bootstrap
 * Admin, the Admin group, and the Bootstrap Admin's membership of it.
 *
 * <p>This is the only writer of a reservation marker. A reserved resource cannot be created by any
 * provisioning path — the ports expose {@code createReserved} operations that only this class
 * calls, and the marker column is not updatable — so the recovery resources exist because this ran
 * and for no other reason.
 *
 * <h2>Idempotent by looking first, under a lock</h2>
 *
 * <p>Every write is preceded by a read that decides whether it is needed, and no write is ever
 * expected to fail. That is deliberate: a unique-key violation leaves the transaction unusable
 * even where SQL Server rolls back only the failed statement — the exception marks the Spring
 * transaction rollback-only as it leaves the repository, and Hibernate's session may not be
 * flushed after it — so a seed that INSERTed and caught the violation as "it is already there"
 * would fail every restart against a seeded database. Catching does not undo either.
 *
 * <p>Read-then-write alone would let two instances starting together both read "absent", so the
 * transaction first takes {@link ScimSeedLock}. The second instance waits, then reads what the
 * first committed and writes nothing. The reservations stay UNIQUE in the schema as the backstop:
 * if the lock were ever bypassed, the outcome is a failed startup, never two Admin groups.
 *
 * <h2>The recovery path is the only thing every deployment gets</h2>
 *
 * <p>The Bootstrap Admin is the one configured identity. Every other startup User — the
 * non-administrative {@code user} included — is a development fixture, seeded only when
 * {@code app.dev-fixtures.enabled} is set, so a deployment that never enables them starts with no
 * User but the recovery one and no second credential from {@code application.yaml}.
 *
 * <h2>What it does not do</h2>
 *
 * <p>It never overwrites. A Bootstrap Admin that exists keeps its password, its failure run and
 * its lock — whatever the operator and the login path made them — because a restart that silently
 * reset the recovery credential to the configured one would undo a deliberate rotation.
 *
 * <p>It does not reconcile. If the reserved User exists but has been removed from the Admin group,
 * the membership is restored, because that membership is the deployment's authority and the SCIM
 * surface refuses to remove it — so its absence means something outside SCIM removed it, and
 * leaving it absent would leave a recovery identity with no authority to recover with.
 */
@Service
public class ScimSeedService {

    /**
     * The stable id a fresh database's Admin group is seeded under. Fixed so local runs and the
     * test suite can name the Group, and nothing more: the Admin group is RECOGNISED by its
     * reservation marker alone, never by this id, so a database seeded under another keeps it.
     */
    public static final UUID ADMIN_GROUP_ID =
            UUID.fromString("00000000-0000-4000-8000-00000000a001");

    private final ScimUserRepository users;
    private final ScimGroupRepository groups;
    private final ScimWriteLocks writeLocks;
    private final ScimSeedLock seedLock;
    private final AuditTrail audit;
    private final PasswordEncoder passwordEncoder;
    private final Clock clock;
    private final ScimGroupPermissionStore groupPermissions;
    private final ScimUserLoginState loginStates;

    public ScimSeedService(
            ScimUserRepository users,
            ScimGroupRepository groups,
            ScimWriteLocks writeLocks,
            ScimSeedLock seedLock,
            AuditTrail audit,
            PasswordEncoder passwordEncoder,
            Clock clock,
            ScimGroupPermissionStore groupPermissions,
            ScimUserLoginState loginStates) {
        this.users = users;
        this.groups = groups;
        this.writeLocks = writeLocks;
        this.seedLock = seedLock;
        this.audit = audit;
        this.passwordEncoder = passwordEncoder;
        this.clock = clock;
        this.groupPermissions = groupPermissions;
        this.loginStates = loginStates;
    }

    /**
     * Creates whatever of the recovery path is missing.
     *
     * <p>One transaction, so a deployment never comes up with a Bootstrap Admin that is not in the
     * Admin group: the two halves of the recovery path are useless apart, and a partial seed would
     * be a directory with an administrator that cannot administer.
     *
     * <p>The lock is the transaction's first statement, so every read after it sees what any
     * concurrent seed committed.
     *
     * @param recovery the Bootstrap Admin's configured credentials
     */
    @Transactional
    public void seed(SeededIdentity recovery) {
        seed(recovery, true);
    }

    /**
     * {@link #seed(SeededIdentity)}, choosing whether a newly seeded Bootstrap Admin owes a
     * password change. Only the development fixtures pass {@code false}, so a local run signs
     * straight in; a Bootstrap Admin that already exists keeps its flag either way.
     *
     * @param recovery               the Bootstrap Admin's configured credentials
     * @param passwordChangeRequired whether a newly seeded Bootstrap Admin owes a change
     */
    @Transactional
    public void seed(SeededIdentity recovery, boolean passwordChangeRequired) {
        seedLock.acquire();
        Instant now = clock.instant();
        ScimUser bootstrapAdmin = users.findByReservedName(ReservedResourceName.BOOTSTRAP_ADMIN)
                .orElseGet(() -> seedBootstrapAdmin(recovery, passwordChangeRequired, now));
        seedAdminGroup(bootstrapAdmin, now);
    }

    /**
     * The development fixtures: one Group per fixture, under the fixture's fixed id, with one
     * User in it, so local runs and the e2e suite have a User per Group to sign in as. What each
     * Group confers is {@link #seedGroupPermissionFixtures}'.
     *
     * <p>Called only when {@code app.dev-fixtures.enabled} is set. Idempotent like the rest of
     * seeding, and like the ordinary identity it never overwrites: a fixture User that exists keeps
     * its password, and a fixture Group that exists keeps whatever membership it has been given
     * since. Nothing is audited — these are development conveniences, not the recovery path.
     *
     * <p>The baseline member is a User in no Group, so it holds the baseline Permissions and
     * nothing else: the identity the non-administrative paths are exercised as. Like the others it
     * is created when absent and otherwise left alone.
     *
     * @param fixtures       the Groups to create and the User each contains
     * @param baselineMember the userName of a User in no Group; {@code null} for none
     * @param password       every fixture User's password; refused when blank, because this
     *                       setting has no published fallback and an empty one would be no
     *                       credential at all
     */
    @Transactional
    public void seedDevFixtures(List<DevFixture> fixtures, String baselineMember, String password) {
        requireFixturePassword(password);
        seedLock.acquire();
        Instant now = clock.instant();
        if (baselineMember != null) {
            findOrCreate(baselineMember, password, now);
        }
        for (DevFixture fixture : fixtures) {
            ScimUser member = findOrCreate(fixture.member(), password, now);
            if (groups.findById(fixture.groupId()).isEmpty()) {
                groups.create(ScimGroup.created(
                        fixture.groupId(),
                        fixture.displayName(),
                        List.of(ScimGroupMember.reference(member.id())),
                        now));
            }
        }
    }

    /**
     * The development fixture for an Epic EHR launch: one User in no Group, so it holds the
     * baseline Permissions and nothing else, whose userName is a Practitioner id. Epic Login
     * links a clinician to the User whose userName equals the Practitioner id character for
     * character (ADR 0012 D3), which outside a developer's machine is a User only because
     * SCIM provisioned it; under the {@code local} profile this seeds that User directly, so the
     * local Epic stand-in's clinician signs in without a step by hand.
     *
     * <p>Created when absent and otherwise left alone, like the other fixtures, and not audited.
     * Called only when {@code app.dev-fixtures.enabled} is set and an Epic clinician is named.
     *
     * @param userName    the Practitioner id the User is linked by
     * @param displayName the User's displayName; {@code null} or blank for its userName, as every
     *                    other fixture User has
     * @param password    the fixture password, as for {@link #seedDevFixtures}
     */
    @Transactional
    public void seedEpicClinicianFixture(String userName, String displayName, String password) {
        requireFixturePassword(password);
        seedLock.acquire();
        findOrCreate(userName,
                displayName == null || displayName.isBlank() ? userName : displayName,
                password, clock.instant());
    }

    private static void requireFixturePassword(String password) {
        if (password == null || password.isBlank()) {
            throw new IllegalStateException(
                    "app.dev-fixtures.password (APP_DEV_FIXTURES_PASSWORD) must be set"
                            + " when the development fixtures are enabled");
        }
    }

    /**
     * The development fixture for the dormancy lockout: a User whose dormancy basis is put
     * {@code dormantFor} in the past on every startup, so the dormancy run the development
     * profile makes at startup locks it, and the e2e suite can show a User locked for dormancy,
     * refused at login, unlocked and signed in again.
     *
     * <p>Unlike the Group fixtures it is RESET, not merely created: the e2e journey ends with the
     * User unlocked and holding a password it chose, so a fixture that kept that state would be
     * usable once per database. Each startup therefore puts back the fixture password — with no
     * change required — clears any lock, and backdates the basis; that reset is the login-state
     * module's ({@link ScimUserLoginState#dormantFixtureReset}). It is also put back into each of
     * {@code groupIds} that exists, so the startup run has direct memberships to remove once its
     * basis is past the revocation window, and the e2e suite can show every one of them gone.
     * Like the rest of the development seeding the rejoin is not audited. Called only when
     * {@code app.dev-fixtures.enabled} is set and a dormant fixture is named.
     *
     * @param userName   the fixture User's name
     * @param password   its password, refused when blank for the reason {@link #seedDevFixtures}
     *                   gives
     * @param dormantFor how long before now its basis is put — past the lockout window, and past
     *                   the revocation window for its memberships to be removed
     * @param groupIds   the Groups it is put back into; one that does not exist is skipped
     */
    @Transactional
    public void seedDormantDevFixture(
            String userName, String password, Duration dormantFor, List<UUID> groupIds) {
        if (password == null || password.isBlank()) {
            throw new IllegalStateException(
                    "app.dev-fixtures.password (APP_DEV_FIXTURES_PASSWORD) must be set"
                            + " when the development fixtures are enabled");
        }
        seedLock.acquire();
        Instant now = clock.instant();
        ScimUser dormant = findOrCreate(userName, password, now);
        loginStates.dormantFixtureReset(
                dormant, passwordEncoder.encode(password), now, dormantFor);
        rejoin(dormant, groupIds, now);
    }

    /**
     * Puts the dormant fixture back into each of these Groups that exists. The Groups and the User
     * are locked together first, through the lock module and in its one order, as any Group write
     * locks them: a replacement that adds the User advances the Group's version and the User's. A
     * Group already holding it is replaced with the membership it has — a Group's members are
     * distinct, so the reference adds nothing — and an unchanged replacement advances nothing.
     */
    private void rejoin(ScimUser dormant, List<UUID> groupIds, Instant now) {
        Set<UUID> resources = new LinkedHashSet<>(groupIds);
        resources.add(dormant.id());
        writeLocks.lock(resources);
        for (UUID groupId : groupIds) {
            groups.findById(groupId)
                    .ifPresent(group -> {
                        List<ScimGroupMember> members = new ArrayList<>(group.members());
                        members.add(ScimGroupMember.reference(dormant.id()));
                        groups.replace(group.replacedWith(group.displayName(), members, now));
                    });
        }
    }

    /**
     * The development fixtures' Group Permissions (ADR 0015): each named Group is given its
     * Permissions, but only while it carries none, so a Group whose Permissions have been set since
     * keeps them, and a restart changes nothing.
     *
     * <p>Called only when {@code app.dev-fixtures.enabled} is set, after the fixture Groups exist,
     * and only the {@code local} profile names any. Every fixture is checked before any is
     * applied, so a bad one fails startup with nothing written, every problem named: a name the
     * vocabulary does not have, and whatever the Group Permissions store refuses
     * ({@link ScimGroupPermissionStore#refusal}) — a Permission no Group may carry, a Group that
     * does not exist, and the Admin group, which carries none because its members are the
     * Superuser. Like the rest of the development seeding it is not audited.
     *
     * @param fixtures each Group's stable id and the Permission names it is given
     */
    @Transactional
    public void seedGroupPermissionFixtures(List<GroupPermissionFixture> fixtures) {
        seedLock.acquire();
        List<String> problems = new ArrayList<>();
        Map<UUID, Set<Permission>> given = new LinkedHashMap<>();
        for (GroupPermissionFixture fixture : fixtures) {
            Set<Permission> permissions = given.computeIfAbsent(
                    fixture.groupId(), group -> EnumSet.noneOf(Permission.class));
            for (String value : fixture.permissions()) {
                Permission.fromValue(value).ifPresentOrElse(permissions::add,
                        () -> problems.add(
                                GroupPermissionValues.unknownPermission(fixture.groupId(), value)));
            }
        }
        given.forEach((groupId, permissions) -> groupPermissions.refusal(groupId, permissions)
                .ifPresent(refusal -> problems.addAll(problems(groupId, refusal))));
        if (!problems.isEmpty()) {
            throw new InvalidGroupPermissionsException(problems);
        }
        given.forEach((groupId, permissions) -> {
            if (!groupPermissions.carriesAny(groupId)) {
                groupPermissions.add(groupId, permissions);
            }
        });
    }

    /** The store's refusal of a fixture, spelled as startup reports it: one line per problem. */
    private static List<String> problems(UUID groupId, GroupPermissionRefusal refusal) {
        return switch (refusal) {
            case GroupPermissionRefusal.NotDelegatable(Set<Permission> refused) -> refused.stream()
                    .map(permission ->
                            GroupPermissionValues.notCarriable(groupId, permission.value()))
                    .toList();
            case GroupPermissionRefusal.UnknownGroup _ ->
                    List.of("Group " + groupId + " does not exist");
            case GroupPermissionRefusal.AdminGroup _ -> List.of("Group " + groupId
                    + " is the Admin group, whose members are the Superuser");
        };
    }

    /**
     * Refuses startup when a stored Group Permission names a Permission the vocabulary does not
     * have, or one no Group may carry ({@link GroupPermissionValues}), naming every such row. A
     * Permission removed from code therefore needs a migration that deletes its rows.
     */
    @Transactional(readOnly = true)
    public void verifyGroupPermissions() {
        GroupPermissionValues.validated(groupPermissions.findAll());
    }

    /**
     * The fixture User holding {@code userName}, created unreserved and in no Group when absent.
     * Looked up rather than INSERTed and caught; see the class comment for why a caught violation
     * is fatal here.
     */
    private ScimUser findOrCreate(String userName, String password, Instant now) {
        return findOrCreate(userName, userName, password, now);
    }

    /** As {@link #findOrCreate(String, String, Instant)}, created with {@code displayName}. */
    private ScimUser findOrCreate(
            String userName, String displayName, String password, Instant now) {
        return users.findByNormalizedUserName(NormalizedUserName.of(userName))
                .orElseGet(() -> users.create(
                        newUser(new SeededIdentity(userName, password), displayName, now)));
    }

    /**
     * The Bootstrap Admin, reserved as it is created. Called only once {@link #seed} has found no
     * User holding the reservation.
     *
     * <p>The reservation is the question that matters: a User holding the configured userName but
     * carrying no reservation is NOT the Bootstrap Admin, and treating it as one is the silent
     * failure this whole arrangement exists to avoid. So the create is attempted regardless, and a
     * userName collision surfaces as {@link DuplicateUserNameException} — a startup failure,
     * loudly, rather than a deployment that boots with an unprotected recovery identity. This is
     * the one write that is allowed to fail, because failing is its purpose.
     *
     * <p>Seeded with a password change required. Its password comes from deployment configuration
     * — and {@code application.yaml} carries working fallbacks on a public remote — so it is a
     * default credential known outside the User, which must be replaced before it is used for
     * anything else. Login confines the session until it is. The reserved User is exempt from
     * dormancy, so an unchanged recovery credential never locks the recovery path.
     *
     * <p>The development fixtures skip the flag: their deployment is a developer's machine, and
     * {@code app.dev-fixtures.enabled} is never set anywhere else.
     */
    private ScimUser seedBootstrapAdmin(
            SeededIdentity recovery, boolean passwordChangeRequired, Instant now) {
        ScimUser seeded = newUser(recovery, recovery.userName(), now);
        LoginStateChange imposed = loginStates.created(seeded.login().passwordHash(), now);
        // Built whole rather than by copying the new User's fields: it is a new User — version 1,
        // created and last modified now — whose credential is marked for replacement by the
        // login-state module's rule for a credential somebody else chose, unless the caller
        // declined the flag.
        ScimUser bootstrapAdmin = new ScimUser(
                seeded.id(),
                seeded.profile(),
                passwordChangeRequired ? imposed.after() : imposed.before(),
                null,
                ScimUser.INITIAL_VERSION,
                now,
                now);
        ScimUser created = users.createReserved(bootstrapAdmin, ReservedResourceName.BOOTSTRAP_ADMIN);
        audit.append(new Audited.ReservedResourceSeeded(created.id(), AuditSeededResource.USER));
        return created;
    }

    /**
     * The Admin group, reserved as it is created, with the Bootstrap Admin in it.
     *
     * <p>Created under {@link #ADMIN_GROUP_ID}. A database whose Admin group was seeded under
     * another id keeps it — the id is never rewritten — and is no less the Admin group: the
     * reservation marker, not the id, is what makes it one.
     *
     * <p>When the Group already exists its ordinary membership is left exactly as provisioning left
     * it — that membership is what external provisioning is for — and only the Bootstrap Admin's
     * own place in it is restored if something outside SCIM removed it.
     */
    private void seedAdminGroup(ScimUser bootstrapAdmin, Instant now) {
        Optional<ScimGroup> existing =
                groups.findByReservedName(ReservedResourceName.ADMIN_GROUP);
        if (existing.isEmpty()) {
            ScimGroup created = groups.createReserved(
                    ScimGroup.created(
                            ADMIN_GROUP_ID,
                            ADMIN_GROUP_DISPLAY_NAME,
                            List.of(ScimGroupMember.reference(bootstrapAdmin.id())),
                            now),
                    ReservedResourceName.ADMIN_GROUP);
            audit.append(new Audited.ReservedResourceSeeded(created.id(), AuditSeededResource.GROUP));
            return;
        }
        ScimGroup adminGroup = existing.get();
        if (adminGroup.hasMember(bootstrapAdmin.id())) {
            return;
        }
        List<ScimGroupMember> restored = new ArrayList<>(adminGroup.members());
        restored.add(ScimGroupMember.reference(bootstrapAdmin.id()));
        // The restore advances the Admin group, its members and the Bootstrap Admin, so it locks
        // them as any Group write does: through the lock module, in its one order. This
        // transaction holds no resource lock before this call, so the order cannot be broken here.
        // The membership it restores is the one read above, before the lock: a startup repair
        // under the seed lock, not a connector's conditional write.
        writeLocks.lockGroupWrite(adminGroup.id(), List.of(bootstrapAdmin.id()));
        groups.replace(adminGroup.replacedWith(adminGroup.displayName(), restored, now));
        // Audited because this is a WRITE that changes who holds Admin authority. It had been
        // silent, which meant a deployment whose administrative membership had been removed
        // outside SCIM restored it at startup leaving nothing in the trail to say either the
        // removal or the restore had happened.
        audit.append(new Audited.ReservedMembershipRestored(adminGroup.id(), bootstrapAdmin.id()));
    }

    private ScimUser newUser(SeededIdentity identity, String displayName, Instant now) {
        return ScimUser.created(
                UUID.randomUUID(),
                new ScimUserProfile(
                        identity.userName(),
                        null,
                        displayName,
                        null,
                        null,
                        null,
                        true,
                        List.of()),
                passwordEncoder.encode(identity.password()),
                now);
    }

    /**
     * The Admin group's label.
     *
     * <p>Cosmetic, and deliberately so: the glossary is explicit that the Admin group's STABLE
     * RESOURCE ID carries the authorization meaning, which is why authority derivation resolves the
     * Group by its reservation and never by this string. It is also why the Group cannot be
     * renamed — not because the name matters, but because a renameable recovery resource is one a
     * connector can make unrecognisable to an administrator reading the directory.
     */
    static final String ADMIN_GROUP_DISPLAY_NAME = "Admins";

    /**
     * One startup identity's credentials. Whether it is the recovery one is decided by which method
     * it is passed to, not by anything on this record: a seed that carried its own privilege would
     * be a value a future caller could construct with the wrong one.
     */
    public record SeededIdentity(String userName, String password) {
    }

    /**
     * One development fixture: a Group under a fixed stable id — the id the development Group
     * Permissions name — and the userName of the one User seeded into it.
     */
    public record DevFixture(UUID groupId, String displayName, String member) {
    }

    /**
     * One development fixture's Group Permissions: the Group's stable id and the Permission NAMES
     * it is given, as configuration states them, so a bad one is reported by name.
     */
    public record GroupPermissionFixture(UUID groupId, List<String> permissions) {

        public GroupPermissionFixture {
            permissions = permissions == null ? List.of() : List.copyOf(permissions);
        }
    }
}

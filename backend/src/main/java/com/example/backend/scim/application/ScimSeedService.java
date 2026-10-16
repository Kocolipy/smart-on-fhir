package com.example.backend.scim.application;

import com.example.backend.audit.domain.AuditSeededResource;
import com.example.backend.audit.domain.AuditTrail;
import com.example.backend.audit.domain.Audited;
import com.example.backend.authorization.domain.InvalidRoleMappingException;
import com.example.backend.authorization.domain.RoleMapping;
import com.example.backend.scim.domain.DuplicateUserNameException;
import com.example.backend.scim.domain.NormalizedUserName;
import com.example.backend.scim.domain.ReservedResourceName;
import com.example.backend.scim.domain.ScimGroup;
import com.example.backend.scim.domain.ScimGroupMember;
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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

    private final ScimUserRepository users;
    private final ScimGroupRepository groups;
    private final ScimWriteLocks writeLocks;
    private final ScimSeedLock seedLock;
    private final AuditTrail audit;
    private final PasswordEncoder passwordEncoder;
    private final Clock clock;
    private final RoleMapping roleMapping;
    private final ScimUserLoginState loginStates;

    public ScimSeedService(
            ScimUserRepository users,
            ScimGroupRepository groups,
            ScimWriteLocks writeLocks,
            ScimSeedLock seedLock,
            AuditTrail audit,
            PasswordEncoder passwordEncoder,
            Clock clock,
            RoleMapping roleMapping,
            ScimUserLoginState loginStates) {
        this.users = users;
        this.groups = groups;
        this.writeLocks = writeLocks;
        this.seedLock = seedLock;
        this.audit = audit;
        this.passwordEncoder = passwordEncoder;
        this.clock = clock;
        this.roleMapping = roleMapping;
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
        seedLock.acquire();
        Instant now = clock.instant();
        ScimUser bootstrapAdmin = users.findByReservedName(ReservedResourceName.BOOTSTRAP_ADMIN)
                .orElseGet(() -> seedBootstrapAdmin(recovery, now));
        seedAdminGroup(bootstrapAdmin, now);
    }

    /**
     * The development fixtures: one Group per fixture, under the fixture's fixed id, with one
     * User in it, so local runs and the e2e suite have a User per Role to sign in as.
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
        if (password == null || password.isBlank()) {
            throw new IllegalStateException(
                    "app.dev-fixtures.password (APP_DEV_FIXTURES_PASSWORD) must be set"
                            + " when the development fixtures are enabled");
        }
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
     * The development fixture for the dormancy lockout: a User whose dormancy basis is put
     * {@code dormantFor} in the past on every startup, so the dormancy run the development
     * profile makes at startup locks it, and the e2e suite can show a User locked for dormancy,
     * refused at login, unlocked and signed in again.
     *
     * <p>Unlike the Role fixtures it is RESET, not merely created: the e2e journey ends with the
     * User unlocked and holding a password it chose, so a fixture that kept that state would be
     * usable once per database. Each startup therefore puts back the fixture password — with no
     * change required — clears any lock, and backdates the basis; that reset is the login-state
     * module's ({@link ScimUserLoginState#dormantFixtureReset}). It belongs to no Group, so the
     * Role it would lose at the role-revocation window is nobody's concern. Called only when
     * {@code app.dev-fixtures.enabled} is set and a dormant fixture is named.
     *
     * @param userName   the fixture User's name
     * @param password   its password, refused when blank for the reason {@link #seedDevFixtures}
     *                   gives
     * @param dormantFor how long before now its basis is put — past the lockout window
     */
    @Transactional
    public void seedDormantDevFixture(String userName, String password, Duration dormantFor) {
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
    }

    /**
     * Refuses startup unless every Group the role mapping names exists, and the Superuser Group is
     * the reserved Admin group.
     *
     * <p>The half of the mapping's validation that needs the directory, so it runs after seeding:
     * a mapping naming a Group that does not exist would be a Role nobody can hold, which an
     * operator should learn at deploy time rather than as a missing power. And a Superuser Group
     * that is not the reserved Admin group would be a Role holding every Permission conferred by a
     * Group a connector may rename, empty or delete.
     */
    @Transactional(readOnly = true)
    public void verifyMappedGroups() {
        List<UUID> mapped = List.copyOf(roleMapping.mappedGroupIds());
        Map<UUID, ScimGroup> found = new HashMap<>();
        groups.findAllById(mapped).forEach(group -> found.put(group.id(), group));
        List<String> problems = new ArrayList<>();
        for (UUID groupId : mapped) {
            if (!found.containsKey(groupId)) {
                problems.add("Group " + groupId + " does not exist");
            }
        }
        ScimGroup superuserGroup = found.get(roleMapping.superuserGroupId());
        if (superuserGroup != null
                && superuserGroup.reservedName() != ReservedResourceName.ADMIN_GROUP) {
            problems.add("Superuser Group " + superuserGroup.id()
                    + " is not the reserved Admin group");
        }
        if (!problems.isEmpty()) {
            throw new InvalidRoleMappingException(problems);
        }
    }

    /**
     * The fixture User holding {@code userName}, created unreserved and in no Group when absent.
     * Looked up rather than INSERTed and caught; see the class comment for why a caught violation
     * is fatal here.
     */
    private ScimUser findOrCreate(String userName, String password, Instant now) {
        return users.findByNormalizedUserName(NormalizedUserName.of(userName))
                .orElseGet(() -> users.create(
                        newUser(new SeededIdentity(userName, password), now)));
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
     */
    private ScimUser seedBootstrapAdmin(SeededIdentity recovery, Instant now) {
        ScimUser seeded = newUser(recovery, now);
        // Built whole rather than by copying the new User's fields: it is a new User — version 1,
        // created and last modified now — whose credential is already marked for replacement, by
        // the login-state module's rule for a credential somebody else chose.
        ScimUser flagged = new ScimUser(
                seeded.id(),
                seeded.profile(),
                loginStates.created(seeded.login().passwordHash(), now),
                null,
                ScimUser.INITIAL_VERSION,
                now,
                now);
        ScimUser created = users.createReserved(flagged, ReservedResourceName.BOOTSTRAP_ADMIN);
        audit.append(new Audited.ReservedResourceSeeded(created.id(), AuditSeededResource.USER));
        return created;
    }

    /**
     * The Admin group, reserved as it is created, with the Bootstrap Admin in it.
     *
     * <p>Created under the role mapping's Superuser Group id, so the mapping can name the Group by
     * its stable id before the Group exists: the Admin group IS the Superuser Group. A database
     * whose Admin group was seeded under another id keeps it — the id is never rewritten — and
     * {@link #verifyMappedGroups} then refuses startup, naming the mismatch.
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
                            roleMapping.superuserGroupId(),
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

    private ScimUser newUser(SeededIdentity identity, Instant now) {
        return ScimUser.created(
                UUID.randomUUID(),
                new ScimUserProfile(
                        identity.userName(),
                        null,
                        identity.userName(),
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
     * One development fixture: a Group under a fixed stable id — the id the development role
     * mapping names — and the userName of the one User seeded into it.
     */
    public record DevFixture(UUID groupId, String displayName, String member) {
    }
}

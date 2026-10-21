package com.example.backend.scim.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.backend.audit.RecordingAuditTrail;
import com.example.backend.audit.domain.AuditOperation;
import com.example.backend.auth.LoginStateModules;
import com.example.backend.authorization.TestRoleMappings;
import com.example.backend.authorization.domain.InvalidRoleMappingException;
import com.example.backend.authorization.domain.RoleMapping;
import com.example.backend.authorization.domain.RoleMapping.GroupAssignment;
import com.example.backend.authorization.domain.RoleMapping.RoleDefinition;
import com.example.backend.scim.InMemoryScimGroupRepository;
import com.example.backend.scim.InMemoryScimUserRepository;
import com.example.backend.scim.InMemoryScimWriteLocks;
import com.example.backend.scim.ScimIdentities;
import com.example.backend.scim.application.ScimSeedService.DevFixture;
import com.example.backend.scim.application.ScimSeedService.SeededIdentity;
import com.example.backend.scim.domain.NormalizedUserName;
import com.example.backend.scim.domain.ReservedResourceName;
import com.example.backend.scim.domain.ScimGroup;
import com.example.backend.scim.domain.ScimGroupMember;
import com.example.backend.scim.domain.ScimLoginState;
import com.example.backend.scim.domain.ScimSeedLock;
import com.example.backend.scim.domain.ScimUser;
import java.time.Clock;
import java.time.Duration;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * Seeding the deployment's recovery path.
 *
 * <p>This is the ticket's fourth acceptance criterion at the level below HTTP: on a fresh database a
 * Bootstrap Admin and an Admin group exist, with the Bootstrap Admin an immutable member.
 *
 * <p>The idempotence assertions matter more than they look. Seeding runs on every startup, so a
 * version of it that overwrote would reset the recovery credential on every restart — undoing a
 * deliberate rotation — and one that created rather than found would accumulate Admin groups.
 */
class ScimSeedServiceTests {

    private static final SeededIdentity RECOVERY = new SeededIdentity("admin", "admin-password");

    /** The development fixtures' User in no Group, which used to be a configured identity. */
    private static final String BASELINE = "user";

    private static final String FIXTURE_PASSWORD = "fixture-password";

    private final InMemoryScimUserRepository users = new InMemoryScimUserRepository();

    private final InMemoryScimGroupRepository groups = new InMemoryScimGroupRepository(users);

    private final RecordingAuditTrail audit = new RecordingAuditTrail();

    private final CountingPasswordEncoder passwordEncoder = new CountingPasswordEncoder();

    private final RecordingSeedLock seedLock = new RecordingSeedLock();

    private final InMemoryScimWriteLocks writeLocks = new InMemoryScimWriteLocks();

    private final ScimSeedService seeding = new ScimSeedService(
            users,
            groups,
            writeLocks,
            seedLock,
            audit,
            passwordEncoder,
            Clock.fixed(ScimIdentities.NOW, ZoneOffset.UTC),
            TestRoleMappings.superuserOnly(),
            LoginStateModules.over(users, Clock.fixed(ScimIdentities.NOW, ZoneOffset.UTC)));

    /**
     * The Bootstrap Admin is the only User a deployment is seeded with: the non-administrative
     * {@code user} is a development fixture, so a deployment that never enables the fixtures has no
     * second configured credential.
     */
    @Test
    void a_fresh_database_gets_the_bootstrap_admin_and_no_other_user() {
        seeding.seed(RECOVERY);

        assertThat(users.findByReservedName(ReservedResourceName.BOOTSTRAP_ADMIN)).isPresent();
        assertThat(users.findByNormalizedUserName(NormalizedUserName.of(BASELINE))).isEmpty();
        assertThat(users.size()).isEqualTo(1);
    }

    @Test
    void the_recovery_identity_is_the_secondary_one_and_is_reserved_as_the_bootstrap_admin() {
        seeding.seed(RECOVERY);

        ScimUser bootstrapAdmin =
                users.findByReservedName(ReservedResourceName.BOOTSTRAP_ADMIN).orElseThrow();
        assertThat(bootstrapAdmin.profile().userName()).isEqualTo("admin");
        assertThat(bootstrapAdmin.isProtectedFromWrites()).isTrue();
        assertThat(bootstrapAdmin.isExemptFromLockout()).isTrue();
    }

    /**
     * The baseline fixture is NOT reserved and is in no Group, so it holds baseline access and
     * nothing more — the identity the non-administrative paths are exercised as.
     */
    @Test
    void the_baseline_fixture_is_unreserved_and_in_no_group() {
        seeding.seed(RECOVERY);
        seeding.seedDevFixtures(List.of(), BASELINE, FIXTURE_PASSWORD);

        ScimUser baseline = users.require(BASELINE);
        assertThat(baseline.isProtectedFromWrites()).isFalse();
        assertThat(groups.findGroupsOfUser(baseline.id())).isEmpty();
        assertThat(baseline.login().isPasswordChangeRequired()).isFalse();
        assertThat(baseline.login().passwordHash()).isNotEqualTo(FIXTURE_PASSWORD);
    }

    @Test
    void a_fresh_database_gets_an_admin_group_with_the_bootstrap_admin_in_it() {
        seeding.seed(RECOVERY);

        ScimUser bootstrapAdmin =
                users.findByReservedName(ReservedResourceName.BOOTSTRAP_ADMIN).orElseThrow();
        ScimGroup adminGroup =
                groups.findByReservedName(ReservedResourceName.ADMIN_GROUP).orElseThrow();

        assertThat(adminGroup.isProtectedFromWrites()).isTrue();
        assertThat(adminGroup.hasMember(bootstrapAdmin.id())).isTrue();
    }

    /**
     * The membership is what makes the Bootstrap Admin an administrator: there is no role column, so
     * a seeding that created the User without the membership would produce a recovery identity with
     * nothing to recover with.
     */
    @Test
    void the_bootstrap_admin_derives_administrative_authority_from_that_membership() {
        seeding.seed(RECOVERY);

        ScimUser bootstrapAdmin =
                users.findByReservedName(ReservedResourceName.BOOTSTRAP_ADMIN).orElseThrow();

        assertThat(groups.isMemberOfReservedGroup(
                        bootstrapAdmin.id(), ReservedResourceName.ADMIN_GROUP))
                .isTrue();
    }

    @Test
    void each_seeded_reserved_resource_records_its_own_event_with_no_actor() {
        seeding.seed(RECOVERY);

        assertThat(audit.of(AuditOperation.SCIM_RESOURCE_SEED)).hasSize(2)
                .allSatisfy(event -> assertThat(event.actorId())
                        .as("seeding is the deployment acting, not a principal")
                        .isNull())
                .extracting(RecordingAuditTrail.Recorded::detail)
                .containsExactlyInAnyOrder("User", "Group");
        // Each event names the resource it seeded, so the trail can be joined to the directory.
        assertThat(audit.of(AuditOperation.SCIM_RESOURCE_SEED))
                .extracting(RecordingAuditTrail.Recorded::subjectId)
                .containsExactlyInAnyOrder(
                        users.findByReservedName(ReservedResourceName.BOOTSTRAP_ADMIN)
                                .orElseThrow().id(),
                        groups.findByReservedName(ReservedResourceName.ADMIN_GROUP)
                                .orElseThrow().id());
    }

    /**
     * The Bootstrap Admin and the baseline fixture are stamped with the seeding clock and carry
     * their userName as their displayName, which is what the Accounts page lists them by.
     */
    @Test
    void the_seeded_identities_are_created_now_and_displayed_by_their_user_name() {
        seeding.seed(RECOVERY);
        seeding.seedDevFixtures(List.of(), BASELINE, FIXTURE_PASSWORD);

        assertThat(List.of(users.require(BASELINE), users.require("admin")))
                .allSatisfy(seeded -> {
                    assertThat(seeded.createdAt()).isEqualTo(ScimIdentities.NOW);
                    assertThat(seeded.lastModifiedAt()).isEqualTo(ScimIdentities.NOW);
                    assertThat(seeded.profile().displayName())
                            .isEqualTo(seeded.profile().userName());
                    assertThat(seeded.profile().emails()).isEmpty();
                });
    }

    /**
     * The Bootstrap Admin's password comes from deployment configuration, with working fallbacks
     * on a public remote: a default credential, which IM8 ac-6 (ADR 0011) requires be replaced
     * on first use. Dated by the seeding clock.
     */
    @Test
    void the_bootstrap_admin_is_seeded_with_a_password_change_required() {
        seeding.seed(RECOVERY);

        ScimUser bootstrapAdmin =
                users.findByReservedName(ReservedResourceName.BOOTSTRAP_ADMIN).orElseThrow();
        assertThat(bootstrapAdmin.login().passwordChangeRequiredSince())
                .isEqualTo(ScimIdentities.NOW);
    }

    /**
     * A restart does not re-impose the change on a Bootstrap Admin that already completed it:
     * that would force a fresh credential on every boot.
     */
    @Test
    void a_later_run_does_not_re_flag_a_bootstrap_admin_that_changed_its_password() {
        seeding.seed(RECOVERY);
        ScimUser bootstrapAdmin =
                users.findByReservedName(ReservedResourceName.BOOTSTRAP_ADMIN).orElseThrow();
        users.completePasswordChange(bootstrapAdmin.id(), "hashed:rotated", ScimIdentities.NOW);

        seeding.seed(RECOVERY);

        assertThat(users.findById(bootstrapAdmin.id()).orElseThrow()
                        .login().isPasswordChangeRequired())
                .isFalse();
    }

    /** The baseline fixture is not a reserved resource, so its creation records nothing. */
    @Test
    void the_baseline_fixture_records_no_audit_event() {
        seeding.seed(RECOVERY);
        audit.reset();

        seeding.seedDevFixtures(List.of(), BASELINE, FIXTURE_PASSWORD);

        assertThat(users.findByNormalizedUserName(NormalizedUserName.of(BASELINE))).isPresent();
        assertThat(audit.recorded()).isEmpty();
    }

    // ---- idempotence --------------------------------------------------------------------------

    /**
     * Every run takes the seeding lock, and takes it before it creates anything: the reads decide
     * what to write, so a read taken before the lock could see a state a concurrent seed is about
     * to change. The integration test shows the lock really serializes; this one shows seeding
     * asks for it.
     */
    @Test
    void every_run_takes_the_seeding_lock_before_creating_anything() {
        seeding.seed(RECOVERY);
        seeding.seed(RECOVERY);

        assertThat(seedLock.usersSeenAtEachAcquire)
                .as("one acquisition per run, each before that run created anything")
                .containsExactly(0L, 1L);
    }

    /**
     * A run that finds the recovery path already there does no hashing at all. Hashing is the
     * expensive step, and a count that does not move shows neither identity was even prepared for
     * a write on a restart.
     */
    @Test
    void a_later_run_hashes_nothing() {
        seeding.seed(RECOVERY);
        int afterFirst = passwordEncoder.encodes;

        seeding.seed(RECOVERY);

        assertThat(passwordEncoder.encodes).isEqualTo(afterFirst);
    }

    /**
     * A User already holding the baseline fixture's name — one provisioned before the fixtures
     * were enabled — is left alone: not replaced, not re-hashed, and found by a lookup rather than
     * by a failed INSERT, whose constraint violation would mark the seed transaction for rollback.
     */
    @Test
    void the_baseline_fixture_leaves_an_existing_user_under_its_name_alone() {
        ScimUser existing = users.create(ScimIdentities.user(BASELINE));

        seeding.seedDevFixtures(List.of(), BASELINE, FIXTURE_PASSWORD);

        assertThat(users.require(BASELINE).id()).isEqualTo(existing.id());
        assertThat(users.require(BASELINE).login().passwordHash())
                .isEqualTo(existing.login().passwordHash());
        assertThat(passwordEncoder.encodes).isZero();
    }

    @Test
    void a_second_run_creates_nothing_and_records_nothing() {
        seeding.seed(RECOVERY);
        long usersAfterFirst = users.size();
        long groupsAfterFirst = groups.size();
        audit.reset();

        seeding.seed(RECOVERY);

        assertThat(users.size()).isEqualTo(usersAfterFirst);
        assertThat(groups.size()).isEqualTo(groupsAfterFirst);
        assertThat(audit.recorded()).isEmpty();
    }

    /**
     * A restart must not reset the recovery credential to the configured one: an operator who
     * rotated it deliberately would find the rotation silently undone.
     */
    @Test
    void a_second_run_does_not_overwrite_the_recovery_password() {
        seeding.seed(RECOVERY);
        ScimUser afterFirst =
                users.findByReservedName(ReservedResourceName.BOOTSTRAP_ADMIN).orElseThrow();

        seeding.seed(new SeededIdentity("admin", "a-different-password"));

        assertThat(users.findByReservedName(ReservedResourceName.BOOTSTRAP_ADMIN).orElseThrow()
                        .login().passwordHash())
                .isEqualTo(afterFirst.login().passwordHash());
    }

    @Test
    void a_second_run_does_not_reset_the_recovery_identitys_failure_run_or_lock() {
        seeding.seed(RECOVERY);
        ScimUser bootstrapAdmin =
                users.findByReservedName(ReservedResourceName.BOOTSTRAP_ADMIN).orElseThrow();
        ScimIdentities.storeFailureRun(
                users, bootstrapAdmin.id(), bootstrapAdmin.login().withFailureCounted());

        seeding.seed(RECOVERY);

        assertThat(users.findById(bootstrapAdmin.id()).orElseThrow()
                        .login().failedLoginAttempts())
                .isEqualTo(1);
    }

    /** Ordinary membership provisioning put into the Admin group survives a restart. */
    @Test
    void a_second_run_leaves_ordinary_admin_group_membership_alone() {
        seeding.seed(RECOVERY);
        ScimGroup adminGroup =
                groups.findByReservedName(ReservedResourceName.ADMIN_GROUP).orElseThrow();
        ScimUser promoted = users.create(ScimIdentities.user("carol"));
        List<ScimGroupMember> withCarol = new ArrayList<>(adminGroup.members());
        withCarol.add(ScimGroupMember.reference(promoted.id()));
        groups.replace(adminGroup.replacedWith(
                adminGroup.displayName(), withCarol, ScimIdentities.NOW));

        seeding.seed(RECOVERY);

        assertThat(groups.findByReservedName(ReservedResourceName.ADMIN_GROUP).orElseThrow()
                        .hasMember(promoted.id()))
                .isTrue();
    }

    /**
     * If something outside SCIM removed the Bootstrap Admin from the Admin group, seeding restores
     * it — the SCIM surface refuses to remove that membership, so its absence means the deployment's
     * authority was broken by something else, and leaving it broken serves nobody.
     */
    @Test
    void a_later_run_restores_the_bootstrap_admins_membership_if_it_went_missing() {
        seeding.seed(RECOVERY);
        ScimGroup adminGroup =
                groups.findByReservedName(ReservedResourceName.ADMIN_GROUP).orElseThrow();
        ScimUser bootstrapAdmin =
                users.findByReservedName(ReservedResourceName.BOOTSTRAP_ADMIN).orElseThrow();
        groups.given(new ScimGroup(
                adminGroup.id(),
                adminGroup.displayName(),
                List.of(),
                ReservedResourceName.ADMIN_GROUP,
                adminGroup.version(),
                adminGroup.createdAt(),
                adminGroup.lastModifiedAt()));

        seeding.seed(RECOVERY);

        assertThat(groups.findByReservedName(ReservedResourceName.ADMIN_GROUP).orElseThrow()
                        .hasMember(bootstrapAdmin.id()))
                .isTrue();

        // The restore must leave a RECORD, not just a restored membership. This assertion was
        // missing, so the test passed while a write that changes who holds Admin authority emitted
        // nothing: an operator would find neither the outside-SCIM removal nor the startup that
        // undid it. Recorded against the Group, whose representation is what changed.
        assertThat(audit.of(AuditOperation.SCIM_RESOURCE_SEED))
                .as("restoring the recovery identity's Admin membership is an audited write")
                .anySatisfy(recorded -> {
                    assertThat(recorded.subjectId()).isEqualTo(adminGroup.id());
                    // ...and names the User whose Admin membership was restored.
                    assertThat(recorded.detail())
                            .isEqualTo("members-restored " + bootstrapAdmin.id());
                });
    }

    /**
     * The restore advances the Admin group and the Bootstrap Admin, so it asks the lock module for
     * them as a Group write does, and a seed that restores nothing asks for nothing.
     */
    @Test
    void only_a_restore_locks_the_admin_group_with_the_bootstrap_admin() {
        seeding.seed(RECOVERY);
        ScimGroup adminGroup =
                groups.findByReservedName(ReservedResourceName.ADMIN_GROUP).orElseThrow();
        ScimUser bootstrapAdmin =
                users.findByReservedName(ReservedResourceName.BOOTSTRAP_ADMIN).orElseThrow();
        groups.given(new ScimGroup(
                adminGroup.id(),
                adminGroup.displayName(),
                List.of(),
                ReservedResourceName.ADMIN_GROUP,
                adminGroup.version(),
                adminGroup.createdAt(),
                adminGroup.lastModifiedAt()));

        seeding.seed(RECOVERY);

        assertThat(writeLocks.requests())
                .containsExactly(Set.of(adminGroup.id(), bootstrapAdmin.id()));
    }

    /** A run that finds the membership intact restores nothing, so it records no restore. */
    @Test
    void a_later_run_that_finds_the_membership_intact_records_no_restore() {
        seeding.seed(RECOVERY);
        audit.reset();

        seeding.seed(RECOVERY);

        assertThat(audit.of(AuditOperation.SCIM_RESOURCE_SEED))
                .as("an idempotent restart is not an authority change and must not read as one")
                .noneSatisfy(recorded ->
                        assertThat(recorded.detail()).startsWith("members-restored"));
    }

    /**
     * The seeded password is hashed, never stored as submitted. Asserted through the encoder's
     * invocation count and the stored value differing from the plaintext, because the real encoder's
     * output cannot be predicted.
     */
    @Test
    void the_seeded_password_is_hashed_before_it_is_stored() {
        seeding.seed(RECOVERY);

        assertThat(passwordEncoder.encodes).isEqualTo(1);
        assertThat(users.require("admin").login().passwordHash()).isNotEqualTo("admin-password");
    }

    // The role mapping's Superuser Group

    /** The Admin group is created under the mapping's Superuser Group id: they are one Group. */
    @Test
    void the_admin_group_is_created_under_the_superuser_group_id() {
        seeding.seed(RECOVERY);

        assertThat(groups.findByReservedName(ReservedResourceName.ADMIN_GROUP).orElseThrow().id())
                .isEqualTo(TestRoleMappings.SUPERUSER_GROUP_ID);
    }

    @Test
    void a_seeded_directory_satisfies_a_superuser_only_mapping() {
        seeding.seed(RECOVERY);

        assertThatCode(seeding::verifyMappedGroups).doesNotThrowAnyException();
    }

    /**
     * An Admin group seeded earlier under another id keeps it — the id is never rewritten — and the
     * check then refuses startup, because the mapping's Superuser Group does not exist.
     */
    @Test
    void an_admin_group_seeded_under_another_id_keeps_it_and_fails_the_check() {
        ScimUser bootstrapAdmin = users.createReserved(
                ScimIdentities.user("admin"), ReservedResourceName.BOOTSTRAP_ADMIN);
        UUID earlier = groups.createReserved(
                ScimIdentities.group("Admins", bootstrapAdmin), ReservedResourceName.ADMIN_GROUP)
                .id();

        seeding.seed(RECOVERY);

        assertThat(groups.findByReservedName(ReservedResourceName.ADMIN_GROUP).orElseThrow().id())
                .isEqualTo(earlier);
        assertThatThrownBy(seeding::verifyMappedGroups)
                .isInstanceOf(InvalidRoleMappingException.class)
                .hasMessage("Invalid role mapping: Group " + TestRoleMappings.SUPERUSER_GROUP_ID
                        + " does not exist");
    }

    @Test
    void a_mapped_group_that_does_not_exist_fails_the_check_by_id() {
        UUID helpdesk = UUID.fromString("00000000-0000-4000-8000-0000000000c1");
        UUID auditors = UUID.fromString("00000000-0000-4000-8000-0000000000c2");
        ScimSeedService withHelpdesk = seedingUnder(mappingWith(helpdesk, auditors));
        groups.given(ScimGroup.created(auditors, "Auditors", List.of(), ScimIdentities.NOW));
        withHelpdesk.seed(RECOVERY);

        assertThatThrownBy(withHelpdesk::verifyMappedGroups)
                .isInstanceOf(InvalidRoleMappingException.class)
                .hasMessage("Invalid role mapping: Group " + helpdesk + " does not exist");
    }

    /**
     * A Superuser Group id resolving to an ordinary Group would put every Permission behind a Group a
     * connector may rename, empty or delete.
     */
    @Test
    void a_superuser_group_that_is_not_the_reserved_admin_group_fails_the_check() {
        groups.given(ScimGroup.created(
                TestRoleMappings.SUPERUSER_GROUP_ID, "Impostors", List.of(), ScimIdentities.NOW));
        ScimUser bootstrapAdmin = users.createReserved(
                ScimIdentities.user("admin"), ReservedResourceName.BOOTSTRAP_ADMIN);
        groups.createReserved(
                ScimIdentities.group("Admins", bootstrapAdmin), ReservedResourceName.ADMIN_GROUP);

        assertThatThrownBy(seeding::verifyMappedGroups)
                .isInstanceOf(InvalidRoleMappingException.class)
                .hasMessage("Invalid role mapping: Superuser Group "
                        + TestRoleMappings.SUPERUSER_GROUP_ID
                        + " is not the reserved Admin group");
    }

    // Development fixtures

    private static final UUID FIXTURE_GROUP =
            UUID.fromString("00000000-0000-4000-8000-0000000000d1");

    private static final List<DevFixture> FIXTURES =
            List.of(new DevFixture(FIXTURE_GROUP, "Account admins", "account-admin"));

    @Test
    void the_fixtures_create_each_group_under_its_id_with_its_user_in_it() {
        seeding.seedDevFixtures(FIXTURES, null, "fixture-password");

        ScimUser member = users.require("account-admin");
        ScimGroup group = groups.findById(FIXTURE_GROUP).orElseThrow();
        assertThat(group.displayName()).isEqualTo("Account admins");
        assertThat(group.members()).extracting(ScimGroupMember::userId)
                .containsExactly(member.id());
        assertThat(group.isProtectedFromWrites()).isFalse();
        assertThat(group.createdAt()).isEqualTo(ScimIdentities.NOW);
        assertThat(member.createdAt()).isEqualTo(ScimIdentities.NOW);
        assertThat(member.isProtectedFromWrites()).isFalse();
        assertThat(member.login().isPasswordChangeRequired()).isFalse();
        assertThat(passwordEncoder.matches("fixture-password", member.login().passwordHash()))
                .isTrue();
        assertThat(seedLock.usersSeenAtEachAcquire).containsExactly(0L);
    }

    /** Never overwrites: a second run, or a fixture User changed since, is left as it is. */
    @Test
    void the_fixtures_are_idempotent_and_never_overwrite() {
        ScimUser existing = users.given(ScimIdentities.user("account-admin"));

        seeding.seedDevFixtures(FIXTURES, null, "fixture-password");
        seeding.seedDevFixtures(FIXTURES, null, "another-password");

        assertThat(users.require("account-admin").login().passwordHash())
                .isEqualTo(existing.login().passwordHash());
        assertThat(groups.findById(FIXTURE_GROUP).orElseThrow().members())
                .extracting(ScimGroupMember::userId)
                .containsExactly(existing.id());
        assertThat(groups.size()).isEqualTo(1);
        assertThat(passwordEncoder.encodes).isZero();
    }

    /**
     * A Group already standing under a fixture's id — renamed and repopulated since — is left as
     * it is: the fixture is created only where nothing holds the id.
     */
    @Test
    void a_fixture_group_that_already_exists_is_left_as_it_is() {
        ScimUser other = users.given(ScimIdentities.user("someone-else"));
        groups.given(ScimGroup.created(FIXTURE_GROUP, "Renamed since",
                List.of(ScimGroupMember.reference(other.id())), ScimIdentities.NOW));

        seeding.seedDevFixtures(FIXTURES, null, "fixture-password");

        ScimGroup group = groups.findById(FIXTURE_GROUP).orElseThrow();
        assertThat(group.displayName()).isEqualTo("Renamed since");
        assertThat(group.members()).extracting(ScimGroupMember::userId)
                .containsExactly(other.id());
    }

    @Test
    void the_fixtures_are_refused_without_a_password() {
        assertThatThrownBy(() -> seeding.seedDevFixtures(FIXTURES, null, " "))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("APP_DEV_FIXTURES_PASSWORD");
        assertThatThrownBy(() -> seeding.seedDevFixtures(FIXTURES, null, null))
                .isInstanceOf(IllegalStateException.class);
        assertThat(users.size()).isZero();
        assertThat(groups.size()).isZero();
    }

    // ---- the dormant fixture ----------------------------------------------------------------

    /**
     * Created on first use as a settled, credentialed User in no Group, with its basis put the
     * given time in the past and no lock yet — the startup dormancy run imposes that.
     */
    @Test
    void the_dormant_fixture_is_created_backdated_and_unlocked() {
        seeding.seedDormantDevFixture("dormant", "fixture-password", Duration.ofDays(91), List.of());

        ScimUser dormant = users.require("dormant");
        assertThat(dormant.login().lastAuthenticatedAt())
                .isEqualTo(ScimIdentities.NOW.minus(Duration.ofDays(91)));
        assertThat(dormant.dormancyBasis()).isEqualTo(ScimIdentities.NOW.minus(Duration.ofDays(91)));
        assertThat(dormant.login().isLocked()).isFalse();
        assertThat(dormant.login().isPasswordChangeRequired()).isFalse();
        assertThat(passwordEncoder.matches("fixture-password", dormant.login().passwordHash()))
                .isTrue();
        assertThat(dormant.isProtectedFromWrites()).isFalse();
        assertThat(groups.size()).isZero();
        assertThat(seedLock.usersSeenAtEachAcquire).containsExactly(0L);
    }

    /**
     * Unlike the Role fixtures it is reset on every run: after the e2e journey unlocked it and it
     * chose a password, the next startup puts back the fixture password, clears the lock and the
     * flag, and backdates the basis again — so the journey can run after every restart.
     */
    @Test
    void the_dormant_fixture_is_reset_on_every_run() {
        users.given(ScimIdentities.userWithLoginState("dormant", ScimLoginState.of("chosen-hash")
                .withDormancyLock(ScimIdentities.NOW)
                .withPasswordChangeRequired(ScimIdentities.NOW)));

        seeding.seedDormantDevFixture("dormant", "fixture-password", Duration.ofDays(91), List.of());

        ScimUser dormant = users.require("dormant");
        assertThat(dormant.login().isLocked()).isFalse();
        assertThat(dormant.login().lockCause()).isNull();
        assertThat(dormant.login().isPasswordChangeRequired()).isFalse();
        assertThat(passwordEncoder.matches("fixture-password", dormant.login().passwordHash()))
                .isTrue();
        assertThat(dormant.login().lastAuthenticatedAt())
                .isEqualTo(ScimIdentities.NOW.minus(Duration.ofDays(91)));
        assertThat(users.size()).isEqualTo(1);
    }

    /**
     * Put back into each of its Groups that exists on every run, once, so the startup dormancy run
     * has memberships to remove whatever the previous run removed; a Group that does not exist is
     * skipped rather than created.
     */
    @Test
    void the_dormant_fixture_is_put_back_into_its_groups_on_every_run() {
        UUID auditors = UUID.fromString("00000000-0000-4000-8000-00000000a003");
        UUID absent = UUID.fromString("00000000-0000-4000-8000-00000000a0ff");
        ScimUser auditor = users.create(ScimIdentities.user("auditor"));
        groups.create(ScimGroup.created(auditors, "Auditors",
                List.of(ScimGroupMember.reference(auditor.id())), ScimIdentities.NOW));

        seeding.seedDormantDevFixture(
                "dormant", "fixture-password", Duration.ofDays(181), List.of(auditors, absent));
        seeding.seedDormantDevFixture(
                "dormant", "fixture-password", Duration.ofDays(181), List.of(auditors, absent));

        UUID dormant = users.require("dormant").id();
        assertThat(groups.findById(auditors).orElseThrow().members())
                .extracting(ScimGroupMember::userId)
                .containsExactly(auditor.id(), dormant);
        assertThat(groups.findById(absent)).isEmpty();
        assertThat(writeLocks.requests()).as("the Groups and the User, together, on each run")
                .containsExactly(Set.of(auditors, absent, dormant), Set.of(auditors, absent, dormant));
    }

    @Test
    void the_dormant_fixture_is_refused_without_a_password() {
        assertThatThrownBy(() -> seeding.seedDormantDevFixture("dormant", " ", Duration.ofDays(91), List.of()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("APP_DEV_FIXTURES_PASSWORD");
        assertThatThrownBy(() -> seeding.seedDormantDevFixture("dormant", null, Duration.ofDays(91), List.of()))
                .isInstanceOf(IllegalStateException.class);
        assertThat(users.size()).isZero();
    }

    private ScimSeedService seedingUnder(RoleMapping mapping) {
        return new ScimSeedService(
                users, groups, new InMemoryScimWriteLocks(), seedLock, audit, passwordEncoder,
                Clock.fixed(ScimIdentities.NOW, ZoneOffset.UTC), mapping,
                LoginStateModules.over(users, Clock.fixed(ScimIdentities.NOW, ZoneOffset.UTC)));
    }

    private static RoleMapping mappingWith(UUID... more) {
        List<GroupAssignment> entries = new ArrayList<>(List.of(new GroupAssignment(
                TestRoleMappings.SUPERUSER_GROUP_ID, "Superuser", true)));
        for (UUID id : more) {
            entries.add(new GroupAssignment(id, "Superuser", false));
        }
        return RoleMapping.of(
                List.of(new RoleDefinition("Superuser", TestRoleMappings.EVERY_PERMISSION)),
                entries);
    }

    /** Records how many Users existed at each acquisition, so a test can see WHEN it was taken. */
    private final class RecordingSeedLock implements ScimSeedLock {

        private final List<Long> usersSeenAtEachAcquire = new ArrayList<>();

        @Override
        public void acquire() {
            usersSeenAtEachAcquire.add(users.size());
        }
    }

    /**
     * A {@link PasswordEncoder} that counts, so a test can assert hashing HAPPENED rather than
     * assert a value it would have to predict.
     */
    private static final class CountingPasswordEncoder implements PasswordEncoder {

        private int encodes;

        @Override
        public String encode(CharSequence rawPassword) {
            encodes++;
            return hash(rawPassword);
        }

        /**
         * Deliberately does NOT go through {@link #encode}, which would inflate the count this
         * class exists to report.
         */
        @Override
        public boolean matches(CharSequence rawPassword, String encodedPassword) {
            return hash(rawPassword).equals(encodedPassword);
        }

        private static String hash(CharSequence rawPassword) {
            return "hashed:" + UUID.nameUUIDFromBytes(rawPassword.toString().getBytes());
        }
    }
}

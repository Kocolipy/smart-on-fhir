package com.example.backend.scim.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.backend.TokenPermissions;
import com.example.backend.audit.RecordingAuditTrail;
import com.example.backend.audit.domain.AuditOperation;
import com.example.backend.auth.MutableClock;
import com.example.backend.authorization.TestGroupPermissions;
import com.example.backend.authorization.domain.Permission;
import com.example.backend.observability.EcsLogCapture;
import com.example.backend.paging.domain.PageRequest;
import com.example.backend.scim.InMemoryGroupPermissions;
import com.example.backend.scim.InMemoryScimGroupRepository;
import com.example.backend.scim.InMemoryScimQueryRepository;
import com.example.backend.scim.InMemoryScimTombstoneRepository;
import com.example.backend.scim.InMemoryScimUserRepository;
import com.example.backend.scim.InMemoryScimWriteLocks;
import com.example.backend.scim.ScimIdentities;
import com.example.backend.scim.domain.AuthenticatedConnector;
import com.example.backend.scim.domain.DuplicateDisplayNameException;
import com.example.backend.scim.domain.PreconditionFailedException;
import com.example.backend.scim.domain.ProtectedResourceException;
import com.example.backend.scim.domain.ReservedResourceName;
import com.example.backend.scim.domain.ScimGroup;
import com.example.backend.scim.domain.ScimGroupMember;
import com.example.backend.scim.domain.ScimQuery;
import com.example.backend.scim.domain.ScimQueryRepository;
import com.example.backend.scim.domain.ScimResourceType;
import com.example.backend.scim.domain.ScimUser;
import com.example.backend.scim.domain.ScimUserSessions;
import com.example.backend.scim.domain.ScimValueTooLongException;
import com.example.backend.scim.domain.ScimVersionPrecondition;
import com.example.backend.scim.domain.UnknownGroupMemberException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.StandardEnvironment;
import tools.jackson.databind.JsonNode;

/**
 * The Group write and read use cases, and the refusals that protect the deployment's recovery path.
 *
 * <p>These cover the ticket's first, third, fourth and seventh acceptance criteria: direct-User-only
 * membership with a Group, a deleted User or an unknown id refused; a PATCH that fails partway
 * applying nothing; version increments on the Group and on every affected User; the Admin group and
 * the Bootstrap Admin refusing every write that would change them, verified by re-reading unchanged;
 * and an audit event for each write and each refusal.
 */
class ScimGroupServiceTests extends ScimAliasWriteContract {

    private static final AuthenticatedConnector CONNECTOR = new AuthenticatedConnector(
            UUID.randomUUID(), UUID.randomUUID(), TokenPermissions.of(TokenPermissions.ALL));

    /** An ordinary Group that confers Permissions: writable and deletable. */
    private static final UUID HELPDESK_GROUP_ID =
            UUID.fromString("00000000-0000-4000-8000-0000000b0002");

    private final InMemoryScimUserRepository users = new InMemoryScimUserRepository();

    private final InMemoryScimGroupRepository groups = new InMemoryScimGroupRepository(users);

    /**
     * The Groups' stored Permissions over this directory: the Helpdesk Group carries its two, and
     * the Admin group — by its marker — none. A deleted Group's rows go with it, as the cascade
     * takes them in the database.
     */
    private final InMemoryGroupPermissions groupPermissions = InMemoryGroupPermissions.over(groups)
            .carrying(HELPDESK_GROUP_ID, Permission.USER_READ, Permission.ACCOUNT_UNLOCK);

    /** Why a refused write records nothing here; see {@link #refusalsRecordedHere}. */
    private static final String REFUSAL_IS_THE_EDGES =
            "a refusal is recorded at the edge, not by the use case";

    private final RecordingAuditTrail audit = new RecordingAuditTrail();

    private final InMemoryScimTombstoneRepository tombstones =
            new InMemoryScimTombstoneRepository();

    /** One after-commit revocation the service asked for. */
    private record Revocation(UUID connectorId, UUID userId, Set<ScimUserSessions.Cause> causes) {
    }

    private final List<Revocation> revocations = new ArrayList<>();

    private final ScimUserSessions sessions = (connectorId, userId, causes) ->
            revocations.add(new Revocation(connectorId, userId, Set.copyOf(causes)));

    /** Starts at {@link ScimIdentities#NOW}; a test moves it to tell a write's time from a create's. */
    private final MutableClock clock = new MutableClock(ScimIdentities.NOW);

    private final InMemoryScimQueryRepository storedQueries =
            new InMemoryScimQueryRepository(users, groups);

    /** One call the service made to the query port. */
    private record QueryCall(ScimQuery query, UUID connectorId) {
    }

    private final List<QueryCall> queryCalls = new ArrayList<>();

    /** When set, the query port answers with this instead of evaluating against the fakes. */
    private ScimQuery.Result stubbedQueryResult;

    private final ScimQueryRepository queries = (query, connectorId, baseUri) -> {
        queryCalls.add(new QueryCall(query, connectorId));
        return stubbedQueryResult != null
                ? stubbedQueryResult
                : storedQueries.query(query, connectorId, baseUri);
    };

    private final InMemoryScimWriteLocks writeLocks = new InMemoryScimWriteLocks();

    /** How many Superuser grants the service counted. */
    private int superuserGrants;

    private final ScimGroupService service = new ScimGroupService(
            groups,
            writeLocks,
            users,
            aliases,
            sessions,
            tombstones,
            audit,
            clock,
            queries,
            groupPermissions,
            () -> superuserGrants++);

    private ScimUser alice;

    private ScimUser bob;

    @BeforeEach
    void storeUsers() {
        alice = users.create(ScimIdentities.user("alice"));
        bob = users.create(ScimIdentities.user("bob"));
    }

    /**
     * The precondition a well-behaved connector sends: the Group's current version, or any
     * well-formed tag when there is no Group to read one from. These tests are about what a
     * write does, not about preconditions — those are pinned in their own tests.
     */
    private ScimVersionPrecondition current(UUID id) {
        long version = groups.findById(id).map(ScimGroup::version).orElse(ScimUser.INITIAL_VERSION);
        return ScimVersionPrecondition.ofIfMatch(List.of("\"" + version + "\""));
    }

    // ---- the locks a write asks for -----------------------------------------------------------

    @Test
    void a_create_locks_every_member_it_names() {
        service.create(CONNECTOR,
                new NewScimGroup("Engineering", List.of(alice.id(), bob.id()), null));

        assertThat(writeLocks.requests()).containsExactly(Set.of(alice.id(), bob.id()));
    }

    @Test
    void a_put_locks_the_group_and_every_member_the_replacement_names() {
        ScimGroup group = groups.create(ScimIdentities.group("Engineering", alice));

        service.replace(CONNECTOR, group.id(), current(group.id()),
                new ScimGroupReplacement("Engineering", List.of(bob.id()), null));

        assertThat(writeLocks.requests()).containsExactly(Set.of(group.id(), bob.id()));
    }

    /** Every User an add, a remove or a replace names; a rename and an alias name none. */
    @Test
    void a_patch_locks_the_group_and_every_user_its_operations_name() {
        ScimUser carol = users.create(ScimIdentities.user("carol"));
        ScimUser dave = users.create(ScimIdentities.user("dave"));
        ScimGroup group = groups.create(ScimIdentities.group("Engineering", alice, bob));

        service.patch(CONNECTOR, group.id(), current(group.id()), List.of(
                new ScimGroupPatchOperation.SetDisplayName("Platform"),
                new ScimGroupPatchOperation.SetExternalId("eng"),
                new ScimGroupPatchOperation.RemoveExternalId(),
                new ScimGroupPatchOperation.AddMembers(List.of(carol.id())),
                new ScimGroupPatchOperation.RemoveMembers(List.of(alice.id())),
                new ScimGroupPatchOperation.RemoveAllMembers(),
                new ScimGroupPatchOperation.ReplaceMembers(List.of(dave.id()))));

        assertThat(writeLocks.requests())
                .containsExactly(Set.of(group.id(), carol.id(), alice.id(), dave.id()));
    }

    @Test
    void a_delete_locks_the_group() {
        ScimGroup group = groups.create(ScimIdentities.group("Engineering", alice));

        service.delete(CONNECTOR, group.id(), current(group.id()));

        assertThat(writeLocks.requests()).containsExactly(Set.of(group.id()));
    }

    // ---- create -------------------------------------------------------------------------------

    @Test
    void a_created_group_starts_at_version_one_and_carries_its_members_labels() {
        ScimGroupResource created = service.create(
                CONNECTOR, new NewScimGroup("Engineering", List.of(alice.id()), null));

        assertThat(created.displayName()).isEqualTo("Engineering");
        assertThat(created.version()).isEqualTo(ScimUser.INITIAL_VERSION);
        assertThat(created.members()).extracting(ScimGroupMember::userId).containsExactly(alice.id());
        assertThat(created.members()).extracting(ScimGroupMember::display).containsExactly("alice");
    }

    @Test
    void an_empty_group_is_legitimate() {
        assertThat(service.create(CONNECTOR, new NewScimGroup("Empty", List.of(), null)).members())
                .isEmpty();
    }

    @Test
    void a_created_group_records_one_create_event_naming_it() {
        ScimGroupResource created = service.create(
                CONNECTOR, new NewScimGroup("Engineering", List.of(), null));

        assertThat(audit.of(AuditOperation.SCIM_GROUP_CREATE)).singleElement()
                .satisfies(event -> {
                    assertThat(event.actorId()).isEqualTo(CONNECTOR.connectorId());
                    assertThat(event.subjectId()).isEqualTo(created.id());
                    assertThat(event.detail()).isNull();
                });
    }

    @Test
    void a_display_name_a_live_group_already_holds_is_refused_as_a_uniqueness_failure() {
        service.create(CONNECTOR, new NewScimGroup("Engineering", List.of(), null));

        assertThatThrownBy(() -> service.create(
                        CONNECTOR, new NewScimGroup("engineering", List.of(), null)))
                .isInstanceOf(DuplicateDisplayNameException.class);
        assertThat(refusalsRecordedHere(AuditOperation.SCIM_GROUP_CREATE)).isEmpty();
    }

    /** AC 1: a Group's id as a member is rejected as invalid — the membership names Users only. */
    @Test
    void a_group_id_as_a_member_is_refused() {
        ScimGroupResource engineering = service.create(
                CONNECTOR, new NewScimGroup("Engineering", List.of(), null));

        assertThatThrownBy(() -> service.create(
                        CONNECTOR, new NewScimGroup("Nested", List.of(engineering.id()), null)))
                .isInstanceOf(UnknownGroupMemberException.class);
    }

    @Test
    void an_unknown_id_as_a_member_is_refused_as_an_invalid_value() {
        assertThatThrownBy(() -> service.create(
                        CONNECTOR, new NewScimGroup("Ghosts", List.of(UUID.randomUUID()), null)))
                .isInstanceOf(UnknownGroupMemberException.class);
        assertThat(refusalsRecordedHere(AuditOperation.SCIM_GROUP_CREATE)).isEmpty();
    }

    // ---- read ---------------------------------------------------------------------------------

    /** Deliberately unaudited: a single read is ordinary traffic and would bury the bulk reads. */
    @Test
    void retrieving_one_group_records_nothing() {
        ScimGroupResource created = service.create(
                CONNECTOR, new NewScimGroup("Engineering", List.of(), null));
        audit.reset();

        assertThat(service.findById(CONNECTOR, created.id())).isPresent();
        assertThat(audit.recorded()).isEmpty();
    }

    @Test
    void a_collection_read_is_audited_even_when_it_returns_nothing() {
        ScimGroupListing listing = service.query(CONNECTOR, groupQuery(1, 0), BASE_URI);

        assertThat(listing.resources()).isEmpty();
        assertThat(listing.totalResults()).isZero();
        assertThat(audit.of(AuditOperation.SCIM_GROUP_LIST))
                .singleElement()
                .extracting(RecordingAuditTrail.Recorded::detail)
                .isEqualTo("0");
    }

    @Test
    void a_zero_count_page_still_reports_the_total() {
        service.create(CONNECTOR, new NewScimGroup("Engineering", List.of(), null));
        service.create(CONNECTOR, new NewScimGroup("Support", List.of(), null));

        ScimGroupListing listing = service.query(CONNECTOR, groupQuery(1, 0), BASE_URI);

        assertThat(listing.resources()).isEmpty();
        assertThat(listing.totalResults()).isEqualTo(2);
    }

    /** The recorded count is what the response carried, not the total that matched. */
    @Test
    void a_collection_read_records_how_many_groups_the_page_returned() {
        service.create(CONNECTOR, new NewScimGroup("Engineering", List.of(), null));
        service.create(CONNECTOR, new NewScimGroup("Support", List.of(), null));
        audit.reset();

        ScimGroupListing listing = service.query(CONNECTOR, groupQuery(2, 5), BASE_URI);

        assertThat(listing.resources()).extracting(ScimGroupResource::displayName)
                .containsExactly("Support");
        assertThat(listing.totalResults()).isEqualTo(2);
        assertThat(audit.of(AuditOperation.SCIM_GROUP_LIST))
                .singleElement()
                .satisfies(event -> {
                    assertThat(event.actorId()).isEqualTo(CONNECTOR.connectorId());
                    assertThat(event.detail()).isEqualTo("1");
                });
    }

    private static final String BASE_URI = "https://scim.example/scim/v2";

    private static ScimQuery groupQuery(int startIndex, int count) {
        return new ScimQuery(
                Set.of(ScimResourceType.GROUP), null, null, new PageRequest(startIndex, count));
    }

    // ---- replace and patch --------------------------------------------------------------------

    /**
     * A no-op write reports no changed attributes. Compared rather than inferred from the verb,
     * because a trail that recorded every PUT as changing both attributes could not be used to find
     * the writes that mattered.
     */
    @Test
    void a_replacement_that_changes_nothing_records_no_changed_attributes() {
        ScimGroupResource created = service.create(
                CONNECTOR, new NewScimGroup("Engineering", List.of(alice.id()), null));
        audit.reset();

        ScimGroupResource replayed = service.replace(
                CONNECTOR, created.id(), current(created.id()), new ScimGroupReplacement("Engineering", List.of(alice.id()), null))
                .orElseThrow();

        assertThat(audit.of(AuditOperation.SCIM_GROUP_REPLACE)).singleElement()
                .satisfies(event -> assertThat(event.detail()).isEmpty());
        // And the VERSION did not move either — the two halves of "this write changed nothing"
        // have to agree, or a connector's idempotent re-send would invalidate its own cached copy.
        assertThat(replayed.version())
                .as("a write that changed nothing must not move the resource's validator")
                .isEqualTo(created.version());
        assertThat(users.require("alice").version())
                .as("nor any member's")
                .isEqualTo(alice.version() + 1);
    }

    @Test
    void a_patch_adds_a_member_without_restating_the_membership() {
        ScimGroupResource created = service.create(
                CONNECTOR, new NewScimGroup("Engineering", List.of(alice.id()), null));

        ScimGroupResource patched = service.patch(
                CONNECTOR,
                created.id(), current(created.id()),
                List.of(new ScimGroupPatchOperation.AddMembers(List.of(bob.id())))).orElseThrow();

        assertThat(patched.members()).extracting(ScimGroupMember::userId)
                .containsExactlyInAnyOrder(alice.id(), bob.id());
    }

    @Test
    void a_patch_removing_a_member_who_is_not_one_is_not_an_error() {
        ScimGroupResource created = service.create(
                CONNECTOR, new NewScimGroup("Engineering", List.of(alice.id()), null));

        ScimGroupResource patched = service.patch(
                CONNECTOR,
                created.id(), current(created.id()),
                List.of(new ScimGroupPatchOperation.RemoveMembers(List.of(bob.id())))).orElseThrow();

        assertThat(patched.members()).extracting(ScimGroupMember::userId).containsExactly(alice.id());
    }

    @Test
    void a_patch_can_clear_the_whole_membership() {
        ScimGroupResource created = service.create(
                CONNECTOR, new NewScimGroup("Engineering", List.of(alice.id(), bob.id()), null));

        ScimGroupResource patched = service.patch(
                CONNECTOR,
                created.id(), current(created.id()),
                List.of(new ScimGroupPatchOperation.RemoveAllMembers())).orElseThrow();

        assertThat(patched.members()).isEmpty();
    }

    /**
     * AC 1: partial-failure PATCH rolls back entirely. The operations are folded into one desired
     * state and written once, so a sequence whose later operation is refused leaves the earlier ones
     * unapplied — there was never an intermediate state to roll back.
     */
    @Test
    void a_patch_whose_later_operation_is_refused_applies_none_of_the_earlier_ones() {
        ScimGroupResource created = service.create(
                CONNECTOR, new NewScimGroup("Engineering", List.of(), null));

        assertThatThrownBy(() -> service.patch(CONNECTOR, created.id(), current(created.id()), List.of(
                        new ScimGroupPatchOperation.SetDisplayName("Platform"),
                        new ScimGroupPatchOperation.AddMembers(List.of(alice.id())),
                        new ScimGroupPatchOperation.AddMembers(List.of(UUID.randomUUID())))))
                .isInstanceOf(UnknownGroupMemberException.class);

        ScimGroupResource reread = service.findById(CONNECTOR, created.id()).orElseThrow();
        assertThat(reread.displayName()).isEqualTo("Engineering");
        assertThat(reread.members()).isEmpty();
        assertThat(reread.version()).isEqualTo(created.version());
    }

    @Test
    void a_write_to_a_group_that_does_not_exist_reports_absence_rather_than_throwing() {
        assertThat(service.replace(
                        CONNECTOR,
                        UUID.randomUUID(), current(UUID.randomUUID()),
                        new ScimGroupReplacement("Ghost", List.of(), null))).isEmpty();
    }

    /**
     * A write naming a member that does not exist is refused as an invalid value, on the REPLACE
     * operation rather than the create one, and records no write.
     *
     * <p>Separate from the create-time case above because they are separate call sites. Each once
     * carried its own audit call, and mutation testing removed this one with every test still
     * passing; the refusal is audited at the edge now, for every call site at once
     * ({@code ScimRefusalAuditTests}).
     */
    @Test
    void a_write_naming_an_unknown_member_is_refused_as_an_invalid_value_on_the_write() {
        ScimGroupResource created = service.create(
                CONNECTOR, new NewScimGroup("Engineering", List.of(), null));

        assertThatThrownBy(() -> service.replace(
                        CONNECTOR,
                        created.id(), current(created.id()),
                        new ScimGroupReplacement("Engineering", List.of(UUID.randomUUID()), null)))
                .isInstanceOf(UnknownGroupMemberException.class);

        assertThat(audit.of(AuditOperation.SCIM_GROUP_REPLACE)).as(REFUSAL_IS_THE_EDGES).isEmpty();
    }

    /** The same refusal reached through PATCH, which shares the one write path. */
    @Test
    void a_patch_naming_an_unknown_member_is_refused_as_an_invalid_value() {
        ScimGroupResource created = service.create(
                CONNECTOR, new NewScimGroup("Engineering", List.of(), null));

        assertThatThrownBy(() -> service.patch(CONNECTOR, created.id(), current(created.id()), List.of(
                        new ScimGroupPatchOperation.AddMembers(List.of(UUID.randomUUID())))))
                .isInstanceOf(UnknownGroupMemberException.class);

        assertThat(refusalsRecordedHere(AuditOperation.SCIM_GROUP_REPLACE)).isEmpty();
    }

    /**
     * A rename onto a displayName another live Group holds is refused as a uniqueness failure on
     * the write — the replace-path counterpart of the create-time uniqueness refusal.
     */
    @Test
    void a_rename_onto_a_held_display_name_is_refused_as_a_uniqueness_failure_on_the_write() {
        service.create(CONNECTOR, new NewScimGroup("Engineering", List.of(), null));
        ScimGroupResource platform = service.create(
                CONNECTOR, new NewScimGroup("Platform", List.of(), null));

        assertThatThrownBy(() -> service.replace(
                        CONNECTOR,
                        platform.id(), current(platform.id()),
                        new ScimGroupReplacement("ENGINEERING", List.of(), null)))
                .isInstanceOf(DuplicateDisplayNameException.class);

        assertThat(audit.of(AuditOperation.SCIM_GROUP_REPLACE)).as(REFUSAL_IS_THE_EDGES).isEmpty();
    }

    // ---- externalId: the shared cases are ScimAliasWriteContract's, over Engineering --------

    private ScimGroupResource aliased() {
        ScimGroupResource created = service.create(
                CONNECTOR, new NewScimGroup("Engineering", List.of(alice.id()), "eng-1"));
        aliases.put(OTHER_CONNECTOR.connectorId(), created.id(), THEIRS);
        audit.reset();
        return created;
    }

    @Override
    protected AuthenticatedConnector connector() {
        return CONNECTOR;
    }

    @Override
    protected Aliased aliasedResource() {
        ScimGroupResource created = aliased();
        return new Aliased(created.id(), created.externalId(), created.version());
    }

    @Override
    protected Aliased create(String externalId) {
        ScimGroupResource created =
                service.create(CONNECTOR, new NewScimGroup("Platform", List.of(), externalId));
        return new Aliased(created.id(), created.externalId(), created.version());
    }

    /** Restates Engineering with alice, as {@link #aliased()} created it. */
    @Override
    protected Answer put(UUID id, String externalId) {
        return answer(service.replace(CONNECTOR, id, current(id),
                new ScimGroupReplacement("Engineering", List.of(alice.id()), externalId))
                .orElseThrow());
    }

    @Override
    protected Answer setAlias(AuthenticatedConnector caller, UUID id, String externalId) {
        return answer(service.patch(caller, id, current(id),
                List.of(new ScimGroupPatchOperation.SetExternalId(externalId))).orElseThrow());
    }

    @Override
    protected Answer removeAlias(UUID id) {
        return answer(service.patch(CONNECTOR, id, current(id),
                List.of(new ScimGroupPatchOperation.RemoveExternalId())).orElseThrow());
    }

    @Override
    protected Answer changeAnotherAttribute(UUID id) {
        return answer(service.patch(CONNECTOR, id, current(id),
                List.of(new ScimGroupPatchOperation.SetDisplayName("Platform"))).orElseThrow());
    }

    @Override
    protected long storedVersion(UUID id) {
        return groups.findById(id).orElseThrow().version();
    }

    @Override
    protected String readBy(AuthenticatedConnector caller, UUID id) {
        return service.findById(caller, id).orElseThrow().externalId();
    }

    @Override
    protected List<String> recordedReplaceDetails() {
        return audit.of(AuditOperation.SCIM_GROUP_REPLACE).stream()
                .map(RecordingAuditTrail.Recorded::detail).toList();
    }

    private static Answer answer(ScimGroupResource written) {
        return new Answer(written.externalId(), written.version());
    }

    /**
     * A PUT re-keying the alias advances the Group's version alone: a member renders no alias of
     * its Groups, so its version stays.
     */
    @Test
    void an_alias_change_advances_no_members_version() {
        ScimGroupResource created = aliased();
        long aliceVersion = users.findById(alice.id()).orElseThrow().version();

        put(created.id(), "eng-2");

        assertThat(users.findById(alice.id()).orElseThrow().version()).isEqualTo(aliceVersion);
    }

    /**
     * An alias change alongside a column change advances the version ONCE: the replacement
     * already advanced it, so no second bump is added for the alias.
     */
    @Test
    void a_rename_and_an_alias_change_together_advance_the_version_once() {
        ScimGroupResource created = aliased();

        ScimGroupResource replaced = service.replace(CONNECTOR, created.id(),
                current(created.id()),
                new ScimGroupReplacement("Platform", List.of(alice.id()), "eng-2"))
                .orElseThrow();

        assertThat(replaced.version()).isEqualTo(created.version() + 1);
        assertThat(replaced.externalId()).isEqualTo("eng-2");
        assertThat(audit.of(AuditOperation.SCIM_GROUP_REPLACE)).singleElement()
                .extracting(RecordingAuditTrail.Recorded::detail)
                .isEqualTo("DISPLAY_NAME,EXTERNAL_ID");
    }

    /**
     * A Group deleted between an alias-only write and the version bump that follows it reports
     * absence — the same 404 a delete a moment earlier would have produced.
     */
    @Test
    void a_group_removed_before_an_alias_only_bump_reports_absence() {
        ScimGroupResource created = aliased();
        ScimVersionPrecondition precondition = current(created.id());
        groups.vanishBeforeNextAdvance(created.id());

        assertThat(service.patch(CONNECTOR, created.id(), precondition,
                List.of(new ScimGroupPatchOperation.SetExternalId("eng-2")))).isEmpty();
    }

    /**
     * The Admin group may not be renamed, but the calling connector's own alias for it is
     * writable: it is that connector's name for the Group, read by no other, and changing it
     * neither renames the recovery authority nor changes who holds it.
     */
    @Test
    void the_callers_alias_on_the_admin_group_is_writable() {
        ScimGroup adminGroup = seedAdminGroup();

        ScimGroupResource set = service.patch(CONNECTOR, adminGroup.id(),
                current(adminGroup.id()),
                List.of(new ScimGroupPatchOperation.SetExternalId("admins-1"))).orElseThrow();

        assertThat(set.externalId()).isEqualTo("admins-1");
        assertThat(set.displayName()).isEqualTo("Admins");
        assertThat(set.version()).isEqualTo(adminGroup.version() + 1);
        assertThat(aliases.find(CONNECTOR.connectorId(), adminGroup.id())).contains("admins-1");
    }

    // ---- removing Admin membership: the use case's refusals come before any revocation -------

    /** The Admin group with alice and bob added as ordinary members beside the Bootstrap Admin. */
    private ScimGroup adminGroupWithAliceAndBob() {
        ScimGroup adminGroup = seedAdminGroup();
        service.patch(CONNECTOR, adminGroup.id(), current(adminGroup.id()),
                List.of(new ScimGroupPatchOperation.AddMembers(List.of(alice.id(), bob.id()))));
        assertThat(revocations).as("adding authority revokes nothing").isEmpty();
        return groups.findById(adminGroup.id()).orElseThrow();
    }

    /** A stale or refused write is raised before anything is written, so it revokes nothing. */
    @Test
    void a_stale_or_refused_admin_write_revokes_nothing() {
        ScimGroup adminGroup = adminGroupWithAliceAndBob();
        ScimVersionPrecondition stale = ScimVersionPrecondition.ofIfMatch(
                List.of("\"" + (adminGroup.version() - 1) + "\""));

        assertThatThrownBy(() -> service.patch(CONNECTOR, adminGroup.id(), stale,
                List.of(new ScimGroupPatchOperation.RemoveMembers(List.of(alice.id())))))
                .isInstanceOf(PreconditionFailedException.class);
        assertThatThrownBy(() -> service.patch(CONNECTOR, adminGroup.id(), current(adminGroup.id()),
                List.of(new ScimGroupPatchOperation.RemoveMembers(List.of(alice.id())),
                        new ScimGroupPatchOperation.SetDisplayName("Not Admins"))))
                .isInstanceOf(ProtectedResourceException.class);

        assertThat(revocations).isEmpty();
        assertThat(groups.findById(adminGroup.id()).orElseThrow().hasMember(alice.id())).isTrue();
    }

    // ---- a membership of a Group that confers Permissions is a change of power -------------------

    /** What the Helpdesk Group carries, sorted by name. */
    private static final String HELPDESK_PERMISSIONS = "account:unlock,user:read";

    /**
     * What the Superuser holds beyond the baseline — every delegatable Permission, expanded in
     * code, since the Admin group carries none — sorted by name.
     */
    private static final String EVERY_PERMISSION = "account:unlock,audit:read,connector:read,"
            + "group:read,ops:read,user:read";

    /** The Group conferring Permissions, stored under the id its rows name, with these members. */
    private ScimGroup helpdeskWith(ScimUser... members) {
        return groups.create(ScimGroup.created(
                HELPDESK_GROUP_ID,
                "Helpdesk",
                java.util.Arrays.stream(members)
                        .map(member -> ScimGroupMember.reference(member.id()))
                        .toList(),
                ScimIdentities.NOW));
    }

    /** The revocation a removal from a conferring Group asks for, on behalf of this connector. */
    private Revocation membershipRevocation(ScimUser user) {
        return new Revocation(CONNECTOR.connectorId(), user.id(),
                Set.of(ScimUserSessions.Cause.MEMBERSHIP_REVOKED));
    }

    /**
     * One recorded change of power: the connector, the User, then as detail the Group, the User's
     * effective Permissions before and after, and the Superuser marker.
     */
    private static RecordingAuditTrail.Recorded permissionChange(AuditOperation operation,
            ScimUser user, UUID groupId, String before, String after, boolean superuser) {
        return new RecordingAuditTrail.Recorded(operation, CONNECTOR.connectorId(), user.id(),
                groupId + ":" + before + ">" + after + ":" + superuser);
    }

    /**
     * The use case hands the module the stored and the written membership, and whether the Group
     * confers Permissions, and carries out what comes back. The rules themselves — Groups
     * conferring nothing, unchanged memberships, order — are pinned in
     * {@code ScimWriteEffectsTests}.
     */
    @Test
    void a_patch_removing_a_member_of_a_conferring_group_revokes_its_sessions_and_its_permissions() {
        helpdeskWith(alice, bob);

        service.patch(CONNECTOR, HELPDESK_GROUP_ID, current(HELPDESK_GROUP_ID),
                List.of(new ScimGroupPatchOperation.RemoveMembers(List.of(alice.id()))));

        assertThat(revocations).containsExactly(membershipRevocation(alice));
        assertThat(audit.of(AuditOperation.PERMISSION_REVOKE)).containsExactly(permissionChange(
                AuditOperation.PERMISSION_REVOKE, alice, HELPDESK_GROUP_ID,
                HELPDESK_PERMISSIONS, "", false));
        assertThat(audit.of(AuditOperation.PERMISSION_GRANT)).isEmpty();
    }

    /**
     * One write moving a Group of power from one User to another records each User's own power:
     * the User leaving it held its Permissions and holds none, the User joining it the reverse.
     */
    @Test
    void a_write_moving_a_group_of_power_between_users_records_each_users_own_change() {
        helpdeskWith(alice);

        service.patch(CONNECTOR, HELPDESK_GROUP_ID, current(HELPDESK_GROUP_ID),
                List.of(new ScimGroupPatchOperation.ReplaceMembers(List.of(bob.id()))));

        assertThat(List.of(audit.of(AuditOperation.PERMISSION_REVOKE),
                        audit.of(AuditOperation.PERMISSION_GRANT)))
                .containsExactly(
                        List.of(permissionChange(AuditOperation.PERMISSION_REVOKE, alice,
                                HELPDESK_GROUP_ID, HELPDESK_PERMISSIONS, "", false)),
                        List.of(permissionChange(AuditOperation.PERMISSION_GRANT, bob,
                                HELPDESK_GROUP_ID, "", HELPDESK_PERMISSIONS, false)));
    }

    /**
     * The Admin group's membership is a change of power too: joining it grants the Superuser, and
     * leaving it removes it, which the marker says.
     */
    @Test
    void an_admin_group_membership_change_records_the_superuser_granted_and_removed() {
        ScimGroup adminGroup = adminGroupWithAliceAndBob();

        service.patch(CONNECTOR, adminGroup.id(), current(adminGroup.id()),
                List.of(new ScimGroupPatchOperation.RemoveMembers(List.of(alice.id()))));

        assertThat(audit.of(AuditOperation.PERMISSION_GRANT)).containsExactlyInAnyOrder(
                permissionChange(AuditOperation.PERMISSION_GRANT, alice, adminGroup.id(),
                        "", EVERY_PERMISSION, true),
                permissionChange(AuditOperation.PERMISSION_GRANT, bob, adminGroup.id(),
                        "", EVERY_PERMISSION, true));
        assertThat(audit.of(AuditOperation.PERMISSION_REVOKE)).containsExactly(
                permissionChange(AuditOperation.PERMISSION_REVOKE, alice, adminGroup.id(),
                        EVERY_PERMISSION, "", true));
    }

    /**
     * Before and after are the User's EFFECTIVE Permissions, from every Group it is a direct member
     * of: leaving one Group while another confers the same leaves them unchanged, and moves no
     * Superuser.
     */
    @Test
    void the_permissions_before_and_after_are_what_every_membership_confers() {
        ScimGroup adminGroup = adminGroupWithAliceAndBob();
        helpdeskWith(alice);
        audit.reset();

        service.patch(CONNECTOR, HELPDESK_GROUP_ID, current(HELPDESK_GROUP_ID),
                List.of(new ScimGroupPatchOperation.RemoveMembers(List.of(alice.id()))));

        assertThat(groups.findById(adminGroup.id()).orElseThrow().hasMember(alice.id())).isTrue();
        assertThat(audit.of(AuditOperation.PERMISSION_REVOKE)).containsExactly(permissionChange(
                AuditOperation.PERMISSION_REVOKE, alice, HELPDESK_GROUP_ID,
                EVERY_PERMISSION, EVERY_PERMISSION, false));
    }

    /** A Group that confers nothing moves nobody's Permissions: no event, no session ended. */
    @Test
    void a_membership_change_of_a_group_conferring_nothing_records_no_change_of_power() {
        ScimGroup plain = groups.create(ScimIdentities.group("Book club", alice));

        service.patch(CONNECTOR, plain.id(), current(plain.id()),
                List.of(new ScimGroupPatchOperation.ReplaceMembers(List.of(bob.id()))));

        assertThat(audit.of(AuditOperation.PERMISSION_GRANT)).isEmpty();
        assertThat(audit.of(AuditOperation.PERMISSION_REVOKE)).isEmpty();
        assertThat(revocations).isEmpty();
    }

    /** Each User added to the Admin group is a Superuser grant, and counted as one. */
    @Test
    void each_admin_group_grant_counts_one_superuser_grant() {
        adminGroupWithAliceAndBob();

        assertThat(superuserGrants).isEqualTo(2);
    }

    /** Joining a Group that confers Permissions but not the Superuser counts no Superuser grant. */
    @Test
    void a_grant_of_any_other_group_counts_no_superuser_grant() {
        helpdeskWith();

        service.patch(CONNECTOR, HELPDESK_GROUP_ID, current(HELPDESK_GROUP_ID),
                List.of(new ScimGroupPatchOperation.AddMembers(List.of(alice.id()))));

        assertThat(audit.of(AuditOperation.PERMISSION_GRANT)).hasSize(1);
        assertThat(superuserGrants).isZero();
    }

    /**
     * Unlike the Admin group, every other Group that confers Permissions can be renamed and
     * deleted — and deleting it takes them from every member, so each is revoked as a removal
     * would be.
     */
    @Test
    void a_conferring_group_other_than_the_admin_group_can_be_renamed_and_deleted() {
        helpdeskWith(alice, bob);

        ScimGroupResource renamed = service.patch(CONNECTOR, HELPDESK_GROUP_ID,
                current(HELPDESK_GROUP_ID),
                List.of(new ScimGroupPatchOperation.SetDisplayName("Service desk"))).orElseThrow();
        assertThat(renamed.displayName()).isEqualTo("Service desk");
        assertThat(revocations).as("a rename moves no membership").isEmpty();

        assertThat(service.delete(CONNECTOR, HELPDESK_GROUP_ID, current(HELPDESK_GROUP_ID)))
                .isTrue();

        assertThat(groups.findById(HELPDESK_GROUP_ID)).isEmpty();
        assertThat(revocations).containsExactlyInAnyOrder(membershipRevocation(alice), membershipRevocation(bob));
        assertThat(audit.of(AuditOperation.PERMISSION_REVOKE)).containsExactlyInAnyOrder(
                permissionChange(AuditOperation.PERMISSION_REVOKE, alice, HELPDESK_GROUP_ID,
                        HELPDESK_PERMISSIONS, "", false),
                permissionChange(AuditOperation.PERMISSION_REVOKE, bob, HELPDESK_GROUP_ID,
                        HELPDESK_PERMISSIONS, "", false));
    }

    /**
     * A conferring Group another transaction deleted between the read and this delete records no
     * change of power: that transaction removed the row, and with it the members' Permissions, so
     * it is the one that revoked and audited them. A second set here would double every
     * {@code PERMISSION_REVOKE} in the trail and end sessions for a delete that removed nothing.
     */
    @Test
    void a_conferring_group_removed_between_the_read_and_the_delete_records_no_change_of_power() {
        helpdeskWith(alice, bob);
        groups.vanishBeforeNextDelete(HELPDESK_GROUP_ID);

        assertThat(service.delete(CONNECTOR, HELPDESK_GROUP_ID, current(HELPDESK_GROUP_ID)))
                .isFalse();

        assertThat(revocations).isEmpty();
        assertThat(audit.of(AuditOperation.PERMISSION_REVOKE)).isEmpty();
    }

    /**
     * Each change of power is one {@code INFO} record classified {@code user-administration} /
     * {@code change} / {@code success}, naming the User, the Group by id and the Permissions the
     * Group confers — and never a {@code userName} or the Group's {@code displayName}.
     */
    @Test
    void each_change_of_power_is_logged_at_info_with_the_user_the_group_and_its_permissions() {
        helpdeskWith(alice);

        try (EcsLogCapture ecs = EcsLogCapture.attach(new StandardEnvironment())) {
            service.patch(CONNECTOR, HELPDESK_GROUP_ID, current(HELPDESK_GROUP_ID), List.of(
                    new ScimGroupPatchOperation.ReplaceMembers(List.of(bob.id()))));

            List<JsonNode> changes = ecs.records().stream()
                    .filter(record -> record.at("/app/event/action").asText()
                            .startsWith("identity.permission_"))
                    .toList();
            assertThat(changes).extracting(record -> record.at("/app/event/action").asText())
                    .containsExactly("identity.permission_revoke", "identity.permission_grant");
            assertThat(changes).extracting(record -> record.at("/user/target/id").asText())
                    .containsExactly(alice.id().toString(), bob.id().toString());
            assertThat(changes).allSatisfy(record -> {
                assertThat(record.at("/log/level").asText()).isEqualTo("INFO");
                assertThat(record.at("/event/action").asText()).isEqualTo("user-administration");
                assertThat(record.at("/event/type").get(0).asText()).isEqualTo("change");
                assertThat(record.at("/event/type")).hasSize(1);
                assertThat(record.at("/event/outcome").asText()).isEqualTo("success");
                assertThat(record.at("/group/id").asText()).isEqualTo(HELPDESK_GROUP_ID.toString());
                assertThat(record.at("/app/authorization/group_permissions").asText())
                        .isEqualTo(HELPDESK_PERMISSIONS);
                assertThat(record.toString()).doesNotContain("alice", "bob");
            });
        }
    }

    // ---- stored-length limits ---------------------------------------------------------------

    /**
     * A write carrying one value its column cannot hold is refused whole, as an invalid value
     * naming the attribute — not as the uniqueness conflict the database's refusal was once
     * mistaken for — and the valid rename beside it is not written either. Each limit is
     * {@code ScimAttributeLimitsTests}'; that the column holds the value at its limit, and that
     * every verb is refused, is {@code ScimStoredLengthIntegrationTests}'.
     */
    @Test
    void a_write_with_one_value_too_long_for_its_column_writes_nothing() {
        ScimGroupResource engineering = aliased();
        ScimGroup before = groups.findById(engineering.id()).orElseThrow();

        assertThatThrownBy(() -> service.patch(CONNECTOR, engineering.id(),
                        current(engineering.id()), List.of(
                                new ScimGroupPatchOperation.SetDisplayName("Platform"),
                                new ScimGroupPatchOperation.SetExternalId("x".repeat(257)))))
                .isInstanceOfSatisfying(ScimValueTooLongException.class,
                        tooLong -> assertThat(tooLong.attribute()).isEqualTo("externalId"));

        assertThat(groups.inQueryOrder()).containsExactly(before);
        assertThat(aliases.find(CONNECTOR.connectorId(), engineering.id())).contains("eng-1");
        assertThat(audit.events()).as(REFUSAL_IS_THE_EDGES).isEmpty();
    }

    // ---- delete -------------------------------------------------------------------------------

    @Test
    void deleting_a_group_advances_every_former_members_version_and_is_audited() {
        ScimGroupResource created = service.create(
                CONNECTOR, new NewScimGroup("Engineering", List.of(alice.id()), null));
        long aliceBefore = users.require("alice").version();

        assertThat(service.delete(CONNECTOR, created.id(), current(created.id()))).isTrue();

        assertThat(users.require("alice").version()).isEqualTo(aliceBefore + 1);
        assertThat(audit.of(AuditOperation.SCIM_GROUP_DELETE)).singleElement()
                .satisfies(event -> {
                    assertThat(event.actorId()).isEqualTo(CONNECTOR.connectorId());
                    assertThat(event.subjectId()).isEqualTo(created.id());
                });
        assertThat(tombstones.recorded()).containsExactly(
                new InMemoryScimTombstoneRepository.Tombstone(
                        ScimResourceType.GROUP, created.id(), ScimIdentities.NOW));
    }

    /** A Group carrying no Permissions confers nothing, so deleting it is no change of power. */
    @Test
    void deleting_a_group_carrying_nothing_ends_no_members_session() {
        ScimGroupResource created = service.create(
                CONNECTOR, new NewScimGroup("Engineering", List.of(alice.id(), bob.id()), null));

        assertThat(service.delete(CONNECTOR, created.id(), current(created.id()))).isTrue();

        assertThat(revocations).isEmpty();
    }

    @Test
    void deleting_a_group_that_is_not_there_reports_absence() {
        assertThat(service.delete(CONNECTOR, UUID.randomUUID(), current(UUID.randomUUID()))).isFalse();
    }

    /**
     * A Group removed between the read and the delete reports absence, not success.
     *
     * <p>The delete reads the Group first — it has to, because a protected one must be refused
     * before anything is removed — which opens a window in which another transaction can delete
     * the row. So the method must report what the repository actually did rather than assume the
     * read result still holds; mutation testing replaced that returned value with a constant
     * {@code true} and no test noticed, because in every other case the row was still there.
     *
     * <p>It matters at the edge: the controller turns {@code false} into a {@code 404}, so a
     * hard-coded success would answer {@code 204} to a connector whose delete removed nothing,
     * and a connector converging on a desired state would record the Group as gone.
     */
    @Test
    void a_group_removed_between_the_read_and_the_delete_reports_absence() {
        ScimGroupResource created = service.create(
                CONNECTOR, new NewScimGroup("Engineering", List.of(alice.id()), null));
        groups.vanishBeforeNextDelete(created.id());

        assertThat(service.delete(CONNECTOR, created.id(), current(created.id())))
                .as("the delete must report what the repository did, not what the read found")
                .isFalse();
        assertThat(tombstones.recorded())
                .as("the transaction that removed the row wrote its tombstone; a second collides")
                .isEmpty();
    }

    // ---- the reserved resources ---------------------------------------------------------------

    /** AC 4: the Admin group cannot be renamed, and the refusal changes nothing. */
    @Test
    void renaming_the_admin_group_is_refused_and_leaves_it_unchanged_on_re_read() {
        ScimGroup adminGroup = seedAdminGroup();

        assertThatThrownBy(() -> service.replace(
                        CONNECTOR,
                        adminGroup.id(), current(adminGroup.id()),
                        new ScimGroupReplacement("Not Admins", List.of(bootstrapAdmin().id()), null)))
                .isInstanceOf(ProtectedResourceException.class)
                .satisfies(refusal -> assertThat(
                                ((ProtectedResourceException) refusal).reservedName())
                        .isEqualTo(ReservedResourceName.ADMIN_GROUP));

        ScimGroupResource reread = service.findById(CONNECTOR, adminGroup.id()).orElseThrow();
        assertThat(reread.displayName()).isEqualTo("Admins");
        assertThat(reread.version()).isEqualTo(adminGroup.version());
    }

    /** AC 4: the Admin group cannot be deleted. */
    @Test
    void deleting_the_admin_group_is_refused_and_it_is_still_there() {
        ScimGroup adminGroup = seedAdminGroup();

        assertThatThrownBy(() -> service.delete(CONNECTOR, adminGroup.id(), current(adminGroup.id())))
                .isInstanceOfSatisfying(ProtectedResourceException.class, refusal ->
                        assertThat(refusal.reservedName())
                                .as("the refusal names the reserved resource it protects")
                                .isEqualTo(ReservedResourceName.ADMIN_GROUP));
        assertThat(tombstones.recorded()).isEmpty();

        assertThat(service.findById(CONNECTOR, adminGroup.id())).isPresent();
    }

    /** AC 4: the Bootstrap Admin's membership of the Admin group cannot be removed. */
    @Test
    void removing_the_bootstrap_admins_membership_is_refused_and_it_is_still_a_member() {
        ScimGroup adminGroup = seedAdminGroup();
        ScimUser recovery = bootstrapAdmin();

        assertThatThrownBy(() -> service.patch(
                        CONNECTOR,
                        adminGroup.id(), current(adminGroup.id()),
                        List.of(new ScimGroupPatchOperation.RemoveMembers(List.of(recovery.id())))))
                .isInstanceOf(ProtectedResourceException.class)
                .satisfies(refusal -> assertThat(
                                ((ProtectedResourceException) refusal).reservedName())
                        .isEqualTo(ReservedResourceName.BOOTSTRAP_ADMIN));

        assertThat(service.findById(CONNECTOR, adminGroup.id()).orElseThrow().members())
                .extracting(ScimGroupMember::userId)
                .contains(recovery.id());
    }

    /**
     * The refusals are evaluated against the RESULT of the whole sequence, not each step: the rule
     * protects the User's state, not the shape of the request.
     */
    @Test
    void a_patch_that_removes_and_re_adds_the_bootstrap_admin_is_allowed() {
        ScimGroup adminGroup = seedAdminGroup();
        ScimUser recovery = bootstrapAdmin();

        ScimGroupResource patched = service.patch(CONNECTOR, adminGroup.id(), current(adminGroup.id()), List.of(
                new ScimGroupPatchOperation.RemoveMembers(List.of(recovery.id())),
                new ScimGroupPatchOperation.AddMembers(List.of(recovery.id())))).orElseThrow();

        assertThat(patched.members()).extracting(ScimGroupMember::userId).contains(recovery.id());
    }

    /**
     * The Bootstrap Admin's membership is frozen in BOTH directions: it cannot be added to an
     * ordinary Group either, because a membership change moves its computed {@code groups} attribute
     * and its version — which the glossary's "no SCIM operation may mutate the User" forbids.
     */
    @Test
    void adding_the_bootstrap_admin_to_an_ordinary_group_is_refused_at_create() {
        seedAdminGroup();
        ScimUser recovery = bootstrapAdmin();

        assertThatThrownBy(() -> service.create(
                        CONNECTOR, new NewScimGroup("Engineering", List.of(recovery.id()), null)))
                .isInstanceOf(ProtectedResourceException.class);

        // The refusal must be AUDITED, not merely thrown — once a test asserted the exception
        // type alone and passed while the create-time refusal recorded nothing at all. It is
        // audited at the edge now, as a refused CREATE with no resource id: ScimRefusalAuditTests
        // holds that a protected-resource refusal is mutability and that POST /Groups records it
        // as ScimGroupCreateRejected. The use case records nothing.
        assertThat(refusalsRecordedHere(AuditOperation.SCIM_GROUP_CREATE)).isEmpty();
    }

    @Test
    void adding_the_bootstrap_admin_to_an_existing_ordinary_group_is_refused() {
        seedAdminGroup();
        ScimUser recovery = bootstrapAdmin();
        ScimGroupResource engineering = service.create(
                CONNECTOR, new NewScimGroup("Engineering", List.of(), null));

        assertThatThrownBy(() -> service.patch(
                        CONNECTOR,
                        engineering.id(), current(engineering.id()),
                        List.of(new ScimGroupPatchOperation.AddMembers(List.of(recovery.id())))))
                .isInstanceOf(ProtectedResourceException.class);
    }

    /**
     * AC 7: every protected-resource refusal produces its own audit event — at the edge, which
     * {@code ScimRefusalAuditTests} holds; the use case deletes nothing and records no deletion.
     */
    @Test
    void a_protected_resource_refusal_deletes_nothing_and_records_no_deletion() {
        ScimGroup adminGroup = seedAdminGroup();
        audit.reset();

        assertThatThrownBy(() -> service.delete(CONNECTOR, adminGroup.id(), current(adminGroup.id())))
                .isInstanceOf(ProtectedResourceException.class);

        assertThat(groups.findById(adminGroup.id())).isPresent();
        assertThat(audit.events()).as(REFUSAL_IS_THE_EDGES).isEmpty();
    }

    /** Ordinary membership of the Admin group stays writable — that is what provisioning is for. */
    @Test
    void an_ordinary_user_can_be_added_to_and_removed_from_the_admin_group() {
        ScimGroup adminGroup = seedAdminGroup();
        ScimUser recovery = bootstrapAdmin();

        ScimGroupResource granted = service.patch(
                CONNECTOR,
                adminGroup.id(), current(adminGroup.id()),
                List.of(new ScimGroupPatchOperation.AddMembers(List.of(alice.id())))).orElseThrow();
        assertThat(granted.members()).extracting(ScimGroupMember::userId)
                .containsExactlyInAnyOrder(recovery.id(), alice.id());

        ScimGroupResource revoked = service.patch(
                CONNECTOR,
                adminGroup.id(), current(adminGroup.id()),
                List.of(new ScimGroupPatchOperation.RemoveMembers(List.of(alice.id()))))
                .orElseThrow();
        assertThat(revoked.members()).extracting(ScimGroupMember::userId)
                .containsExactly(recovery.id());
    }

    /**
     * Before seeding there is no Bootstrap Admin, so there is no User whose membership could be
     * frozen and every Group write is unaffected. This happens once per deployment, between the
     * first migration and the first startup's seeding.
     */
    @Test
    void with_no_bootstrap_admin_seeded_no_membership_is_frozen() {
        assertThat(users.findByReservedName(ReservedResourceName.BOOTSTRAP_ADMIN)).isEmpty();

        assertThat(service.create(
                        CONNECTOR, new NewScimGroup("Engineering", List.of(alice.id()), null))
                .members())
                .hasSize(1);
    }

    // ---- timestamps, races, preconditions and query audit --------------------------------------

    private static final Duration LATER = Duration.ofMinutes(5);

    /** A create stamps both timestamps with the service clock's time, not some other one. */
    @Test
    void a_created_group_is_stamped_with_the_time_of_the_create() {
        clock.advanceBy(LATER);

        ScimGroupResource created = service.create(
                CONNECTOR, new NewScimGroup("Engineering", List.of(), null));

        assertThat(created.createdAt()).isEqualTo(ScimIdentities.NOW.plus(LATER));
        assertThat(created.lastModifiedAt()).isEqualTo(ScimIdentities.NOW.plus(LATER));
    }

    /**
     * A PUT and a PATCH each move {@code lastModified} to the time of the write and leave
     * {@code created} alone — {@code meta.lastModified} is what a connector's incremental sync
     * compares, so a write that kept the old one would never be picked up.
     */
    @Test
    void a_put_and_a_patch_stamp_last_modified_with_the_time_of_the_write() {
        ScimGroupResource created = service.create(
                CONNECTOR, new NewScimGroup("Engineering", List.of(), null));
        clock.advanceBy(LATER);

        ScimGroupResource replaced = service.replace(CONNECTOR, created.id(), current(created.id()),
                new ScimGroupReplacement("Platform", List.of(), null)).orElseThrow();
        assertThat(replaced.createdAt()).isEqualTo(ScimIdentities.NOW);
        assertThat(replaced.lastModifiedAt()).isEqualTo(ScimIdentities.NOW.plus(LATER));

        clock.advanceBy(LATER);
        ScimGroupResource patched = service.patch(CONNECTOR, created.id(), current(created.id()),
                List.of(new ScimGroupPatchOperation.SetDisplayName("Infra"))).orElseThrow();
        assertThat(patched.createdAt()).isEqualTo(ScimIdentities.NOW);
        assertThat(patched.lastModifiedAt()).isEqualTo(ScimIdentities.NOW.plus(LATER).plus(LATER));
    }

    /** An alias-only write moves no column, so the version bump is what stamps its time. */
    @Test
    void an_alias_only_write_stamps_last_modified_with_the_time_of_the_write() {
        ScimGroupResource created = aliased();
        clock.advanceBy(LATER);

        ScimGroupResource patched = service.patch(CONNECTOR, created.id(), current(created.id()),
                List.of(new ScimGroupPatchOperation.SetExternalId("eng-2"))).orElseThrow();

        assertThat(patched.version()).isEqualTo(created.version() + 1);
        assertThat(patched.lastModifiedAt()).isEqualTo(ScimIdentities.NOW.plus(LATER));
    }

    /**
     * A Group deleted between the write's locked read and its replacement reports absence — the
     * 404 a moment earlier would have produced — and records no write and no alias for it.
     */
    @Test
    void a_group_removed_between_the_read_and_the_write_reports_absence() {
        ScimGroupResource created = service.create(
                CONNECTOR, new NewScimGroup("Engineering", List.of(alice.id()), null));
        ScimVersionPrecondition precondition = current(created.id());
        audit.reset();
        groups.vanishBeforeNextReplace(created.id());

        assertThat(service.replace(CONNECTOR, created.id(), precondition,
                new ScimGroupReplacement("Platform", List.of(), "eng-1"))).isEmpty();

        assertThat(audit.recorded()).isEmpty();
        assertThat(aliases.find(CONNECTOR.connectorId(), created.id())).isEmpty();
        assertThat(revocations).isEmpty();
    }

    /**
     * A PATCH carrying a null operation is a programming error upstream, not a request: it is
     * refused as one before anything is written, rather than falling through as an unmatched
     * case.
     */
    @Test
    void a_patch_with_a_null_operation_is_refused_and_writes_nothing() {
        ScimGroupResource created = service.create(
                CONNECTOR, new NewScimGroup("Engineering", List.of(), null));

        assertThatThrownBy(() -> service.patch(CONNECTOR, created.id(), current(created.id()),
                        Arrays.asList(new ScimGroupPatchOperation.SetDisplayName("Platform"), null)))
                .isExactlyInstanceOf(NullPointerException.class);

        ScimGroupResource reread = service.findById(CONNECTOR, created.id()).orElseThrow();
        assertThat(reread.displayName()).isEqualTo("Engineering");
        assertThat(reread.version()).isEqualTo(created.version());
    }

    /**
     * A delete honours {@code If-Match} as a write does: a stale version is refused before
     * anything is removed, so two connectors racing on one Group cannot delete the one the other
     * just changed.
     */
    @Test
    void a_delete_with_a_stale_precondition_is_refused_and_the_group_is_still_there() {
        ScimGroupResource created = service.create(
                CONNECTOR, new NewScimGroup("Engineering", List.of(alice.id()), null));
        ScimVersionPrecondition stale = current(created.id());
        service.patch(CONNECTOR, created.id(), current(created.id()),
                List.of(new ScimGroupPatchOperation.SetDisplayName("Platform")));
        audit.reset();

        assertThatThrownBy(() -> service.delete(CONNECTOR, created.id(), stale))
                .isInstanceOf(PreconditionFailedException.class);

        assertThat(service.findById(CONNECTOR, created.id())).isPresent();
        assertThat(tombstones.recorded()).isEmpty();
        assertThat(audit.of(AuditOperation.SCIM_GROUP_DELETE)).isEmpty();
    }

    /**
     * A query is evaluated as the calling connector — whose aliases are the {@code externalId}
     * values it sees — and audited with the filter's shape, and the listing carries the page that
     * was asked for, which renders {@code startIndex} and {@code itemsPerPage}.
     */
    @Test
    void a_query_runs_as_the_connector_and_records_the_filters_shape_and_the_page() {
        PageRequest page = new PageRequest(3, 7);
        ScimQuery query = ScimQuery.of(
                Set.of(ScimResourceType.GROUP), "displayName eq \"Engineering\"", null, null, page);
        stubbedQueryResult = new ScimQuery.Result(0, List.of());

        ScimGroupListing listing = service.query(CONNECTOR, query, BASE_URI);

        assertThat(queryCalls).containsExactly(new QueryCall(query, CONNECTOR.connectorId()));
        assertThat(listing.page()).isEqualTo(page);
        assertThat(audit.of(AuditOperation.SCIM_GROUP_LIST)).singleElement()
                .extracting(RecordingAuditTrail.Recorded::detail)
                .isEqualTo("0 displayName eq ?");
    }

    // ---- fixtures -----------------------------------------------------------------------------

    /**
     * Seeds the recovery path through the PORTS, not through a fixture that fabricates a reserved
     * resource: production has no such path, and a test that used one would prove the wrong thing.
     */
    private ScimGroup seedAdminGroup() {
        ScimUser recovery = users.createReserved(
                ScimIdentities.user("bootstrap"), ReservedResourceName.BOOTSTRAP_ADMIN);
        return groups.createReserved(
                ScimGroup.created(
                        TestGroupPermissions.SUPERUSER_GROUP_ID,
                        "Admins",
                        List.of(ScimGroupMember.reference(recovery.id())),
                        ScimIdentities.NOW),
                ReservedResourceName.ADMIN_GROUP);
    }

    private ScimUser bootstrapAdmin() {
        return users.findByReservedName(ReservedResourceName.BOOTSTRAP_ADMIN).orElseThrow();
    }

    /**
     * The detail of every refused event of one operation — the closed-set reason it recorded.
     *
     * <p>Each refusal must also name the connector that made it: an operator tracing a connector
     * that never converged filters the trail by actor, so a refusal recorded without one is a
     * refusal that connector's investigation never finds. Checked here, once, so every refusal
     * path a test drives is held to it.
     */
    /**
     * The refusals of this operation the use case recorded itself — none, ever: a refusal is
     * audited by what it is, once, at the edge of the request ({@code ScimRefusalAuditTests}).
     */
    private List<RecordingAuditTrail.Recorded> refusalsRecordedHere(AuditOperation operation) {
        return audit.of(operation).stream().filter(recorded -> recorded.detail() != null).toList();
    }
}

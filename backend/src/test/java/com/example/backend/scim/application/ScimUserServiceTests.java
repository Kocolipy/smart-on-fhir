package com.example.backend.scim.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.backend.TokenPermissions;
import com.example.backend.audit.RecordingAuditTrail;
import com.example.backend.audit.domain.AuditOperation;
import com.example.backend.auth.LoginStateModules;
import com.example.backend.authorization.domain.Permission;
import com.example.backend.observability.EcsLogCapture;
import com.example.backend.scim.InMemoryGroupPermissions;
import com.example.backend.scim.InMemoryScimGroupRepository;
import com.example.backend.scim.InMemoryScimPasswordHistoryRepository;
import com.example.backend.scim.InMemoryScimQueryRepository;
import com.example.backend.scim.InMemoryScimTombstoneRepository;
import com.example.backend.scim.InMemoryScimUserRepository;
import com.example.backend.scim.InMemoryScimWriteLocks;
import com.example.backend.scim.ScimIdentities;
import com.example.backend.scim.config.ScimPasswordAcceptanceConfig;
import com.example.backend.scim.domain.AuthenticatedConnector;
import com.example.backend.scim.domain.DuplicateUserNameException;
import com.example.backend.scim.domain.PasswordAcceptance;
import com.example.backend.scim.domain.PasswordPolicy;
import com.example.backend.scim.domain.PasswordPolicyRefusedException;
import com.example.backend.scim.domain.PasswordReusedException;
import com.example.backend.scim.domain.PreconditionFailedException;
import com.example.backend.scim.domain.ProtectedResourceException;
import com.example.backend.scim.domain.ReservedResourceName;
import com.example.backend.scim.domain.ScimEmail;
import com.example.backend.scim.domain.ScimEmailFilter;
import com.example.backend.scim.domain.ScimEmailPart;
import com.example.backend.scim.domain.ScimGroup;
import com.example.backend.scim.domain.ScimLoginState;
import com.example.backend.scim.domain.ScimName;
import com.example.backend.scim.domain.ScimPatchRefusedException;
import com.example.backend.scim.domain.ScimUser;
import com.example.backend.scim.domain.ScimUserPatchOperation;
import com.example.backend.scim.domain.ScimUserPatchOperation.RemoveEmails;
import com.example.backend.scim.domain.ScimUserPatchOperation.RemovePassword;
import com.example.backend.scim.domain.ScimUserPatchOperation.RemoveText;
import com.example.backend.scim.domain.ScimUserPatchOperation.SetActive;
import com.example.backend.scim.domain.ScimUserPatchOperation.SetPassword;
import com.example.backend.scim.domain.ScimUserPatchOperation.SetText;
import com.example.backend.scim.domain.ScimUserPatchOperation.TextAttribute;
import com.example.backend.scim.domain.ScimUserProfile;
import com.example.backend.scim.domain.ScimUserSessions;
import com.example.backend.scim.domain.ScimUserSessions.Cause;
import com.example.backend.scim.domain.ScimValueTooLongException;
import com.example.backend.scim.domain.ScimVersionPrecondition;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * The User write use cases over in-memory ports: replacement and partial-update semantics, the
 * order of the refusals, the password history, which changes end sessions, and what each write
 * records. The same behaviour against SQL Server and Redis is in
 * {@code ScimConditionalWriteIntegrationTests}.
 */
class ScimUserServiceTests extends ScimAliasWriteContract {

    private static final AuthenticatedConnector CONNECTOR = new AuthenticatedConnector(
            UUID.randomUUID(), UUID.randomUUID(), TokenPermissions.of(TokenPermissions.ALL));

    private static final Instant LATER = ScimIdentities.NOW.plusSeconds(60);

    /** Revocations requested of the session port, in order. */
    private record Revocation(UUID connectorId, UUID userId, Set<Cause> causes) {
    }

    /**
     * A salted-looking fake: each encode yields a distinct string, as Argon2id's random salt does,
     * so "the credential changed" cannot be faked by an equal hash, and matching compares the
     * plaintext part only. Counts matches, so a test can see the history was actually consulted.
     */
    private static final class FakeSaltedEncoder implements PasswordEncoder {

        private int salt;
        private int matches;

        @Override
        public String encode(CharSequence raw) {
            return "{fake}" + (salt++) + ":" + raw;
        }

        @Override
        public boolean matches(CharSequence raw, String encoded) {
            matches++;
            return encoded.substring(encoded.indexOf(':') + 1).equals(raw.toString());
        }
    }

    private final InMemoryScimUserRepository users = new InMemoryScimUserRepository();

    private final InMemoryScimGroupRepository groups = new InMemoryScimGroupRepository(users);

    private final InMemoryScimPasswordHistoryRepository history =
            new InMemoryScimPasswordHistoryRepository();

    private final List<Revocation> revocations = new ArrayList<>();

    private final ScimUserSessions sessions = (connectorId, userId, causes) ->
            revocations.add(new Revocation(connectorId, userId, Set.copyOf(causes)));

    /**
     * Why a refused write leaves this trail empty: a refusal is audited by what it is, once, at the
     * edge of the request ({@code ScimRefusalAuditTests}), so the use case records neither the
     * refusal nor any write for it.
     */
    private static final String REFUSAL_IS_THE_EDGES =
            "a refusal is recorded at the edge, not by the use case";

    private final RecordingAuditTrail audit = new RecordingAuditTrail();

    private final FakeSaltedEncoder encoder = new FakeSaltedEncoder();

    private final Clock clock = Clock.fixed(LATER, ZoneOffset.UTC);

    private final InMemoryScimTombstoneRepository tombstones =
            new InMemoryScimTombstoneRepository();

    private final InMemoryScimWriteLocks writeLocks = new InMemoryScimWriteLocks();

    /** The Groups' stored Permissions over this directory, the Admin group by its marker. */
    private final InMemoryGroupPermissions groupPermissions = InMemoryGroupPermissions.over(groups);

    private final ScimUserService service = new ScimUserService(
            users, writeLocks, groups, aliases,
            new PasswordAcceptance(history, ScimPasswordAcceptanceConfig.hasher(encoder)),
            sessions, LoginStateModules.over(users, clock), tombstones, audit, clock,
            new InMemoryScimQueryRepository(users, groups), groupPermissions,
            () -> { });

    private ScimUserResource ada;

    private int writesAtSetUp;

    @BeforeEach
    void createAda() {
        ada = service.create(CONNECTOR, new NewScimUser(
                new ScimUserProfile("ada", new ScimName(null, "King", "Ada", null, null, null),
                        "Ada", "en", "en-GB", "Europe/London", true,
                        List.of(new ScimEmail("ada@work.example", "work", true))),
                "first-password-1",
                "ext-ada"));
        audit.reset();
        writesAtSetUp = users.writes();
    }

    /** Writes the store took after the fixture's own create. */
    private int writesSinceSetUp() {
        return users.writes() - writesAtSetUp;
    }

    private ScimVersionPrecondition current() {
        return ScimVersionPrecondition.ofIfMatch(
                List.of("\"" + users.findById(ada.id()).orElseThrow().version() + "\""));
    }

    private ScimUser stored() {
        return users.findById(ada.id()).orElseThrow();
    }

    /** A PUT; the third argument is the externalId the body carried, null when it omitted it. */
    private ScimUserResource put(ScimUserProfile profile, String password, String externalId) {
        return service.replace(CONNECTOR, ada.id(), current(),
                new ScimUserReplacement(profile, password, externalId, true)).orElseThrow();
    }

    private ScimUserResource patch(ScimUserPatchOperation... operations) {
        return service.patch(CONNECTOR, ada.id(), current(), List.of(operations)).orElseThrow();
    }

    private static ScimUserProfile minimal(String userName, boolean active) {
        return ScimIdentities.profile(userName, active);
    }

    // ---- the locks a write asks for -------------------------------------------------------

    /**
     * Each write asks the lock module for the User's write locks — the User and its Groups — once,
     * before it reads: a PUT, a PATCH and a DELETE alike. A create has nothing yet to lock.
     */
    @Test
    void every_user_write_asks_for_the_users_write_locks_once() {
        put(minimal("ada", true), null, "ext-ada");
        patch(new ScimUserPatchOperation.SetExternalId("ext-ada-2"));
        service.delete(CONNECTOR, ada.id(), current());

        assertThat(writeLocks.requests())
                .containsExactly(Set.of(ada.id()), Set.of(ada.id()), Set.of(ada.id()));
    }

    // ---- creation starts the history ------------------------------------------------------

    @Test
    void a_created_password_starts_the_history_so_it_cannot_be_set_again() {
        assertThat(history.findRecentHashes(ada.id())).containsExactly(stored().login().passwordHash());
    }

    /**
     * The create is recorded against the connector and the new User, and returns the User as the
     * connector sees it: its alias, and no Group — a create cannot put a User in one.
     */
    @Test
    void a_create_is_recorded_and_returns_the_connectors_alias_and_no_group() {
        ScimUserResource grace = service.create(
                CONNECTOR, new NewScimUser(minimal("grace", true), "a-valid-new-password", "ext-grace"));

        assertThat(grace.externalId()).isEqualTo("ext-grace");
        assertThat(grace.groups()).isEmpty();
        assertThat(audit.of(AuditOperation.SCIM_USER_CREATE)).singleElement()
                .satisfies(event -> {
                    assertThat(event.actorId()).isEqualTo(CONNECTOR.connectorId());
                    assertThat(event.subjectId()).isEqualTo(grace.id());
                    assertThat(event.detail()).isNull();
                });
    }

    /**
     * A taken userName fails the create at the store, after the password was accepted: nothing is
     * recorded here — the refusal is the edge's to audit — and no history is remembered for anyone.
     */
    @Test
    void a_create_with_a_taken_user_name_is_refused_and_remembers_no_password() {
        List<String> adaHistory = history.findRecentHashes(ada.id());

        assertThatThrownBy(() -> service.create(
                CONNECTOR, new NewScimUser(minimal("ADA", true), "a-valid-new-password", null)))
                .isInstanceOf(DuplicateUserNameException.class);

        assertThat(writesSinceSetUp()).isZero();
        assertThat(history.findRecentHashes(ada.id())).isEqualTo(adaHistory);
        assertThat(audit.events()).as(REFUSAL_IS_THE_EDGES).isEmpty();
    }

    @Test
    void a_credentialless_create_records_no_history() {
        ScimUserResource grace = service.create(
                CONNECTOR, new NewScimUser(minimal("grace", true), null, null));

        assertThat(history.findRecentHashes(grace.id())).isEmpty();
    }

    /** A User provisioned without a credential can be given its first one later, on either verb. */
    @Test
    void a_credentialless_user_is_given_its_first_password_later_and_it_starts_the_history() {
        UUID grace = service.create(
                CONNECTOR, new NewScimUser(minimal("grace", true), null, null)).id();
        UUID hopper = service.create(
                CONNECTOR, new NewScimUser(minimal("hopper", true), null, null)).id();

        service.replace(CONNECTOR, grace, versionOf(grace),
                new ScimUserReplacement(minimal("grace", true), "a-first-put-password", null, true));
        service.patch(CONNECTOR, hopper, versionOf(hopper),
                List.of(new SetPassword("a-first-patch-password")));

        assertThat(users.findById(grace).orElseThrow().login().passwordHash())
                .endsWith(":a-first-put-password");
        assertThat(history.findRecentHashes(grace))
                .containsExactly(users.findById(grace).orElseThrow().login().passwordHash());
        assertThat(history.findRecentHashes(hopper))
                .containsExactly(users.findById(hopper).orElseThrow().login().passwordHash());
    }

    /**
     * Only a write that sets a password remembers one. Omitting it keeps the credential, removing
     * it clears the credential but not the history — so a removed password is still refused when
     * set again — and neither adds an entry.
     */
    @Test
    void omitting_keeping_or_removing_the_password_adds_no_history_entry() {
        List<String> before = history.findRecentHashes(ada.id());

        put(minimal("ada", true), null, "ext-ada");
        patch(new SetText(TextAttribute.DISPLAY_NAME, "Countess"));
        patch(new RemovePassword());
        patch(new RemovePassword());

        assertThat(stored().login().hasPassword()).isFalse();
        assertThat(history.findRecentHashes(ada.id())).isEqualTo(before);
        assertThatThrownBy(() -> patch(new SetPassword("first-password-1")))
                .isInstanceOf(PasswordReusedException.class);
        assertThat(history.findRecentHashes(ada.id())).isEqualTo(before);
    }

    // ---- a connector-set password requires a change ----------------------------------------

    /**
     * A credential chosen and transported by a connector is known outside the User, so it must be
     * replaced before it is used for anything else: a create carrying one flags the User as of
     * the create.
     */
    @Test
    void a_create_with_a_password_requires_a_change() {
        assertThat(stored().login().passwordChangeRequiredSince()).isEqualTo(LATER);
        assertThat(stored().createdAt()).as("flagging keeps the creation stamps").isEqualTo(LATER);
        assertThat(stored().lastModifiedAt()).isEqualTo(LATER);
        assertThat(ada.createdAt()).isEqualTo(LATER);
    }

    @Test
    void a_write_that_sets_no_password_does_not_flag_an_unflagged_user() {
        ScimUserResource grace = service.create(
                CONNECTOR, new NewScimUser(minimal("grace", true), null, null));
        UUID id = grace.id();

        service.replace(CONNECTOR, id, versionOf(id),
                new ScimUserReplacement(minimal("grace", false), null, null, true));

        assertThat(users.findById(id).orElseThrow().login().isPasswordChangeRequired()).isFalse();
    }

    @Test
    void a_create_without_a_password_requires_no_change() {
        ScimUserResource grace = service.create(
                CONNECTOR, new NewScimUser(minimal("grace", true), null, null));

        assertThat(users.findById(grace.id()).orElseThrow().login().isPasswordChangeRequired())
                .isFalse();
    }

    @Test
    void a_put_or_patch_setting_a_password_requires_a_change() {
        ScimUserResource grace = service.create(
                CONNECTOR, new NewScimUser(minimal("grace", true), null, null));
        UUID id = grace.id();
        service.replace(CONNECTOR, id, versionOf(id),
                new ScimUserReplacement(minimal("grace", true), "a-put-password", null, true));
        assertThat(users.findById(id).orElseThrow().login().isPasswordChangeRequired())
                .as("PUT with a password").isTrue();

        ScimUser hopper =
                users.given(ScimIdentities.userWithLoginState("hopper", ScimLoginState.of("hash")));
        service.patch(CONNECTOR, hopper.id(), versionOf(hopper.id()),
                List.of(new SetPassword("a-patch-password")));
        assertThat(users.require("hopper").login().passwordChangeRequiredSince())
                .as("PATCH with a password").isEqualTo(LATER);
    }

    /** Neither omitting the password, removing it, nor any other attribute clears the flag. */
    @Test
    void no_connector_write_clears_the_requirement() {
        put(minimal("ada", true), null, "ext-ada");
        assertThat(stored().login().isPasswordChangeRequired()).as("PUT omitting it").isTrue();
        patch(new SetText(TextAttribute.DISPLAY_NAME, "Countess"));
        assertThat(stored().login().isPasswordChangeRequired()).as("a profile change").isTrue();
        patch(new RemovePassword());
        assertThat(stored().login().isPasswordChangeRequired())
                .as("removing the password").isTrue();
    }

    private ScimVersionPrecondition versionOf(UUID id) {
        return ScimVersionPrecondition.ofIfMatch(
                List.of("\"" + users.findById(id).orElseThrow().version() + "\""));
    }

    // ---- full replacement -----------------------------------------------------------------

    /** Everything the document omits is unassigned, except the password, which is kept. */
    @Test
    void a_put_replaces_the_profile_clears_omitted_optionals_and_keeps_an_omitted_password() {
        String hashBefore = stored().login().passwordHash();

        ScimUserResource written = put(minimal("ada", true), null, "ext-ada");

        assertThat(written.profile()).isEqualTo(minimal("ada", true));
        assertThat(stored().profile().displayName()).isNull();
        assertThat(stored().profile().emails()).isEmpty();
        assertThat(stored().profile().name()).isEqualTo(ScimName.NONE);
        assertThat(stored().login().passwordHash())
                .as("an omitted password leaves the credential usable")
                .isEqualTo(hashBefore);
        assertThat(revocations).as("a profile-only change ends no session").isEmpty();
    }

    @Test
    void a_changing_write_advances_the_version_exactly_once_and_stamps_last_modified() {
        long before = stored().version();

        ScimUserResource written = put(minimal("ada", false), "second-password-2", null);

        assertThat(written.version()).isEqualTo(before + 1);
        assertThat(stored().version()).isEqualTo(before + 1);
        assertThat(stored().lastModifiedAt()).isEqualTo(LATER);
        assertThat(writesSinceSetUp()).as("one write, however many attributes moved").isEqualTo(1);
    }

    @Test
    void a_put_restating_the_stored_state_writes_nothing_and_advances_no_version() {
        ScimUser before = stored();

        ScimUserResource written = put(before.profile(), null, "ext-ada");

        assertThat(written.version()).isEqualTo(before.version());
        assertThat(stored()).isEqualTo(before);
        assertThat(writesSinceSetUp()).isZero();
        assertThat(audit.of(AuditOperation.SCIM_USER_REPLACE))
                .singleElement()
                .extracting(RecordingAuditTrail.Recorded::detail)
                .as("recorded, with nothing changed")
                .isEqualTo("");
    }

    @Test
    void a_write_is_audited_with_the_connector_and_exactly_the_attributes_that_moved() {
        put(new ScimUserProfile("ada", new ScimName(null, "King", "Ada", null, null, null),
                "Countess", "en", "en-GB", "Europe/London", true,
                List.of(new ScimEmail("ada@work.example", "work", true))), null, "ext-ada");

        assertThat(audit.of(AuditOperation.SCIM_USER_REPLACE))
                .singleElement()
                .satisfies(event -> {
                    assertThat(event.actorId()).isEqualTo(CONNECTOR.connectorId());
                    assertThat(event.subjectId()).isEqualTo(ada.id());
                    assertThat(event.detail()).isEqualTo("DISPLAY_NAME");
                });
    }

    // ---- aliases: the shared cases are ScimAliasWriteContract's, over Ada ---------------

    @Override
    protected AuthenticatedConnector connector() {
        return CONNECTOR;
    }

    @Override
    protected Aliased aliasedResource() {
        aliases.put(OTHER_CONNECTOR.connectorId(), ada.id(), THEIRS);
        audit.reset();
        return new Aliased(ada.id(), "ext-ada", stored().version());
    }

    @Override
    protected Aliased create(String externalId) {
        ScimUserResource created = service.create(
                CONNECTOR, new NewScimUser(minimal("grace", true), null, externalId));
        return new Aliased(created.id(), created.externalId(), created.version());
    }

    @Override
    protected Answer put(UUID id, String externalId) {
        return answer(put(stored().profile(), null, externalId));
    }

    @Override
    protected Answer setAlias(AuthenticatedConnector caller, UUID id, String externalId) {
        return answer(service.patch(caller, id, current(),
                List.of(new ScimUserPatchOperation.SetExternalId(externalId))).orElseThrow());
    }

    @Override
    protected Answer removeAlias(UUID id) {
        return answer(patch(new ScimUserPatchOperation.RemoveExternalId()));
    }

    @Override
    protected Answer changeAnotherAttribute(UUID id) {
        return answer(patch(new SetText(TextAttribute.DISPLAY_NAME, "Countess")));
    }

    @Override
    protected long storedVersion(UUID id) {
        return users.findById(id).orElseThrow().version();
    }

    @Override
    protected String readBy(AuthenticatedConnector caller, UUID id) {
        return service.findById(caller, id).orElseThrow().externalId();
    }

    @Override
    protected List<String> recordedReplaceDetails() {
        return audit.of(AuditOperation.SCIM_USER_REPLACE).stream()
                .map(RecordingAuditTrail.Recorded::detail).toList();
    }

    private static Answer answer(ScimUserResource written) {
        return new Answer(written.externalId(), written.version());
    }

    /**
     * The alias is not a User column, so a write that moved only the alias writes no User row: its
     * version and {@code lastModified} are advanced on their own, once, as a Group's are.
     */
    @Test
    void an_alias_only_write_advances_the_version_without_writing_the_user() {
        long before = stored().version();

        ScimUserResource written = patch(new ScimUserPatchOperation.SetExternalId("ext-ada-2"));

        assertThat(written.version()).isEqualTo(before + 1);
        assertThat(stored().version()).isEqualTo(before + 1);
        assertThat(stored().lastModifiedAt()).isEqualTo(LATER);
        assertThat(writesSinceSetUp()).as("no User row is written").isZero();
    }

    /**
     * The Bootstrap Admin keeps its protection: an alias write is a write to that User — it
     * advances its version — so it is refused like any other, and no alias is stored.
     */
    @Test
    void the_bootstrap_admin_alias_cannot_be_written() {
        ScimUser reserved = users.createReserved(
                ScimIdentities.user("root"), ReservedResourceName.BOOTSTRAP_ADMIN);
        ScimVersionPrecondition itsVersion = ScimVersionPrecondition.ofIfMatch(
                List.of("\"" + reserved.version() + "\""));

        assertThatThrownBy(() -> service.patch(CONNECTOR, reserved.id(), itsVersion,
                List.of(new ScimUserPatchOperation.SetExternalId("root-alias"))))
                .isInstanceOf(ProtectedResourceException.class);

        assertThat(aliases.find(CONNECTOR.connectorId(), reserved.id())).isEmpty();
        assertThat(users.findById(reserved.id()).orElseThrow()).isEqualTo(reserved);
    }

    // ---- the order of refusals ------------------------------------------------------------

    /** Existence comes before the precondition, so a missing header cannot disclose an id. */
    @Test
    void an_unknown_id_is_absent_whatever_the_precondition_and_nothing_is_recorded() {
        assertThat(service.replace(CONNECTOR, UUID.randomUUID(),
                ScimVersionPrecondition.ofIfMatch(List.of()),
                new ScimUserReplacement(minimal("x", true), null, null, true))).isEmpty();
        assertThat(service.patch(CONNECTOR, UUID.randomUUID(),
                ScimVersionPrecondition.ofIfMatch(List.of()),
                List.of(new SetActive(false)))).isEmpty();
        assertThat(audit.recorded()).isEmpty();
    }

    /** {@code If-Match} is optional: a write without one is applied and advances the version. */
    @Test
    void a_write_without_a_precondition_is_applied_unconditionally() {
        ScimUser before = stored();

        ScimUserResource written = service.patch(CONNECTOR, ada.id(),
                ScimVersionPrecondition.ofIfMatch(List.of()), List.of(new SetActive(false)))
                .orElseThrow();

        assertThat(written.version()).isEqualTo(before.version() + 1);
        assertThat(stored().profile().active()).isFalse();
        assertThat(audit.of(AuditOperation.SCIM_USER_REPLACE)).singleElement()
                .extracting(RecordingAuditTrail.Recorded::detail).isEqualTo("ACTIVE");
    }

    /** Nor does dropping the header get past the reservation the Bootstrap Admin carries. */
    @Test
    void the_bootstrap_admin_cannot_be_written_without_a_precondition_either() {
        ScimUser reserved = users.createReserved(
                ScimIdentities.user("root"), ReservedResourceName.BOOTSTRAP_ADMIN);

        assertThatThrownBy(() -> service.patch(CONNECTOR, reserved.id(),
                ScimVersionPrecondition.ofIfMatch(List.of()), List.of(new SetActive(false))))
                .isInstanceOf(ProtectedResourceException.class);
        assertThatThrownBy(() -> service.delete(CONNECTOR, reserved.id(),
                ScimVersionPrecondition.ofIfMatch(List.of())))
                .isInstanceOf(ProtectedResourceException.class);

        assertThat(users.findById(reserved.id()).orElseThrow()).isEqualTo(reserved);
    }

    @Test
    void a_stale_precondition_changes_nothing_and_records_nothing() {
        ScimUser before = stored();

        assertThatThrownBy(() -> service.replace(CONNECTOR, ada.id(),
                ScimVersionPrecondition.ofIfMatch(List.of("\"" + (before.version() + 1) + "\"")),
                new ScimUserReplacement(minimal("ada", false), "x-password-9", null, true)))
                .isInstanceOf(PreconditionFailedException.class);

        assertThat(stored()).isEqualTo(before);
        assertThat(writesSinceSetUp()).isZero();
        assertThat(audit.recorded()).isEmpty();
        assertThat(revocations).isEmpty();
        assertThat(history.findRecentHashes(ada.id()))
                .as("the stale write's password was never remembered")
                .containsExactly(before.login().passwordHash());
    }

    /** A protected User's credential and history are untouched by a write that sets a password. */
    @Test
    void a_password_write_to_the_bootstrap_admin_changes_neither_credential_nor_history() {
        ScimUser reserved = users.createReserved(
                ScimIdentities.user("root"), ReservedResourceName.BOOTSTRAP_ADMIN);

        assertThatThrownBy(() -> service.patch(CONNECTOR, reserved.id(), versionOf(reserved.id()),
                List.of(new SetPassword("a-valid-new-password"))))
                .isInstanceOf(ProtectedResourceException.class);
        assertThatThrownBy(() -> service.replace(CONNECTOR, reserved.id(), versionOf(reserved.id()),
                new ScimUserReplacement(reserved.profile(), "a-valid-new-password", null, true)))
                .isInstanceOf(ProtectedResourceException.class);

        assertThat(users.findById(reserved.id()).orElseThrow()).isEqualTo(reserved);
        assertThat(history.findRecentHashes(reserved.id())).isEmpty();
        assertThat(revocations).isEmpty();
        assertThat(encoder.matches).as("acceptance was never reached").isZero();
    }

    @Test
    void the_bootstrap_admin_cannot_be_written_and_no_write_is_recorded() {
        ScimUser reserved = users.createReserved(
                ScimIdentities.user("root"), ReservedResourceName.BOOTSTRAP_ADMIN);
        ScimVersionPrecondition itsVersion = ScimVersionPrecondition.ofIfMatch(
                List.of("\"" + reserved.version() + "\""));

        assertThatThrownBy(() -> service.patch(CONNECTOR, reserved.id(), itsVersion,
                List.of(new SetActive(false))))
                .isInstanceOf(ProtectedResourceException.class);

        assertThat(users.findById(reserved.id()).orElseThrow()).isEqualTo(reserved);
        assertThat(audit.events()).as(REFUSAL_IS_THE_EDGES).isEmpty();
    }

    @Test
    void a_taken_user_name_is_refused_as_a_duplicate_and_no_write_is_recorded() {
        service.create(CONNECTOR, new NewScimUser(minimal("grace", true), null, null));
        audit.reset();

        assertThatThrownBy(() -> patch(new SetText(TextAttribute.USER_NAME, "GRACE")))
                .isInstanceOf(DuplicateUserNameException.class);
        assertThat(audit.events()).as(REFUSAL_IS_THE_EDGES).isEmpty();
        assertThat(revocations).isEmpty();
    }

    // ---- stored-length limits ---------------------------------------------------------------

    /**
     * A write carrying one value its column cannot hold is refused whole, as an invalid value
     * naming the attribute: the valid change beside it is not written either. Each limit, and the
     * refusal of a NUL or an unpaired code point, is {@code ScimAttributeLimitsTests}'; that the
     * column really holds the value at its limit, and that every verb is refused, is
     * {@code ScimStoredLengthIntegrationTests}'.
     */
    @Test
    void a_write_with_one_value_too_long_for_its_column_writes_nothing() {
        ScimUser before = stored();

        assertThatThrownBy(() -> patch(
                        new SetText(TextAttribute.DISPLAY_NAME, "Ada Lovelace"),
                        new ScimUserPatchOperation.SetExternalId("x".repeat(257))))
                .isInstanceOfSatisfying(ScimValueTooLongException.class,
                        tooLong -> assertThat(tooLong.attribute()).isEqualTo("externalId"));

        assertLengthRefusedAndNothingChanged(before);
        assertThat(aliases.find(CONNECTOR.connectorId(), ada.id())).contains("ext-ada");
    }

    /** The limit is checked before the password policy, so a refused write hashes nothing. */
    @Test
    void a_write_refused_for_length_never_reaches_the_password_policy() {
        ScimUser before = stored();
        int saltBefore = encoder.salt;

        assertThatThrownBy(() -> patch(
                        new SetPassword("a-new-valid-password"),
                        new SetText(TextAttribute.TIMEZONE, "z".repeat(65))))
                .isInstanceOf(ScimValueTooLongException.class);

        assertThat(encoder.salt).isEqualTo(saltBefore);
        assertThat(encoder.matches).isZero();
        assertLengthRefusedAndNothingChanged(before);
    }

    private void assertLengthRefusedAndNothingChanged(ScimUser before) {
        assertThat(stored()).isEqualTo(before);
        assertThat(writesSinceSetUp()).isZero();
        assertThat(revocations).isEmpty();
        assertThat(audit.events()).as(REFUSAL_IS_THE_EDGES).isEmpty();
    }

    // ---- partial update -------------------------------------------------------------------

    @Test
    void a_no_op_patch_leaves_the_version_and_last_modified_unchanged() {
        ScimUser before = stored();

        ScimUserResource written = patch(
                new SetActive(true), new SetText(TextAttribute.DISPLAY_NAME, "Ada"));

        assertThat(written.version()).isEqualTo(before.version());
        assertThat(written.lastModifiedAt()).isEqualTo(before.lastModifiedAt());
        assertThat(writesSinceSetUp()).isZero();
    }

    @Test
    void removing_the_user_name_is_refused_and_changes_nothing() {
        ScimUser before = stored();

        assertThatThrownBy(() -> patch(new RemoveText(TextAttribute.USER_NAME)))
                .isInstanceOf(ScimPatchRefusedException.class);

        assertThat(stored()).isEqualTo(before);
        assertThat(audit.events()).as(REFUSAL_IS_THE_EDGES).isEmpty();
    }

    /**
     * The first operation would have deactivated the User; the second fails. Re-reading shows the
     * User exactly as it was — the first was never written, and no session was ended for it.
     */
    @Test
    void a_failing_operation_anywhere_in_the_sequence_leaves_the_user_as_it_was() {
        ScimUser before = stored();
        ScimEmailFilter pager = new ScimEmailFilter(
                List.of(new ScimEmailFilter.Condition(ScimEmailPart.TYPE, "pager")));

        assertThatThrownBy(() -> patch(new SetActive(false), new RemoveEmails(pager)))
                .isInstanceOfSatisfying(ScimPatchRefusedException.class, refused ->
                        assertThat(refused.reason())
                                .isEqualTo(ScimPatchRefusedException.Reason.NO_TARGET));

        assertThat(stored()).isEqualTo(before);
        assertThat(writesSinceSetUp()).isZero();
        assertThat(revocations).isEmpty();
        assertThat(audit.events()).as(REFUSAL_IS_THE_EDGES).isEmpty();
    }

    // ---- password history -----------------------------------------------------------------

    @Test
    void the_current_password_is_refused_on_both_paths() {
        assertThatThrownBy(() -> patch(new SetPassword("first-password-1")))
                .isInstanceOf(PasswordReusedException.class);
        assertThatThrownBy(() -> put(stored().profile(), "first-password-1", "ext-ada"))
                .isInstanceOf(PasswordReusedException.class);

        assertThat(audit.events()).as(REFUSAL_IS_THE_EDGES).isEmpty();
        assertThat(revocations).isEmpty();
        assertThat(history.findRecentHashes(ada.id())).hasSize(1);
    }

    @Test
    void a_new_password_is_hashed_stored_remembered_and_never_kept_in_plaintext() {
        patch(new SetPassword("second-password-2"));

        String hash = stored().login().passwordHash();
        assertThat(hash).startsWith("{fake}").endsWith(":second-password-2");
        assertThat(history.findRecentHashes(ada.id()).get(0)).isEqualTo(hash);
        assertThat(encoder.matches).as("the history was consulted").isPositive();
    }

    // ---- password policy ------------------------------------------------------------------

    private static void assertRefusedFor(
            ThrowingCallable write, String candidate, PasswordPolicy.Rule rule) {
        assertThatThrownBy(write).isInstanceOfSatisfying(
                PasswordPolicyRefusedException.class, refused -> {
                    assertThat(refused.rule()).isEqualTo(rule);
                    assertThat(refused.getMessage()).isEqualTo(rule.message())
                            .doesNotContain(candidate);
                });
    }

    /** Judged against the userName the create provisions, which the fixture's {@code ada} is not. */
    @Test
    void a_create_with_a_sub_policy_password_is_refused_and_writes_nothing() {
        String candidate = "i-am-ADA-LOVELACE-really";

        assertRefusedFor(() -> service.create(CONNECTOR, new NewScimUser(
                        minimal("ada-lovelace", true), candidate, "ext-new")),
                candidate, PasswordPolicy.Rule.CONTAINS_USER_NAME);

        assertThat(writesSinceSetUp()).isZero();
        assertThat(history.findRecentHashes(ada.id())).hasSize(1);
        assertThat(audit.events()).as(REFUSAL_IS_THE_EDGES).isEmpty();
    }

    @Test
    void a_put_with_a_sub_policy_password_is_refused_and_changes_nothing() {
        ScimUser before = stored();

        assertRefusedFor(() -> put(before.profile(), "short-pw-1", "ext-ada"),
                "short-pw-1", PasswordPolicy.Rule.TOO_SHORT);

        assertWriteRefusedAndNothingChanged(before);
    }

    @Test
    void a_patch_with_a_sub_policy_password_is_refused_and_changes_nothing() {
        ScimUser before = stored();

        assertRefusedFor(() -> patch(new SetPassword("short-pw-1")),
                "short-pw-1", PasswordPolicy.Rule.TOO_SHORT);

        assertWriteRefusedAndNothingChanged(before);
    }

    private void assertWriteRefusedAndNothingChanged(ScimUser before) {
        assertThat(stored()).isEqualTo(before);
        assertThat(writesSinceSetUp()).isZero();
        assertThat(history.findRecentHashes(ada.id())).hasSize(1);
        assertThat(revocations).isEmpty();
        assertThat(encoder.matches).as("a sub-policy value is never compared to the history")
                .isZero();
        assertThat(audit.events()).as(REFUSAL_IS_THE_EDGES).isEmpty();
    }

    /**
     * The policy is checked against the userName the write leaves the User with, so a PUT renaming
     * the User cannot carry a password containing its new name.
     */
    @Test
    void a_write_renaming_the_user_checks_the_password_against_the_new_user_name() {
        String candidate = "grace-hopper-passphrase";

        assertRefusedFor(() -> put(minimal("grace", true), candidate, "ext-ada"),
                candidate, PasswordPolicy.Rule.CONTAINS_USER_NAME);
        assertRefusedFor(() -> patch(new SetText(TextAttribute.USER_NAME, "grace"),
                        new SetPassword(candidate)),
                candidate, PasswordPolicy.Rule.CONTAINS_USER_NAME);
        assertThat(stored().profile().userName()).isEqualTo("ada");
    }

    /**
     * And the other way: a password containing the name the User is leaving is accepted with the
     * rename, because the excluded name is the resulting one.
     */
    @Test
    void a_write_renaming_the_user_accepts_a_password_containing_only_the_old_user_name() {
        put(minimal("grace", true), "ada-was-my-name-once", "ext-ada");
        assertThat(stored().profile().userName()).isEqualTo("grace");
        assertThat(stored().login().passwordHash()).endsWith(":ada-was-my-name-once");

        patch(new SetText(TextAttribute.USER_NAME, "hopper"), new SetPassword("grace-was-my-name"));
        assertThat(stored().profile().userName()).isEqualTo("hopper");
        assertThat(history.findRecentHashes(ada.id())).hasSize(3);
        assertThat(revocations).extracting(Revocation::causes).containsOnly(
                Set.of(Cause.PASSWORD_CHANGED, Cause.USER_NAME_CHANGED));
    }

    // ---- carrying out the revocations a write implies ----------------------------------------

    /**
     * The use case hands the module the stored and the written credential — the hashes, which is
     * what makes a password removal a change — and requests each revocation the effects name, on
     * behalf of the calling connector. Which edits end sessions is pinned in
     * {@code ScimWriteEffectsTests}.
     */
    @Test
    void a_write_requests_the_revocation_its_effects_name_for_the_connector() {
        put(minimal("ada.lovelace", false), "second-password-2", "ext-ada");

        assertThat(revocations).containsExactly(new Revocation(CONNECTOR.connectorId(), ada.id(),
                Set.of(Cause.DEACTIVATED, Cause.PASSWORD_CHANGED, Cause.USER_NAME_CHANGED)));
    }

    @Test
    void a_password_removal_is_written_and_ends_the_users_sessions() {
        patch(new RemovePassword());

        assertThat(revocations).extracting(Revocation::causes)
                .containsExactly(Set.of(Cause.PASSWORD_CHANGED));
        assertThat(stored().login().hasPassword()).isFalse();
    }

    /** Removing a password that is not there changes nothing, so it writes and ends nothing. */
    @Test
    void removing_an_absent_password_is_a_no_op() {
        patch(new RemovePassword());
        revocations.clear();
        long version = stored().version();

        patch(new RemovePassword());

        assertThat(revocations).isEmpty();
        assertThat(stored().version()).isEqualTo(version);
    }

    // ---- deletion -------------------------------------------------------------------------

    @Test
    void a_deletion_removes_the_user_leaves_a_tombstone_records_it_and_ends_the_sessions() {
        assertThat(service.delete(CONNECTOR, ada.id(), current())).isTrue();

        assertThat(users.findById(ada.id())).isEmpty();
        assertThat(service.findById(CONNECTOR, ada.id())).isEmpty();
        assertThat(tombstones.recorded()).containsExactly(
                new InMemoryScimTombstoneRepository.Tombstone(
                        com.example.backend.scim.domain.ScimResourceType.USER, ada.id(), LATER));
        assertThat(audit.recorded()).containsExactly(new RecordingAuditTrail.Recorded(
                AuditOperation.SCIM_USER_DELETE, CONNECTOR.connectorId(), ada.id(), null));
        assertThat(revocations).containsExactly(
                new Revocation(CONNECTOR.connectorId(), ada.id(), Set.of(Cause.DELETED)));
    }

    @Test
    void deleting_an_id_that_names_no_user_reports_absence_and_touches_nothing() {
        assertThat(service.delete(CONNECTOR, UUID.randomUUID(), current())).isFalse();

        assertThat(writesSinceSetUp()).isZero();
        assertThat(tombstones.recorded()).isEmpty();
        assertThat(audit.recorded()).isEmpty();
        assertThat(revocations).isEmpty();
    }

    @Test
    void a_deletion_with_a_stale_precondition_is_refused_and_changes_nothing() {
        ScimVersionPrecondition stale = ScimVersionPrecondition.ofIfMatch(
                List.of("\"" + (stored().version() + 1) + "\""));

        assertThatThrownBy(() -> service.delete(CONNECTOR, ada.id(), stale))
                .isInstanceOf(com.example.backend.scim.domain.PreconditionFailedException.class);

        assertThat(users.findById(ada.id())).isPresent();
        assertThat(writesSinceSetUp()).isZero();
        assertThat(tombstones.recorded()).isEmpty();
        assertThat(audit.recorded()).isEmpty();
        assertThat(revocations).isEmpty();
    }

    @Test
    void the_bootstrap_admin_cannot_be_deleted_and_no_deletion_is_recorded() {
        ScimUser reserved = users.createReserved(
                ScimIdentities.user("root"), ReservedResourceName.BOOTSTRAP_ADMIN);
        ScimVersionPrecondition itsVersion = ScimVersionPrecondition.ofIfMatch(
                List.of("\"" + reserved.version() + "\""));

        assertThatThrownBy(() -> service.delete(CONNECTOR, reserved.id(), itsVersion))
                .isInstanceOf(ProtectedResourceException.class);

        assertThat(users.findById(reserved.id()).orElseThrow()).isEqualTo(reserved);
        assertThat(tombstones.recorded()).isEmpty();
        assertThat(revocations).isEmpty();
        assertThat(audit.events()).as(REFUSAL_IS_THE_EDGES).isEmpty();
    }

    /**
     * A deleted User's Groups each lost a member, so each one's rendered {@code members} changed:
     * its version advances once and the User is no longer listed.
     */
    @Test
    void a_deletion_advances_every_group_the_user_was_in_once_and_drops_the_membership() {
        com.example.backend.scim.domain.ScimGroup engineers =
                groups.create(ScimIdentities.group("Engineers", stored()));

        service.delete(CONNECTOR, ada.id(), current());

        com.example.backend.scim.domain.ScimGroup after =
                groups.findById(engineers.id()).orElseThrow();
        assertThat(after.version()).isEqualTo(engineers.version() + 1);
        assertThat(after.hasMember(ada.id())).as("the membership went with the User").isFalse();
    }

    /**
     * The User's memberships go with it, so each one of a Group that confers Permissions is a
     * change of power, as removing it from that Group would be. Every event records what the User
     * held before the deletion — both Groups' Permissions — and nothing after; a Group conferring
     * nothing records none. The Sessions end once, for the deletion.
     */
    @Test
    void deleting_a_user_records_the_loss_of_each_group_of_power_it_was_in() {
        com.example.backend.scim.domain.ScimGroup helpdesk =
                groups.create(ScimIdentities.group("Helpdesk", stored()));
        com.example.backend.scim.domain.ScimGroup auditors =
                groups.create(ScimIdentities.group("Auditors", stored()));
        groups.create(ScimIdentities.group("Book club", stored()));
        groupPermissions
                .carrying(helpdesk.id(), Permission.USER_READ, Permission.ACCOUNT_UNLOCK)
                .carrying(auditors.id(), Permission.AUDIT_READ);

        service.delete(CONNECTOR, ada.id(), current());

        String before = "account:unlock,audit:read,user:read";
        assertThat(audit.of(AuditOperation.PERMISSION_REVOKE)).containsExactlyInAnyOrder(
                permissionRevoke(helpdesk.id(), before, false),
                permissionRevoke(auditors.id(), before, false));
        assertThat(revocations).containsExactly(
                new Revocation(CONNECTOR.connectorId(), ada.id(), Set.of(Cause.DELETED)));
    }

    /**
     * Each Group of power the deletion took the User from is logged with what that Group confers,
     * not with what another of its Groups does.
     */
    @Test
    void deleting_a_user_logs_each_group_of_power_with_what_that_group_confers() {
        ScimGroup helpdesk = groups.create(ScimIdentities.group("Helpdesk", stored()));
        ScimGroup auditors = groups.create(ScimIdentities.group("Auditors", stored()));
        groupPermissions
                .carrying(helpdesk.id(), Permission.USER_READ, Permission.ACCOUNT_UNLOCK)
                .carrying(auditors.id(), Permission.AUDIT_READ);

        try (EcsLogCapture ecs = EcsLogCapture.attach(new StandardEnvironment())) {
            service.delete(CONNECTOR, ada.id(), current());

            assertThat(ecs.records().stream()
                    .filter(record -> record.at("/app/event/action").asText()
                            .equals("identity.permission_revoke"))
                    .map(record -> record.at("/group/id").asText() + " confers "
                            + record.at("/app/authorization/group_permissions").asText()))
                    .containsExactlyInAnyOrder(
                            helpdesk.id() + " confers account:unlock,user:read",
                            auditors.id() + " confers audit:read");
        }
    }

    /** Leaving the Admin group with the deletion is the Superuser's loss, which the marker says. */
    @Test
    void deleting_a_member_of_the_admin_group_records_the_superuser_lost() {
        com.example.backend.scim.domain.ScimGroup admins = groups.createReserved(
                ScimIdentities.group("Admins", stored()), ReservedResourceName.ADMIN_GROUP);

        service.delete(CONNECTOR, ada.id(), current());

        assertThat(audit.of(AuditOperation.PERMISSION_REVOKE)).containsExactly(permissionRevoke(
                admins.id(), "account:unlock,audit:read,connector:read,group:read,ops:read,"
                        + "user:read", true));
    }

    /**
     * One recorded loss of power of Ada's, by the connector: as detail the Group, her effective
     * Permissions before, none after, and whether the Superuser moved.
     */
    private RecordingAuditTrail.Recorded permissionRevoke(
            UUID groupId, String before, boolean superuser) {
        return new RecordingAuditTrail.Recorded(AuditOperation.PERMISSION_REVOKE,
                CONNECTOR.connectorId(), ada.id(), groupId + ":" + before + ">:" + superuser);
    }

    @Test
    void a_deleted_users_former_user_name_is_free_for_the_next_create() {
        service.delete(CONNECTOR, ada.id(), current());

        ScimUserResource again = service.create(CONNECTOR, new NewScimUser(
                minimal("ADA", true), null, "ext-ada"));

        assertThat(again.id()).isNotEqualTo(ada.id());
    }

    // ---- what a write returns and leaves alone ---------------------------------------------

    /** A write returns the User as the connector sees it, its computed Group memberships included. */
    @Test
    void a_write_returns_the_users_group_memberships() {
        com.example.backend.scim.domain.ScimGroup engineers =
                groups.create(ScimIdentities.group("Engineers", stored()));

        ScimUserResource written = patch(new SetText(TextAttribute.DISPLAY_NAME, "Countess"));

        assertThat(written.groups()).extracting(
                        com.example.backend.scim.domain.ScimGroupReference::id)
                .containsExactly(engineers.id());
    }

    /**
     * Each Group renders its members by label — the {@code displayName} here — so a write that
     * changes it changes every Group the User is in, and each one's version advances once.
     */
    @Test
    void a_write_changing_the_member_label_advances_every_group_the_user_is_in_once() {
        com.example.backend.scim.domain.ScimGroup engineers =
                groups.create(ScimIdentities.group("Engineers", stored()));

        patch(new SetText(TextAttribute.DISPLAY_NAME, "Countess"));

        assertThat(groups.findById(engineers.id()).orElseThrow().version())
                .isEqualTo(engineers.version() + 1);
    }

    /**
     * Taking {@code active} from false to true is a Reactivation: the write stores the dormancy
     * basis the login-state module restarted as of the write, so the dormancy job does not lock a
     * User the directory has just brought back.
     */
    @Test
    void a_reactivation_restarts_the_dormancy_basis_as_of_the_write() {
        patch(new SetActive(false));
        assertThat(stored().login().lastAuthenticatedAt()).as("before").isNull();

        patch(new SetActive(true));

        assertThat(stored().login().lastAuthenticatedAt()).isEqualTo(LATER);
    }

    /** A PUT restating {@code active=true} and a PATCH of {@code active} are one Reactivation. */
    @Test
    void a_reactivation_by_put_leaves_the_login_state_a_patch_does() {
        patch(new SetActive(false));
        patch(new SetActive(true));
        ScimLoginState patched = stored().login();
        patch(new SetActive(false));

        put(minimal("ada", true), null, "ext-ada");

        assertThat(stored().login()).isEqualTo(patched);
    }

    /** A PUT that does not assert {@code active} keeps the stored value rather than reactivating. */
    @Test
    void a_put_without_active_keeps_the_stored_value() {
        patch(new SetActive(false));

        service.replace(CONNECTOR, ada.id(), current(),
                new ScimUserReplacement(minimal("ada", true), null, "ext-ada", false));

        assertThat(stored().profile().active()).isFalse();
    }

    /** A query is run for the calling connector, whose aliases it renders, and keeps its page. */
    @Test
    void a_query_runs_for_the_calling_connector_and_returns_the_requested_page() {
        List<UUID> askedFor = new ArrayList<>();
        ScimUserService querying = new ScimUserService(
                users, new InMemoryScimWriteLocks(), groups, aliases,
                new PasswordAcceptance(history, ScimPasswordAcceptanceConfig.hasher(encoder)),
                sessions, LoginStateModules.over(users, clock), tombstones, audit, clock,
                (query, connectorId, baseUri) -> {
                    askedFor.add(connectorId);
                    // The total is the whole match, not the page, so it differs from the one hit.
                    return new com.example.backend.scim.domain.ScimQuery.Result(
                            5, List.of(new com.example.backend.scim.domain.ScimQuery.Hit(
                                    com.example.backend.scim.domain.ScimResourceType.USER,
                                    ada.id())));
                }, groupPermissions, () -> { });
        com.example.backend.scim.domain.ScimPageRequest page =
                new com.example.backend.scim.domain.ScimPageRequest(1, 10);
        com.example.backend.scim.domain.ScimFilter filter =
                new com.example.backend.scim.domain.ScimFilter.Presence(
                        new com.example.backend.scim.domain.ScimFilter.AttributeRef(
                                com.example.backend.scim.domain.ScimFilterPath.USER_NAME, null));

        ScimUserListing listing = querying.query(CONNECTOR, new com.example.backend.scim.domain.ScimQuery(
                Set.of(com.example.backend.scim.domain.ScimResourceType.USER), filter, null, page), "u");

        assertThat(askedFor).containsExactly(CONNECTOR.connectorId());
        assertThat(listing.page()).isEqualTo(page);
        assertThat(listing.totalResults()).isEqualTo(5);
        assertThat(listing.resources()).extracting(ScimUserResource::externalId)
                .containsExactly("ext-ada");
        // The read is audited for the calling connector with the returned count and the
        // filter's shape (never its value).
        assertThat(audit.of(AuditOperation.SCIM_USER_LIST)).singleElement()
                .satisfies(event -> {
                    assertThat(event.actorId()).isEqualTo(CONNECTOR.connectorId());
                    assertThat(event.detail()).isEqualTo("1 userName pr");
                });
    }
}

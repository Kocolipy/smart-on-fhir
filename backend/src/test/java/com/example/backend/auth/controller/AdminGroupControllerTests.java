package com.example.backend.auth.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import com.example.backend.audit.RecordingAuditTrail;
import com.example.backend.audit.RecordingOperationalAlerts;
import com.example.backend.auth.InMemoryAccountSessions;
import com.example.backend.auth.PendingCommit;
import com.example.backend.auth.application.GroupPermissionAdministrationService;
import com.example.backend.auth.application.GroupPermissionsChangeRefusedException;
import com.example.backend.auth.application.GroupMemberPage;
import com.example.backend.auth.application.GroupSummary;
import com.example.backend.auth.application.IdentityAdministrationService;
import com.example.backend.auth.application.SessionRevocationService;
import com.example.backend.auth.application.UnknownSessionIdentityException;
import com.example.backend.authorization.config.AuthorizationChangeMetrics;
import com.example.backend.authorization.domain.Permission;
import com.example.backend.scim.InMemoryGroupPermissions;
import com.example.backend.scim.InMemoryScimGroupRepository;
import com.example.backend.scim.InMemoryScimQueryRepository;
import com.example.backend.scim.InMemoryScimUserRepository;
import com.example.backend.scim.InMemoryScimWriteLocks;
import com.example.backend.scim.ScimIdentities;
import com.example.backend.scim.domain.ScimGroup;
import com.example.backend.scim.domain.ScimUser;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.session.FindByIndexNameSessionRepository;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The Groups adapter over the real use cases and in-memory ports: the Superuser's write of a
 * Group's Permissions as it reads the body — which bodies reach the use case as a set of names,
 * and which as the refusal of one — and the member read as it reads its paging. How each refusal is answered, logged and audited is the administrative edge's
 * ({@code AdminRefusalsTests}); the chain and method security are
 * {@code AuthorizationContractTests}'; the stack end to end is
 * {@code GroupPermissionPropagationIntegrationTests}'.
 */
class AdminGroupControllerTests {

    private static final UUID SUPERUSER = UUID.fromString("00000000-0000-4000-8000-00000000a0a0");

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final InMemoryScimUserRepository users = new InMemoryScimUserRepository();
    private final InMemoryScimGroupRepository groups = new InMemoryScimGroupRepository(users);
    private final InMemoryGroupPermissions groupPermissions = InMemoryGroupPermissions.over(groups);
    private final RecordingAuditTrail audit = new RecordingAuditTrail();

    private final AdminGroupController controller = new AdminGroupController(
            new IdentityAdministrationService(users, groups, null, audit, groupPermissions,
                    new InMemoryScimQueryRepository(users, groups)),
            new GroupPermissionAdministrationService(
                    groups,
                    new InMemoryScimWriteLocks(),
                    groupPermissions,
                    groupPermissions,
                    new SessionRevocationService(new InMemoryAccountSessions(),
                            new PendingCommit(), audit, new RecordingOperationalAlerts()),
                    audit,
                    new AuthorizationChangeMetrics(new SimpleMeterRegistry())));

    private final ScimGroup auditors = groups.create(ScimIdentities.group("Auditors"));

    @Test
    void anArrayOfNamesIsTheSetTheGroupNowCarries() {
        GroupSummary changed = controller.setPermissions(
                auditors.id(), body("[\"user:read\",\"audit:read\"]"), signedInAsSuperuser());

        assertThat(changed).isEqualTo(new GroupSummary(
                auditors.id(), "Auditors", 0, false, List.of("audit:read", "user:read")));
    }

    @Test
    void anEmptyArrayClearsTheGroup() {
        groupPermissions.carrying(auditors.id(), Permission.AUDIT_READ);

        assertThat(controller.setPermissions(auditors.id(), body("[]"), signedInAsSuperuser())
                .permissions()).isEmpty();
    }

    /** A missing body, one that is not an array, and an element that is not a name. */
    @Test
    void aBodyThatIsNotAnArrayOfNamesReachesTheUseCaseAsInvalid() {
        for (JsonNode refused : new JsonNode[] {null, body("{\"permissions\":[]}"),
                body("\"audit:read\""), body("[\"audit:read\",7]"), body("[[\"audit:read\"]]"),
                body("[null]")}) {
            assertThatExceptionOfType(GroupPermissionsChangeRefusedException.class)
                    .as("%s", refused)
                    .isThrownBy(() -> controller.setPermissions(
                            auditors.id(), refused, signedInAsSuperuser()))
                    .satisfies(invalid -> assertThat(List.of(
                                    invalid.reason(), invalid.actorId(), invalid.groupId()))
                            .containsExactly(
                                    GroupPermissionsChangeRefusedException.Reason.INVALID_PERMISSIONS,
                                    SUPERUSER, auditors.id()));
        }
        assertThat(audit.events()).isEmpty();
        assertThat(carried()).isEmpty();
    }

    @Test
    void aSessionThatNamesNobodyChangesNothing() {
        assertThatExceptionOfType(UnknownSessionIdentityException.class)
                .isThrownBy(() -> controller.setPermissions(
                        auditors.id(), body("[\"audit:read\"]"), new MockHttpServletRequest()));
        assertThat(carried()).isEmpty();
        assertThat(audit.events()).isEmpty();
    }

    /** The Groups projection: each Group with its direct member count, counted. */
    @Test
    void theGroupsAreListedWithTheirMemberCounts() {
        ScimUser ada = users.given(ScimIdentities.user("ada"));
        ScimGroup nurses = groups.create(ScimIdentities.group("Nurses", ada));

        assertThat(controller.listGroups()).containsExactly(
                new GroupSummary(auditors.id(), "Auditors", 0, false, List.of()),
                new GroupSummary(nurses.id(), "Nurses", 1, false, List.of()));
    }

    /** A Group's direct members, from the one-based {@code startIndex} for {@code count}. */
    @Test
    void aGroupsMembersAreAnsweredFromTheStartIndexForTheCount() {
        ScimUser ada = users.given(ScimIdentities.user("ada"));
        ScimUser bea = users.given(ScimIdentities.user("bea"));
        ScimUser cy = users.given(ScimIdentities.user("cy"));
        ScimGroup nurses = groups.create(ScimIdentities.group("Nurses", cy, ada, bea));

        assertThat(controller.members(nurses.id(), 2, 1)).isEqualTo(new GroupMemberPage(3, 2,
                List.of(new GroupMemberPage.Member(bea.id(), "bea", null, true))));
    }

    /**
     * Paged as SCIM pages: no parameters is the first page at the default size, and an index
     * below one and a count past the cap are coerced rather than refused.
     */
    @Test
    void absentAndOutOfRangePagingIsCoercedAsScimCoercesIt() {
        ScimUser ada = users.given(ScimIdentities.user("ada"));
        ScimGroup nurses = groups.create(ScimIdentities.group("Nurses", ada));
        GroupMemberPage firstPage = new GroupMemberPage(1, 1,
                List.of(new GroupMemberPage.Member(ada.id(), "ada", null, true)));

        assertThat(List.of(controller.members(nurses.id(), null, null),
                        controller.members(nurses.id(), -4, 10_000),
                        controller.members(nurses.id(), 1, -1)))
                .containsExactly(firstPage, firstPage, new GroupMemberPage(1, 1, List.of()));
    }

    /** No Group by that id is a {@code 404}, which names nothing. */
    @Test
    void theMembersOfAGroupThatIsNotThereAreNotFound() {
        assertThatExceptionOfType(ResponseStatusException.class)
                .isThrownBy(() -> controller.members(UUID.randomUUID(), null, null))
                .satisfies(notFound -> assertThat(List.of(notFound.getStatusCode(),
                                String.valueOf(notFound.getReason())))
                        .containsExactly(HttpStatus.NOT_FOUND, "null"));
    }

    private Set<Permission> carried() {
        return groupPermissions.carriedBy(List.of(auditors.id()))
                .getOrDefault(auditors.id(), Set.of());
    }

    private static JsonNode body(String json) {
        return JSON.readTree(json);
    }

    private static MockHttpServletRequest signedInAsSuperuser() {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(
                FindByIndexNameSessionRepository.PRINCIPAL_NAME_INDEX_NAME, SUPERUSER.toString());
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setSession(session);
        return request;
    }
}

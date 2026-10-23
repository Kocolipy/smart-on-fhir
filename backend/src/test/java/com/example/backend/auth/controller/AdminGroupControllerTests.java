package com.example.backend.auth.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import com.example.backend.audit.RecordingAuditTrail;
import com.example.backend.audit.RecordingOperationalAlerts;
import com.example.backend.auth.InMemoryAccountSessions;
import com.example.backend.auth.PendingCommit;
import com.example.backend.auth.application.GroupPermissionAdministrationService;
import com.example.backend.auth.application.GroupPermissionsChangeRefusedException;
import com.example.backend.auth.application.GroupSummary;
import com.example.backend.auth.application.SessionRevocationService;
import com.example.backend.auth.application.UnknownSessionIdentityException;
import com.example.backend.authorization.config.AuthorizationChangeMetrics;
import com.example.backend.authorization.domain.Permission;
import com.example.backend.scim.InMemoryGroupPermissions;
import com.example.backend.scim.InMemoryScimGroupRepository;
import com.example.backend.scim.InMemoryScimUserRepository;
import com.example.backend.scim.InMemoryScimWriteLocks;
import com.example.backend.scim.ScimIdentities;
import com.example.backend.scim.domain.ScimGroup;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.session.FindByIndexNameSessionRepository;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The Superuser's write of a Group's Permissions as its adapter reads the body, over the real use
 * case and in-memory ports: which bodies reach the use case as a set of names, and which as the
 * refusal of one. How each refusal is answered, logged and audited is the administrative edge's
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

    private final AdminGroupController controller = new AdminGroupController(null,
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

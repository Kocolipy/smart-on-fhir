package com.example.backend.auth.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import com.example.backend.auth.application.GroupSummary;
import com.example.backend.auth.application.IdentityAdministrationService;
import com.example.backend.auth.application.IdentitySummary;
import com.example.backend.auth.application.UnknownSessionIdentityException;
import com.example.backend.scim.domain.LockCause;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.RecordComponent;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.session.FindByIndexNameSessionRepository;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;

class AdminAccountControllerTests {

    private static final Instant CREATED_AT = Instant.parse("2026-01-02T03:04:05Z");

    private static final UUID ADA = UUID.fromString("00000000-0000-0000-0000-00000000ada0");
    private static final UUID BOB = UUID.fromString("00000000-0000-0000-0000-0000000000b0");
    private static final UUID ENG = UUID.fromString("00000000-0000-0000-0000-000000000e00");

    /** A request in Ada's signed-in session, whose principal index holds her stable id. */
    private final MockHttpServletRequest signedInAsAda = requestInSessionOf(ADA);

    @Test
    void listsEveryAccountTheServiceReports() {
        AdminAccountController controller = new AdminAccountController(new RecordingService(List.of(
                summary(ADA, "ada", true, false),
                summary(BOB, "bob", false, true))));

        List<IdentitySummary> response = controller.listAccounts();

        assertThat(response).containsExactly(
                summary(ADA, "ada", true, false),
                summary(BOB, "bob", false, true));
    }

    @Test
    void listsNothingWhenNoAccountExists() {
        AdminAccountController controller = new AdminAccountController(new RecordingService(List.of()));

        assertThat(controller.listAccounts()).isEmpty();
    }

    /**
     * The target reaches the service by its stable id, and so does the acting administrator: the
     * one its signed-in session holds, not the request's and not the principal's {@code userName}
     * — which is what lets the service refuse an administrator acting on themselves whatever the
     * directory has renamed since, and what the body cannot spoof.
     */
    @Test
    void unlockingAndForcingAChangeReachTheirOperationsByStableId() {
        RecordingService service = new RecordingService(List.of());
        AdminAccountController controller = new AdminAccountController(service);

        assertThat(controller.unlock(BOB, signedInAsAda))
                .isEqualTo(summary(BOB, "bob", false, false));
        assertThat(controller.forcePasswordChange(BOB, signedInAsAda))
                .isEqualTo(flagged(BOB, "bob"));

        assertThat(service.calls).containsExactly(
                "unlock:" + BOB + ":" + ADA, "force-password-change:" + BOB + ":" + ADA);
    }

    /** A session that identifies nobody cannot act: the operation never reaches the service. */
    @Test
    void anUnlockFromASessionIdentifyingNobodyIsRefused() {
        RecordingService service = new RecordingService(List.of());
        AdminAccountController controller = new AdminAccountController(service);
        MockHttpServletRequest unindexed = new MockHttpServletRequest();
        unindexed.setSession(new MockHttpSession());

        assertThatExceptionOfType(UnknownSessionIdentityException.class)
                .isThrownBy(() -> controller.unlock(BOB, unindexed));
    }

    @Test
    void aForcedChangeWithoutASessionIsRefused() {
        RecordingService service = new RecordingService(List.of());
        AdminAccountController controller = new AdminAccountController(service);

        assertThatExceptionOfType(UnknownSessionIdentityException.class)
                .isThrownBy(() -> controller.forcePasswordChange(BOB, new MockHttpServletRequest()));
    }

    @Test
    void aRefusedSessionNeverReachesTheService() {
        RecordingService service = new RecordingService(List.of());
        AdminAccountController controller = new AdminAccountController(service);

        assertThatExceptionOfType(UnknownSessionIdentityException.class)
                .isThrownBy(() -> controller.unlock(BOB, new MockHttpServletRequest()));

        assertThat(service.calls).isEmpty();
    }

    private static MockHttpServletRequest requestInSessionOf(UUID owner) {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(
                FindByIndexNameSessionRepository.PRINCIPAL_NAME_INDEX_NAME, owner.toString());
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setSession(session);
        return request;
    }

    @Test
    void theGroupsAdapterListsWhatTheServiceReports() {
        GroupSummary engineering =
                new GroupSummary(ENG, "Engineering", 3, false, List.of("ops:read"));
        AdminGroupController controller =
                new AdminGroupController(new RecordingService(List.of(), List.of(engineering)));

        assertThat(controller.listGroups()).containsExactly(engineering);
    }

    /**
     * The read-only criterion, as the adapters declare it: the only unsafe handlers are Unlock and
     * the forced change, both of which write application-owned state, and the Groups adapter has
     * none at all. A handler added for Disable, a rename or a membership change fails here.
     */
    @Test
    void noHandlerWritesADirectoryOwnedAttribute() {
        assertThat(unsafeHandlers(AdminAccountController.class))
                .containsExactlyInAnyOrder("unlock", "forcePasswordChange");
        assertThat(unsafeHandlers(AdminGroupController.class)).isEmpty();
        assertThat(unsafeHandlers(AdminPermissionController.class)).isEmpty();
    }

    /**
     * What a Group may carry is every delegatable Permission, by name and sorted: never the
     * baseline {@code patient:read}, and never a token-only write half (ADR 0015).
     */
    @Test
    void thePermissionsAdapterListsWhatAGroupMayCarry() {
        assertThat(new AdminPermissionController().listDelegatablePermissions())
                .containsExactly("account:unlock", "audit:read", "connector:read", "group:read",
                        "ops:read", "user:read");
    }

    /**
     * The guarantee the whole endpoint exists to respect. It is asserted here, on
     * the adapter that publishes the type, because this is where a field reaching
     * a client becomes a disclosure: a hash added to {@link IdentitySummary} fails
     * here rather than in review.
     *
     * <p>{@code hasPassword} is a boolean saying whether a credential is set at
     * all, which is not the credential; the component that could carry one is
     * absent, and that is what this pins.
     */
    @Test
    void theResponseShapeHasNoFieldThatCouldCarryACredential() {
        assertThat(IdentitySummary.class.getRecordComponents())
                .extracting(RecordComponent::getName)
                .containsExactly(
                        "id", "userName", "displayName", "admin", "bootstrapAdmin", "active",
                        "locked", "lockCause", "hasPassword", "passwordChangeRequired",
                        "lastAuthenticatedAt", "createdAt", "groups");
        assertThat(IdentitySummary.DirectGroup.class.getRecordComponents())
                .extracting(RecordComponent::getName)
                .containsExactly("id", "displayName");
        assertThat(GroupSummary.class.getRecordComponents())
                .extracting(RecordComponent::getName)
                .containsExactly("id", "displayName", "memberCount", "adminGroup", "permissions");
    }

    private static List<String> unsafeHandlers(Class<?> controller) {
        return Arrays.stream(controller.getDeclaredMethods())
                .filter(method -> Modifier.isPublic(method.getModifiers()))
                .filter(AdminAccountControllerTests::isUnsafeHandler)
                .map(Method::getName)
                .toList();
    }

    private static boolean isUnsafeHandler(Method method) {
        return method.isAnnotationPresent(PostMapping.class)
                || method.isAnnotationPresent(PutMapping.class)
                || method.isAnnotationPresent(PatchMapping.class)
                || method.isAnnotationPresent(DeleteMapping.class)
                || method.isAnnotationPresent(
                        org.springframework.web.bind.annotation.RequestMapping.class);
    }

    private static IdentitySummary summary(UUID id, String userName, boolean admin, boolean locked) {
        return new IdentitySummary(id, userName, null, admin, false, true, locked,
                locked ? LockCause.DORMANCY : null, true, false, null, CREATED_AT, List.of());
    }

    private static IdentitySummary flagged(UUID id, String userName) {
        return new IdentitySummary(id, userName, null, false, false, true, false, null, true, true,
                null, CREATED_AT, List.of());
    }

    private static final class RecordingService extends IdentityAdministrationService {

        private final List<IdentitySummary> summaries;
        private final List<GroupSummary> groupSummaries;
        private final List<String> calls = new java.util.ArrayList<>();

        RecordingService(List<IdentitySummary> summaries) {
            this(summaries, List.of());
        }

        RecordingService(List<IdentitySummary> summaries, List<GroupSummary> groupSummaries) {
            super(null, null, null, null, null, null);
            this.summaries = summaries;
            this.groupSummaries = groupSummaries;
        }

        @Override
        public List<IdentitySummary> listIdentities() {
            return summaries;
        }

        @Override
        public List<GroupSummary> listGroups() {
            return groupSummaries;
        }

        @Override
        public IdentitySummary unlock(UUID userId, UUID actorId) {
            calls.add("unlock:" + userId + ":" + actorId);
            return summary(userId, "bob", false, false);
        }

        @Override
        public IdentitySummary forcePasswordChange(UUID userId, UUID actorId) {
            calls.add("force-password-change:" + userId + ":" + actorId);
            return flagged(userId, "bob");
        }
    }
}

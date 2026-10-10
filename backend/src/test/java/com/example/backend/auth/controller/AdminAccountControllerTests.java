package com.example.backend.auth.controller;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.backend.auth.application.GroupSummary;
import com.example.backend.auth.application.IdentityAdministrationService;
import com.example.backend.auth.application.IdentitySummary;
import com.example.backend.scim.domain.LockCause;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.RecordComponent;
import java.security.Principal;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;

class AdminAccountControllerTests {

    private static final Instant CREATED_AT = Instant.parse("2026-01-02T03:04:05Z");

    private static final UUID ADA = UUID.fromString("00000000-0000-0000-0000-00000000ada0");
    private static final UUID BOB = UUID.fromString("00000000-0000-0000-0000-0000000000b0");
    private static final UUID ENG = UUID.fromString("00000000-0000-0000-0000-000000000e00");

    private final Principal principal = () -> "ada";

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
     * The target reaches the service by its stable id, and the caller's own name from the
     * authenticated principal rather than from the request — which is what lets the service refuse
     * an administrator acting on themselves, and what the body cannot spoof.
     */
    @Test
    void unlockingAndForcingAChangeReachTheirOperationsByStableId() {
        RecordingService service = new RecordingService(List.of());
        AdminAccountController controller = new AdminAccountController(service);

        assertThat(controller.unlock(BOB, principal)).isEqualTo(summary(BOB, "bob", false, false));
        assertThat(controller.forcePasswordChange(BOB, principal))
                .isEqualTo(flagged(BOB, "bob"));

        assertThat(service.calls).containsExactly(
                "unlock:" + BOB + ":ada", "force-password-change:" + BOB + ":ada");
    }

    @Test
    void theGroupsAdapterListsWhatTheServiceReports() {
        GroupSummary engineering = new GroupSummary(ENG, "Engineering", 3, false);
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
                .containsExactly("id", "displayName", "memberCount", "adminGroup");
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
            super(null, null, null, null, null);
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
        public IdentitySummary unlock(UUID userId, String requestedBy) {
            calls.add("unlock:" + userId + ":" + requestedBy);
            return summary(userId, "bob", false, false);
        }

        @Override
        public IdentitySummary forcePasswordChange(UUID userId, String requestedBy) {
            calls.add("force-password-change:" + userId + ":" + requestedBy);
            return flagged(userId, "bob");
        }
    }
}

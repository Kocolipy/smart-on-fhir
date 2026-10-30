package com.example.backend.auth.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import com.example.backend.auth.application.GroupPage;
import com.example.backend.auth.application.GroupSummary;
import com.example.backend.auth.application.IdentityAdministrationService;
import com.example.backend.auth.application.IdentityPage;
import com.example.backend.auth.application.IdentitySummary;
import com.example.backend.auth.application.UnknownSessionIdentityException;
import com.example.backend.scim.domain.InvalidScimQueryException;
import com.example.backend.scim.domain.LockCause;
import com.example.backend.scim.domain.ScimPageRequest;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.RecordComponent;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.session.FindByIndexNameSessionRepository;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.server.ResponseStatusException;

class AdminAccountControllerTests {

    private static final Instant CREATED_AT = Instant.parse("2026-01-02T03:04:05Z");

    private static final UUID ADA = UUID.fromString("00000000-0000-0000-0000-00000000ada0");
    private static final UUID BOB = UUID.fromString("00000000-0000-0000-0000-0000000000b0");
    private static final UUID ENG = UUID.fromString("00000000-0000-0000-0000-000000000e00");

    /** A {@code sortBy} the recording service refuses, as SCIM's sort refuses one. */
    private static final String REFUSED_SORT = "password";

    /** A request in Ada's signed-in session, whose principal index holds her stable id. */
    private final MockHttpServletRequest signedInAsAda = requestInSessionOf(ADA);

    @Test
    void listsThePageOfAccountsTheServiceReports() {
        AdminAccountController controller = new AdminAccountController(new RecordingService(List.of(
                summary(ADA, "ada", true, false),
                summary(BOB, "bob", false, true))));

        IdentityPage response = controller.listAccounts(null, null, null, null, null);

        assertThat(response).isEqualTo(new IdentityPage(2, 1, List.of(
                summary(ADA, "ada", true, false),
                summary(BOB, "bob", false, true))));
    }

    /**
     * The search reaches the service as sent, and the page as SCIM coerces it: {@code startIndex}
     * one-based and {@code count} capped — the same {@link ScimPageRequest#of} the member read and
     * SCIM itself resolve their paging through.
     */
    @Test
    void theSearchAndTheCoercedPageReachTheService() {
        RecordingService service = new RecordingService(List.of());
        AdminAccountController controller = new AdminAccountController(service);

        controller.listAccounts("  Ada ", null, null, 0, 500);
        controller.listAccounts(null, null, null, 21, 20);

        assertThat(service.calls).containsExactly(
                "list:  Ada :null:null:" + new ScimPageRequest(1, 200),
                "list:null:null:null:" + new ScimPageRequest(21, 20));
    }

    /**
     * {@code sortBy} and {@code sortOrder} reach each listing's service as sent, for SCIM's sort
     * to read: the adapter neither checks nor rewrites them.
     */
    @Test
    void theSortReachesEachListingAsSent() {
        RecordingService service = new RecordingService(List.of());

        new AdminAccountController(service).listAccounts("ada", "displayName", "Descending", 1, 20);
        new AdminGroupController(service, null).listGroups(null, "displayName", null, null, null);

        assertThat(service.calls).containsExactly(
                "list:ada:displayName:Descending:" + new ScimPageRequest(1, 20),
                "groups:null:displayName:null:" + ScimPageRequest.of(null, null));
    }

    /**
     * A sort the listing refuses is the caller's error: a {@code 400}, as a malformed
     * {@code startIndex} is, carrying the refusal as its cause, so the refusal record names why.
     */
    @Test
    void aSortTheListingRefusesIsABadRequest() {
        AdminAccountController controller =
                new AdminAccountController(new RecordingService(List.of()));

        assertThatExceptionOfType(ResponseStatusException.class)
                .isThrownBy(() -> controller.listAccounts(null, REFUSED_SORT, null, null, null))
                .satisfies(refused -> assertThat(List.of(
                                refused.getStatusCode(), refused.getCause().getClass()))
                        .containsExactly(HttpStatus.BAD_REQUEST, InvalidScimQueryException.class));
    }

    /**
     * The target reaches the service by its stable id, and so does the acting administrator: the
     * one its signed-in session holds, not the request's and not the principal's {@code userName}
     * — which is what lets the service refuse an administrator acting on themselves whatever the
     * directory has renamed since, and what the body cannot spoof.
     */
    @Test
    void unlockingReachesItsOperationByStableId() {
        RecordingService service = new RecordingService(List.of());
        AdminAccountController controller = new AdminAccountController(service);

        assertThat(controller.unlock(BOB, signedInAsAda))
                .isEqualTo(summary(BOB, "bob", false, false));

        assertThat(service.calls).containsExactly("unlock:" + BOB + ":" + ADA);
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
        // No Group Permissions service: a listing never reaches one.
        AdminGroupController controller = new AdminGroupController(
                new RecordingService(List.of(), List.of(engineering)), null);

        assertThat(controller.listGroups("eng", null, null, 1, 20))
                .isEqualTo(new GroupPage(1, 1, List.of(engineering)));
    }

    /**
     * The read-only criterion, as the adapters declare it: the only unsafe handlers are Unlock and
     * setting a Group's Permissions — each of which writes application-owned state (ADR 0015). A
     * handler added that sets {@code active}, renames a User or changes a membership fails here.
     */
    @Test
    void noHandlerWritesADirectoryOwnedAttribute() {
        assertThat(unsafeHandlers(AdminAccountController.class))
                .containsExactly("unlock");
        assertThat(unsafeHandlers(AdminGroupController.class)).containsExactly("setPermissions");
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
        // A page wraps its rows and adds only where it sits and how many there are.
        assertThat(IdentityPage.class.getRecordComponents())
                .extracting(RecordComponent::getName)
                .containsExactly("totalResults", "startIndex", "users");
        assertThat(GroupPage.class.getRecordComponents())
                .extracting(RecordComponent::getName)
                .containsExactly("totalResults", "startIndex", "groups");
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

        /** Refuses {@link #REFUSED_SORT} as SCIM's sort refuses a path it cannot sort by. */
        @Override
        public IdentityPage listIdentities(
                String search, String sortBy, String sortOrder, ScimPageRequest page) {
            if (REFUSED_SORT.equals(sortBy)) {
                throw new InvalidScimQueryException("sortBy does not name a sortable attribute");
            }
            calls.add("list:" + search + ":" + sortBy + ":" + sortOrder + ":" + page);
            return new IdentityPage(summaries.size(), page.startIndex(), summaries);
        }

        @Override
        public GroupPage listGroups(
                String search, String sortBy, String sortOrder, ScimPageRequest page) {
            calls.add("groups:" + search + ":" + sortBy + ":" + sortOrder + ":" + page);
            return new GroupPage(groupSummaries.size(), page.startIndex(), groupSummaries);
        }

        @Override
        public IdentitySummary unlock(UUID userId, UUID actorId) {
            calls.add("unlock:" + userId + ":" + actorId);
            return summary(userId, "bob", false, false);
        }
    }
}

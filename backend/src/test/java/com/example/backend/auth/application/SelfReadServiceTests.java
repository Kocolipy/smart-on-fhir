package com.example.backend.auth.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.backend.scim.InMemoryScimGroupRepository;
import com.example.backend.scim.InMemoryScimUserRepository;
import com.example.backend.scim.ScimIdentities;
import com.example.backend.scim.domain.ReservedResourceName;
import com.example.backend.scim.domain.ScimGroup;
import com.example.backend.scim.domain.ScimLoginState;
import com.example.backend.scim.domain.ScimUser;
import com.example.backend.scim.domain.ScimUserProfile;
import java.lang.reflect.RecordComponent;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The self-read's use case: the record of the User a stable id names, every component read from
 * the directory, and nothing about the lock or the failure run.
 */
class SelfReadServiceTests {

    private static final Instant LAST_LOGIN = Instant.parse("2026-03-04T05:06:07Z");
    private static final Instant FLAGGED_AT = Instant.parse("2026-03-05T00:00:00Z");

    private final InMemoryScimUserRepository users = new InMemoryScimUserRepository();
    private final InMemoryScimGroupRepository groups = new InMemoryScimGroupRepository(users);

    private final SelfReadService service = new SelfReadService(users, groups);

    @Test
    void reportsEveryComponentOfTheUserTheIdNames() {
        ScimUser ada = users.given(user("ada", "Ada Lovelace",
                new ScimLoginState("hash", 0, null, LAST_LOGIN, FLAGGED_AT)));
        ScimGroup engineers = groups.given(ScimIdentities.group("Engineers", ada));

        SelfRecord record = service.read(ada.id());

        assertThat(record.id()).isEqualTo(ada.id());
        assertThat(record.userName()).isEqualTo("ada");
        assertThat(record.displayName()).isEqualTo("Ada Lovelace");
        assertThat(record.groups())
                .containsExactly(new SelfRecord.Group(engineers.id(), "Engineers"));
        assertThat(record.passwordChangeRequired()).isTrue();
        assertThat(record.lastAuthenticatedAt()).isEqualTo(LAST_LOGIN);
    }

    /**
     * A User with neither a display name, a Group, a login nor a pending change: each absence is
     * reported as one, not as a default borrowed from somewhere else.
     */
    @Test
    void reportsAbsencesAsAbsences() {
        ScimUser fresh = users.given(ScimIdentities.user("fresh"));

        SelfRecord record = service.read(fresh.id());

        assertThat(record.userName()).isEqualTo("fresh");
        assertThat(record.displayName()).isNull();
        assertThat(record.groups()).isEmpty();
        assertThat(record.passwordChangeRequired()).isFalse();
        assertThat(record.lastAuthenticatedAt()).isNull();
    }

    /** Only the named User's memberships, and the reserved Admin group like any other. */
    @Test
    void reportsOnlyTheDirectGroupsOfTheNamedUser() {
        ScimUser ada = users.given(ScimIdentities.user("ada"));
        ScimUser bob = users.given(ScimIdentities.user("bob"));
        ScimGroup admins = groups.createReserved(
                ScimIdentities.group("Admins", ada), ReservedResourceName.ADMIN_GROUP);
        ScimGroup shared = groups.given(ScimIdentities.group("Shared", ada, bob));
        groups.given(ScimIdentities.group("BobOnly", bob));

        assertThat(service.read(ada.id()).groups())
                .extracting(SelfRecord.Group::id)
                .containsExactlyInAnyOrder(admins.id(), shared.id());
        assertThat(service.read(bob.id()).groups())
                .extracting(SelfRecord.Group::displayName)
                .containsExactlyInAnyOrder("Shared", "BobOnly");
    }

    /** Two Users, two records: nothing of one appears in the other's. */
    @Test
    void twoUsersEachReceiveTheirOwnRecord() {
        ScimUser ada = users.given(user("ada", "Ada", ScimLoginState.of("hash")));
        ScimUser bob = users.given(user("bob", "Bob",
                new ScimLoginState("hash", 0, null, LAST_LOGIN, null)));

        SelfRecord adas = service.read(ada.id());
        SelfRecord bobs = service.read(bob.id());

        assertThat(adas.id()).isEqualTo(ada.id());
        assertThat(adas.userName()).isEqualTo("ada");
        assertThat(adas.lastAuthenticatedAt()).isNull();
        assertThat(bobs.id()).isEqualTo(bob.id());
        assertThat(bobs.userName()).isEqualTo("bob");
        assertThat(bobs.lastAuthenticatedAt()).isEqualTo(LAST_LOGIN);
        assertThat(adas).isNotEqualTo(bobs);
    }

    @Test
    void anIdNamingNoLiveUserIsRefused() {
        users.given(ScimIdentities.user("someone"));

        assertThatThrownBy(() -> service.read(UUID.randomUUID()))
                .isInstanceOf(UnknownSessionIdentityException.class);
    }

    /**
     * The whole published shape, by component name: the exact set the ticket names, so any
     * component added — a failure count or a lock under whatever name — fails here as well as in
     * {@code ArchitectureTest}.
     */
    @Test
    void theRecordHasExactlyTheSpecifiedComponents() {
        assertThat(Arrays.stream(SelfRecord.class.getRecordComponents())
                        .map(RecordComponent::getName))
                .containsExactly("id", "userName", "displayName", "groups",
                        "passwordChangeRequired", "lastAuthenticatedAt");
        assertThat(Arrays.stream(SelfRecord.Group.class.getRecordComponents())
                        .map(RecordComponent::getName))
                .containsExactly("id", "displayName");
    }

    /** A caller's list cannot change the record after it is built. */
    @Test
    void theGroupsAreCopiedOnConstruction() {
        List<SelfRecord.Group> mutable = new ArrayList<>();
        SelfRecord record = new SelfRecord(UUID.randomUUID(), "u", null, mutable, false, null);

        mutable.add(new SelfRecord.Group(UUID.randomUUID(), "later"));

        assertThat(record.groups()).isEmpty();
    }

    private static ScimUser user(String userName, String displayName, ScimLoginState login) {
        return new ScimUser(
                UUID.randomUUID(),
                new ScimUserProfile(userName, null, displayName, null, null, null, true, List.of()),
                login,
                null,
                ScimUser.INITIAL_VERSION,
                ScimIdentities.NOW,
                ScimIdentities.NOW);
    }
}

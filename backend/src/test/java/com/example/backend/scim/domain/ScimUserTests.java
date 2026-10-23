package com.example.backend.scim.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** The directory's own User record: its invariants, and the credential it may not have. */
class ScimUserTests {

    private static final UUID ID = UUID.fromString("8a5c1f4e-0000-4000-8000-000000000001");
    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");

    private static ScimUserProfile profile() {
        return new ScimUserProfile(
                "bjensen", ScimName.NONE, null, null, null, null, true, List.of());
    }

    /** An unreserved User at the given version, with a credential and no login history. */
    private static ScimUser user(UUID id, ScimUserProfile profile, String hash, long version) {
        return new ScimUser(id, profile, ScimLoginState.of(hash), null, version, NOW, NOW);
    }

    @Test
    void a_created_user_starts_at_version_one_with_one_timestamp_for_both_fields() {
        ScimUser user = ScimUser.created(ID, profile(), "hash", NOW);

        assertThat(user.version()).isEqualTo(ScimUser.INITIAL_VERSION);
        assertThat(user.version()).isEqualTo(1L);
        assertThat(user.createdAt()).isEqualTo(NOW);
        assertThat(user.lastModifiedAt()).isEqualTo(NOW);
        assertThat(user.id()).isEqualTo(ID);
    }

    @Test
    void a_user_without_a_stable_id_is_refused() {
        assertThatThrownBy(() -> user(null, profile(), "hash", 1L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("stable id");
    }

    @Test
    void a_user_without_a_profile_is_refused() {
        assertThatThrownBy(() -> user(ID, null, "hash", 1L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("has a profile");
    }

    /**
     * The authentication state is a component now, not an optional one: a User with no
     * {@link ScimLoginState} at all is a different thing from a credentialless User, whose
     * state is {@link ScimLoginState#CREDENTIALLESS}.
     */
    @Test
    void a_user_without_an_authentication_state_is_refused() {
        assertThatThrownBy(() -> new ScimUser(ID, profile(), null, null, 1L, NOW, NOW))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("authentication state");
    }

    @Test
    void a_version_below_one_is_refused_because_every_live_resource_has_an_etag() {
        assertThatThrownBy(() -> user(ID, profile(), "hash", 0L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("starts at 1");
        assertThatThrownBy(() -> user(ID, profile(), "hash", -1L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("starts at 1");
    }

    @Test
    void version_one_is_accepted_as_the_first_live_version() {
        assertThat(user(ID, profile(), "hash", 1L).version()).isEqualTo(1L);
    }

    /**
     * The credentialless User is a supported state, not an incomplete one: SCIM allows a
     * create with no {@code password}, and such a User exists and can be read while being
     * unable to authenticate. {@code hasPassword} is how that is answered without the hash
     * leaving the slice — asked of the login state, which is where the credential lives.
     */
    @Test
    void a_user_with_a_hash_has_a_password() {
        assertThat(ScimUser.created(ID, profile(), "hash", NOW).login().hasPassword()).isTrue();
    }

    @Test
    void a_credentialless_user_has_no_password() {
        assertThat(ScimUser.created(ID, profile(), null, NOW).login().hasPassword()).isFalse();
    }

    /** A created User has no login history, whatever it is later asked about that state. */
    @Test
    void a_created_user_has_no_login_history() {
        ScimUser user = ScimUser.created(ID, profile(), "hash", NOW);

        assertThat(user.login().failedLoginAttempts()).isZero();
        assertThat(user.login().isLocked()).isFalse();
    }

    @Test
    void the_uniqueness_form_of_the_user_name_comes_from_the_profile() {
        ScimUser user = ScimUser.created(ID, profile(), null, NOW);

        assertThat(user.profile().normalizedUserName()).isEqualTo(profile().normalizedUserName());
        assertThat(user.profile().normalizedUserName())
                .isEqualTo(NormalizedUserName.of("BJensen"));
    }

    /**
     * A created User is unreserved, and that is the guarantee the factory exists to make: no
     * provisioning path can mint a resource that protects itself from being changed.
     */
    @Test
    void a_created_user_is_neither_write_protected_nor_lockout_exempt() {
        ScimUser user = ScimUser.created(ID, profile(), "hash", NOW);

        assertThat(user.reservedName()).isNull();
        assertThat(user.isProtectedFromWrites()).isFalse();
        assertThat(user.isExemptFromLockout()).isFalse();
    }

    /**
     * Both reservations protect a User from SCIM writes, but only the Bootstrap Admin is
     * exempt from lockout — the two rules are about different things and are asserted apart
     * so a future third reservation cannot silently acquire the exemption.
     */
    @Test
    void a_reserved_user_is_write_protected_and_only_the_bootstrap_admin_is_lockout_exempt() {
        ScimUser bootstrap = new ScimUser(
                ID,
                profile(),
                ScimLoginState.of("hash"),
                ReservedResourceName.BOOTSTRAP_ADMIN,
                1L,
                NOW,
                NOW);
        ScimUser otherwiseReserved = new ScimUser(
                ID,
                profile(),
                ScimLoginState.of("hash"),
                ReservedResourceName.ADMIN_GROUP,
                1L,
                NOW,
                NOW);

        assertThat(bootstrap.isProtectedFromWrites()).isTrue();
        assertThat(bootstrap.isExemptFromLockout()).isTrue();
        assertThat(otherwiseReserved.isProtectedFromWrites()).isTrue();
        assertThat(otherwiseReserved.isExemptFromLockout()).isFalse();
    }

    // ---- dormancy -------------------------------------------------------------------------

    private static final Instant CREATED = Instant.parse("2026-01-01T00:00:00Z");

    private static ScimUser authenticatedAt(Instant lastAuthenticatedAt, ReservedResourceName r) {
        return new ScimUser(
                ID,
                profile(),
                new ScimLoginState("hash", 0, null, lastAuthenticatedAt),
                r,
                1L,
                CREATED,
                CREATED);
    }

    /** A User that has never authenticated is measured from its creation. */
    @Test
    void a_user_that_never_authenticated_is_measured_from_its_creation() {
        assertThat(authenticatedAt(null, null).dormancyBasis()).isEqualTo(CREATED);
    }

    @Test
    void a_user_that_authenticated_is_measured_from_its_last_authentication() {
        Instant last = CREATED.plusSeconds(86_400);

        assertThat(authenticatedAt(last, null).dormancyBasis()).isEqualTo(last);
    }

    /**
     * Only the Bootstrap Admin is exempt from the dormancy jobs; an unreserved User is not, and
     * neither is a resource carrying some other reservation.
     */
    @Test
    void only_the_bootstrap_admin_is_exempt_from_dormancy() {
        assertThat(authenticatedAt(null, ReservedResourceName.BOOTSTRAP_ADMIN)
                .isExemptFromDormancy()).isTrue();
        assertThat(authenticatedAt(null, null).isExemptFromDormancy()).isFalse();
        assertThat(authenticatedAt(null, ReservedResourceName.ADMIN_GROUP)
                .isExemptFromDormancy()).isFalse();
    }
}

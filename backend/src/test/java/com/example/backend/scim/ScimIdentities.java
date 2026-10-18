package com.example.backend.scim;

import com.example.backend.scim.domain.ScimGroup;
import com.example.backend.scim.domain.ScimGroupMember;
import com.example.backend.scim.domain.ReservedResourceName;
import com.example.backend.scim.domain.ScimLoginState;
import com.example.backend.scim.domain.ScimUser;
import com.example.backend.scim.domain.ScimUserProfile;
import com.example.backend.scim.domain.ScimUserRepository;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

/**
 * Builders for the SCIM identity values tests need, so a test that is about the lockout rule does
 * not spell out seven profile attributes to get there.
 *
 * <p>Deliberately NOT a place to put a reserved resource. {@link ScimUser#created} and
 * {@link ScimGroup#created} both yield an unreserved resource on purpose — a provisioning path
 * cannot mint a protected one — and the reservation is applied by the port's
 * {@code createReserved}. A convenience factory here that produced a reserved value directly
 * would let a test construct something production cannot, which is how a test comes to prove the
 * wrong thing.
 */
public final class ScimIdentities {

    /** A fixed instant, so a test asserting a timestamp has one to compare against. */
    public static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");

    private ScimIdentities() {
    }

    /** An active User with a credential and no login history. */
    public static ScimUser user(String userName) {
        return ScimUser.created(UUID.randomUUID(), profile(userName, true), "hash", NOW);
    }

    /** An active User with no credential — a supported state, which cannot log in. */
    public static ScimUser credentiallessUser(String userName) {
        return ScimUser.created(UUID.randomUUID(), profile(userName, true), null, NOW);
    }

    /** A User an administrator has deactivated. */
    public static ScimUser inactiveUser(String userName) {
        return ScimUser.created(UUID.randomUUID(), profile(userName, false), "hash", NOW);
    }

    /** A User carrying a login history — the shape the lockout rule reasons about. */
    public static ScimUser userWithLoginState(String userName, ScimLoginState login) {
        return new ScimUser(
                UUID.randomUUID(),
                profile(userName, true),
                login,
                null,
                ScimUser.INITIAL_VERSION,
                NOW,
                NOW);
    }

    /**
     * An active, credentialed User created at {@link #NOW} whose last successful login was at
     * {@code at} — the shape the dormancy rules reason about.
     */
    public static ScimUser userAuthenticatedAt(String userName, Instant at) {
        return new ScimUser(
                UUID.randomUUID(),
                profile(userName, true),
                new ScimLoginState("hash", 0, null, at),
                null,
                ScimUser.INITIAL_VERSION,
                NOW,
                NOW);
    }

    /**
     * {@code user} with {@code active} as given and its login state replaced by {@code login} —
     * the value a test hands {@code ScimUserRepository#replace} to arrange a directory state
     * directly. The store keeps what it is given, so a test arranging an active User this way
     * performs no Reactivation: that is the login-state module's, reached through a SCIM write.
     */
    public static ScimUser withActive(ScimUser user, boolean active, ScimLoginState login) {
        ScimUserProfile profile = user.profile();
        return new ScimUser(
                user.id(),
                new ScimUserProfile(profile.userName(), profile.name(), profile.displayName(),
                        profile.preferredLanguage(), profile.locale(), profile.timezone(), active,
                        profile.emails()),
                login,
                user.reservedName(),
                user.version(),
                user.createdAt(),
                user.lastModifiedAt());
    }

    /** {@code user} with {@code displayName} as its display name and everything else as it was. */
    public static ScimUser withDisplayName(ScimUser user, String displayName) {
        ScimUserProfile profile = user.profile();
        return new ScimUser(
                user.id(),
                new ScimUserProfile(profile.userName(), profile.name(), displayName,
                        profile.preferredLanguage(), profile.locale(), profile.timezone(),
                        profile.active(), profile.emails()),
                user.login(),
                user.reservedName(),
                user.version(),
                user.createdAt(),
                user.lastModifiedAt());
    }

    /**
     * Frees {@code reservation} in {@code users} by deleting whoever holds it, as of {@code at}, so
     * a test can claim it through {@code createReserved}: a reservation is held once per store. A
     * store shared with other tests — the seeded Bootstrap Admin holds its reservation there — must
     * undo the deletion afterwards.
     */
    public static void releaseReservation(
            ScimUserRepository users, ReservedResourceName reservation, Instant at) {
        users.findByReservedName(reservation)
                .ifPresent(holder -> users.deleteById(holder.id(), at));
    }

    /** The minimum profile: a userName, an active flag, and nothing else assigned. */
    public static ScimUserProfile profile(String userName, boolean active) {
        return new ScimUserProfile(userName, null, null, null, null, null, active, List.of());
    }

    /** An unreserved Group containing these Users. */
    public static ScimGroup group(String displayName, ScimUser... members) {
        return ScimGroup.created(
                UUID.randomUUID(),
                displayName,
                Arrays.stream(members)
                        .map(member -> ScimGroupMember.reference(member.id()))
                        .toList(),
                NOW);
    }
}

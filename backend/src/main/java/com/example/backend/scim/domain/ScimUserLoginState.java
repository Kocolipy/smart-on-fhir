package com.example.backend.scim.domain;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/**
 * The login state a directory event leaves a User in: a create (a connector's, or the seeded
 * Bootstrap Admin's), a SCIM replacement (PUT and PATCH alike), and the development seed's dormant
 * fixture.
 *
 * <p>A port in the directory's domain for a decision the login surface owns, for the reason
 * {@link ScimUserSessions} is one: the login state's rules — the change-required flag, the
 * dormancy basis, a Reactivation — are the login-state module's, and the dependency between the
 * two slices runs one way, so the SCIM use cases cannot name that module. They name this instead,
 * and the module answers it. Nothing on the directory side decides any of those fields itself:
 * neither the use cases, nor {@link ScimUserRepository}, nor its queries.
 *
 * <p>Every event answers with what it changed, as every transition of the module does: a
 * {@link LoginStateChange}. A create's and a replacement's {@link LoginStateChange#after} is
 * returned rather than written, because it is stored with the profile in the write's one row
 * update ({@link ScimUserRepository#create}, {@link ScimUserRepository#replace}), which stores
 * what it is given. The dormant fixture has no profile write to ride on, so the module writes it
 * itself.
 */
public interface ScimUserLoginState {

    /**
     * The credential a SCIM replacement leaves: its hash — {@code null} when the User is left
     * without one — and whether the connector set it in this write. One value, so a hash and the
     * flag saying where it came from cannot be passed apart or in each other's place.
     *
     * @param passwordHash the credential as the write leaves it, {@code null} when it has none
     * @param connectorSet whether the write set a password the connector supplied
     */
    record Credential(String passwordHash, boolean connectorSet) {

        public Credential {
            if (connectorSet) {
                Objects.requireNonNull(passwordHash, "a set password has a hash");
            }
        }

        /** The write left the stored credential — {@code null} for none — as it was. */
        public static Credential unchanged(String passwordHash) {
            return new Credential(passwordHash, false);
        }

        /** The write removed the credential. */
        public static Credential cleared() {
            return new Credential(null, false);
        }

        /** The write set a password the connector supplied, hashed as {@code passwordHash}. */
        public static Credential set(String passwordHash) {
            return new Credential(passwordHash, true);
        }
    }

    /**
     * The login state of a User created with {@code passwordHash} — {@code null} for a
     * credentialless User — as of {@code now}: by a connector, or as the seeded Bootstrap Admin.
     *
     * @return what the creation decided, from a new User holding the credential and nothing else
     *     to the state its row write stores
     */
    LoginStateChange created(String passwordHash, Instant now);

    /**
     * The login state of {@code stored} after a SCIM replacement as of {@code now} — a PUT or a
     * PATCH, which are one write and so one decision.
     *
     * @param stored     the User as read under its resource lock, before the write
     * @param profile    the profile as the write leaves it
     * @param credential the credential as the write leaves it
     * @param now        the write's instant
     * @return what the replacement decided, from the state {@code stored} holds to the one its row
     *     write stores
     */
    LoginStateChange afterReplacement(
            ScimUser stored, ScimUserProfile profile, Credential credential, Instant now);

    /**
     * Resets the development fixture {@code fixture} to dormant as of {@code now}: its
     * credential back to {@code passwordHash}, and its dormancy basis {@code dormantFor} before
     * {@code now}.
     *
     * @return what the reset moved, and the instant its password change was completed at
     */
    LoginStateChange dormantFixtureReset(
            ScimUser fixture, String passwordHash, Instant now, Duration dormantFor);
}

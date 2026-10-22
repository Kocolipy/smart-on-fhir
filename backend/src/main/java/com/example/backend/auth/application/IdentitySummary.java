package com.example.backend.auth.application;

import com.example.backend.scim.domain.LockCause;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * What an administrator is allowed to know about a User: the Users projection of the Accounts
 * page, one row per User.
 *
 * <p>This exists so that the password hash cannot be exposed by accident rather than by
 * discipline: the web adapter is handed summaries, and a summary has no field the hash could be
 * written into. A future component that must stay private is added to
 * {@link com.example.backend.scim.domain.ScimUser} and simply not mirrored here.
 *
 * <p>It is also the shape the administrative endpoints put on the wire, serialised as-is by
 * {@code AdminAccountController} rather than copied into a response record of the same fields. So
 * a field added here is published: the openapi {@code AccountSummary} schema is this record's
 * documented counterpart.
 *
 * <p>READ-ONLY for everything the directory owns. {@code userName}, {@code displayName},
 * {@code active} and {@code groups} are SCIM attributes a connector writes, and nothing in the
 * administration adapter can change them: the only operation that takes this identity's id is
 * Unlock, and it writes application-owned state. That is why
 * there is no Deactivate or Activate any more — {@code active} is the directory's to decide, and
 * an administrator overriding it here would be overwritten by the next synchronization anyway.
 *
 * <p>{@code id} is what every operation addresses: it is the SCIM resource id, stable and never
 * reassigned, so an administrator acting on a row and a connector renaming the same User cannot
 * end up naming two different identities.
 *
 * <p>{@code admin} is DERIVED from direct membership of the Admin group rather than stored, so a
 * listing showing it is reporting the same fact the login path derives, and there is no column
 * the two could disagree about.
 *
 * <p>There is no field for when a lockout lifts, because a lockout does not lift on its own: it
 * ends when an administrator unlocks the identity. {@code locked} is therefore the whole of the
 * lock state, and the only remaining question about it is whose action will end it.
 *
 * @param id                     the SCIM resource id, stable and non-reassignable
 * @param userName               the login and display attribute, mutable, directory-owned
 * @param displayName            the directory's display name, or {@code null} when it sets none
 * @param admin                  whether the Admin group confers administrative authority on this
 *                               identity
 * @param bootstrapAdmin         whether this is the deployment's reserved recovery identity, which
 *                               can never be locked — so it has no lockout state to report and
 *                               nothing to Unlock
 * @param active                 the directory's {@code active} attribute; read-only here
 * @param locked                 whether a lockout is in force, which stands until Unlock; always
 *                               false for the Bootstrap Admin
 * @param lockCause              why the lockout was imposed — a failure run or dormancy — so an
 *                               operator can tell a forgotten password from an abandoned account
 *                               before unlocking; {@code null} exactly when {@code locked} is false
 * @param hasPassword            whether a credential is set at all; a credentialless User exists
 *                               and cannot log in, which is otherwise indistinguishable from a
 *                               forgotten password
 * @param passwordChangeRequired whether the User must replace its password before it may do
 *                               anything but submit that change or log out
 * @param lastAuthenticatedAt    the last successful login, explicit reactivation or Unlock — the
 *                               dormancy basis — or {@code null} for a User that has had none
 * @param createdAt              when the resource was created
 * @param groups                 the Groups this User is a DIRECT member of, by display name;
 *                               read-only here as it is over SCIM
 */
public record IdentitySummary(
        UUID id,
        String userName,
        String displayName,
        boolean admin,
        boolean bootstrapAdmin,
        boolean active,
        boolean locked,
        LockCause lockCause,
        boolean hasPassword,
        boolean passwordChangeRequired,
        Instant lastAuthenticatedAt,
        Instant createdAt,
        List<DirectGroup> groups) {

    public IdentitySummary {
        groups = groups == null ? List.of() : List.copyOf(groups);
    }

    /**
     * A Group this User belongs to directly: enough to name it and nothing else, so a row never
     * carries every other member of every Group its User is in.
     *
     * @param id          the Group's SCIM resource id
     * @param displayName its display name
     */
    public record DirectGroup(UUID id, String displayName) {
    }
}

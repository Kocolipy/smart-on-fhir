package com.example.backend.scim.domain;

/**
 * A write was aimed at a resource the deployment reserves for its own recovery.
 *
 * <p>The Bootstrap Admin cannot be written to or deleted, the Admin group cannot be renamed
 * or deleted, and the Bootstrap Admin's membership of it cannot be removed. Each of those is
 * this exception, because each is the same refusal for the same reason: the resource exists
 * so that a deployment whose external provisioning has failed — or has removed every
 * SCIM-managed administrator — can still be recovered, and a provisioning system able to
 * remove it could remove exactly that.
 *
 * <p>Not an authorization failure. The caller's token may hold every Permission the write
 * needs; it is the action that is refused, which is why the web adapter renders it as SCIM's
 * {@code mutability} error rather than as a {@code 403}.
 *
 * <p>Raised before anything is written, so a refused write changes nothing — which is what
 * makes "verified by re-reading unchanged" a property of the code rather than of a test's
 * luck.
 *
 * <p>Carries which reservation was hit, because the two have different remedies and an
 * administrator reading the refusal needs to know whether the recovery User or the recovery
 * Group was the target. It carries nothing the caller submitted.
 */
public final class ProtectedResourceException extends ScimRequestRefusedException {

    private final ReservedResourceName reservedName;

    public ProtectedResourceException(ReservedResourceName reservedName) {
        super("this resource is reserved for deployment recovery and cannot be changed");
        this.reservedName = reservedName;
    }

    /** Which reserved resource the write was aimed at. */
    public ReservedResourceName reservedName() {
        return reservedName;
    }
}

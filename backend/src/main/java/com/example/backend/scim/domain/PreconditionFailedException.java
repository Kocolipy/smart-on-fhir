package com.example.backend.scim.domain;

/**
 * The {@code If-Match} precondition named a version that is not the resource's current one —
 * another writer changed it first.
 *
 * <p>Rendered as {@code 412 Precondition Failed}. Raised before anything is computed or
 * written, so the refused write changes nothing and a client can re-read and retry.
 */
public final class PreconditionFailedException extends ScimRequestRefusedException {

    public PreconditionFailedException() {
        super("the If-Match precondition does not name the resource's current version");
    }
}

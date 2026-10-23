package com.example.backend.scim.domain;

import java.util.Optional;

/**
 * The Group Permissions store refused a write, and wrote nothing ({@link GroupPermissionRefusal}).
 *
 * <p>A writer that reports a refusal of its own asks {@link ScimGroupPermissionStore#refusal}
 * first, so this reaching a caller means a writer that did not ask: a defect in that writer, never
 * a request to answer. It names no Permission and no Group, only which refusal it was.
 *
 * <p>A {@link RuntimeException} rather than an {@link IllegalStateException}: the database adapter
 * is a {@code @Repository}, whose exception translation would rewrite the latter into a data-access
 * exception and lose the refusal on its way to the caller.
 */
public final class GroupPermissionsRefusedException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final transient GroupPermissionRefusal refusal;

    public GroupPermissionsRefusedException(GroupPermissionRefusal refusal) {
        super("The Group Permissions store refused the write: "
                + refusal.getClass().getSimpleName());
        this.refusal = refusal;
    }

    /**
     * Throws when the store refused the write, and returns when it did not: the one way each
     * adapter turns {@link ScimGroupPermissionStore#refusal} into a write that refuses anyway.
     */
    public static void refuseIfPresent(Optional<GroupPermissionRefusal> refusal) {
        if (refusal.isPresent()) {
            throw new GroupPermissionsRefusedException(refusal.get());
        }
    }

    /** Which refusal it was. */
    public GroupPermissionRefusal refusal() {
        return refusal;
    }
}

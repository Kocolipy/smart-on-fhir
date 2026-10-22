package com.example.backend.auth.application;

import java.util.Objects;
import java.util.UUID;

/**
 * A change of a Group's Permissions refused before anything was written: which Superuser asked,
 * which Group it addressed, and one generic reason — never which Permission, nor what the body
 * held. The administrative edge answers, logs and audits each reason
 * ({@code auth.controller.AdminRefusals}).
 */
public final class GroupPermissionsRefusedException extends AdministrativeRefusalException {

    /** Why the change was refused, in the order the use case decides them. */
    public enum Reason {

        /**
         * The body is not a set of Permissions a Group may carry: missing, not JSON, not an array
         * of names, or naming something that is not a delegatable Permission. Decided before the
         * Group is locked or looked up.
         */
        INVALID_PERMISSIONS,

        /** No Group has the id addressed, or it was deleted. */
        UNKNOWN_GROUP,

        /**
         * The Admin group, recognised by its reservation marker: its direct members' power is the
         * Superuser, which is not a Permission.
         */
        ADMIN_GROUP
    }

    private final Reason reason;

    private final UUID actorId;

    private final UUID groupId;

    /**
     * @param actorId the Superuser's stable id, as its signed-in session holds it
     * @param groupId the Group the request's path addressed, which may not exist
     */
    public GroupPermissionsRefusedException(Reason reason, UUID actorId, UUID groupId) {
        super("Group Permissions change refused");
        this.reason = reason;
        this.actorId = Objects.requireNonNull(actorId,
                "a refused change of a Group's Permissions names its Superuser");
        this.groupId = Objects.requireNonNull(groupId,
                "a refused change of a Group's Permissions names the Group it addressed");
    }

    public Reason reason() {
        return reason;
    }

    public UUID actorId() {
        return actorId;
    }

    public UUID groupId() {
        return groupId;
    }
}

package com.example.backend.auth.application;

import java.util.Objects;
import java.util.UUID;

/**
 * An Unlock refused before anything was written: who asked, which User it addressed, and why.
 *
 * <p>Not an authorization failure in any case — the caller holds {@code account:unlock} — but a
 * refusal about the target. The administrative edge answers, logs and audits each reason
 * ({@code auth.controller.AdminRefusals}).
 */
public final class UnlockRefusedException extends AdministrativeRefusalException {

    /** Why the Unlock was refused, in the order the use case decides them. */
    public enum Reason {

        /**
         * No User has the id addressed. The caller may already read the listing, so naming what
         * is missing reveals nothing; an id that is not a UUID at all never reaches the use case.
         */
        UNKNOWN_IDENTITY,

        /**
         * The User is the acting administrator, whatever Permissions it holds and whatever its
         * state: recovering from a self-inflicted state takes a second administrator.
         */
        SELF_TARGET,

        /**
         * The User is serving no lockout — the Bootstrap Admin, which can never be locked, always
         * among them — so there is nothing to lift; most often another administrator's Unlock
         * landed first (ADR 0015).
         */
        NOT_LOCKED
    }

    private final Reason reason;

    private final UUID actorId;

    private final UUID userId;

    /**
     * @param actorId the acting administrator's stable id, as its signed-in session holds it
     * @param userId  the User the Unlock addressed, which may not exist
     */
    public UnlockRefusedException(Reason reason, UUID actorId, UUID userId) {
        super("Unlock refused");
        this.reason = reason;
        this.actorId = Objects.requireNonNull(actorId, "a refused Unlock names its administrator");
        this.userId = Objects.requireNonNull(userId, "a refused Unlock names the User it addressed");
    }

    public Reason reason() {
        return reason;
    }

    public UUID actorId() {
        return actorId;
    }

    public UUID userId() {
        return userId;
    }
}

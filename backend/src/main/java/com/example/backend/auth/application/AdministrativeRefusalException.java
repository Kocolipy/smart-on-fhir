package com.example.backend.auth.application;

/**
 * A refusal of an administrative operation on an identity or a Group, thrown before anything was
 * written and recorded nowhere by the use case that refused.
 *
 * <p>Each kind carries what its record needs — who was refused, what the operation addressed and
 * why, by stable id and a closed-set reason — and nothing else: no message names a value. What a
 * refusal is answered, logged and audited as is decided once, at the edge of {@code /api/admin}
 * ({@code auth.controller.AdminRefusals}), whose table is exhaustive over this sealed type, so a
 * new kind does not compile without its entry. The use case only decides that it refuses, and
 * throws; the edge appends the refusal's {@code Audited.Refused} event after the use case's
 * transaction has rolled back (ADR 0004's 2026-10-22 addendum).
 */
public abstract sealed class AdministrativeRefusalException extends RuntimeException
        permits UnlockRefusedException, GroupPermissionsChangeRefusedException {

    AdministrativeRefusalException(String message) {
        super(message);
    }
}

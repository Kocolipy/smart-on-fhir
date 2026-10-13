package com.example.backend.audit.domain;

/**
 * Why a SCIM write was refused, as a closed set.
 *
 * <p>Not every refusal has a reason here: a body that is not SCIM ({@code invalidSyntax}), a
 * malformed PATCH path ({@code invalidPath} or {@code invalidFilter}) and a refused precondition
 * are answered and logged, but not audited.
 *
 * <p>A closed set for the same reason {@link AuditRefusalReason} is one: the reason
 * is a field every later query groups by, and a free-text reason is a place a
 * caller's own value — a {@code userName}, a filter, a submitted attribute — could
 * be written. The names are SCIM's own {@code scimType} values, upper-cased, so a
 * recorded refusal and the error body the caller received name the same thing.
 */
public enum AuditScimRefusal {

    /** A live resource already holds the unique attribute the write asked for. */
    UNIQUENESS,

    /**
     * The write named a value the schema does not accept — for a Group, a member id that
     * does not name a live User, which covers a Group's id, a deleted User's id and an id
     * that names nothing.
     *
     * <p>One reason for all three because the caller is told one thing: distinguishing
     * them would disclose the existence and the type of resources it has not been shown.
     */
    INVALID_VALUE,

    /**
     * The write targeted a resource the deployment reserves, which no SCIM operation may
     * change: the Bootstrap Admin, the Admin group's name or existence, or the Bootstrap
     * Admin's membership of it.
     *
     * <p>SCIM's own {@code scimType} for an attempt to change something immutable, so the
     * recorded refusal and the error body the caller received name the same thing.
     *
     * <p>Also a PATCH naming a read-only attribute, or a User PATCH removing a required one —
     * {@code userName}, or an email's {@code value} — which is the same {@code scimType} for the
     * same reason.
     */
    MUTABILITY,

    /**
     * A PATCH selected no value — a filtered path matching nothing, or a {@code remove} with no
     * path — SCIM's {@code noTarget}.
     */
    NO_TARGET
}

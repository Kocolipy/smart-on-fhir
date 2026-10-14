package com.example.backend.scim.controller;

import org.springframework.http.HttpStatus;

/**
 * A refusal a SCIM caller is entitled to see: its {@link ScimRefusals.Kind kind}, which
 * decides the status, the standard {@code scimType} where one applies and whether the refusal
 * is audited, and a detail safe to render.
 *
 * <p>Whoever raises it — a reader, a controller, or the refusal table answering for the
 * directory — raises a kind, so the same kind is answered and audited alike wherever it came
 * from.
 *
 * <p>An exception rather than a returned error value because the refusals happen deep
 * in parsing — an unsupported attribute is discovered while reading a nested object —
 * and threading a failure back out through every reader would mean every reader having
 * a failure path a future one could forget.
 *
 * <p><strong>{@code detail} is written by this service, never by the caller.</strong>
 * Every construction site below passes a literal or an attribute PATH; no site passes a
 * submitted value. That is the rule that keeps a {@code userName}, a password or a
 * filter literal out of an error body — and out of the log line an unhandled exception
 * would produce — and it is a rule about call sites rather than about this class,
 * because a {@code String} parameter cannot express it.
 */
public class ScimErrorException extends RuntimeException {

    private final ScimRefusals.Kind kind;

    private ScimErrorException(ScimRefusals.Kind kind, String detail) {
        super(detail);
        this.kind = kind;
    }

    /** The body was not valid SCIM: unparseable, or missing something required. */
    public static ScimErrorException invalidSyntax(String detail) {
        return new ScimErrorException(ScimRefusals.Kind.INVALID_SYNTAX, detail);
    }

    /**
     * A value or an attribute was not acceptable: the wrong JSON type, an attribute this
     * service does not implement, or a projection naming something that is not an
     * attribute.
     */
    public static ScimErrorException invalidValue(String detail) {
        return new ScimErrorException(ScimRefusals.Kind.INVALID_VALUE, detail);
    }

    /** A live resource already holds a unique attribute the write asked for. */
    public static ScimErrorException uniqueness(String detail) {
        return new ScimErrorException(ScimRefusals.Kind.UNIQUENESS, detail);
    }

    /**
     * The write targeted something that cannot be changed — a resource this deployment
     * reserves for its own recovery.
     *
     * <p>{@code 400} and not {@code 403}. The caller's token may hold every Permission the
     * write needs: it is the TARGET that is refused, not the credential, and answering
     * {@code 403} would send an integrator to re-check token Permissions that are fine. SCIM's
     * own {@code scimType} for this condition is {@code mutability}.
     */
    public static ScimErrorException mutability(String detail) {
        return new ScimErrorException(ScimRefusals.Kind.MUTABILITY, detail);
    }

    /** A PATCH path that is malformed or names no attribute this service implements. */
    public static ScimErrorException invalidPath(String detail) {
        return new ScimErrorException(ScimRefusals.Kind.INVALID_PATH, detail);
    }

    /** A PATCH filter that selected no value. */
    public static ScimErrorException noTarget(String detail) {
        return new ScimErrorException(ScimRefusals.Kind.NO_TARGET, detail);
    }

    /** A filter using syntax or a comparison this service does not support. */
    public static ScimErrorException invalidFilter(String detail) {
        return new ScimErrorException(ScimRefusals.Kind.INVALID_FILTER, detail);
    }

    /**
     * A write's {@code If-Match} was not one strong entity tag: {@code invalidValue}, but a refused
     * precondition, which is not audited.
     */
    static ScimErrorException invalidPrecondition(String detail) {
        return new ScimErrorException(ScimRefusals.Kind.INVALID_PRECONDITION, detail);
    }

    /** A write's {@code If-Match} named a version that is no longer current. */
    public static ScimErrorException preconditionFailed(String detail) {
        return new ScimErrorException(ScimRefusals.Kind.PRECONDITION_FAILED, detail);
    }

    /** No live resource has this id, or no such discovery document exists. */
    public static ScimErrorException notFound(String detail) {
        return new ScimErrorException(ScimRefusals.Kind.NOT_FOUND, detail);
    }

    /**
     * The request asked for a query capability this service does not implement.
     *
     * <p>{@code 403} and no {@code scimType}, which is RFC 7644 §3.4.2.2's answer for a
     * provider that does not support filtering: the alternative — ignoring the parameter
     * — would return every resource to a caller that asked for a few and believes it
     * received a match. A refusal is the only answer that cannot be misread, and it is
     * consistent with what discovery advertises.
     */
    public static ScimErrorException unsupportedQuery(String detail) {
        return new ScimErrorException(ScimRefusals.Kind.UNSUPPORTED_QUERY, detail);
    }

    /** The request body ran past the namespace's size bound while it was being read. */
    public static ScimErrorException payloadTooLarge(String detail) {
        return new ScimErrorException(ScimRefusals.Kind.PAYLOAD_TOO_LARGE, detail);
    }

    /**
     * An endpoint RFC 7644 defines and this service deliberately does not implement.
     *
     * <p>{@code 501} rather than {@code 404}: the path is SCIM's and a client probing for it is
     * told that this provider does not offer the operation, which RFC 7644 §3.12 lists as
     * {@code 501}'s meaning, rather than that it named a resource that does not exist.
     */
    public static ScimErrorException notImplemented(String detail) {
        return new ScimErrorException(ScimRefusals.Kind.NOT_IMPLEMENTED, detail);
    }

    /**
     * A failure on this side that the request could not have avoided. {@code 500} and no
     * {@code scimType}, which RFC 7644 §3.12 defines none for.
     */
    public static ScimErrorException serverError(String detail) {
        return new ScimErrorException(ScimRefusals.Kind.SERVER_ERROR, detail);
    }

    /**
     * A failure on this side that passes: the write was the database's deadlock victim again on
     * its retry (ADR 0015's as-13). {@code 503} and no {@code scimType}, which RFC 7644 §3.12
     * defines none for; the answer carries {@code Retry-After}.
     */
    public static ScimErrorException serviceUnavailable(String detail) {
        return new ScimErrorException(ScimRefusals.Kind.SERVICE_UNAVAILABLE, detail);
    }

    /** The kind of refusal, which decides everything about it but the detail. */
    ScimRefusals.Kind kind() {
        return kind;
    }

    public HttpStatus status() {
        return kind.status;
    }

    /** The standard {@code scimType}, or {@code null} where the RFC defines none. */
    public String scimType() {
        return kind.scimType;
    }

    /**
     * Why the request was refused, in one word from this class's fixed set: the
     * {@code scimType} where the RFC defines one, and otherwise the refusal's own name
     * ({@code notFound}, {@code preconditionFailed}, ...). What a refusal's log record carries
     * as {@code event.reason}; never anything the caller sent.
     */
    public String reason() {
        return kind.reason;
    }

    /** The human-readable detail, safe by construction: never a submitted value. */
    public String detail() {
        return getMessage();
    }
}

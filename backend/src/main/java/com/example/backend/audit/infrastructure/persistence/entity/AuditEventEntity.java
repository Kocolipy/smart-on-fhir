package com.example.backend.audit.infrastructure.persistence.entity;

import com.example.backend.audit.domain.AuditOperation;
import com.example.backend.audit.domain.AuditOutcome;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * Database representation of a recorded audit event.
 *
 * <p>Every column is {@code updatable = false}. That is a statement about intent
 * rather than the enforcement — the enforcement is the schema migration's
 * grants and its {@code BEFORE UPDATE OR DELETE} trigger, which refuse the
 * statement whatever this mapping says. Together they mean an {@code UPDATE} on
 * this table is impossible to reach by accident and impossible to perform on
 * purpose without assuming the retention role.
 */
@Entity
@Table(name = "audit_events")
public class AuditEventEntity {

    @Id
    @Column(nullable = false, updatable = false)
    private UUID id;

    @Column(nullable = false, updatable = false)
    private Instant occurredAt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false, length = 64)
    private AuditOperation operation;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false, length = 16)
    private AuditOutcome outcome;

    /** Null when nobody was authenticated, e.g. a rejected login. */
    @Column(updatable = false)
    private UUID actorId;

    /** Null when the operation named no existing account. */
    @Column(updatable = false)
    private UUID subjectId;

    @Column(nullable = false, updatable = false, length = 64)
    private String resourceType;

    @Column(updatable = false)
    private UUID resourceId;

    /**
     * The changed attribute paths, comma-separated. The adapter owns the join and
     * the split; nothing queries an individual path, so the list is stored as one
     * value rather than as a related table.
     */
    @Column(updatable = false)
    private String changedPaths;

    @Column(nullable = false, updatable = false, length = 32)
    private String statusClass;

    @Column(updatable = false, length = 128)
    private String errorCode;

    @Column(updatable = false, length = 16)
    private String httpMethod;

    /** The matched route template. Never the resolved path. */
    @Column(updatable = false, length = 512)
    private String httpPath;

    @Column(updatable = false, length = 128)
    private String requestId;

    /** How many resources a bulk read returned; null for any other operation. */
    @Column(updatable = false)
    private Integer resultCount;

    /** A bulk read's filter as its shape — never its values; null when it had none. */
    @Column(updatable = false)
    private String filterShape;

    /** The Role a mapped-Group membership change granted or revoked; null for anything else. */
    @Column(name = "role_name", updatable = false)
    private String roleName;

    /**
     * The Permissions a token was issued, rotated or refused with, comma-separated as
     * {@code changedPaths} is; null for anything else.
     */
    @Column(updatable = false)
    private String permissions;

    /** How a login was attempted, {@code password} or {@code sso}; null for anything else. */
    @Column(name = "login_method", updatable = false)
    private String loginMethod;

    /** The MFA factor of an Epic {@code LOGIN_SUCCESS}; null for anything else. */
    @Column(name = "mfa_factor", updatable = false)
    private String mfaFactor;

    protected AuditEventEntity() {
    }

    public AuditEventEntity(
            UUID id,
            Instant occurredAt,
            AuditOperation operation,
            AuditOutcome outcome,
            UUID actorId,
            UUID subjectId,
            String resourceType,
            UUID resourceId,
            String changedPaths,
            String statusClass,
            String errorCode,
            String httpMethod,
            String httpPath,
            String requestId,
            Integer resultCount,
            String filterShape,
            String roleName,
            String permissions,
            String loginMethod,
            String mfaFactor) {
        this.id = id;
        this.occurredAt = occurredAt;
        this.operation = operation;
        this.outcome = outcome;
        this.actorId = actorId;
        this.subjectId = subjectId;
        this.resourceType = resourceType;
        this.resourceId = resourceId;
        this.changedPaths = changedPaths;
        this.statusClass = statusClass;
        this.errorCode = errorCode;
        this.httpMethod = httpMethod;
        this.httpPath = httpPath;
        this.requestId = requestId;
        this.resultCount = resultCount;
        this.filterShape = filterShape;
        this.roleName = roleName;
        this.permissions = permissions;
        this.loginMethod = loginMethod;
        this.mfaFactor = mfaFactor;
    }

    /*
     * No accessors, deliberately.
     *
     * This entity is write-only: the audit trail is append-only, the one read path —
     * the administrative listing, AuditEventReadAdapter — reads the stored ROW with SQL
     * rather than through this mapping, and every assertion about a recorded event is
     * made against that row too — because what is claimed about an audit record is a
     * claim about the bytes that landed, and a mapping's opinion of them is not the
     * same evidence.
     *
     * Hibernate needs none of them either: every mapping annotation above is on a
     * FIELD, so the provider uses field access and never looks for a getter. A previous
     * generation of accessors here was justified as "required for Hibernate hydration",
     * which was simply untrue, and they sat unreachable until mutation testing reported
     * fourteen mutants no test could reach. The remedy the gate asks for is to test the
     * code or delete it; a getter with no caller cannot be tested into relevance.
     */
}

package com.example.backend.audit;

import com.example.backend.audit.domain.AuditEvent;
import com.example.backend.audit.domain.AuditLoginMethod;
import com.example.backend.audit.domain.AuditMfaFactor;
import com.example.backend.audit.domain.AuditOperation;
import com.example.backend.audit.domain.AuditRequest;
import com.example.backend.audit.domain.AuditTrail;
import com.example.backend.audit.domain.Audited;
import com.example.backend.authorization.domain.Role;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * An {@link AuditTrail} that keeps what it was told, so a unit test of a use case
 * can assert which events that use case records without a database.
 *
 * <p>Keeps the events themselves ({@link #events()}), and reads each back as a {@link Recorded}
 * summary: "exactly one event, naming this account by its stable id, for this reason" is the
 * claim most use-case tests check, and a counter cannot express it. The summary's operation,
 * actor and subject are the stored row's, read from the event's own {@link Audited#toEvent}, so a
 * new kind of event needs no entry here unless its test needs a detail beyond its reason.
 */
public final class RecordingAuditTrail implements AuditTrail {

    /** What the trail would stamp; no summary reads it, so it names no request and no instant. */
    private static final Audited.Stamp STAMP =
            new Audited.Stamp(null, null, () -> AuditRequest.NONE);

    private final List<Audited> events = new ArrayList<>();

    private final List<Recorded> recorded = new ArrayList<>();

    private final List<AuditLoginMethod> loginMethods = new ArrayList<>();

    private final List<AuditMfaFactor> mfaFactors = new ArrayList<>();

    private final List<AuditRequest> accessDeniedRequests = new ArrayList<>();

    /** One recorded event: what it said happened, and the ids and reason it named. */
    public record Recorded(
            AuditOperation operation, UUID actorId, UUID subjectId, String detail) {
    }

    /** Every event appended, in order, as the value the use case built. */
    public List<Audited> events() {
        return List.copyOf(events);
    }

    /** Every appended event of one kind, in order. */
    public <E extends Audited> List<E> events(Class<E> kind) {
        return events.stream().filter(kind::isInstance).map(kind::cast).toList();
    }

    public List<Recorded> recorded() {
        return List.copyOf(recorded);
    }

    /** Every recorded call of one operation, for a per-operation count. */
    public List<Recorded> of(AuditOperation operation) {
        return recorded.stream().filter(event -> event.operation() == operation).toList();
    }

    /**
     * The changed paths of every recorded event of one operation, in order, each as the stored
     * row lists them — kept beside {@link #recorded()} for the reason {@link #loginMethods()} is.
     */
    public List<List<String>> changedPaths(AuditOperation operation) {
        return events.stream()
                .map(event -> event.toEvent(STAMP))
                .filter(row -> row.operation() == operation)
                .map(AuditEvent::changedPaths)
                .toList();
    }

    /**
     * The login method of every recorded {@code LOGIN_SUCCESS} and {@code LOGIN_FAILURE}, in the
     * order they were recorded. Kept beside {@link #recorded()} rather than in {@link Recorded},
     * so a test's expected {@code Recorded} values need not change for a field it is not about.
     */
    public List<AuditLoginMethod> loginMethods() {
        return List.copyOf(loginMethods);
    }

    /**
     * The MFA factor of every recorded {@code LOGIN_SUCCESS}, in order, {@code null} where it
     * carried none — kept beside {@link #recorded()} for the reason {@link #loginMethods()} is.
     */
    public List<AuditMfaFactor> mfaFactors() {
        return Collections.unmodifiableList(new ArrayList<>(mfaFactors));
    }

    /** The operation each authorization refusal was recorded with, request id included. */
    public List<AuditRequest> accessDeniedRequests() {
        return List.copyOf(accessDeniedRequests);
    }

    public void reset() {
        events.clear();
        recorded.clear();
        loginMethods.clear();
        mfaFactors.clear();
        accessDeniedRequests.clear();
    }

    @Override
    public void append(Audited event) {
        events.add(event);
        AuditEvent row = event.toEvent(STAMP);
        recorded.add(new Recorded(
                row.operation(), row.actorId(), row.subjectId(), detail(event, row)));
        switch (event) {
            case Audited.LoginSuccess e -> {
                loginMethods.add(e.method());
                mfaFactors.add(e.factor());
            }
            case Audited.LoginFailure e -> loginMethods.add(e.method());
            case Audited.LoginRefusal e -> loginMethods.add(e.method());
            case Audited.AccessDenied e -> accessDeniedRequests.add(e.operation());
            case Audited.ConnectorAccessDenied e -> accessDeniedRequests.add(e.operation());
            default -> {
            }
        }
    }

    /**
     * The {@link Recorded#detail()}: the row's reason, for every event but those whose test needs
     * something else to tell it apart — noted beside each below.
     */
    private static String detail(Audited event, AuditEvent row) {
        return switch (event) {
            // A token's Permissions, comma-joined, after a refusal's reason: "group:read,user:read".
            case Audited.ConnectorTokenIssued _, Audited.ConnectorTokenRotated _ ->
                    (row.errorCode() == null ? "" : row.errorCode() + " ")
                            + String.join(",", row.permissions());
            // A bulk read's count and rendered shape, e.g. "2 userName eq ?".
            case Audited.ScimUsersQueried _, Audited.ScimGroupsQueried _,
                    Audited.ScimResourcesQueried _ ->
                    row.resultCount() + (row.filterShape() == null ? "" : " " + row.filterShape());
            // The changed attributes' names, sorted and comma-joined; empty for a no-op write.
            case Audited.ScimUserReplaced e -> joined(e.changed());
            case Audited.ScimGroupReplaced e -> joined(e.changed());
            // The seeded resource's type, the one thing that tells the two seeding events apart.
            case Audited.ReservedResourceSeeded _ -> row.resourceType();
            case Audited.ReservedMembershipRestored e -> "members-restored " + e.userId();
            // OUTCOME:PATHS:CAUSES, each list sorted by name and comma-joined.
            case Audited.UserSessionsRevoked e ->
                    row.outcome() + ":" + joined(e.paths()) + ":" + joined(e.causes());
            // The Roles lost, by name, in the order given: "Account admin,Superuser".
            case Audited.DormancyRoleRevocation e ->
                    e.roles().stream().map(Role::name).collect(Collectors.joining(","));
            // The Group then the Role: "<groupId>:Account admin".
            case Audited.RoleGranted _, Audited.RoleRevoked _ ->
                    row.resourceId() + ":" + row.role();
            // The refused request's method and route template.
            case Audited.AccessDenied _, Audited.ConnectorAccessDenied _ ->
                    row.httpMethod() + " " + row.httpPath();
            default -> row.errorCode();
        };
    }

    private static String joined(Set<? extends Enum<?>> values) {
        return values.stream().map(Enum::name).sorted().collect(Collectors.joining(","));
    }
}

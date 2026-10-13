package com.example.backend.scim.controller;

import com.example.backend.authorization.domain.Permission;
import com.example.backend.observability.RequestActor;
import com.example.backend.scim.application.ConnectorAdministrationService;
import com.example.backend.scim.application.ConnectorSummary;
import com.example.backend.scim.application.ConnectorTokenEscalationException;
import com.example.backend.scim.application.ConnectorTokenNotRotatableException;
import com.example.backend.scim.application.IssuedConnectorToken;
import com.example.backend.scim.application.UnknownConnectorException;
import com.example.backend.scim.domain.InvalidConnectorTokenLifetimeException;
import com.example.backend.scim.domain.InvalidConnectorTokenPermissionsException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Inbound HTTP adapter for connector and token administration.
 *
 * <p>Under {@code /api/admin}, so it is on the APPLICATION chain — session
 * authenticated, CSRF protected, and each operation requires its own Permission
 * ({@code connector:read} to list, {@code connector:write} to create and delete,
 * {@code connector:token} to issue, rotate and revoke), declared on the handler with
 * method security and repeated by the chain as a backstop. That is deliberate and is
 * the opposite of the namespace it manages credentials for: minting a SCIM token is
 * an administrator's browser action, not something a connector may do for itself, so
 * no bearer token reaches this controller and no connector can issue itself a wider
 * one.
 *
 * <p>Two response shapes, and the difference is the whole point.
 * {@link ConnectorSummary} has no field a credential could occupy and is what a
 * listing returns; {@link IssuedConnectorToken} carries the plaintext and is returned
 * only by issue and rotate, on a response marked {@code Cache-Control: no-store} so
 * it is not written to a shared cache on its way to the browser.
 *
 * <p>The acting administrator is named by the stable id its signed-in session holds, as the
 * security chain resolved it ({@link RequestActor#userOf}) — never by the {@code userName} the
 * principal carries, which a connector may rename between the administrator signing in and
 * acting. Unlike account administration, which refuses a session naming nobody with {@code 401},
 * a {@code null} id here is passed on and recorded as an event with no actor: no rule of
 * connector administration turns on who the actor is, and this slice cannot read the
 * {@code auth} module's session without closing the {@code auth -> scim} dependency into a cycle.
 */
@RestController
@RequestMapping("/api/admin/connectors")
public class AdminConnectorController {

    private final ConnectorAdministrationService connectors;

    public AdminConnectorController(ConnectorAdministrationService connectors) {
        this.connectors = connectors;
    }

    /** A connector to create. */
    public record CreateConnectorRequest(
            @NotBlank @Size(max = 200) String displayName) {
    }

    /**
     * A token to mint.
     *
     * @param permissions  what it is to carry: Permission names, at least one, each of
     *                     {@code user:read}, {@code user:write}, {@code group:read} and
     *                     {@code group:write}, and each one the caller holds itself
     * @param lifetimeDays how long it should live, or {@code null} for the default of
     *                     365 days; the policy refuses more, so a request for two
     *                     years is a {@code 400} rather than a silently shortened token
     */
    public record IssueTokenRequest(
            @NotNull List<String> permissions,
            @Positive Integer lifetimeDays) {
    }

    /**
     * A rotation.
     *
     * @param overlapDays how long the old token should keep working, clamped to 14 and
     *                    to whatever life the old token had left; {@code null} or zero
     *                    ends it immediately
     * @param permissions what the replacement is to carry, on the terms an issue's are, or
     *                    {@code null} to keep the old token's
     */
    public record RotateTokenRequest(Integer overlapDays, List<String> permissions) {
    }

    @GetMapping
    @PreAuthorize("hasAuthority('connector:read')")
    public List<ConnectorSummary> listConnectors() {
        return connectors.listConnectors();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('connector:write')")
    public ConnectorSummary create(
            @Valid @RequestBody CreateConnectorRequest request, HttpServletRequest http) {
        return connectors.create(request.displayName(), RequestActor.userOf(http));
    }

    /**
     * Deletes a connector, revoking every token it holds and removing every alias it
     * owns. Users and Groups are untouched.
     */
    @DeleteMapping("/{connectorId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAuthority('connector:write')")
    public void delete(@PathVariable UUID connectorId, HttpServletRequest http) {
        connectors.delete(connectorId, RequestActor.userOf(http));
    }

    /**
     * Mints a token. The plaintext in this response is the only copy that will ever
     * exist — there is no endpoint that returns it again.
     */
    @PostMapping("/{connectorId}/tokens")
    @PreAuthorize("hasAuthority('connector:token')")
    public ResponseEntity<IssuedConnectorToken> issueToken(
            @PathVariable UUID connectorId,
            @Valid @RequestBody IssueTokenRequest request,
            Authentication caller,
            HttpServletRequest http) {
        return disclosed(connectors.issueToken(
                connectorId,
                permissions(request.permissions()),
                days(request.lifetimeDays()),
                RequestActor.userOf(http),
                held(caller)));
    }

    /**
     * Replaces a token, leaving the old one usable until the overlap window ends. The
     * old token's expiry only ever moves earlier.
     */
    @PostMapping("/{connectorId}/tokens/{tokenId}/rotate")
    @PreAuthorize("hasAuthority('connector:token')")
    public ResponseEntity<IssuedConnectorToken> rotateToken(
            @PathVariable UUID connectorId,
            @PathVariable UUID tokenId,
            @Valid @RequestBody(required = false) RotateTokenRequest request,
            Authentication caller,
            HttpServletRequest http) {
        Integer overlapDays = request == null ? null : request.overlapDays();
        List<String> requested = request == null ? null : request.permissions();
        return disclosed(connectors.rotateToken(
                tokenId,
                requested == null ? null : permissions(requested),
                days(overlapDays),
                RequestActor.userOf(http),
                held(caller)));
    }

    /** Revokes a token, effective immediately. */
    @PostMapping("/{connectorId}/tokens/{tokenId}/revoke")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAuthority('connector:token')")
    public void revokeToken(
            @PathVariable UUID connectorId, @PathVariable UUID tokenId, HttpServletRequest http) {
        connectors.revokeToken(tokenId, RequestActor.userOf(http));
    }

    /**
     * The one response shape that carries plaintext, and the one place
     * {@code no-store} is set.
     *
     * <p>A single helper rather than the header on each of the two methods, so a third
     * disclosing endpoint cannot be added without going through it. {@code no-store}
     * rather than {@code no-cache}: the latter permits storing the response and
     * revalidating, which is storing a credential.
     */
    private static ResponseEntity<IssuedConnectorToken> disclosed(IssuedConnectorToken token) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .cacheControl(CacheControl.noStore())
                .body(token);
    }

    private static Duration days(Integer days) {
        return days == null ? null : Duration.ofDays(days);
    }

    /**
     * The requested names as Permissions. A name that is no Permission is refused here, as the
     * {@code 400} a malformed request gets — the same answer as a Permission no token can carry,
     * which the domain refuses.
     */
    private static List<Permission> permissions(List<String> names) {
        List<Permission> permissions = new ArrayList<>();
        for (String name : names) {
            permissions.add(Permission.fromValue(name).orElseThrow(() ->
                    new InvalidConnectorTokenPermissionsException("Not a Permission")));
        }
        return permissions;
    }

    /**
     * The Permissions the caller's session was issued with — what the no-escalation rule compares
     * a requested token against. Authorities that are not Permissions (the baseline
     * {@code ROLE_USER}) are not Permissions and are not held as one.
     */
    private static Set<Permission> held(Authentication caller) {
        Set<Permission> held = EnumSet.noneOf(Permission.class);
        for (GrantedAuthority authority : caller.getAuthorities()) {
            Permission.fromValue(authority.getAuthority()).ifPresent(held::add);
        }
        return held;
    }

    @ExceptionHandler(UnknownConnectorException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public void unknownConnector() {
        // The caller is already an administrator, so naming what is missing reveals
        // nothing they could not read from the listing.
    }

    /**
     * A lifetime past the ceiling is a bad request rather than a conflict: the request
     * itself is out of range, and reporting it lets the Admin see that the token they
     * would have received is not the one they asked for.
     */
    @ExceptionHandler(InvalidConnectorTokenLifetimeException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public void invalidLifetime() {
    }

    /**
     * No Permission, a name that is no Permission, or one a token cannot carry: the request
     * itself cannot be granted, whoever makes it.
     */
    @ExceptionHandler(InvalidConnectorTokenPermissionsException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public void invalidPermissions() {
    }

    /**
     * A token carrying a Permission the caller does not hold. {@code 403}, bodiless like every
     * refusal on this chain: the caller may mint tokens, but not this one. Already audited, with
     * the Permissions requested, by the service.
     */
    @ExceptionHandler(ConnectorTokenEscalationException.class)
    @ResponseStatus(HttpStatus.FORBIDDEN)
    public void escalation() {
    }

    /**
     * A rotation of a token that is revoked, has expired or was rotated already — the second of
     * two concurrent rotations of one token included. {@code 409}, bodiless: the request is
     * well-formed and the caller may rotate tokens, but this token's state forbids it, and the
     * listing shows which state that is. Already audited, naming the token and why, by the service.
     */
    @ExceptionHandler(ConnectorTokenNotRotatableException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public void notRotatable() {
    }
}

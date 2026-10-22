package com.example.backend.auth.controller;

import com.example.backend.auth.application.ForbiddenIdentityChangeException;
import com.example.backend.auth.application.IdentityAdministrationService;
import com.example.backend.auth.application.IdentitySummary;
import com.example.backend.auth.application.NoLockoutInForceException;
import com.example.backend.auth.application.UnknownIdentityException;
import com.example.backend.web.ApiError;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Inbound HTTP adapter for the Users projection of the Accounts page and the one operation an
 * administrator may perform on a User, Unlock.
 *
 * <p>Each operation declares the Permission it requires where it is defined, with method
 * security ({@code user:read} to list, {@code account:unlock} — the helpdesk Permission — for
 * Unlock); the application chain repeats each declaration as a URL rule, as a backstop that fails
 * closed (ADR 0010). Unlock cannot escalate and has no effect on a User that is not locked, which
 * is why a Group may carry {@code account:unlock}, and why it is not {@code user:write}: that is a
 * connector token's, and over SCIM it sets any User's password (ADR 0015).
 * {@code AuthorizationContractTests} proves every declaration against the running application,
 * driven by the API document.
 *
 * <p>READ-ONLY for everything the directory owns, and enforced by absence: there is no handler
 * that accepts a {@code userName}, an {@code active} flag or a Group membership, so a
 * {@code PUT}, {@code PATCH} or {@code DELETE} on a User is answered {@code 405} by the dispatcher
 * before any code here runs. The Disable and Enable actions that used to live here were removed
 * rather than hidden: {@code active} is a SCIM attribute the connector owns, so an administrator
 * overriding it would be overwritten by the next synchronization, and an endpoint kept for a
 * control nobody renders is an endpoint nobody reviews. The forced password change was removed the
 * same way (ADR 0015's 2026-10-22 addendum), so its path is now an undeclared route the chain
 * refuses.
 *
 * <p>What remains addresses its User by the stable resource {@code id}, never by {@code userName}:
 * a connector may rename a User between the Admin reading the row and clicking, and an id-targeted
 * action cannot then land on whoever inherited the name.
 *
 * <p>{@link IdentitySummary} is returned as the wire shape rather than copied into
 * a response type of this adapter's own. The copy would have been field-identical
 * and would have had no property to enforce: the guarantee that no password hash
 * can reach a client belongs to {@code IdentitySummary}, which has no field one
 * could be written into, and a second record restating its fields only adds a
 * place for the two to drift.
 */
@RestController
@RequestMapping("/api/admin/accounts")
public class AdminAccountController {

    private final IdentityAdministrationService identities;

    public AdminAccountController(IdentityAdministrationService identities) {
        this.identities = identities;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('user:read')")
    public List<IdentitySummary> listAccounts() {
        return identities.listIdentities();
    }

    /**
     * Ends a lockout — the only way one ends — and requires a password change of a User that has a
     * password. Says nothing about {@code active}. Refused {@code 409} on a User serving no
     * lockout.
     */
    @PostMapping("/{id}/unlock")
    @PreAuthorize("hasAuthority('account:unlock')")
    public IdentitySummary unlock(@PathVariable UUID id, HttpServletRequest request) {
        return identities.unlock(id, HttpSessionAttributes.ownerOf(request));
    }

    /** A refusal about whose account it is — the caller's own, whatever Permissions it holds. */
    @ExceptionHandler(ForbiddenIdentityChangeException.class)
    @ResponseStatus(HttpStatus.FORBIDDEN)
    public void forbiddenChange() {
    }

    @ExceptionHandler(UnknownIdentityException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public void unknownIdentity() {
        // The caller is already an administrator, so naming what is missing
        // reveals nothing they could not read from the listing. An id that is
        // not a UUID at all never gets here: the dispatcher answers it 400.
    }

    /**
     * A refusal about the User's state rather than the caller — an Unlock of a User serving no
     * lockout, most often one another administrator unlocked first — so neither 403 (the caller
     * may unlock) nor 400 (the request is well formed) fits. The generic {@code request-refused}
     * document, as every other {@code 409} of the application API.
     */
    @ExceptionHandler(NoLockoutInForceException.class)
    public ResponseEntity<ApiError> notLocked() {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .contentType(MediaType.APPLICATION_JSON)
                .body(ApiError.of(HttpStatus.CONFLICT));
    }
}

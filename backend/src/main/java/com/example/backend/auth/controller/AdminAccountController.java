package com.example.backend.auth.controller;

import com.example.backend.auth.application.ForbiddenIdentityChangeException;
import com.example.backend.auth.application.IdentityAdministrationService;
import com.example.backend.auth.application.IdentitySummary;
import com.example.backend.auth.application.UnknownIdentityException;
import com.example.backend.auth.application.UnsafeIdentityChangeException;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Inbound HTTP adapter for the Users projection of the Accounts page and the two operations an
 * administrator may perform on a User.
 *
 * <p>Each operation declares the Permission it requires where it is defined, with method
 * security ({@code user:read} to list, {@code user:write} for Unlock and the forced password
 * change); the application chain repeats each declaration as a URL rule, as a backstop that
 * fails closed (ADR 0010). {@code AuthorizationContractTests} proves every declaration against
 * the running application, driven by the API document.
 *
 * <p>READ-ONLY for everything the directory owns, and enforced by absence: there is no handler
 * that accepts a {@code userName}, an {@code active} flag or a Group membership, so a
 * {@code PUT}, {@code PATCH} or {@code DELETE} on a User is answered {@code 405} by the dispatcher
 * before any code here runs. The Disable and Enable actions that used to live here were removed
 * rather than hidden: {@code active} is a SCIM attribute the connector owns, so an administrator
 * overriding it would be overwritten by the next synchronization, and an endpoint kept for a
 * control nobody renders is an endpoint nobody reviews.
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
     * password. Says nothing about {@code active}.
     */
    @PostMapping("/{id}/unlock")
    @PreAuthorize("hasAuthority('user:write')")
    public IdentitySummary unlock(@PathVariable UUID id, HttpServletRequest request) {
        return identities.unlock(id, HttpSessionAttributes.ownerOf(request));
    }

    /**
     * Requires the account to replace its password before it may do anything else, ending the
     * sessions it holds. The Admin never learns or chooses the password.
     */
    @PostMapping("/{id}/force-password-change")
    @PreAuthorize("hasAuthority('user:write')")
    public IdentitySummary forcePasswordChange(@PathVariable UUID id, HttpServletRequest request) {
        return identities.forcePasswordChange(id, HttpSessionAttributes.ownerOf(request));
    }

    /**
     * A refusal about whose account it is — the caller's own, whatever Permissions it holds, or
     * the Bootstrap Admin's.
     */
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
     * A refusal about the action rather than the caller — a forced change on a User with no
     * password to replace — so neither 403 (the role is fine) nor 400 (the request is well
     * formed) fits.
     */
    @ExceptionHandler(UnsafeIdentityChangeException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public void unsafeChange() {
    }
}

package com.example.backend.auth.controller;

import com.example.backend.auth.application.IdentityAdministrationService;
import com.example.backend.auth.application.IdentityPage;
import com.example.backend.auth.application.IdentitySummary;
import com.example.backend.scim.domain.ScimPageRequest;
import com.example.backend.web.AdminApiHandler;
import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Inbound HTTP adapter for the Users projection of the Accounts page and the one operation an
 * administrator may perform on a User, Unlock.
 *
 * <p>Each operation declares the Permission it requires where it is defined, with method
 * security ({@code user:read} to list, {@code account:unlock} — the helpdesk Permission — for
 * Unlock); the application chain repeats each declaration as a URL rule, as a backstop that fails
 * closed (ADR 0015). Unlock cannot escalate and has no effect on a User that is not locked, which
 * is why a Group may carry {@code account:unlock}, and why it is not {@code user:write}: that is a
 * connector token's, and over SCIM it sets any User's password (ADR 0015).
 * {@code AuthorizationContractTests} proves every declaration against the running application,
 * driven by the API document.
 *
 * <p>READ-ONLY for everything the directory owns, and enforced by absence: there is no handler
 * that accepts a {@code userName}, an {@code active} flag or a Group membership, so a
 * {@code PUT}, {@code PATCH} or {@code DELETE} on a User is answered {@code 405} by the dispatcher
 * before any code here runs. {@code active} in particular is a SCIM attribute the connector owns:
 * an administrator overriding it would be overwritten by the next synchronization.
 *
 * <p>What remains addresses its User by the stable resource {@code id}, never by {@code userName}:
 * a connector may rename a User between the Admin reading the row and clicking, and an id-targeted
 * action cannot then land on whoever inherited the name.
 *
 * <p>An Unlock the use case refuses — an unknown User, the caller's own account, a User serving no
 * lockout — is answered, logged and audited by the administrative edge
 * ({@link AdminExceptionHandler}), from the one table of administrative refusals.
 *
 * <p>{@link IdentitySummary} is returned as the wire shape — alone by Unlock, and as the
 * rows of an {@link IdentityPage} by the listing — rather than copied into
 * a response type of this adapter's own. The copy would have been field-identical
 * and would have had no property to enforce: the guarantee that no password hash
 * can reach a client belongs to {@code IdentitySummary}, which has no field one
 * could be written into, and a second record restating its fields only adds a
 * place for the two to drift.
 */
@RestController
@AdminApiHandler
@RequestMapping("/api/admin/accounts")
public class AdminAccountController {

    private final IdentityAdministrationService identities;

    public AdminAccountController(IdentityAdministrationService identities) {
        this.identities = identities;
    }

    /**
     * One page of the Users projection: the Users whose {@code userName} or display name contains
     * {@code search}, ignoring case — every User without one — in normalized {@code userName}
     * order, with how many matched. Paged as a SCIM collection and as a Group's member read are:
     * {@code startIndex} one-based, {@code count} defaulted and capped, each out-of-range value
     * coerced rather than refused ({@link ScimPageRequest#of}).
     */
    @GetMapping
    @PreAuthorize("hasAuthority('user:read')")
    public IdentityPage listAccounts(
            @RequestParam(required = false) String search,
            @RequestParam(required = false) Integer startIndex,
            @RequestParam(required = false) Integer count) {
        return identities.listIdentities(search, ScimPageRequest.of(startIndex, count));
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
}

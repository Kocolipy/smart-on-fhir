package com.example.backend.scim.controller;

import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code /Me}, answered {@code 501} for every method.
 *
 * <p>RFC 7644 §3.11 defines {@code /Me} as an alias for the User the caller authenticated as. A
 * connector is not a User: its token represents a directory, not an end User, so there is no
 * resource the alias could name. The endpoint is mapped rather than left to fall through to a
 * {@code 404}, because a {@code 404} would tell a client the path does not exist when what is true
 * is that this provider does not offer the operation — which is what {@code 501} says.
 *
 * <p>Behind authentication like every path in the namespace: an unauthenticated caller learns no
 * more here than anywhere else. A valid token and no Permission (ADR 0015), so the handler declares
 * none — deliberately, and listed as token-only in
 * {@code ArchitectureTest.every_protected_handler_declares_a_permission}.
 */
@RestController
class ScimMeController {

    /** The five methods RFC 7644 gives {@code /Me}, named so no other method is mapped. */
    @RequestMapping(
            path = ScimSchemas.BASE_PATH + "/Me",
            method = {RequestMethod.GET, RequestMethod.POST, RequestMethod.PUT,
                RequestMethod.PATCH, RequestMethod.DELETE})
    void me() {
        throw ScimErrorException.notImplemented(
                "/Me is not supported: a connector token does not represent an end User.");
    }
}

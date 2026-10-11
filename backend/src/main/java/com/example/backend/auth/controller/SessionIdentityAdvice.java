package com.example.backend.auth.controller;

import com.example.backend.auth.application.UnknownSessionIdentityException;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * A session that identifies nobody, answered the one way for every handler that names its caller
 * through {@link HttpSessionAttributes#ownerOf}: the bare {@code 401} an anonymous request gets.
 * For {@code GET /api/self} there is nobody whose record could be returned; for an administrative
 * operation on an account, whether it targets the acting administrator cannot be decided, so
 * nothing is done.
 *
 * <p>Ordered ahead of the application-wide {@code ApiExceptionHandler}, whose catch-all would
 * otherwise answer the refusal as an unexpected {@code 500}.
 */
@RestControllerAdvice(assignableTypes = {SelfController.class, AdminAccountController.class})
@Order(Ordered.HIGHEST_PRECEDENCE)
public class SessionIdentityAdvice {

    @ExceptionHandler(UnknownSessionIdentityException.class)
    @ResponseStatus(HttpStatus.UNAUTHORIZED)
    public void unknownSessionIdentity() {
    }
}

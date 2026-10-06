package com.example.backend.web;

import org.springframework.http.HttpStatusCode;

/**
 * The application API's error body: a status, a code from a closed set, and a fixed sentence.
 *
 * <p>Nothing in it comes from the request or from the exception — no path, no field name, no
 * rejected value, no exception type or message — so it is the same bytes for every failure of
 * one kind and can neither echo what a caller sent nor describe this service's internals. The
 * code is what a client branches on; the detail is for a person reading the response.
 *
 * <p>Deliberately without a {@code message} member: the SPA reads {@code message} off a
 * {@code 400} from the password change as the unmet password rule, and a validation failure is
 * not one.
 *
 * @param status the response's status, repeated in the body
 * @param code   {@link #INVALID_REQUEST}, {@link #REQUEST_REFUSED} or {@link #SERVER_ERROR}
 * @param detail a fixed sentence per code
 */
public record ApiError(int status, String code, String detail) {

    /** A {@code 400}: the body, a parameter or the path could not be read or failed validation. */
    public static final String INVALID_REQUEST = "invalid-request";

    /** Any other {@code 4xx} the dispatcher assigns. */
    public static final String REQUEST_REFUSED = "request-refused";

    /** A {@code 5xx}: a fault on this side, described no further. */
    public static final String SERVER_ERROR = "server-error";

    /**
     * The body for a failure answered with {@code status}. Public for the release-gate filters,
     * which answer ahead of the dispatcher and so must build the same body themselves.
     */
    public static ApiError of(HttpStatusCode status) {
        if (status.is5xxServerError()) {
            return new ApiError(status.value(), SERVER_ERROR,
                    "The request could not be completed because of a server-side failure.");
        }
        if (status.value() == 400) {
            return new ApiError(status.value(), INVALID_REQUEST,
                    "The request could not be read or did not pass validation.");
        }
        return new ApiError(status.value(), REQUEST_REFUSED, "The request was refused.");
    }
}

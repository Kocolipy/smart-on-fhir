package com.example.backend.scim.domain;

/**
 * A filter this service will not evaluate: malformed, over a limit, naming an attribute the
 * queried resource types do not have, naming the password, or comparing a value with an
 * operator its type does not support.
 *
 * <p>SCIM's {@code 400 invalidFilter}. The message is written for the client and never quotes a
 * literal from the filter — a filter is the preferred carrier of personal values, and an error
 * body is logged and cached in places the filter itself was kept out of.
 */
public final class InvalidScimFilterException extends ScimRequestRefusedException {

    public InvalidScimFilterException(String message) {
        super(message);
    }
}

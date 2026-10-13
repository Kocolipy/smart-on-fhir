package com.example.backend.scim.domain;

/**
 * A submitted password matches one of the User's most recent stored passwords, the current one
 * included.
 *
 * <p>Rendered as {@code 400 invalidValue}. The message names the rule and never the value: the
 * value is a password, and an exception message is the shortest path into a log line.
 */
public final class PasswordReusedException extends ScimRequestRefusedException {

    public PasswordReusedException() {
        super("the password matches one of the User's " + PasswordHistoryPolicy.RETAINED
                + " most recent passwords");
    }
}

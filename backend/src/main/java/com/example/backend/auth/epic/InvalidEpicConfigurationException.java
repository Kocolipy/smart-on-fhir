package com.example.backend.auth.epic;

/**
 * Startup refused an Epic Login setting.
 *
 * <p>The message names the environment variable and the rule it broke, <strong>never its
 * value</strong> (D22): two of the values are private keys, and a startup failure is printed to
 * the console and the log. For the same reason this exception never carries a cause — a parser's
 * own exception may quote the input it could not read, and a cause chain is printed with the
 * stack trace.
 */
public class InvalidEpicConfigurationException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /**
     * @param variable the environment variable, e.g. {@code APP_EPIC_CLIENT_KEY}
     * @param rule     what is wrong with it, written with no part of its value
     */
    public InvalidEpicConfigurationException(String variable, String rule) {
        super("Invalid Epic Login configuration: " + variable + " " + rule, null, false, false);
    }
}

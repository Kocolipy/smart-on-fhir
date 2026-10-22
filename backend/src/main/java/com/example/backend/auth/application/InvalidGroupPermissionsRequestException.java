package com.example.backend.auth.application;

/**
 * A change of a Group's Permissions whose body is not a set of Permissions a Group may carry: it is
 * missing, it is not an array of names, or a name in it is not a delegatable Permission. The web
 * adapter renders it as a {@code 400}, with the generic body.
 *
 * <p>Its message names no value: what was sent is the caller's to know, and an exception message is
 * the shortest path into a log line.
 */
public class InvalidGroupPermissionsRequestException extends RuntimeException {

    public InvalidGroupPermissionsRequestException() {
        super("the body is not a set of Permissions a Group may carry");
    }
}

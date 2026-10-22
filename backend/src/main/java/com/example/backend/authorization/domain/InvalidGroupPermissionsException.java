package com.example.backend.authorization.domain;

import java.util.List;

/**
 * Stored Group Permissions the application refuses to run with. The message names every problem
 * found, and what fixes them, so one fix covers them all.
 */
public class InvalidGroupPermissionsException extends IllegalStateException {

    private static final long serialVersionUID = 1L;

    public InvalidGroupPermissionsException(List<String> problems) {
        super("Invalid Group Permissions: " + String.join("; ", problems)
                + ". Delete the rows, or restore the Permission in code");
    }
}

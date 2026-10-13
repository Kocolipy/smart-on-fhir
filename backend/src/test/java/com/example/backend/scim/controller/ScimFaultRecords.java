package com.example.backend.scim.controller;

import com.example.backend.audit.RecordingAuditTrail;
import org.springframework.dao.DataIntegrityViolationException;

/**
 * The SCIM advice's two fault records, written for a test in another package that reads the
 * encoded stream — {@code EcsLogFormatTests} — without a route that could produce them for real.
 * An integrity violation no adapter translates is a fault no well-behaved request reaches.
 */
public final class ScimFaultRecords {

    private ScimFaultRecords() {
    }

    /** The advice's {@code 5xx} refusal record, as a {@code serverError} produces it. */
    public static void serverError() {
        new ScimExceptionHandler(new RecordingAuditTrail()).handle(ScimErrorException.serverError("A test fault."));
    }

    /** The advice's unmapped-integrity-violation record. */
    public static void integrityViolation() {
        new ScimExceptionHandler(new RecordingAuditTrail()).handle(new DataIntegrityViolationException(
                "x", new java.sql.SQLException("y")));
    }
}

package com.example.backend.patient.infrastructure.http;

/**
 * The in-house patient source could not be read: it gave no answer, an answer other than a
 * Patient or a {@code 404}, or a Patient the service cannot hold. A fault, answered {@code 5xx}
 * with the {@code exception} outcome (/docs/domain/patient.md, "Errors").
 *
 * <p>Its message is fixed text, naming at most a status or an element, and its cause, when it has
 * one, is the original failure redacted to its type: a client's or a parser's message can quote
 * the URL, and so the patient's id, or the body, and so the patient (/docs/domain/patient.md,
 * "Logging").
 */
public final class PatientSourceException extends RuntimeException {

    PatientSourceException(String message) {
        super(message);
    }

    PatientSourceException(String message, Throwable redactedCause) {
        super(message, redactedCause);
    }
}

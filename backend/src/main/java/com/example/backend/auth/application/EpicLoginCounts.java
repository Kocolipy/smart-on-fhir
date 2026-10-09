package com.example.backend.auth.application;

import com.example.backend.auth.domain.EpicLoginFailureReason;
import com.example.backend.observability.LogEvent.ErrorCategory;

/**
 * The counts an Epic Login's ending moves (ADR 0013, "Metrics"), for {@link EpicLoginOutcomeService}
 * to move without knowing the meter registry: a port, whose adapter is the Epic package's
 * Micrometer meters.
 */
public interface EpicLoginCounts {

    /** An Epic Login signed a User in. */
    void signedIn();

    /** An Epic Login was refused for {@code reason}. */
    void refused(EpicLoginFailureReason reason);

    /** An Epic Login could not complete because Epic was unavailable (D23). */
    void unavailable();

    /** An Epic Login ended because {@code call} failed under {@code category}. */
    void failedCall(String call, ErrorCategory category);
}

package com.example.backend.auth.application;

import com.example.backend.observability.LogEvent.ErrorCategory;

/**
 * The count of the Epic calls whose failure ended an Epic Login (ADR 0013, "Metrics"), for
 * {@link LoginOutcomeService} to move without knowing the meter registry: a port, whose adapter is
 * the Epic package's Micrometer meter.
 */
public interface EpicCallCounts {

    /** An Epic Login ended because {@code call} failed under {@code category}. */
    void failedCall(String call, ErrorCategory category);
}

package com.example.backend.audit.domain;

import java.util.List;

/**
 * One page of the audit listing, newest first, and where it sits in the whole.
 *
 * <p>The events are the recorded {@link AuditEvent}s themselves, not a copy with fields removed.
 * That is what makes the listing unable to leak: an event has no field a password, a bearer value,
 * a hash, a username or a filter literal could be written into, so there is nothing for a read
 * projection to redact, and a second record restating the same fields would only be a place for
 * the two to drift.
 *
 * @param totalResults how many events match the filters across every page
 * @param startIndex   the one-based position of this page's first event
 * @param events       this page's events, newest first
 */
public record AuditEventPage(long totalResults, int startIndex, List<AuditEvent> events) {

    public AuditEventPage {
        events = List.copyOf(events);
    }

    /** The page {@code query} asked for, holding {@code events} of {@code totalResults}. */
    public static AuditEventPage of(
            AuditEventQuery query, List<AuditEvent> events, long totalResults) {
        return new AuditEventPage(totalResults, query.page().startIndex(), events);
    }
}

package com.example.backend.audit.domain;

/**
 * Persistence port for a fail-open append: the event commits on a connection of its own, from a
 * pool of its own, and never on — or waiting for — a connection of the caller's.
 *
 * <p>A fail-open event is recorded while its caller is refusing, and the caller may still hold a
 * connection and row locks: a rejected Login holds the User row's lock while it appends
 * {@code LOGIN_FAILURE}. An append that needed a second connection from the caller's pool would,
 * with every connection held by such a caller, wait out the pool's timeout for a connection none
 * of them would release, and then be lost. Here it cannot: the connection comes from elsewhere.
 *
 * <p>Committed independently of the caller, in a transaction of its own, so the caller's
 * rollback cannot take the event with it. A failure propagates, for the trail to raise its alert
 * and swallow (ADR 0004).
 */
public interface IsolatedAuditEventRepository {

    /** Writes and commits the event on its own connection, or fails without retrying. */
    void append(AuditEvent event);
}

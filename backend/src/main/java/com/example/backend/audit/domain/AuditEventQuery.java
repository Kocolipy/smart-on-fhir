package com.example.backend.audit.domain;

import com.example.backend.paging.domain.PageRequest;
import java.time.Instant;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * What an administrator asked the audit listing for: which events, and which page of them.
 *
 * <p>Every filter is optional and they combine with AND; a {@code null} means "do not narrow by
 * this", as an empty set of operations does. Each one is typed — operations and an outcome from
 * their closed sets, an actor and a resource by stable id, a time window as instants — so a filter
 * can only ever be compared with a stored column, never become part of the statement that compares
 * it.
 *
 * <p>The listing is newest first. A page is addressed by its position in that order, by the same
 * rules as the Users and Groups projections (ADR 0018). Appends only ever land at the front, so a
 * later page shifts by exactly the number of events recorded since it was read, never reorders.
 *
 * @param operations only events of any of these operations; held without repeats, and a
 *                   {@code null} set or element — an empty value in the request — names none
 * @param outcome    only events with this outcome, or {@code null}
 * @param actorId    only events whose actor is this stable id, or {@code null}
 * @param resourceId only events about this resource's stable id, or {@code null}
 * @param from       only events that occurred at or after this instant, or {@code null}
 * @param to         only events that occurred strictly before this instant, or {@code null}
 * @param page       the page of the matching events to read
 */
public record AuditEventQuery(
        Set<AuditOperation> operations,
        AuditOutcome outcome,
        UUID actorId,
        UUID resourceId,
        Instant from,
        Instant to,
        PageRequest page) {

    public AuditEventQuery {
        operations = operations == null
                ? Set.of()
                : operations.stream().filter(Objects::nonNull).collect(Collectors.toUnmodifiableSet());
    }
}

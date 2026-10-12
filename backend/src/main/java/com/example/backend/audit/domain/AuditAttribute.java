package com.example.backend.audit.domain;

import java.util.List;
import java.util.Set;

/**
 * An attribute a write can move, as one member of a closed set, carrying the path name an event
 * records for it. {@link AuditUserAttribute} and {@link AuditGroupAttribute} are the two sets.
 */
public interface AuditAttribute {

    /** The path name an event records for this attribute. */
    String path();

    /** The recorded path names for a set of changed attributes, in declaration order. */
    static <A extends Enum<A> & AuditAttribute> List<String> paths(Set<A> changed) {
        return changed.stream().sorted().map(AuditAttribute::path).toList();
    }
}

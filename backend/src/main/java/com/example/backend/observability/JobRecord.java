package com.example.backend.observability;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * What one scheduled run did to one kind of record: an element of the {@code record} array its
 * {@code job-end} carries ({@link LogEvent#RECORD}), such as
 * {@code {type: audit_event, deleted: 12, batches: 1}}. Counts only — never an id or a value of
 * a record, which are the audit trail's to carry if anyone's.
 *
 * @param type   the kind of record, in the schema's snake case ({@code audit_event})
 * @param counts what the run did to records of that kind, by name, in the order written
 */
public record JobRecord(String type, Map<String, Long> counts) {

    public JobRecord {
        counts = Collections.unmodifiableMap(new LinkedHashMap<>(counts));
    }

    /** A record kind with two counts, written in this order after its {@code type}. */
    public static JobRecord of(String type, String first, long firstCount, String second,
            long secondCount) {
        Map<String, Long> counts = new LinkedHashMap<>();
        counts.put(first, firstCount);
        counts.put(second, secondCount);
        return new JobRecord(type, counts);
    }

    /** As the {@code record} array's element is written: {@code type} first, then the counts. */
    Map<String, Object> field() {
        Map<String, Object> field = new LinkedHashMap<>();
        field.put("type", type);
        field.putAll(counts);
        return field;
    }
}

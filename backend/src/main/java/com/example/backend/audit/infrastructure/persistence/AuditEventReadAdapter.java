package com.example.backend.audit.infrastructure.persistence;

import com.example.backend.audit.domain.AuditEvent;
import com.example.backend.audit.domain.AuditEventPage;
import com.example.backend.audit.domain.AuditEventQuery;
import com.example.backend.audit.domain.AuditEventReader;
import com.example.backend.audit.domain.AuditOperation;
import com.example.backend.audit.domain.AuditOutcome;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Reads the audit listing out of {@code audit_events}.
 *
 * <p>JDBC rather than the JPA mapping, which stays write-only: what an Admin is shown is the
 * stored row, read column by column, not an entity's opinion of it.
 *
 * <p>One fixed statement per query shape, never assembled. Each optional filter is written as
 * {@code (CAST(:x AS type) IS NULL OR column = CAST(:x AS type))} and bound, so an absent filter
 * matches everything and a present one is only ever a compared value — nothing the caller sent is
 * concatenated into the SQL. The casts give Postgres the parameter's type when the bound value is
 * a {@code NULL}, which it cannot otherwise infer.
 *
 * <p>Newest first, with the id as the tiebreak: two events can share an instant, and without a
 * total order a row could appear on two pages, or on none, as the listing is paged.
 */
@Repository
class AuditEventReadAdapter implements AuditEventReader {

    private static final String FILTER = """
             WHERE (CAST(:operation AS VARCHAR) IS NULL OR operation = CAST(:operation AS VARCHAR))
               AND (CAST(:outcome AS VARCHAR) IS NULL OR outcome = CAST(:outcome AS VARCHAR))
               AND (CAST(:actorId AS UUID) IS NULL OR actor_id = CAST(:actorId AS UUID))
               AND (CAST(:resourceId AS UUID) IS NULL OR resource_id = CAST(:resourceId AS UUID))
               AND (CAST(:from AS TIMESTAMPTZ) IS NULL OR occurred_at >= CAST(:from AS TIMESTAMPTZ))
               AND (CAST(:to AS TIMESTAMPTZ) IS NULL OR occurred_at < CAST(:to AS TIMESTAMPTZ))
            """;

    private static final String COUNT = "SELECT COUNT(*) FROM audit_events" + FILTER;

    private static final String PAGE = """
            SELECT id, occurred_at, operation, outcome, actor_id, subject_id, resource_type,
                   resource_id, changed_paths, status_class, error_code, http_method, http_path,
                   request_id, result_count, filter_shape, role_name, permissions, login_method
              FROM audit_events
            """ + FILTER + """
             ORDER BY occurred_at DESC, id DESC
             LIMIT :limit OFFSET :offset
            """;

    /** The separator {@code AuditEventPersistenceAdapter} joins changed paths with. */
    private static final String PATH_SEPARATOR = ",";

    private final NamedParameterJdbcTemplate jdbc;

    AuditEventReadAdapter(DataSource dataSource) {
        this.jdbc = new NamedParameterJdbcTemplate(dataSource);
    }

    /**
     * Counts, then reads the page. There is no short-circuit for a page past the end: the page
     * statement returns no rows there anyway, and a branch that only saves a query is a branch no
     * test can tell apart from its absence. {@code COUNT(*)} always yields a row, so the count is
     * never null.
     */
    @Override
    public AuditEventPage find(AuditEventQuery query) {
        MapSqlParameterSource parameters = filters(query);
        long total = jdbc.queryForObject(COUNT, parameters, Long.class);
        parameters.addValue("limit", query.size());
        parameters.addValue("offset", query.offset());
        List<AuditEvent> events = jdbc.query(PAGE, parameters, (row, index) -> event(row));
        return AuditEventPage.of(query, events, total);
    }

    private static MapSqlParameterSource filters(AuditEventQuery query) {
        return new MapSqlParameterSource()
                .addValue("operation", name(query.operation()), Types.VARCHAR)
                .addValue("outcome", name(query.outcome()), Types.VARCHAR)
                .addValue("actorId", query.actorId(), Types.OTHER)
                .addValue("resourceId", query.resourceId(), Types.OTHER)
                .addValue("from", timestamp(query.from()), Types.TIMESTAMP)
                .addValue("to", timestamp(query.to()), Types.TIMESTAMP);
    }

    private static AuditEvent event(ResultSet row) throws SQLException {
        return new AuditEvent(
                row.getObject("id", UUID.class),
                row.getTimestamp("occurred_at").toInstant(),
                AuditOperation.valueOf(row.getString("operation")),
                AuditOutcome.valueOf(row.getString("outcome")),
                row.getObject("actor_id", UUID.class),
                row.getObject("subject_id", UUID.class),
                row.getString("resource_type"),
                row.getObject("resource_id", UUID.class),
                paths(row.getString("changed_paths")),
                row.getString("status_class"),
                row.getString("error_code"),
                row.getString("http_method"),
                row.getString("http_path"),
                row.getString("request_id"),
                row.getObject("result_count", Integer.class),
                row.getString("filter_shape"),
                row.getString("role_name"),
                paths(row.getString("permissions")),
                row.getString("login_method"));
    }

    /** The persistence adapter stores "nothing changed" as null, so null reads back as empty. */
    private static List<String> paths(String stored) {
        return stored == null ? List.of() : Arrays.asList(stored.split(PATH_SEPARATOR));
    }

    private static String name(Enum<?> value) {
        return value == null ? null : value.name();
    }

    private static Timestamp timestamp(Instant instant) {
        return instant == null ? null : Timestamp.from(instant);
    }
}

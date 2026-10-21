package com.example.backend.audit.infrastructure.persistence;

import com.example.backend.audit.domain.AuditEvent;
import com.example.backend.audit.domain.AuditEventPage;
import com.example.backend.audit.domain.AuditEventQuery;
import com.example.backend.audit.domain.AuditEventReader;
import com.example.backend.audit.domain.AuditOperation;
import com.example.backend.audit.domain.AuditOutcome;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
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
 * concatenated into the SQL. Each cast is to the column's own SQL Server type, so a bound value —
 * a {@code NULL} included — is compared as the column is stored, and an id bound as text is
 * compared as a {@code UNIQUEIDENTIFIER}.
 *
 * <p>Both statements end {@code OPTION (RECOMPILE)} (ADR 0013): with a catch-all filter per
 * column, one cached plan would serve every combination of present and absent filters, and a plan
 * built for one combination can be a scan for another. A recompile lets each listing be planned
 * for the filters it actually has; the listing is a low-volume administrative read, so the
 * compile costs nothing that matters. Both stay whole constant statements.
 *
 * <p>Newest first, with the id as the tiebreak: two events can share an instant, and without a
 * total order a row could appear on two pages, or on none, as the listing is paged. The tiebreak
 * follows SQL Server's {@code UNIQUEIDENTIFIER} order, which is a total order like any other.
 */
@Repository
class AuditEventReadAdapter implements AuditEventReader {

    private static final String FILTER = """
             WHERE (CAST(:operation AS VARCHAR(64)) IS NULL
                    OR operation = CAST(:operation AS VARCHAR(64)))
               AND (CAST(:outcome AS VARCHAR(16)) IS NULL
                    OR outcome = CAST(:outcome AS VARCHAR(16)))
               AND (CAST(:actorId AS UNIQUEIDENTIFIER) IS NULL
                    OR actor_id = CAST(:actorId AS UNIQUEIDENTIFIER))
               AND (CAST(:resourceId AS UNIQUEIDENTIFIER) IS NULL
                    OR resource_id = CAST(:resourceId AS UNIQUEIDENTIFIER))
               AND (CAST(:from AS DATETIMEOFFSET(6)) IS NULL
                    OR occurred_at >= CAST(:from AS DATETIMEOFFSET(6)))
               AND (CAST(:to AS DATETIMEOFFSET(6)) IS NULL
                    OR occurred_at < CAST(:to AS DATETIMEOFFSET(6)))
            """;

    private static final String COUNT = "SELECT COUNT_BIG(*) FROM audit_events" + FILTER + """
             OPTION (RECOMPILE)
            """;

    private static final String PAGE = """
            SELECT id, occurred_at, operation, outcome, actor_id, subject_id, resource_type,
                   resource_id, changed_paths, status_class, error_code, http_method, http_path,
                   request_id, result_count, filter_shape, permissions, login_method, mfa_factor,
                   token_id, replacement_token_id, permissions_before, permissions_after,
                   superuser_changed, group_ids
              FROM audit_events
            """ + FILTER + """
             ORDER BY occurred_at DESC, id DESC
             OFFSET :offset ROWS FETCH NEXT :limit ROWS ONLY
             OPTION (RECOMPILE)
            """;

    /**
     * SQL Server's {@code SNAPSHOT} isolation level as its driver takes it through
     * {@link Connection#setTransactionIsolation} ({@code SQLServerConnection.TRANSACTION_SNAPSHOT};
     * the driver is a runtime dependency only). JDBC names no such level, and neither does
     * Spring's {@code Isolation}.
     */
    private static final int TRANSACTION_SNAPSHOT = 0x1000;

    /** The separator {@code AuditEventPersistenceAdapter} joins changed paths with. */
    private static final String PATH_SEPARATOR = ",";

    private final NamedParameterJdbcTemplate jdbc;

    AuditEventReadAdapter(DataSource dataSource) {
        this.jdbc = new NamedParameterJdbcTemplate(dataSource);
    }

    /**
     * Counts, then reads the page, both in one snapshot. There is no short-circuit for a page past
     * the end: the page statement returns no rows there anyway, and a branch that only saves a
     * query is a branch no test can tell apart from its absence. {@code COUNT_BIG(*)} always
     * yields a row, so the count is never null.
     *
     * <p>Called in the listing's read-only transaction ({@code AuditEventListingService}), whose
     * first statements these are: the transaction is put at {@code SNAPSHOT} isolation before the
     * count, so it reads one version of the table from its first read to its end, and an event
     * appended between the count and the page is in neither (ADR 0013). Read-committed snapshot,
     * the database's default, gives each statement a snapshot of its own; SQL Server's
     * {@code REPEATABLE READ} is a locking level that still lets a new row in. A transaction that
     * has already read under another level cannot switch to {@code SNAPSHOT}, which is why the
     * switch is the listing's first act.
     */
    @Override
    public AuditEventPage find(AuditEventQuery query) {
        jdbc.getJdbcTemplate().execute(AuditEventReadAdapter::readFromOneSnapshot);
        MapSqlParameterSource parameters = filters(query);
        long total = jdbc.queryForObject(COUNT, parameters, Long.class);
        parameters.addValue("limit", query.size());
        parameters.addValue("offset", query.offset());
        List<AuditEvent> events = jdbc.query(PAGE, parameters, (row, index) -> event(row));
        return AuditEventPage.of(query, events, total);
    }

    /**
     * Puts the transaction's connection at {@code SNAPSHOT} isolation through the JDBC API rather
     * than a {@code SET} statement, so the pool sees the change and restores the connection's own
     * level when it is returned: the snapshot is this transaction's, never the next borrower's.
     */
    private static Void readFromOneSnapshot(Connection connection) throws SQLException {
        connection.setTransactionIsolation(TRANSACTION_SNAPSHOT);
        return null;
    }

    private static MapSqlParameterSource filters(AuditEventQuery query) {
        return new MapSqlParameterSource()
                .addValue("operation", name(query.operation()), Types.VARCHAR)
                .addValue("outcome", name(query.outcome()), Types.VARCHAR)
                .addValue("actorId", text(query.actorId()), Types.VARCHAR)
                .addValue("resourceId", text(query.resourceId()), Types.VARCHAR)
                .addValue("from", utc(query.from()), Types.TIMESTAMP_WITH_TIMEZONE)
                .addValue("to", utc(query.to()), Types.TIMESTAMP_WITH_TIMEZONE);
    }

    private static AuditEvent event(ResultSet row) throws SQLException {
        // A false positive of be-audit-sensitive-value: `token_id` and `replacement_token_id` are
        // uuid columns holding a connector token's stable id, never its value or digest
        // (AuditEventRecordingIntegrationTests holds the column types).
        return new AuditEvent( // nosemgrep: be-audit-sensitive-value
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
                paths(row.getString("permissions")),
                row.getString("login_method"),
                row.getString("mfa_factor"),
                row.getObject("token_id", UUID.class),
                row.getObject("replacement_token_id", UUID.class),
                paths(row.getString("permissions_before")),
                paths(row.getString("permissions_after")),
                row.getObject("superuser_changed", Boolean.class),
                paths(row.getString("group_ids")).stream().map(UUID::fromString).toList());
    }

    /** The persistence adapter stores "nothing changed" as null, so null reads back as empty. */
    private static List<String> paths(String stored) {
        return stored == null ? List.of() : Arrays.asList(stored.split(PATH_SEPARATOR));
    }

    private static String name(Enum<?> value) {
        return value == null ? null : value.name();
    }

    /** An id bound as its text, which the statement casts back to a {@code UNIQUEIDENTIFIER}. */
    private static String text(UUID id) {
        return id == null ? null : id.toString();
    }

    /**
     * A bound instant crosses the seam as an offset at UTC, never a {@code java.sql.Timestamp}:
     * a Timestamp carries the JVM's wall clock, which SQL Server compares as UTC (ADR 0013,
     * "Storage is UTC only"; the {@code be-jdbc-timestamp-bind} Semgrep rule).
     */
    private static OffsetDateTime utc(Instant instant) {
        return instant == null ? null : instant.atOffset(ZoneOffset.UTC);
    }
}

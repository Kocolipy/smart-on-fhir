package com.example.backend;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.ColumnMapRowMapper;
import org.springframework.jdbc.core.RowMapper;

/**
 * Reads a row a test selected as a column map, with every {@code UNIQUEIDENTIFIER} column as a
 * {@link UUID}.
 *
 * <p>{@code JdbcTemplate.queryForMap} and {@code queryForList} read each column with an untyped
 * {@code getObject}, and the SQL Server driver answers that for a {@code UNIQUEIDENTIFIER} with
 * the upper-case text of the id (ADR 0015, spike V2), which equals neither the {@code UUID} nor
 * its {@code toString()}. This mapper asks for the column as a {@code UUID} instead, so a test
 * compares the id it holds with the id the row holds. Every other column reads as before.
 */
public final class UuidColumnRows {

    /** Pass to {@code jdbc.query} for a list of rows, or {@code jdbc.queryForObject} for one. */
    public static final RowMapper<Map<String, Object>> ROWS = new ColumnMapRowMapper() {
        @Override
        protected Object getColumnValue(ResultSet rs, int index) throws SQLException {
            return "uniqueidentifier".equalsIgnoreCase(rs.getMetaData().getColumnTypeName(index))
                    ? rs.getObject(index, UUID.class)
                    : super.getColumnValue(rs, index);
        }
    };

    private UuidColumnRows() {
    }
}

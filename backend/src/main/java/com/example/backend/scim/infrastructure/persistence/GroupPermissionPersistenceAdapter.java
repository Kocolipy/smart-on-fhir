package com.example.backend.scim.infrastructure.persistence;

import com.example.backend.authorization.domain.GroupPermissionValues;
import com.example.backend.authorization.domain.GroupPermissions;
import com.example.backend.authorization.domain.Permission;
import com.example.backend.authorization.domain.StoredGroupPermission;
import com.example.backend.scim.domain.ReservedResourceName;
import com.example.backend.scim.domain.ScimGroupPermissionStore;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * The Groups' Permissions in {@code group_permission}, and the Admin group by its reservation
 * marker: the one source of what a Group confers (ADR 0015).
 *
 * <p>JDBC rather than JPA: a row is a pair of values with no lifecycle of its own, read in bulk by
 * Group id. Every statement joins the caller's transaction through the shared data source, so a
 * read inside a write sees what the write has done — a deleted Group's rows are already gone.
 *
 * <p>A stored name is read through {@link GroupPermissionValues}, so a row naming a Permission the
 * vocabulary no longer has is refused rather than skipped; startup refuses it first, by
 * {@link #findAll}.
 */
@Repository
class GroupPermissionPersistenceAdapter implements GroupPermissions, ScimGroupPermissionStore {

    /** A whole statement as a constant, never assembled; see the local Semgrep ruleset. */
    private static final String SELECT_FOR_GROUPS = """
            SELECT group_id, permission FROM group_permission WHERE group_id IN (:groupIds)""";

    private static final String SELECT_ALL = """
            SELECT group_id, permission FROM group_permission""";

    private static final String COUNT_FOR_GROUP = """
            SELECT COUNT(*) FROM group_permission WHERE group_id = :groupId""";

    private static final String DELETE_FOR_GROUP = """
            DELETE FROM group_permission WHERE group_id = :groupId""";

    private static final String INSERT = """
            INSERT INTO group_permission (group_id, permission) VALUES (:groupId, :permission)""";

    private static final String SELECT_ADMIN_GROUP = """
            SELECT id FROM scim_resources WHERE reserved_name = :reservedName""";

    private final NamedParameterJdbcTemplate jdbc;

    GroupPermissionPersistenceAdapter(DataSource dataSource) {
        this.jdbc = new NamedParameterJdbcTemplate(dataSource);
    }

    @Override
    public Map<UUID, Set<Permission>> carriedBy(Collection<UUID> groupIds) {
        List<StoredGroupPermission> rows = new ArrayList<>();
        // Chunked: the Groups a deletion's members or the whole projection name are not capped.
        for (List<UUID> chunk : BoundIdChunks.of(groupIds)) {
            rows.addAll(jdbc.query(SELECT_FOR_GROUPS,
                    new MapSqlParameterSource("groupIds", chunk), GroupPermissionPersistenceAdapter::row));
        }
        return GroupPermissionValues.validated(rows);
    }

    @Override
    public boolean confersSuperuser(Collection<UUID> groupIds) {
        if (groupIds.isEmpty()) {
            return false;
        }
        List<UUID> adminGroup = jdbc.queryForList(SELECT_ADMIN_GROUP,
                new MapSqlParameterSource(
                        "reservedName", ReservedResourceName.ADMIN_GROUP.storedValue()),
                UUID.class);
        return adminGroup.stream().anyMatch(groupIds::contains);
    }

    @Override
    public List<StoredGroupPermission> findAll() {
        return jdbc.query(SELECT_ALL, GroupPermissionPersistenceAdapter::row);
    }

    @Override
    public boolean carriesAny(UUID groupId) {
        Long count = jdbc.queryForObject(COUNT_FOR_GROUP,
                new MapSqlParameterSource("groupId", groupId), Long.class);
        return count != null && count > 0;
    }

    /**
     * Deletes the Group's rows and inserts the new set, in the caller's transaction: a reader in
     * another transaction sees the old set or the new one, never the gap between (RCSI).
     */
    @Override
    public void replace(UUID groupId, Set<Permission> permissions) {
        jdbc.update(DELETE_FOR_GROUP, new MapSqlParameterSource("groupId", groupId));
        add(groupId, permissions);
    }

    @Override
    public void add(UUID groupId, Set<Permission> permissions) {
        for (Permission permission : permissions) {
            Map<String, Object> values = new HashMap<>();
            values.put("groupId", groupId);
            values.put("permission", permission.value());
            jdbc.update(INSERT, values);
        }
    }

    private static StoredGroupPermission row(ResultSet row, int index)
            throws SQLException {
        return new StoredGroupPermission(
                row.getObject("group_id", UUID.class), row.getString("permission"));
    }
}

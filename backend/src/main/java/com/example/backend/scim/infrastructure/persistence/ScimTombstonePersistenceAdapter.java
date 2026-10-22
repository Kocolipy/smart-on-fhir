package com.example.backend.scim.infrastructure.persistence;

import com.example.backend.scim.domain.ScimResourceType;
import com.example.backend.scim.domain.ScimTombstoneRepository;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Writes SCIM tombstones.
 *
 * <p>JDBC rather than JPA: a tombstone is one insert that is never read back by the
 * application, so an entity would be a mapped type with no reader. The statement joins the
 * caller's transaction through the shared data source, so a rolled-back delete leaves no
 * tombstone behind.
 *
 * <p>One tombstone per id, ever: the first deletion's. Only the seed reuses an id — a development
 * fixture Group deleted over SCIM is created again under its fixed id by the next startup — and
 * deleting it a second time keeps the tombstone already standing rather than colliding on it.
 * The application may insert and read tombstones, never change one, so the first is the one kept.
 */
@Repository
class ScimTombstonePersistenceAdapter implements ScimTombstoneRepository {

    /** A whole statement as a constant, never assembled; see the local Semgrep ruleset. */
    private static final String INSERT_TOMBSTONE = """
            INSERT INTO scim_tombstones (resource_id, resource_type, deleted_at)
            SELECT ?, ?, ?
             WHERE NOT EXISTS (SELECT 1 FROM scim_tombstones WITH (UPDLOCK, HOLDLOCK)
                                WHERE resource_id = ?)""";

    private final JdbcTemplate jdbc;

    ScimTombstonePersistenceAdapter(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void record(ScimResourceType type, UUID id, Instant deletedAt) {
        jdbc.update(INSERT_TOMBSTONE, id, type.resourceTypeName(),
                OffsetDateTime.ofInstant(deletedAt, ZoneOffset.UTC), id);
    }
}

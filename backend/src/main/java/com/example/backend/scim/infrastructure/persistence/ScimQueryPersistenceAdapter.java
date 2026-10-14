package com.example.backend.scim.infrastructure.persistence;

import com.example.backend.scim.domain.ScimQuery;
import com.example.backend.scim.domain.ScimQueryRepository;
import com.example.backend.scim.domain.ScimResourceType;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Evaluates SCIM collection queries in the database.
 *
 * <p>SQL rather than JPQL or the Criteria API, because the query protocol needs things neither
 * expresses directly: an explicit position for missing sort values, a byte-order collation, a
 * {@code UNION ALL} of two resource types ordered as one, and correlated subqueries in an
 * {@code ORDER BY}. Every statement comes from {@link ScimQuerySql}, which binds every literal;
 * nothing a client sent is concatenated into one.
 *
 * <p>Returns identities only. The page's resources are loaded afterwards through the ordinary
 * repositories, so there is one way a User or a Group is assembled. A page of no rows
 * ({@code count=0}) is never queried, since SQL Server rejects {@code FETCH NEXT 0 ROWS}.
 */
@Repository
class ScimQueryPersistenceAdapter implements ScimQueryRepository {

    private final NamedParameterJdbcTemplate jdbc;

    ScimQueryPersistenceAdapter(DataSource dataSource) {
        this.jdbc = new NamedParameterJdbcTemplate(dataSource);
    }

    @Override
    public ScimQuery.Result query(ScimQuery query, UUID connectorId, String baseUri) {
        ScimQuerySql countSql = new ScimQuerySql(connectorId, baseUri);
        String count = countSql.count(query);
        Long total = jdbc.queryForObject(count, countSql.parameters(), Long.class);
        long totalResults = total == null ? 0 : total;
        if (query.page().count() == 0 || totalResults == 0) {
            return new ScimQuery.Result(totalResults, List.of());
        }
        ScimQuerySql pageSql = new ScimQuerySql(connectorId, baseUri);
        String page = pageSql.page(query);
        List<ScimQuery.Hit> hits = jdbc.query(page, pageSql.parameters(), (row, index) ->
                new ScimQuery.Hit(type(row.getString("kind")), row.getObject("id", UUID.class)));
        return new ScimQuery.Result(totalResults, hits);
    }

    private static ScimResourceType type(String stored) {
        for (ScimResourceType type : ScimResourceType.values()) {
            if (type.resourceTypeName().equals(stored)) {
                return type;
            }
        }
        throw new IllegalStateException("scim_resources holds an unknown resource type");
    }
}

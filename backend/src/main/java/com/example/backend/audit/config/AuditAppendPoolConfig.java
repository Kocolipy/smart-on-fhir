package com.example.backend.audit.config;

import com.zaxxer.hikari.HikariDataSource;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The audit append pool: the small, dedicated connection pool fail-open appends — refusals, and
 * events recorded after their change committed — commit on (ADR 0004, 2026-10-13 addendum).
 *
 * <p>A fail-open append used to run {@code PROPAGATION_REQUIRES_NEW} on the main pool, and
 * {@code REQUIRES_NEW} keeps the caller's connection checked out, so every such append inside an
 * open transaction needed a second connection. A refusal is recorded while its caller holds one —
 * a rejected Login holds the User row's lock while it appends — so as many overlapping refusals as
 * the pool has connections held all of them, each waited the pool's whole timeout for another,
 * and every other request failed meanwhile. A pool of its own breaks the cycle: an append never
 * waits for a connection a refusing caller holds. A bigger main pool would only move the
 * threshold; deferring to after completion keeps the caller's connection checked out all the
 * same; and a nested transaction would lose the event with the caller's rollback.
 *
 * <p><strong>Beside the main datasource, not instead of it.</strong> Both beans are declared
 * {@code defaultCandidate = false}: they are injected only where asked for by qualifier, so the
 * main pool stays the one {@link DataSource} every other bean is given, and Boot's DataSource,
 * JPA, transaction-manager, {@code JdbcTemplate} and Flyway auto-configurations — which ignore a
 * bean that is not a default candidate — still create the main pool and the one, primary, JPA
 * transaction manager. There is no second transaction manager: the pool's connections
 * auto-commit, so each append is a transaction of its own.
 *
 * <p><strong>The main pool's connection settings, not a copy of them.</strong> The URL, the
 * username, the password and the {@code connection-init-sql} are read off the main pool, so an
 * append connects as the same login and runs as the same runtime role ({@code SET ROLE
 * backend_app} in a deployment) as every other statement the service issues; there is no second
 * set of credentials and no fallback for one. Only the pool's size and its connection timeout are
 * its own ({@link AuditAppendPoolProperties}). The pool starts lazily, on the first append, so a
 * context that records no refusal opens no connection here.
 */
@Configuration
@EnableConfigurationProperties(AuditAppendPoolProperties.class)
public class AuditAppendPoolConfig {

    /**
     * The qualifier the append adapter injects the template by. Spelled again there, since a
     * configuration and a persistence adapter may not depend on each other.
     */
    static final String AUDIT_APPEND_JDBC = "auditAppendJdbcTemplate";

    /** The pool's name in its metrics and its log lines. */
    static final String POOL_NAME = "audit-append";

    /**
     * The pool, connected as the main one is.
     *
     * @param main the main pool, the one default {@link DataSource} — read for its connection
     *             settings, never used
     */
    @Bean(defaultCandidate = false)
    @Qualifier(POOL_NAME)
    public HikariDataSource auditAppendDataSource(
            HikariDataSource main, AuditAppendPoolProperties settings) {
        HikariDataSource pool = new HikariDataSource();
        pool.setPoolName(POOL_NAME);
        pool.setJdbcUrl(main.getJdbcUrl());
        pool.setUsername(main.getUsername());
        pool.setPassword(main.getPassword());
        pool.setDriverClassName(main.getDriverClassName());
        pool.setConnectionInitSql(main.getConnectionInitSql());
        pool.setMaximumPoolSize(settings.size());
        pool.setConnectionTimeout(settings.timeout().toMillis());
        pool.setAutoCommit(true);
        return pool;
    }

    /** The template the append adapter writes through, on the pool and nothing else. */
    @Bean(defaultCandidate = false)
    @Qualifier(AUDIT_APPEND_JDBC)
    public JdbcTemplate auditAppendJdbcTemplate(@Qualifier(POOL_NAME) DataSource pool) {
        return new JdbcTemplate(pool);
    }
}

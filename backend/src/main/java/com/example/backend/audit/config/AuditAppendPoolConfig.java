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
 * main pool ({@code database.config.DataSourceConfig}) stays the one {@link DataSource} every
 * other bean is given, and Boot's JPA, transaction-manager, {@code JdbcTemplate} and Flyway
 * auto-configurations — which ignore a bean that is not a default candidate — still use the main
 * pool and create the one JPA transaction manager. This pool adds no transaction manager: its
 * connections auto-commit, so each append is a transaction of its own.
 *
 * <p><strong>The main pool's login, not a copy of its settings.</strong> The URL, the username and
 * the password are read off the main pool, so an append connects as the app login — a member of
 * {@code backend_app} only, which may insert an audit event and never change or delete one — as
 * every other statement the service issues; there is no second set of credentials, no fallback
 * for one, and never the owner login. Nothing runs on a new connection: the login's privileges
 * are the pool's (ADR 0015, "The audit append pool needs its own role switch", resolved by the
 * separate logins of #66). Only the pool's size and its connection timeout are its own
 * ({@link AuditAppendPoolProperties}). The pool starts lazily, on the first append, so a context
 * that records no refusal opens no connection here.
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
        HikariDataSource pool = SecondaryPools.onTheMainPoolsDatabase(
                main, POOL_NAME, settings.size(), settings.timeout());
        pool.setUsername(main.getUsername());
        pool.setPassword(main.getPassword());
        return pool;
    }

    /** The template the append adapter writes through, on the pool and nothing else. */
    @Bean(defaultCandidate = false)
    @Qualifier(AUDIT_APPEND_JDBC)
    public JdbcTemplate auditAppendJdbcTemplate(@Qualifier(POOL_NAME) DataSource pool) {
        return new JdbcTemplate(pool);
    }
}

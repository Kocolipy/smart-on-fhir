package com.example.backend.audit.config;

import com.zaxxer.hikari.HikariDataSource;
import java.time.Duration;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;

/**
 * The retention pool: the audit retention job's own connections, as the retention login (ADR
 * 0015, "Runtime model"; #66).
 *
 * <p>The retention login is a member of {@code backend_audit_retention} only: it may read and
 * delete audit events and nothing else, and it is the one principal the audit table's guard lets
 * delete. No other code reaches these connections, so the right to delete an event is held by
 * the retention adapter alone — by the login it connects as, not by anything it does on a
 * connection.
 *
 * <p><strong>Beside the main pool, not instead of it.</strong> Every bean here is declared
 * {@code defaultCandidate = false}, as the audit append pool's are: injected only by qualifier,
 * so the main pool stays the one {@link DataSource} and its JPA transaction manager the one
 * {@code @Transactional} resolves to. The scheduled-job lock stays on the main pool, where the
 * app login's grants are; a retention run's deletes commit in transactions of this pool's own
 * transaction manager, inside the run's lock transaction (ADR 0005).
 *
 * <p><strong>Its own login, the main pool's database.</strong> The URL and driver are read off the
 * main pool; the username and password bind from {@code app.datasource.retention.*}
 * ({@code DATABASE_RETENTION_USERNAME} and {@code DATABASE_RETENTION_PASSWORD}, with no fallback),
 * and nothing is copied from the app login. Two connections at most, and a 10 s connection
 * timeout: the job is one statement at a time, once a day. The pool starts on its first use.
 */
@Configuration
public class AuditRetentionPoolConfig {

    /** The pool's name in its metrics, its health entry and its log lines. */
    static final String POOL_NAME = "retention";

    /**
     * The qualifiers the retention adapter injects by. Spelled again there, since a configuration
     * and a persistence adapter may not depend on each other.
     */
    static final String RETENTION_JDBC = "auditRetentionJdbcTemplate";

    static final String RETENTION_TRANSACTIONS = "auditRetentionTransactionManager";

    static final int MAXIMUM_POOL_SIZE = 2;

    static final Duration CONNECTION_TIMEOUT = Duration.ofSeconds(10);

    /**
     * The pool, on the main pool's database; its login binds from
     * {@code app.datasource.retention}.
     *
     * @param main the main pool, the one default {@link DataSource} — read for its URL and
     *             driver, never used
     */
    @Bean(defaultCandidate = false)
    @Qualifier(POOL_NAME)
    @ConfigurationProperties("app.datasource.retention")
    public HikariDataSource auditRetentionDataSource(HikariDataSource main) {
        return SecondaryPools.onTheMainPoolsDatabase(
                main, POOL_NAME, MAXIMUM_POOL_SIZE, CONNECTION_TIMEOUT);
    }

    /** The template the retention adapter deletes through, on this pool and no other. */
    @Bean(defaultCandidate = false)
    @Qualifier(RETENTION_JDBC)
    public JdbcTemplate auditRetentionJdbcTemplate(@Qualifier(POOL_NAME) DataSource pool) {
        return new JdbcTemplate(pool);
    }

    /** The transactions the retention adapter's deletes commit in, on this pool. */
    @Bean(defaultCandidate = false)
    @Qualifier(RETENTION_TRANSACTIONS)
    public DataSourceTransactionManager auditRetentionTransactionManager(
            @Qualifier(POOL_NAME) DataSource pool) {
        return new DataSourceTransactionManager(pool);
    }
}

package com.example.backend.database.config;

import com.zaxxer.hikari.HikariDataSource;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.jdbc.autoconfigure.DataSourceProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

/**
 * The main pool: the connections every request, the scheduled-job lock and JPA run on, as the
 * app login (ADR 0015, "Runtime model"; #66).
 *
 * <p>Declared here rather than left to Boot because the service now has more than one pool —
 * the audit append pool and the retention pool beside this one — and the main one must be the
 * pool by declaration, not by being the only {@code DataSource} Boot happened to see. It is
 * a Hikari pool connected as Boot connects its own: from {@code spring.datasource.*} ({@code DATABASE_URL},
 * {@code DATABASE_USERNAME}, {@code DATABASE_PASSWORD}), with every {@code spring.datasource.hikari.*}
 * setting bound onto it, so a deployment's existing settings keep reaching it. Boot's own pool
 * backs off, and its JPA, transaction-manager, {@code JdbcTemplate}, health and metrics
 * configurations use this one.
 *
 * <p>The app login is a member of {@code backend_app} only, and nothing runs on a new connection
 * to change that: there is no {@code connection-init-sql}. The pool's privileges are exactly the
 * login's, enforced by the server.
 *
 * <p>Two defaults are this class's, and a {@code spring.datasource.hikari.*} setting may still
 * override either: the pool's name, {@value #POOL_NAME}, in its metrics and its health entry
 * (lm-16), and a 10 s connection timeout (ADR 0015's bounded timeouts).
 */
@Configuration
public class DataSourceConfig {

    /** The main pool's name in its metrics, its health entry and its log lines. */
    public static final String POOL_NAME = "app";

    static final Duration CONNECTION_TIMEOUT = Duration.ofSeconds(10);

    @Bean
    @Primary
    @ConfigurationProperties("spring.datasource.hikari")
    public HikariDataSource dataSource(DataSourceProperties properties) {
        HikariDataSource pool = new HikariDataSource();
        pool.setJdbcUrl(properties.determineUrl());
        pool.setUsername(properties.determineUsername());
        pool.setPassword(properties.determinePassword());
        pool.setDriverClassName(properties.determineDriverClassName());
        pool.setPoolName(POOL_NAME);
        pool.setConnectionTimeout(CONNECTION_TIMEOUT.toMillis());
        return pool;
    }
}

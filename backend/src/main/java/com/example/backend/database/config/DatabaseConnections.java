package com.example.backend.database.config;

import com.zaxxer.hikari.HikariDataSource;
import java.time.Duration;

/**
 * The database connections module: the one place that knows how a pool reaches the service's
 * database. A caller asks it for <em>a pool for login X</em> — the pool's name, its login, its size
 * and how long a caller waits for a connection — and says nothing about how to get there.
 *
 * <p>How to get there is this module's alone, and alike for every pool: the JDBC URL and the
 * driver from {@code spring.datasource.*} ({@code DATABASE_URL}), and every driver property in
 * {@link DatabaseConnectionProperties}, where a connection's TLS settings, its trust store and the
 * driver's login timeout live once they leave the URL (ADR 0013). So the main pool, the audit
 * append pool and the retention pool cannot drift apart on any of it, and a change of how the
 * service reaches its database — the SQL Server cutover's included — is a change here. The migrate
 * step opens no pool; its owner connection takes the same driver properties from
 * {@link MigrationConnection}.
 *
 * <p>A pool it builds starts on its first use, auto-commits as Hikari's pools do by default, and
 * runs nothing on a new connection: there is no {@code connection-init-sql}, so a pool's
 * privileges are exactly its login's, enforced by the server (ADR 0013, "Runtime model").
 */
public final class DatabaseConnections {

    private final String url;

    private final String driverClassName;

    private final DatabaseLogin appLogin;

    private final DatabaseConnectionProperties connectionProperties;

    /**
     * @param url                  the database every pool reaches
     * @param driverClassName      the driver every pool reaches it through
     * @param appLogin             the app login, the main pool's
     * @param connectionProperties the driver properties every connection is opened with
     */
    public DatabaseConnections(String url, String driverClassName, DatabaseLogin appLogin,
            DatabaseConnectionProperties connectionProperties) {
        this.url = url;
        this.driverClassName = driverClassName;
        this.appLogin = appLogin;
        this.connectionProperties = connectionProperties;
    }

    /**
     * The app login — {@code DATABASE_USERNAME} and {@code DATABASE_PASSWORD}, a member of
     * {@code backend_app} only — which the main pool and the audit append pool connect as.
     */
    public DatabaseLogin appLogin() {
        return appLogin;
    }

    /** A pool for {@code login}, holding at most {@code size} connections. */
    public HikariDataSource pool(String name, DatabaseLogin login, int size, Duration timeout) {
        HikariDataSource pool = pool(name, login, timeout);
        pool.setMaximumPoolSize(size);
        return pool;
    }

    /**
     * A pool for {@code login} sized by Hikari's own default, for a caller that binds its sizing
     * itself — the main pool, which binds every {@code spring.datasource.hikari.*} setting.
     */
    public HikariDataSource pool(String name, DatabaseLogin login, Duration timeout) {
        HikariDataSource pool = new HikariDataSource();
        pool.setJdbcUrl(url);
        pool.setDriverClassName(driverClassName);
        pool.setDataSourceProperties(connectionProperties.asDriverProperties());
        pool.setUsername(login.username());
        pool.setPassword(login.password());
        pool.setPoolName(name);
        pool.setConnectionTimeout(timeout.toMillis());
        return pool;
    }

    /**
     * A database login: who a pool connects as. Either half may be unset, and a pool connecting as
     * a login with none is refused by the server at its first connection — there is no fallback.
     *
     * @param username the login's name
     * @param password its password, never written by {@link #toString()}
     */
    public record DatabaseLogin(String username, String password) {

        @Override
        public String toString() {
            return "DatabaseLogin[username=" + username + ", password=<redacted>]";
        }
    }
}

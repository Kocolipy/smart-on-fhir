package com.example.backend;

import javax.sql.DataSource;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * This context's database, reached as one specific database login rather than through the
 * service's pools: what a test needs to speak as a principal the service never is, or to prove
 * what each login may do.
 *
 * <ul>
 *   <li>{@link #owner()} — the owner login, which owns the schema. For a test that changes it
 *       (a forced constraint or trigger) and for proving the owner is refused by the audit table's
 *       own guard.
 *   <li>{@link #app()} and {@link #retention()} — the two logins the service's pools connect as,
 *       on connections of the test's own.
 *   <li>{@link #superuser()} — the container's superuser, an observer only: it reads another
 *       login's activity in {@code pg_stat_activity}, which a login without
 *       {@code pg_read_all_stats} sees with its query text hidden. It is not a login the service
 *       or the deployment has, so no claim about privileges is ever made through it.
 * </ul>
 *
 * <p>Each opens a plain connection per use, outside every pool, and {@link Login#transactions()}
 * gives one transaction on it when a test needs several statements on the same connection.
 * {@link ContainerTestConfiguration} creates the logins and declares this bean.
 */
public final class DatabaseLogins {

    private final Environment environment;

    DatabaseLogins(Environment environment) {
        this.environment = environment;
    }

    public Login owner() {
        return login(ContainerTestConfiguration.OWNER_LOGIN, ContainerTestConfiguration.OWNER_PASSWORD);
    }

    public Login app() {
        return login(ContainerTestConfiguration.APP_LOGIN, ContainerTestConfiguration.APP_PASSWORD);
    }

    public Login retention() {
        return login(ContainerTestConfiguration.RETENTION_LOGIN,
                ContainerTestConfiguration.RETENTION_PASSWORD);
    }

    public Login superuser() {
        return login(ContainerTestConfiguration.superuserName(),
                ContainerTestConfiguration.superuserPassword());
    }

    private Login login(String name, String password) {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                environment.getRequiredProperty("spring.datasource.url"), name, password);
        return new Login(dataSource);
    }

    /** One login's connections to this context's database. */
    public static final class Login {

        private final DataSource dataSource;

        private Login(DataSource dataSource) {
            this.dataSource = dataSource;
        }

        public JdbcTemplate jdbc() {
            return new JdbcTemplate(dataSource);
        }

        /** Transactions whose statements, through {@link #jdbc()}, share one connection. */
        public TransactionTemplate transactions() {
            return new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        }
    }
}

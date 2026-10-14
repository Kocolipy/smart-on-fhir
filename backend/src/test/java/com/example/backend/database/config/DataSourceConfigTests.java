package com.example.backend.database.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.zaxxer.hikari.HikariDataSource;
import java.util.List;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * The main pool, declared by the service rather than by Boot, and still bound from the
 * {@code spring.datasource.*} settings a deployment already sets. Started without opening a
 * connection: the pool starts on its first use.
 */
class DataSourceConfigTests {

    private final ApplicationContextRunner contexts = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(DataSourceAutoConfiguration.class))
            .withUserConfiguration(DataSourceConfig.class)
            .withPropertyValues(
                    "spring.datasource.url=jdbc:postgresql://db.internal:5432/backend",
                    "spring.datasource.username=backend_app_login",
                    "spring.datasource.password=the-app-logins");

    @Test
    void theMainPoolConnectsAsTheConfiguredLogin() {
        contexts.run(context -> {
            HikariDataSource pool = context.getBean(HikariDataSource.class);
            assertThat(List.of(pool.getJdbcUrl(), pool.getUsername(), pool.getPassword()))
                    .containsExactly("jdbc:postgresql://db.internal:5432/backend",
                            "backend_app_login", "the-app-logins");
        });
    }

    /** The driver is the one the URL names, as Boot's own pool would have chosen it. */
    @Test
    void theMainPoolUsesTheDriverItsUrlNames() {
        contexts.run(context -> assertThat(context.getBean(HikariDataSource.class)
                .getDriverClassName()).isEqualTo("org.postgresql.Driver"));
    }

    @Test
    void theHikariSettingsStillBindToTheMainPool() {
        contexts.withPropertyValues("spring.datasource.hikari.maximumPoolSize=7")
                .run(context -> assertThat(context.getBean(HikariDataSource.class)
                        .getMaximumPoolSize()).isEqualTo(7));
    }

    /** Named {@code app} in its metrics and its health entry, with the bounded 10 s timeout. */
    @Test
    void theMainPoolIsNamedAppAndWaitsTenSecondsForAConnection() {
        contexts.run(context -> {
            HikariDataSource pool = context.getBean(HikariDataSource.class);
            assertThat(List.<Object>of(pool.getPoolName(), pool.getConnectionTimeout()))
                    .containsExactly("app", 10_000L);
        });
    }

    /** Boot's own pool backs off: the declared one is the only {@code DataSource} there is. */
    @Test
    void theDeclaredPoolIsTheContextsOnlyDataSource() {
        contexts.run(context -> assertThat(context.getBeansOfType(DataSource.class))
                .containsOnlyKeys("dataSource"));
    }

    /** No statement runs on a new connection: the login's privileges are the pool's. */
    @Test
    void theMainPoolRunsNoConnectionInitSql() {
        contexts.run(context -> assertThat(
                context.getBean(HikariDataSource.class).getConnectionInitSql()).isNull());
    }
}

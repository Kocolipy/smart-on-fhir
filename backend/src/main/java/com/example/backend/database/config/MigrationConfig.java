package com.example.backend.database.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * What a migration needs beyond Boot's own Flyway set-up: the check on the login names it puts
 * into DDL ({@link MigrationLoginNames}), and the driver properties every connection to the
 * database is opened with, given to Flyway's owner connection ({@link MigrationConnection}).
 *
 * <p>Imported by the migrate step ({@link MigrationStep}) and found by the service's own scan,
 * so the migrate step, a local run and the test suite — the three places Flyway is on — check
 * the names and connect alike. In a deployed service Flyway is off, and neither is ever asked.
 */
@Configuration
@EnableConfigurationProperties(DatabaseConnectionProperties.class)
public class MigrationConfig {

    @Bean
    MigrationLoginNames migrationLoginNames() {
        return new MigrationLoginNames();
    }

    @Bean
    MigrationConnection migrationConnection(DatabaseConnectionProperties connectionProperties) {
        return new MigrationConnection(connectionProperties);
    }
}

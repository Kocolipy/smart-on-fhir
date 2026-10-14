package com.example.backend.database.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * What a migration needs beyond Boot's own Flyway set-up: the check on the login names it puts
 * into DDL ({@link MigrationLoginNames}).
 *
 * <p>Imported by the migrate step ({@link MigrationStep}) and found by the service's own scan,
 * so the migrate step, a local run and the test suite — the three places Flyway is on — check
 * the names alike. In a deployed service Flyway is off, and the check is never asked.
 */
@Configuration
public class MigrationConfig {

    @Bean
    MigrationLoginNames migrationLoginNames() {
        return new MigrationLoginNames();
    }
}

package com.example.backend.database.config;

import org.flywaydb.core.api.configuration.FluentConfiguration;
import org.springframework.boot.flyway.autoconfigure.FlywayConfigurationCustomizer;
import org.springframework.jdbc.datasource.AbstractDriverBasedDataSource;

/**
 * Opens Flyway's owner connection with the driver properties every pool's connections are opened
 * with ({@link DatabaseConnectionProperties}), so the migrate step reaches the database as the
 * service does — encrypted, against the same trust store, within the same login timeout — without
 * starting a pool.
 *
 * <p>Wherever migrations run, Flyway connects as the owner login on a connection of its own: Boot
 * builds it a plain driver-based {@code DataSource} from {@code spring.flyway.*}, outside every
 * pool, and knows nothing of the pools' driver properties. This adds them, as Boot builds Flyway's
 * configuration and before any connection is opened. The owner's username and password stay
 * Flyway's own and win over a property of the same name.
 *
 * <p>Should Flyway ever be handed a connection of another kind while driver properties are set,
 * the start fails rather than migrate without them.
 */
public final class MigrationConnection implements FlywayConfigurationCustomizer {

    private final DatabaseConnectionProperties connectionProperties;

    public MigrationConnection(DatabaseConnectionProperties connectionProperties) {
        this.connectionProperties = connectionProperties;
    }

    @Override
    public void customize(FluentConfiguration configuration) {
        if (connectionProperties.dataSourceProperties().isEmpty()) {
            return;
        }
        if (!(configuration.getDataSource() instanceof AbstractDriverBasedDataSource owner)) {
            throw new IllegalStateException("Flyway's connection is not a driver-based one of its"
                    + " own, so the driver properties every connection is opened with cannot"
                    + " reach it");
        }
        owner.setConnectionProperties(connectionProperties.asDriverProperties());
    }
}

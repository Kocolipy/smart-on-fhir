package com.example.backend.database.config;

import java.util.Map;
import java.util.Properties;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The driver properties every connection to the service's database is opened with, read from
 * {@code spring.datasource.hikari.data-source-properties} — the key the main pool has always bound
 * them from, so a deployment's existing settings keep reaching it.
 *
 * <p>This is where a connection's TLS settings, its trust store and that store's password, and the
 * driver's login timeout belong once they leave the JDBC URL (ADR 0015, "The RDS CA trust store is
 * driver-scoped" and "Bounded timeouts"). {@link DatabaseConnections} gives them to every pool,
 * and {@link MigrationConnection} to the migrate step's owner connection, so no connection the
 * service opens is made without them. Each key is passed to the driver as bound: write a
 * mixed-case name in brackets ({@code data-source-properties.[trustStorePassword]}) so it reaches
 * the driver as written.
 *
 * @param dataSourceProperties the properties, by name; none when unset
 */
@ConfigurationProperties("spring.datasource.hikari")
public record DatabaseConnectionProperties(Map<String, String> dataSourceProperties) {

    public DatabaseConnectionProperties {
        dataSourceProperties =
                dataSourceProperties == null ? Map.of() : Map.copyOf(dataSourceProperties);
    }

    /** The properties as a driver takes them: a fresh copy, which the caller may keep. */
    Properties asDriverProperties() {
        Properties properties = new Properties();
        properties.putAll(dataSourceProperties);
        return properties;
    }
}

package com.example.backend.audit.config;

import com.example.backend.database.config.DatabaseConnections;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The retention login, which the retention pool connects as: {@code DATABASE_RETENTION_USERNAME}
 * and {@code DATABASE_RETENTION_PASSWORD}, bound from {@code app.datasource.retention.*} with no
 * fallback and nothing copied from the app login (ADR 0013, "Runtime model"; #66).
 *
 * @param username the login's name
 * @param password its password, never written by {@link #toString()}
 */
@ConfigurationProperties("app.datasource.retention")
public record AuditRetentionLoginProperties(String username, String password) {

    /** The login, as the database connections module takes one. */
    DatabaseConnections.DatabaseLogin login() {
        return new DatabaseConnections.DatabaseLogin(username, password);
    }

    @Override
    public String toString() {
        return login().toString();
    }
}

package com.example.backend.audit.config;

import com.zaxxer.hikari.HikariDataSource;
import java.time.Duration;

/**
 * The audit slice's own pools beside the main one — the append pool and the retention pool —
 * built alike: on the main pool's database and driver, under their own name, size and connection
 * timeout. Which login a pool connects as is its configuration's to set. Each starts on its first
 * use, and its connections auto-commit, as Hikari's do by default.
 */
final class SecondaryPools {

    private SecondaryPools() {
    }

    /**
     * A pool on the main pool's database.
     *
     * @param main the main pool, read for its URL and driver, never used
     */
    static HikariDataSource onTheMainPoolsDatabase(
            HikariDataSource main, String name, int size, Duration timeout) {
        HikariDataSource pool = new HikariDataSource();
        pool.setPoolName(name);
        pool.setJdbcUrl(main.getJdbcUrl());
        pool.setDriverClassName(main.getDriverClassName());
        pool.setMaximumPoolSize(size);
        pool.setConnectionTimeout(timeout.toMillis());
        return pool;
    }
}

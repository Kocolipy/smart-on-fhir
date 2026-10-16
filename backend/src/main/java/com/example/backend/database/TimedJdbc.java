package com.example.backend.database;

import java.time.Duration;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * A template of a caller's own over a pool's connections, with every statement bounded by a
 * query timeout (ADR 0015, "Bounded timeouts").
 *
 * <p>The pool's shared template is left as it is, so the bound belongs to the one adapter that
 * asked for it and reaches no other caller of the pool.
 */
public final class TimedJdbc {

    private TimedJdbc() {
    }

    /**
     * @param pool    the pool's template, whose data source the new template uses
     * @param timeout how long one statement may run before the driver cancels it
     * @return a new template over the same data source, with that query timeout
     */
    public static JdbcTemplate over(JdbcTemplate pool, Duration timeout) {
        JdbcTemplate timed = new JdbcTemplate(pool.getDataSource());
        timed.setQueryTimeout(Math.toIntExact(timeout.toSeconds()));
        return timed;
    }
}

package com.example.backend.scim.infrastructure.persistence;

import com.example.backend.scim.domain.ScimSeedLock;
import java.time.Duration;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The seeding lock as a SQL Server application lock, owned by the transaction (ADR 0015).
 *
 * <p>An application lock rather than a row lock: there is no row to lock on a fresh database,
 * which is exactly when the lock matters, and {@code sp_getapplock} needs no table, no migration
 * and no privilege beyond the connection the application already has.
 *
 * <p>{@code MANDATORY}: the lock is owned by the transaction ({@code @LockOwner = 'Transaction'}),
 * and on an auto-commit connection that transaction ends with the statement, so a call outside a
 * transaction would succeed and protect nothing. Refusing that call turns a silent loss of the
 * guarantee into a startup failure.
 *
 * <p>It waits at most {@link #LOCK_TIMEOUT}. A lock not granted — a timeout, a deadlock, a refusal — returns a
 * negative status, and seeding fails the start rather than seed unserialized: as a lock the
 * database would not give ({@link CannotAcquireLockException}), which ADR 0015's mapping
 * classifies as a {@code database} failure to investigate.
 *
 * <p>JDBC rather than JPA, as in {@code ScheduledJobLockAdapter}: the statement is a lock, not a
 * read, and it joins the caller's transaction through the shared data source.
 */
@Repository
class ScimSeedLockAdapter implements ScimSeedLock {

    /** How long a start waits for another start's seeding before it fails. */
    private static final Duration LOCK_TIMEOUT = Duration.ofSeconds(60);

    /**
     * Whole statement as a constant, never assembled; see the local Semgrep ruleset. The resource
     * name is fixed and recognisable in {@code sys.dm_tran_locks}; no other application lock is
     * taken in this schema. The status is {@code 0} or {@code 1} when the lock is granted.
     *
     * <p>The timeout is bound as a parameter, from {@link #LOCK_TIMEOUT}.
     *
     * <p>The empty read comes first because the driver runs a transaction as SQL Server's
     * implicit transaction, which begins at the first statement that reads a table, and
     * {@code sp_getapplock} is not one: on its own it would find no transaction to be owned by
     * and refuse ({@code -999}).
     */
    private static final String LOCK_SEEDING = """
            DECLARE @transaction_opened INT, @granted INT;
            SELECT @transaction_opened = COUNT(*) FROM scim_resources WHERE 1 = 0;
            EXEC @granted = sp_getapplock @Resource = N'ScimSeed', @LockMode = N'Exclusive',
                @LockOwner = N'Transaction', @LockTimeout = ?;
            SELECT @granted""";

    private final JdbcTemplate jdbc;

    ScimSeedLockAdapter(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void acquire() {
        Integer granted = jdbc.queryForObject(LOCK_SEEDING, Integer.class,
                Math.toIntExact(LOCK_TIMEOUT.toMillis()));
        if (granted == null || granted < 0) {
            throw new CannotAcquireLockException(
                    "the seeding lock was not granted within " + LOCK_TIMEOUT.toSeconds()
                            + " s (sp_getapplock status " + granted + ")");
        }
    }
}

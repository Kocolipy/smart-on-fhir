package com.example.backend.scim.infrastructure.persistence;

import com.example.backend.database.TimedJdbc;
import com.example.backend.scim.domain.ScimSeedLock;
import java.time.Duration;
import java.util.List;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The seeding lock as the update lock on the one row of {@code scim_seed_locks}, held by the
 * calling transaction (ADR 0015).
 *
 * <p>A row lock, as {@code ScheduledJobLockAdapter}'s is, rather than an application lock. The
 * V2 migration inserts the row before the service can start, so there is a row to lock even on a
 * fresh database, which is when the lock matters. And a row lock is the engine's ordinary lock
 * manager, which works under every server collation: on a server collated
 * {@code Latin1_General_100_BIN2_UTF8} — the deployed server's — {@code sp_getapplock} fails on
 * the pinned SQL Server 2022 image with error 17750 ({@code xp_userlock}: "Could not load the
 * DLL") and reports a lock it never took as granted.
 *
 * <p>{@code WITH (UPDLOCK, ROWLOCK)}, and no {@code READPAST}: a second start must <em>wait</em>
 * for the first start's seeding to commit and then read what it seeded, not skip seeding. The
 * update lock is held until the transaction ends, whatever its isolation, and read-committed
 * snapshot does not let a locking read past it. Two update locks on one row conflict, so two
 * seedings serialize; a plain read is not blocked, so nothing else waits on seeding. The
 * statement needs {@code SELECT} on the table and nothing more, so the runtime role can neither
 * delete the row nor change it.
 *
 * <p>{@code MANDATORY}: the lock is the transaction's, and on an auto-commit connection that
 * transaction ends with the statement, so a call outside a transaction would succeed and protect
 * nothing. Refusing that call turns a silent loss of the guarantee into a startup failure.
 *
 * <p>It waits at most {@link #LOCK_TIMEOUT}, as the statement's query timeout (ADR 0015,
 * "Bounded timeouts"). A wait that runs out fails the start rather than seed unserialized: as a
 * lock the database would not give ({@link CannotAcquireLockException}), which ADR 0015's mapping
 * classifies as a {@code database} failure to investigate.
 *
 * <p>JDBC rather than JPA, as in {@code ScheduledJobLockAdapter}: the row has no attributes worth
 * mapping, the statement is a lock rather than a read, and it joins the caller's transaction
 * through the shared data source.
 */
@Repository
class ScimSeedLockAdapter implements ScimSeedLock {

    /** How long a start waits for another start's seeding before it fails. */
    private static final Duration LOCK_TIMEOUT = Duration.ofSeconds(60);

    /** The one row of {@code scim_seed_locks}, inserted by the V2 migration. */
    private static final String SEED_ROW = "ScimSeed";

    /** Whole statement as a constant, never assembled; see the local Semgrep ruleset. */
    private static final String LOCK_SEED_ROW = """
            SELECT lock_name FROM scim_seed_locks WITH (UPDLOCK, ROWLOCK)
             WHERE lock_name = ?""";

    private final JdbcTemplate jdbc;

    /**
     * @param pool the main pool's template: its data source, under a template of this adapter's
     *             own that times the lock statement out, so the bound is this adapter's alone
     */
    ScimSeedLockAdapter(JdbcTemplate pool) {
        this.jdbc = TimedJdbc.over(pool, LOCK_TIMEOUT);
    }

    /**
     * Waits for the seed row's update lock.
     *
     * <p>A held row is waited for, never read past, so an empty result can only be a row that
     * does not exist: the migration that inserts it has not run, and seeding under a lock that
     * locks nothing is refused.
     */
    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void acquire() {
        List<String> locked;
        try {
            locked = jdbc.queryForList(LOCK_SEED_ROW, String.class, SEED_ROW);
        } catch (QueryTimeoutException timedOut) {
            throw new CannotAcquireLockException(
                    "the seeding lock was not granted within " + LOCK_TIMEOUT.toSeconds() + " s",
                    timedOut);
        }
        if (locked.isEmpty()) {
            throw new IllegalStateException("No seeding lock row exists in scim_seed_locks;"
                    + " the migration that creates it has not run.");
        }
    }
}

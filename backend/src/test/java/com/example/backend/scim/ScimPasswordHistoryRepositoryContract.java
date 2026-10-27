package com.example.backend.scim;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.backend.scim.domain.PasswordHistoryPolicy;
import com.example.backend.scim.domain.ScimPasswordHistoryRepository;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * What every {@link ScimPasswordHistoryRepository} does, whatever stores the history: the SQL
 * Server adapter, and the in-memory store the password change and SCIM write unit tests run
 * against. Each runs it.
 *
 * <p>A history belongs to a User, which the SQL Server table holds it to by a foreign key, so each
 * case first arranges its User through {@link #givenUser()}. Each entry's {@code setAt} differs
 * from every other's: "most recent" is ordered by it. Every instant is in whole seconds, so it
 * survives the {@code DATETIMEOFFSET(6)} column unchanged.
 */
public abstract class ScimPasswordHistoryRepositoryContract {

    private static final Instant FIRST = Instant.parse("2026-01-01T00:00:00Z");

    /** The password history port under test. */
    protected abstract ScimPasswordHistoryRepository history();

    /** A User the store holds, which a history can belong to. */
    protected abstract UUID givenUser();

    @Test
    void a_user_with_no_history_has_no_recent_hash() {
        assertThat(history().findRecentHashes(givenUser())).isEmpty();
    }

    @Test
    void recorded_hashes_are_read_back_newest_first() {
        UUID user = givenUser();
        history().record(user, "hash-1", at(1));
        history().record(user, "hash-2", at(2));

        assertThat(history().findRecentHashes(user)).containsExactly("hash-2", "hash-1");
    }

    /** Recording past the retained number drops the oldest, so the history never grows past it. */
    @Test
    void the_history_keeps_only_the_newest_retained_hashes() {
        UUID user = givenUser();
        for (int set = 1; set <= PasswordHistoryPolicy.RETAINED + 2; set++) {
            history().record(user, "hash-" + set, at(set));
        }

        assertThat(history().findRecentHashes(user)).containsExactly("hash-5", "hash-4", "hash-3");
    }

    /**
     * Newest is the latest {@code setAt}, not the latest call: an entry recorded with an earlier
     * {@code setAt} than one already held reads as older than it.
     */
    @Test
    void the_newest_hash_is_the_one_set_latest() {
        UUID user = givenUser();
        history().record(user, "set-later", at(2));
        history().record(user, "set-earlier", at(1));

        assertThat(history().findRecentHashes(user)).containsExactly("set-later", "set-earlier");
    }

    /** What a trim drops is the entry set earliest, whenever it was recorded. */
    @Test
    void the_trim_drops_the_hash_set_earliest() {
        UUID user = givenUser();
        history().record(user, "set-2", at(2));
        history().record(user, "set-3", at(3));
        history().record(user, "set-4", at(4));
        history().record(user, "set-1", at(1));

        assertThat(history().findRecentHashes(user)).containsExactly("set-4", "set-3", "set-2");
    }

    @Test
    void one_users_history_is_not_anothers() {
        UUID ada = givenUser();
        UUID bob = givenUser();
        history().record(ada, "ada-hash", at(1));

        assertThat(history().findRecentHashes(bob)).isEmpty();
    }

    /** Another User's history counts nothing toward this one's trim. */
    @Test
    void another_users_history_trims_nothing_of_this_ones() {
        UUID ada = givenUser();
        UUID bob = givenUser();
        history().record(ada, "ada-1", at(1));
        for (int set = 1; set <= PasswordHistoryPolicy.RETAINED; set++) {
            history().record(bob, "bob-" + set, at(set + 1));
        }

        assertThat(history().findRecentHashes(ada)).containsExactly("ada-1");
    }

    private static Instant at(int seconds) {
        return FIRST.plusSeconds(seconds);
    }
}

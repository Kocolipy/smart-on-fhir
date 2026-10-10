package com.example.backend.auth;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.backend.auth.domain.AccountSessions;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * What every {@link AccountSessions} does, whatever stores the sessions: the Spring Session adapter
 * over Redis, and the in-memory double the use-case tests run against. A use case's test that
 * passes against the double therefore says something about production only while both satisfy
 * this, so each runs it.
 *
 * <p>The seam is narrow on purpose — end an account's sessions, all or all but one, and say how
 * many ended. Auditing and logging the revocation are the Session revocation module's, above it.
 */
public abstract class AccountSessionsContract {

    /** The implementation under test. */
    protected abstract AccountSessions sessions();

    /** Opens a session for the account, as an accepted Login would, and returns its id. */
    protected abstract String open(UUID accountId);

    /** Whether the session is still live. */
    protected abstract boolean isLive(String sessionId);

    private final UUID bob = UUID.randomUUID();

    private final UUID zoe = UUID.randomUUID();

    @Test
    void revoking_all_ends_every_session_the_account_holds_and_counts_them() {
        String first = open(bob);
        String second = open(bob);

        assertThat(sessions().revokeAll(bob)).isEqualTo(2);
        assertThat(isLive(first) || isLive(second)).isFalse();
    }

    @Test
    void revoking_all_ends_no_session_of_another_account() {
        String zoes = open(zoe);
        open(bob);

        sessions().revokeAll(bob);

        assertThat(isLive(zoes)).isTrue();
    }

    @Test
    void an_account_signed_in_nowhere_ends_nothing_and_is_not_a_failure() {
        assertThat(sessions().revokeAll(bob)).isZero();
    }

    /** A Login keeps the session it is completed in and ends every other one of the account's. */
    @Test
    void revoking_all_but_one_keeps_the_retained_session_and_counts_the_rest() {
        open(bob);
        open(bob);
        String retained = open(bob);

        assertThat(sessions().revokeAllExcept(bob, retained)).isEqualTo(2);
        assertThat(isLive(retained)).isTrue();
    }

    @Test
    void revoking_all_but_one_ends_the_others() {
        String earlier = open(bob);
        String retained = open(bob);

        sessions().revokeAllExcept(bob, retained);

        assertThat(isLive(earlier)).isFalse();
    }

    @Test
    void revoking_all_but_one_ends_no_session_of_another_account() {
        String retained = open(bob);
        String zoes = open(zoe);

        sessions().revokeAllExcept(bob, retained);

        assertThat(isLive(zoes)).isTrue();
    }

    /** A caller that held no session yet retains nothing, so every session of the account ends. */
    @Test
    void retaining_no_session_ends_every_one() {
        open(bob);
        open(bob);

        assertThat(sessions().revokeAllExcept(bob, null)).isEqualTo(2);
    }

    /** Retaining a session the account does not hold neither fails nor spares one it does. */
    @Test
    void retaining_another_accounts_session_spares_none_of_this_ones() {
        String bobs = open(bob);
        String zoes = open(zoe);

        assertThat(sessions().revokeAllExcept(bob, zoes)).isEqualTo(1);
        assertThat(isLive(bobs)).isFalse();
    }
}

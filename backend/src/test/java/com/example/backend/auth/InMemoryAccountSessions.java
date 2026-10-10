package com.example.backend.auth;

import com.example.backend.auth.domain.AccountSessions;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Session registry for tests, standing in for the Spring Session adapter.
 *
 * <p>Records what was opened and what was revoked so a test can assert that the
 * right account's sessions ended and that nobody else's did — the whole point of
 * the port, and invisible in a mock that only counts calls.
 *
 * <p>Keyed by the account's stable id, matching {@link AccountSessions}: a
 * session outlives a username change, so this fake must not be indexable by one.
 */
public final class InMemoryAccountSessions implements AccountSessions {

    private final Map<UUID, List<String>> live = new LinkedHashMap<>();

    private final List<UUID> revocations = new ArrayList<>();

    private final List<UUID> loginRevocations = new ArrayList<>();

    private RuntimeException failure;

    /** Record a session this account holds, as a successful login would. */
    public void open(UUID accountId, String sessionId) {
        live.computeIfAbsent(accountId, key -> new ArrayList<>()).add(sessionId);
    }

    /** The sessions this account still holds. */
    public List<String> sessionsOf(UUID accountId) {
        return List.copyOf(live.getOrDefault(accountId, List.of()));
    }

    /**
     * Makes every revocation from now on fail with {@code failure}, as a session store that is
     * down would — or, given {@code null}, work again. A full-context test sharing this bean
     * must restore it.
     */
    public void failWith(RuntimeException failure) {
        this.failure = failure;
    }

    private void failIfDown() {
        if (failure != null) {
            throw failure;
        }
    }

    /** Whether any account still holds this session. */
    public boolean isLive(String sessionId) {
        return live.values().stream().anyMatch(held -> held.contains(sessionId));
    }

    /** Every account id revoked, in order, including ones holding no session. */
    public List<UUID> revocations() {
        return List.copyOf(revocations);
    }

    @Override
    public int revokeAll(UUID accountId) {
        failIfDown();
        revocations.add(accountId);
        List<String> ended = live.remove(accountId);
        return ended == null ? 0 : ended.size();
    }

    /**
     * Every account a login ended the other sessions of, in order. Kept apart from
     * {@link #revocations()} because a login does it on every success, and a test
     * counting the revocations an administrative action caused must not see them.
     */
    public List<UUID> loginRevocations() {
        return List.copyOf(loginRevocations);
    }

    @Override
    public int revokeAllExcept(UUID accountId, String retainedSessionId) {
        failIfDown();
        loginRevocations.add(accountId);
        List<String> held = live.getOrDefault(accountId, List.of());
        List<String> kept = held.stream().filter(id -> id.equals(retainedSessionId)).toList();
        int ended = held.size() - kept.size();
        if (kept.isEmpty()) {
            live.remove(accountId);
        } else {
            live.put(accountId, new ArrayList<>(kept));
        }
        return ended;
    }
}

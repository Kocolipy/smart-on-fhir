package com.example.backend.auth.domain;

import java.util.UUID;

/**
 * The live sessions an account holds, as something that can be taken away.
 *
 * <p>Exists because account status is otherwise only consulted when
 * authenticating: a session carries its own authentication, so a request
 * arriving with one never asks the account whether it may still act. Disabling an
 * account therefore has to reach the sessions already issued to it, and this is
 * the port it reaches them through.
 *
 * <p>Deliberately not a session <em>store</em>. Nothing here reads a session,
 * lists one, or says what is in it — the one thing the domain has to express is
 * that an account's sessions end, so that is the whole interface. What a session
 * is, where it lives, and how it is found are the adapter's.
 *
 * <p>Indexed and revoked by the account's stable id, not its username: a session
 * outlives a username change, and username is a mutable display/login attribute
 * only. The login path is what records a session under this id in the first
 * place — see {@code SessionEstablishment}.
 */
public interface AccountSessions {

    /**
     * Ends every session held by this account, so its next request arrives
     * unauthenticated.
     *
     * @return how many sessions were ended; zero when the account was not signed
     *     in anywhere, which is not a failure
     */
    int revokeAll(UUID accountId);

    /**
     * Ends every session held by this account except one — the session a login
     * is being completed in, so that a User holds at most one session at a time:
     * a successful login keeps its own and ends every other.
     *
     * @param retainedSessionId the id the caller's session is stored under, or
     *     {@code null} when the caller holds no session yet, in which case every
     *     session of the account ends
     * @return how many sessions were ended, the retained one never among them
     */
    int revokeAllExcept(UUID accountId, String retainedSessionId);
}

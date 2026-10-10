package com.example.backend.auth.domain;

import java.util.Optional;

/**
 * The Epic tokens a session holds: the access token, refresh token and {@code id_token} a
 * successful Epic Login kept, server-side, for the life of the session it signed in (ADR 0013,
 * D29).
 *
 * <p>A port for the reason {@link AccountSessions} is one: the application asks for a session's
 * tokens, and the adapter owns what a session is and where it lives. They are held as an
 * attribute of the session itself, so they go wherever the session goes — rotated with its id at
 * sign-in, and gone with it on logout, the idle timeout, the absolute session lifetime, every
 * session revocation and the next launch in that browser — with no cleanup of their own.
 *
 * <p>Nothing here hands a token to the browser. No web adapter's response is built from an
 * {@link EpicTokenSet}, and {@code ArchitectureTest} holds that no REST controller depends on one.
 */
public interface EpicTokens {

    /** The session attribute an Epic Login keeps its tokens under, once the session is signed in. */
    String SESSION_ATTRIBUTE = "app.epic.tokens";

    /**
     * The Epic tokens the live session {@code sessionId} holds.
     *
     * @param sessionId the id the session is stored under, as signed in
     * @return the tokens; empty for a password Login's session, for a session that does not exist
     *     or has ended — its absolute lifetime included — and for no id at all
     */
    Optional<EpicTokenSet> forSession(String sessionId);
}

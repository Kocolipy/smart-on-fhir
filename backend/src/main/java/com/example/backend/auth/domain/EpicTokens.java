package com.example.backend.auth.domain;

import java.util.Optional;

/**
 * The Epic tokens a session holds: the access token, refresh token and {@code id_token} a
 * successful Epic Login kept, server-side, for the life of the session it signed in (ADR 0012,
 * D29).
 *
 * <p>A port for the reason {@link AccountSessions} is one: the application asks for a session's
 * tokens, and the adapter owns what a session is and where it lives. They are held as an
 * attribute of the session itself, so they go wherever the session goes — rotated with its id at
 * sign-in, and gone with it on logout, the idle timeout, the absolute session lifetime, every
 * session revocation and the next launch in that browser — with no cleanup of their own. Which
 * attribute that is, how long a session holding them is stored, and when they stop being handed
 * out are {@link SignedInSession}'s, which the adapter asks.
 *
 * <p>Nothing here hands a token to the browser. No web adapter's response is built from an
 * {@link EpicTokenSet}, and {@code ArchitectureTest} holds that no REST controller depends on one.
 * The one release, of the access token alone, is {@code FhircastAccessService}'s, which reads it
 * here with the {@link FhircastHub} kept beside it (ADR 0016).
 */
public interface EpicTokens {

    /**
     * The Epic tokens the live session {@code sessionId} holds.
     *
     * @param sessionId the id the session is stored under, as signed in
     * @return the tokens; empty for a password Login's session, for a session that does not exist
     *     or has ended — its absolute lifetime included — and for no id at all
     */
    Optional<EpicTokenSet> forSession(String sessionId);

    /**
     * The Epic tokens the live session {@code sessionId} holds and the FHIRcast hub it kept
     * beside them (ADR 0016), read together from one find of the session, so a session that ends
     * meanwhile answers neither rather than the one alone.
     *
     * @param sessionId the id the session is stored under, as signed in
     * @return the tokens and the hub; empty for every session {@link #forSession} answers empty
     *     for, and with no hub for an Epic session whose token response named none this service
     *     would keep
     */
    Optional<HeldForFhircast> forFhircast(String sessionId);

    /**
     * What a session holds for the FHIRcast release: its Epic tokens, and the FHIRcast hub kept
     * beside them, when there is one.
     */
    record HeldForFhircast(EpicTokenSet tokens, Optional<FhircastHub> hub) {
    }
}

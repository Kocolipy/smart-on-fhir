package com.example.backend.auth.domain;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * The signed-in session's own state, and the one place that reads or writes it: whose session it
 * is, the role mapping its Permissions were resolved under, and the Epic tokens an Epic Login kept
 * on it. Its callers ask it questions — who owns this session, was it issued under this mapping,
 * which tokens does it hold — rather than reading the attribute map, so the shape is defined once
 * and every caller gets the same answer from it.
 *
 * <ul>
 *   <li><b>The owner</b> is the User's stable id (the SCIM resource id), written at sign-in under
 *       Spring Session's principal index, which the indexed repository finds a User's sessions by,
 *       so a revocation reaches the session by an id that survives a later username change. It is
 *       parsed here and nowhere else: a session with no index, an index of another type, or one
 *       that is not a well-formed id, identifies nobody — the answer an anonymous request gets —
 *       never a failure.
 *   <li><b>The role-mapping hash</b> is written beside the owner, so a session minted under
 *       another mapping can be told apart from one minted under the running one.
 *   <li><b>The Epic tokens</b> are kept on the signed-in session for exactly its life (ADR 0013,
 *       D29), and are never handed out once the session has outlived its absolute lifetime,
 *       whatever the store still holds.
 *   <li><b>The idle bound</b> of a session holding them is cut to what remains of that lifetime
 *       once that is the shorter ({@link AbsoluteSessionLifetimePolicy#idleBoundAt}), so the store
 *       never keeps the session, and the tokens with it, past the lifetime's end. Applied when the
 *       tokens are kept, and again on each later request, whose renewal would otherwise undo it.
 * </ul>
 *
 * <p>Domain code over a session it does not own: the session is whatever {@link Attributes} the
 * caller adapts — the servlet container's {@code HttpSession} at a web adapter, a Spring Session
 * {@code Session} found by id at a store adapter — so the shape stays plain Java, and its tests
 * run over a map. Clock-free, as {@link AbsoluteSessionLifetimePolicy} is: a question about the
 * lifetime is asked of a policy at an instant the caller read from the injected clock.
 */
public final class SignedInSession {

    /**
     * The attribute the owner's stable id is written under: Spring Session's principal index,
     * {@code FindByIndexNameSessionRepository.PRINCIPAL_NAME_INDEX_NAME}. Spelled out because the
     * domain does not depend on Spring Session; {@code SignedInSessionTests} pins the two
     * together.
     */
    public static final String OWNER_ATTRIBUTE =
            "org.springframework.session.FindByIndexNameSessionRepository.PRINCIPAL_NAME_INDEX_NAME";

    /** The attribute the role mapping's hash is written under, read by {@link RoleMappingSessions}. */
    public static final String ROLE_MAPPING_HASH_ATTRIBUTE = "app.authorization.roleMappingHash";

    /** The attribute an Epic Login keeps its tokens under, read by {@link EpicTokens}. */
    public static final String EPIC_TOKENS_ATTRIBUTE = "app.epic.tokens";

    /**
     * A session as this module sees it: an attribute map, a creation time and an idle bound — what
     * both the servlet container's session and Spring Session's offer, so an adapter implements it
     * by delegating.
     */
    public interface Attributes {

        /** The attribute stored under {@code name}, or {@code null} for none. */
        Object attribute(String name);

        /** Stores {@code value} under {@code name}. */
        void setAttribute(String name, Object value);

        /** When the session was created. */
        Instant createdAt();

        /** How long the session survives without a request. */
        Duration idleBound();

        /** Sets how long the session survives without a request. */
        void setIdleBound(Duration idleBound);
    }

    private final Attributes session;

    private SignedInSession(Attributes session) {
        this.session = Objects.requireNonNull(session, "session");
    }

    /** The session {@code session} adapts, as this module reads and writes it. */
    public static SignedInSession of(Attributes session) {
        return new SignedInSession(session);
    }

    /**
     * Signs the session in to {@code owner}, under the role mapping {@code roleMappingHash}: the
     * session is indexed by the User's stable id, and records the mapping its Permissions were
     * resolved under.
     */
    public void signIn(UUID owner, String roleMappingHash) {
        session.setAttribute(OWNER_ATTRIBUTE, owner.toString());
        session.setAttribute(ROLE_MAPPING_HASH_ATTRIBUTE, roleMappingHash);
    }

    /**
     * The User whose session this is, by stable id.
     *
     * @return the owner; empty for a session nobody signed in to, and for one whose index is not a
     *     well-formed stable id, either of which identifies nobody
     */
    public Optional<UUID> owner() {
        if (!(session.attribute(OWNER_ATTRIBUTE) instanceof String indexed)) {
            return Optional.empty();
        }
        try {
            return Optional.of(UUID.fromString(indexed));
        } catch (IllegalArgumentException notAnId) {
            return Optional.empty();
        }
    }

    /**
     * Whether the session's Permissions were resolved under the role mapping {@code roleMappingHash}
     * — false for one issued under another, and for one carrying no hash at all, which predates the
     * hash being recorded.
     */
    public boolean issuedUnder(String roleMappingHash) {
        return roleMappingHash.equals(session.attribute(ROLE_MAPPING_HASH_ATTRIBUTE));
    }

    /** Whether the session has outlived {@code lifetime} as of {@code now}. */
    public boolean hasOutlived(AbsoluteSessionLifetimePolicy lifetime, Instant now) {
        return lifetime.isExpired(session.createdAt(), now);
    }

    /**
     * Keeps Epic's tokens on the session, and bounds how long the store keeps it by what remains of
     * {@code lifetime} as of {@code now} ({@link #boundByLifetime}), so the tokens are never stored
     * past its end.
     */
    public void keepEpicTokens(EpicTokenSet tokens, AbsoluteSessionLifetimePolicy lifetime,
            Instant now) {
        session.setAttribute(EPIC_TOKENS_ATTRIBUTE, tokens);
        boundByLifetime(lifetime, now);
    }

    /**
     * The Epic tokens the session holds, as of {@code now}.
     *
     * @return the tokens; empty for a session holding none — a password Login's — and for one that
     *     has outlived {@code lifetime}, which has ended whatever the store still holds
     */
    public Optional<EpicTokenSet> epicTokens(AbsoluteSessionLifetimePolicy lifetime, Instant now) {
        if (hasOutlived(lifetime, now)) {
            return Optional.empty();
        }
        return session.attribute(EPIC_TOKENS_ATTRIBUTE) instanceof EpicTokenSet tokens
                ? Optional.of(tokens)
                : Optional.empty();
    }

    /**
     * Cuts the idle bound of a session holding Epic tokens back to what remains of
     * {@code lifetime} as of {@code now}, once that is the shorter, so the store keeps it no longer
     * than the lifetime allows (ADR 0013, D29). Far from the end the idle bound is untouched, and a
     * session holding nothing under the tokens' name is never touched. Whatever is stored there is
     * bounded, a well-formed token set or not, because the bound is about what the store keeps.
     */
    public void boundByLifetime(AbsoluteSessionLifetimePolicy lifetime, Instant now) {
        if (session.attribute(EPIC_TOKENS_ATTRIBUTE) != null) {
            session.setIdleBound(
                    lifetime.idleBoundAt(session.idleBound(), session.createdAt(), now));
        }
    }
}

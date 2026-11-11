package com.example.backend.auth.domain;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * The signed-in session's own state, and the one place that reads or writes it: whose session it
 * is, when it was signed in, the Groups it was signed in with, and the Epic tokens an Epic Login
 * kept on it. Its callers ask it
 * questions — who owns this session, which tokens does it hold — rather than reading the attribute
 * map, so the shape is defined once and every caller gets the same answer from it.
 *
 * <ul>
 *   <li><b>Signing in starts from nothing.</b> Every attribute this module writes belongs to one
 *       sign-in, and {@link #signIn} removes them all before it records the new one, so a Login
 *       over a session another Login signed in — a password Login in a browser an EHR launch
 *       signed in, say — carries none of the earlier sign-in's state over: not its owner, not its
 *       Epic tokens, not its FHIRcast hub. The module writes only the members of one closed set,
 *       so an attribute added later is one more member, and reset with the rest. What the module
 *       does not write — the security context, the CSRF token, the framework's own — is left
 *       to its owner.
 *   <li><b>The owner</b> is the User's stable id (the SCIM resource id), written at sign-in under
 *       Spring Session's principal index, which the indexed repository finds a User's sessions by,
 *       so a revocation reaches the session by an id that survives a later username change. It is
 *       parsed here and nowhere else: a session with no index, an index of another type, or one
 *       that is not a well-formed id, identifies nobody — the answer an anonymous request gets —
 *       never a failure.
 *   <li><b>The absolute session lifetime</b> counts from the Login that signed the session in:
 *       the instant {@link #signIn} records. A session nobody signed in to — a Guest's, minted to
 *       hold a CSRF token — counts from its creation, and so does one whose sign-in instant is
 *       missing or of another type, which only shortens its life.
 *   <li><b>The Groups</b> are the display names of the User's direct Groups, read by the Login
 *       in the same read under the User's lock that its Permissions come from, and kept here as
 *       that snapshot: a Group renamed or a membership changed during the session shows from the
 *       next sign-in, so the Groups reported always match the session's Permissions. A session
 *       nobody signed in to holds none, and so does one whose attribute is not a list of names.
 *   <li><b>The Epic tokens</b> are kept on the signed-in session for exactly its life (ADR 0012,
 *       D29), and are never handed out once the session has outlived its absolute lifetime,
 *       whatever the store still holds. So is the <b>FHIRcast hub</b> the token response named,
 *       beside them (ADR 0016).
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
 * lifetime is asked of a policy at an instant the caller read from the injected clock, and a
 * sign-in is recorded at one.
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

    /** The attribute an Epic Login keeps its tokens under, read by {@link EpicTokens}. */
    public static final String EPIC_TOKENS_ATTRIBUTE = "app.epic.tokens";

    /**
     * The attribute an Epic Login keeps its FHIRcast hub under, beside the tokens (ADR 0016), read
     * by {@link EpicTokens}.
     */
    public static final String FHIRCAST_HUB_ATTRIBUTE = "app.epic.fhircast-hub";

    /**
     * The attribute the instant the session was signed in at is kept under, which its absolute
     * lifetime counts from.
     */
    public static final String SIGNED_IN_AT_ATTRIBUTE = "app.signed-in-at";

    /** The attribute the display names of the Groups the session was signed in with are kept under. */
    private static final String GROUPS_ATTRIBUTE = "app.signed-in-groups";

    /**
     * Every attribute this module reads or writes, each of which belongs to one sign-in: the only
     * names {@link #read} and {@link #write} accept, and the ones {@link #signIn} removes. A new attribute is a new
     * member, so it is reset with the rest by construction.
     */
    private enum SignInAttribute {
        OWNER(OWNER_ATTRIBUTE),
        SIGNED_IN_AT(SIGNED_IN_AT_ATTRIBUTE),
        GROUPS(GROUPS_ATTRIBUTE),
        EPIC_TOKENS(EPIC_TOKENS_ATTRIBUTE),
        FHIRCAST_HUB(FHIRCAST_HUB_ATTRIBUTE);

        private final String name;

        SignInAttribute(String name) {
            this.name = name;
        }
    }

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

        /** Removes whatever is stored under {@code name}; nothing happens when there is none. */
        void removeAttribute(String name);

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
     * Signs the session in to {@code owner} at {@code at}, with {@code groups}: everything an
     * earlier sign-in kept on it is removed, the session is indexed by the User's stable id, its
     * absolute lifetime counts from {@code at}, and it keeps its own copy of the Groups.
     *
     * @param at     the instant the Login signed the session in, read from the injected clock
     * @param groups the display names of the User's direct Groups, as the Login read them, in
     *               the order they are reported in
     */
    public void signIn(UUID owner, Instant at, List<String> groups) {
        for (SignInAttribute earlier : SignInAttribute.values()) {
            session.removeAttribute(earlier.name);
        }
        write(SignInAttribute.OWNER, owner.toString());
        write(SignInAttribute.SIGNED_IN_AT, at);
        write(SignInAttribute.GROUPS, List.copyOf(groups));
    }

    /**
     * The User whose session this is, by stable id.
     *
     * @return the owner; empty for a session nobody signed in to, and for one whose index is not a
     *     well-formed stable id, either of which identifies nobody
     */
    public Optional<UUID> owner() {
        if (!(read(SignInAttribute.OWNER) instanceof String indexed)) {
            return Optional.empty();
        }
        try {
            return Optional.of(UUID.fromString(indexed));
        } catch (IllegalArgumentException notAnId) {
            return Optional.empty();
        }
    }

    /**
     * The display names of the Groups the session was signed in with, in the order the sign-in
     * kept them.
     *
     * @return the Groups; empty for a session signed in to a User in no Group, for one nobody
     *     signed in to, and for one whose attribute is anything but a list of names
     */
    public List<String> groups() {
        if (!(read(SignInAttribute.GROUPS) instanceof List<?> kept)) {
            return List.of();
        }
        List<String> names = new ArrayList<>();
        for (Object entry : kept) {
            if (!(entry instanceof String name)) {
                return List.of();
            }
            names.add(name);
        }
        return List.copyOf(names);
    }

    /** Whether the session has outlived {@code lifetime} as of {@code now}. */
    public boolean hasOutlived(AbsoluteSessionLifetimePolicy lifetime, Instant now) {
        return lifetime.isExpired(lifetimeStart(), now);
    }

    /**
     * What the absolute lifetime counts from: the instant the session was signed in at, or its
     * creation for a session holding none — nobody signed in to it, or what is there is no instant.
     */
    private Instant lifetimeStart() {
        return read(SignInAttribute.SIGNED_IN_AT) instanceof Instant signedInAt
                ? signedInAt
                : session.createdAt();
    }

    /**
     * Keeps Epic's tokens on the session, and bounds how long the store keeps it by what remains of
     * {@code lifetime} as of {@code now} ({@link #boundByLifetime}), so the tokens are never stored
     * past its end.
     */
    public void keepEpicTokens(EpicTokenSet tokens, AbsoluteSessionLifetimePolicy lifetime,
            Instant now) {
        write(SignInAttribute.EPIC_TOKENS, tokens);
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
        return read(SignInAttribute.EPIC_TOKENS) instanceof EpicTokenSet tokens
                ? Optional.of(tokens)
                : Optional.empty();
    }

    /**
     * Keeps the FHIRcast hub Epic's token response named on the session, beside the tokens (ADR
     * 0016). Kept together with them, so the store keeps it exactly as long.
     */
    public void keepFhircastHub(FhircastHub hub) {
        write(SignInAttribute.FHIRCAST_HUB, hub);
    }

    /**
     * The FHIRcast hub the session holds, as of {@code now}.
     *
     * @return the hub; empty for a session holding none — a password Login's, or an Epic one whose
     *     token response named no hub this service keeps — and for one that has outlived
     *     {@code lifetime}
     */
    public Optional<FhircastHub> fhircastHub(AbsoluteSessionLifetimePolicy lifetime, Instant now) {
        if (hasOutlived(lifetime, now)) {
            return Optional.empty();
        }
        return read(SignInAttribute.FHIRCAST_HUB) instanceof FhircastHub hub
                ? Optional.of(hub)
                : Optional.empty();
    }

    /**
     * Cuts the idle bound of a session holding Epic tokens back to what remains of
     * {@code lifetime} as of {@code now}, once that is the shorter, so the store keeps it no longer
     * than the lifetime allows (ADR 0012, D29). Far from the end the idle bound is untouched, and a
     * session holding nothing under the tokens' name is never touched. Whatever is stored there is
     * bounded, a well-formed token set or not, because the bound is about what the store keeps.
     */
    public void boundByLifetime(AbsoluteSessionLifetimePolicy lifetime, Instant now) {
        if (read(SignInAttribute.EPIC_TOKENS) != null) {
            session.setIdleBound(
                    lifetime.idleBoundAt(session.idleBound(), lifetimeStart(), now));
        }
    }

    /** Every read this module makes: of a member of the one set {@link #signIn} resets. */
    private Object read(SignInAttribute attribute) {
        return session.attribute(attribute.name);
    }

    /** Every write this module makes: under a member of the one set {@link #signIn} resets. */
    private void write(SignInAttribute attribute, Object value) {
        session.setAttribute(attribute.name, value);
    }
}

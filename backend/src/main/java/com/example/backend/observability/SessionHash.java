package com.example.backend.observability;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * The {@code session.hash} a security record carries in place of the session it concerns
 * (Log_Schema; the SSO standard's §3.4 logging contract): a record that names no user — a
 * refused Login above all — can then still be correlated with the other records of the same
 * browser session, and not by {@code trace.id} alone.
 *
 * <p>Never the id itself, which is the session's bearer credential. The hash is the first 64 bits
 * of its SHA-256: a session id is a random UUID, so there is no guessing it back from its hash the
 * way a password can be, and 64 bits tell the sessions in a log apart.
 */
public final class SessionHash {

    /** Hex characters kept: 64 bits. */
    private static final int LENGTH = 16;

    private SessionHash() {
    }

    /**
     * {@code sessionId}'s hash, or {@code null} when there is no session to name, so the field is
     * simply absent.
     */
    public static String of(String sessionId) {
        if (sessionId == null) {
            return null;
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(sessionId.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest).substring(0, LENGTH);
        } catch (NoSuchAlgorithmException absent) {
            throw new IllegalStateException("SHA-256 is required of every JVM", absent);
        }
    }
}

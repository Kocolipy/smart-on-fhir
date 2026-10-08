package com.example.backend.auth.epic;

import com.nimbusds.jose.jwk.JWKSet;
import java.io.IOException;
import java.net.ServerSocket;
import java.text.ParseException;

/**
 * What every Epic Login integration test sets its fake Epic up with: a port for it, the JWKS it
 * verifies our client assertions against, and a D26 JWKS refetch that does not wait.
 */
final class EpicTestFixtures {

    /** {@link #noRetryPause()}, as {@code @TestBean(methodName = NO_RETRY_PAUSE)} names it. */
    static final String NO_RETRY_PAUSE =
            "com.example.backend.auth.epic.EpicTestFixtures#noRetryPause";

    private EpicTestFixtures() {
    }

    /** A free local port for a {@link FakeEpic}, to name in {@code APP_EPIC_OAUTH_ISSUER}. */
    static int freePort() {
        // Test-only: binds an ephemeral local port just to learn a free number for the fake
        // Epic, and closes at once. Nothing is ever sent over it, so there is no traffic for
        // TLS to protect.
        // nosemgrep: java.lang.security.audit.crypto.unencrypted-socket.unencrypted-socket
        try (ServerSocket socket = new ServerSocket(0)) {
            socket.setReuseAddress(true);
            return socket.getLocalPort();
        } catch (IOException unavailable) {
            throw new IllegalStateException(unavailable);
        }
    }

    /** The JWKS we publish, as Epic reads it to verify our client assertion. */
    static JWKSet publishedJwks(EpicJwks ourJwks) {
        try {
            return JWKSet.parse(ourJwks.document());
        } catch (ParseException malformed) {
            throw new IllegalStateException(malformed);
        }
    }

    /**
     * No wait before a D26 JWKS refetch, for a test that is not about them. A test names it to
     * {@code @TestBean} by its fully qualified name, {@link #NO_RETRY_PAUSE}.
     */
    static EpicRetryPause noRetryPause() {
        return wait -> { };
    }
}

package com.example.backend.auth.infrastructure.persistence;

import com.example.backend.auth.domain.PendingAuthorizations;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Optional;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.stereotype.Component;

/**
 * Outbound adapter for {@link PendingAuthorizations}, in the Redis that already holds the HTTP
 * sessions, so the pending request is shared by every node exactly as the session is.
 *
 * <p>Each pending request is one string key, {@value #KEY_PREFIX}{@code {sessionId}}, written
 * with an expiry ({@code PSETEX}) and taken with {@code GETDEL}: one Redis command that returns
 * the value and removes it, so two callbacks racing on one session — on one node or on two —
 * cannot both read it. That is the guarantee a request to the session attribute could not give: Spring Session
 * loads each request's own copy of the session and writes its changes back only when the request
 * ends, so two concurrent requests would each have found the attribute, and each removed it.
 *
 * <p>Keyed by the session id, the value is scoped to the browser session that began the Login
 * (ADR 0013's deviation "session-scoped `state`"): no other session can take it, and when the launch's
 * session ends, the key expires on its own.
 */
@Component
public class PendingAuthorizationsAdapter implements PendingAuthorizations {

    /** Every pending authorization request's key begins so, then the session id. */
    static final String KEY_PREFIX = "epic:pending-authorization:";

    private final RedisConnectionFactory redis;

    public PendingAuthorizationsAdapter(RedisConnectionFactory redis) {
        this.redis = redis;
    }

    @Override
    public void hold(String sessionId, String pending, Duration lifetime) {
        try (RedisConnection connection = redis.getConnection()) {
            connection.stringCommands().pSetEx(key(sessionId), lifetime.toMillis(),
                    pending.getBytes(StandardCharsets.UTF_8));
        }
    }

    @Override
    public Optional<String> take(String sessionId) {
        try (RedisConnection connection = redis.getConnection()) {
            byte[] taken = connection.stringCommands().getDel(key(sessionId));
            return Optional.ofNullable(taken)
                    .map(value -> new String(value, StandardCharsets.UTF_8));
        }
    }

    private static byte[] key(String sessionId) {
        return (KEY_PREFIX + sessionId).getBytes(StandardCharsets.UTF_8);
    }
}

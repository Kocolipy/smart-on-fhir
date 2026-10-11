package com.example.backend.auth;

import com.example.backend.audit.RecordingAuditTrail;
import com.example.backend.audit.RecordingOperationalAlerts;
import com.example.backend.auth.application.LoginStateService;
import com.example.backend.auth.application.SessionRevocationService;
import com.example.backend.scim.domain.LockoutPolicy;
import com.example.backend.scim.domain.ScimUserRepository;
import java.time.Clock;

/**
 * The real login-state module, for a test whose subject reports events to it — the dormancy job,
 * a SCIM write, the development seed — and asserts what was stored, never a Lockout's audit event
 * or Session revocation. Those are {@code LoginStateServiceTests}' to assert, so the module's
 * own collaborators here record into nothing the test reads.
 *
 * <p>The real module rather than a stand-in: a fake that decided the login state its own way would
 * be a second copy of the rules, which is exactly what the module exists to remove.
 */
public final class LoginStateModules {

    private LoginStateModules() {
    }

    /** The module writing through {@code users} and deciding as of {@code clock}. */
    public static LoginStateService over(ScimUserRepository users, Clock clock) {
        RecordingAuditTrail audit = new RecordingAuditTrail();
        return new LoginStateService(
                users,
                new SessionRevocationService(
                        new InMemoryAccountSessions(),
                        new PendingCommit(),
                        audit,
                        new RecordingOperationalAlerts()),
                new LockoutPolicy(5),
                audit,
                clock);
    }
}

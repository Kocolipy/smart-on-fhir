package com.example.backend.auth;

import com.example.backend.audit.domain.AuditTrail;
import com.example.backend.audit.RecordingAuditTrail;
import com.example.backend.audit.RecordingOperationalAlerts;
import com.example.backend.auth.application.LoginStateService;
import com.example.backend.auth.application.SessionRevocationService;
import com.example.backend.scim.InMemoryScimUserRepository;
import com.example.backend.scim.InMemoryScimWriteLocks;
import com.example.backend.scim.domain.LockoutPolicy;
import java.time.Clock;

/**
 * The real login-state module, for a test whose subject reports events to it — a SCIM write, the
 * development seed — and asserts what was stored, never a Lockout's audit event or Session
 * revocation. Those are {@code LoginStateServiceTests}' to assert, so the module's own
 * collaborators here record into nothing the test reads.
 *
 * <p>A test that does assert them — the password-change, Unlock and dormancy paths — hands the
 * module its own {@link SessionRevocationService} and {@link AuditTrail} through the wider
 * overload, so the
 * module's lock and its other collaborators are still wired in this one place. A test that drives
 * the whole Login decision takes the module from {@link LoginHarness} instead, which wires it
 * together with the rest of that decision.
 *
 * <p>The real module rather than a stand-in: a fake that decided the login state its own way would
 * be a second copy of the rules, which is exactly what the module exists to remove.
 */
public final class LoginStateModules {

    private LoginStateModules() {
    }

    /** The module writing through {@code users} and deciding as of {@code clock}. */
    public static LoginStateService over(InMemoryScimUserRepository users, Clock clock) {
        RecordingAuditTrail audit = new RecordingAuditTrail();
        return over(
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

    /**
     * The module writing through {@code users} under {@code policy}, revoking through
     * {@code sessions} and auditing into {@code audit}, so the test can read both back.
     */
    public static LoginStateService over(
            InMemoryScimUserRepository users,
            SessionRevocationService sessions,
            LockoutPolicy policy,
            AuditTrail audit,
            Clock clock) {
        return new LoginStateService(
                users, users, new InMemoryScimWriteLocks(), sessions, policy, audit, clock);
    }
}

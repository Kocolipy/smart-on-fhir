package com.example.backend.scim.controller;

import com.example.backend.audit.RecordingAuditTrail;
import com.example.backend.auth.LoginStateModules;
import com.example.backend.authorization.TestGroupPermissions;
import com.example.backend.scim.InMemoryScimExternalIdRepository;
import com.example.backend.scim.InMemoryScimGroupRepository;
import com.example.backend.scim.InMemoryScimPasswordHistoryRepository;
import com.example.backend.scim.InMemoryScimQueryRepository;
import com.example.backend.scim.InMemoryScimTombstoneRepository;
import com.example.backend.scim.InMemoryScimUserRepository;
import com.example.backend.scim.InMemoryScimWriteLocks;
import com.example.backend.scim.application.ScimUserService;
import com.example.backend.scim.config.ScimPasswordAcceptanceConfig;
import com.example.backend.scim.domain.PasswordAcceptance;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * The User endpoints over the in-memory adapters, with the real use case behind them, for tests
 * that drive {@link ScimUserController} directly rather than over HTTP.
 *
 * <p>The clock is fixed at {@code 2026-01-01T00:00:00Z}, and a credential is stored as
 * {@code {contract}} followed by the raw value, so a test can name both exactly.
 */
final class ScimUserControllers {

    /** A fake whose output is a known literal, so a stored credential can be named exactly. */
    private static final PasswordEncoder ENCODER = new PasswordEncoder() {
        @Override
        public String encode(CharSequence raw) {
            return "{contract}" + raw;
        }

        @Override
        public boolean matches(CharSequence raw, String encoded) {
            return encoded.equals(encode(raw));
        }
    };

    private ScimUserControllers() {
    }

    /** The endpoints over this directory, which a test may read back through its port. */
    static ScimUserController over(InMemoryScimUserRepository users) {
        InMemoryScimGroupRepository groups = new InMemoryScimGroupRepository(users);
        Clock clock = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);
        return new ScimUserController(new ScimUserService(
                users, new InMemoryScimWriteLocks(), groups, new InMemoryScimExternalIdRepository(),
                new PasswordAcceptance(new InMemoryScimPasswordHistoryRepository(),
                        ScimPasswordAcceptanceConfig.hasher(ENCODER)),
                (connectorId, userId, causes) -> { }, LoginStateModules.over(users, clock),
                new InMemoryScimTombstoneRepository(), new RecordingAuditTrail(), clock,
                new InMemoryScimQueryRepository(users, groups), TestGroupPermissions.superuserOnly(),
                () -> { }));
    }
}

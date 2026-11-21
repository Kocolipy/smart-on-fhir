package com.example.backend.auth.application;

import com.example.backend.audit.domain.AuditFhircastRefusal;
import com.example.backend.audit.domain.AuditTrail;
import com.example.backend.audit.domain.Audited;
import com.example.backend.auth.domain.EpicTokenSet;
import com.example.backend.auth.domain.EpicTokens;
import com.example.backend.auth.domain.EpicTokens.HeldForFhircast;
import com.example.backend.auth.domain.FhircastAccess;
import com.example.backend.auth.domain.FhircastHub;
import java.time.Clock;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * The FHIRcast release (ADR 0016): the hub an Epic session's token response named, and, while it
 * is unexpired by the injected clock, the session's SMART access token with its expiry and scope,
 * handed to the SPA so the browser can connect to Epic's local FHIRcast hub — the one thing that
 * ever takes an Epic token out of the backend, and only the access token.
 *
 * <p>The session's tokens and hub are read together through the {@link EpicTokens} port, by the
 * session's id, in one read, so a session that ends meanwhile is refused as holding no Epic
 * tokens. Nothing is released for a session holding no Epic tokens (a password Login's) or one
 * that kept no hub; each refusal is recorded, fail-open, with its reason. A release is recorded
 * fail-closed before it is handed back, so a token this service cannot account for is not
 * released; one past the token's expiry releases the hub alone, recorded as a success naming the
 * expired token. No record names the token.
 */
@Service
public class FhircastAccessService {

    private final EpicTokens epicTokens;

    private final AuditTrail audit;

    private final Clock clock;

    public FhircastAccessService(EpicTokens epicTokens, AuditTrail audit, Clock clock) {
        this.epicTokens = epicTokens;
        this.audit = audit;
        this.clock = clock;
    }

    /**
     * Releases the hub, and the access token while it is unexpired, that the session
     * {@code sessionId} holds to its owner {@code caller}.
     *
     * @param caller    the stable id of the User whose session asks
     * @param sessionId the id the asking session is stored under
     * @return what is released; empty, and recorded as a refusal, when nothing is
     */
    public Optional<FhircastAccess> release(UUID caller, String sessionId) {
        Optional<HeldForFhircast> held = epicTokens.forFhircast(sessionId);
        if (held.isEmpty()) {
            return refused(caller, AuditFhircastRefusal.NO_EPIC_TOKENS);
        }
        EpicTokenSet tokens = held.get().tokens();
        Optional<FhircastHub> hub = held.get().hub();
        if (hub.isEmpty()) {
            return refused(caller, AuditFhircastRefusal.NO_FHIRCAST_HUB);
        }
        if (tokens.isExpired(clock)) {
            audit.append(new Audited.FhircastTokenWithheld(caller));
            return Optional.of(FhircastAccess.hubAlone(hub.get()));
        }
        audit.append(new Audited.FhircastTokenReleased(caller));
        return Optional.of(FhircastAccess.of(tokens, hub.get()));
    }

    private Optional<FhircastAccess> refused(UUID caller, AuditFhircastRefusal reason) {
        audit.append(new Audited.FhircastTokenReleaseRefused(caller, reason));
        return Optional.empty();
    }
}

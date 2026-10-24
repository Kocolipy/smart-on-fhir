package com.example.backend.auth.epic.controller;

import com.example.backend.auth.application.FhircastAccessService;
import com.example.backend.auth.controller.HttpSessionAttributes;
import com.example.backend.auth.domain.FhircastAccess;
import com.example.backend.auth.epic.EpicLogin;
import com.example.backend.auth.epic.EpicRoutes;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.context.annotation.Conditional;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * {@code GET /api/auth/epic/fhircast}: the FHIRcast release (ADR 0016) — the signed-in Epic
 * session's SMART access token, its expiry, its granted scope and the hub its token response
 * named, for the SPA to connect to Epic's local FHIRcast hub. The one REST controller that reaches
 * an Epic token ({@code ArchitectureTest.the_released_access_token_reaches_one_rest_controller}),
 * and it reaches the access token alone: what it is handed, {@link FhircastAccess}, has no field
 * the {@code id_token} or a refresh token could go in.
 *
 * <p>Self-service: the application chain admits a session with baseline access and no Permission.
 * The handler takes nothing from the request but its session, so it can release nothing but that
 * session's own token. The answer is never stored ({@code Cache-Control: no-store}). Nothing is
 * released — a {@code 404}, the same whichever reason — for a password Login's session, one that
 * kept no hub, or one whose access token has expired; the service records each answer. Exists only
 * while Epic Login is on; while it is off the route is not mapped, and the release gate answers
 * {@code 404} for it.
 */
@RestController
@Conditional(EpicLogin.WhenOn.class)
public class EpicFhircastController {

    private final FhircastAccessService access;

    public EpicFhircastController(FhircastAccessService access) {
        this.access = access;
    }

    @GetMapping(path = EpicRoutes.FHIRCAST, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<FhircastRelease> release(HttpServletRequest request) {
        UUID caller = HttpSessionAttributes.ownerOf(request);
        return access.release(caller, request.getSession(false).getId())
                .map(released -> ResponseEntity.ok()
                        .cacheControl(CacheControl.noStore())
                        .body(FhircastRelease.of(released)))
                .orElseThrow(NothingReleasedException::new);
    }

    /**
     * The release as the SPA reads it.
     *
     * @param accessToken the SMART access token, the {@code Bearer} of the hub's registration
     * @param expiresAt   when it stops being valid at Epic
     * @param scope       the scope Epic granted it, sorted
     * @param hubUrl      the hub's URL, on {@code 127.0.0.1}
     * @param hubTopic    the clinician's FHIRcast topic
     */
    public record FhircastRelease(String accessToken, Instant expiresAt, List<String> scope,
            String hubUrl, String hubTopic) {

        static FhircastRelease of(FhircastAccess released) {
            return new FhircastRelease(released.accessToken(), released.expiresAt(),
                    released.scope().stream().sorted().toList(), released.hub().url(),
                    released.hub().topic());
        }

        /** Names the access token by its presence alone (D22). */
        @Override
        public String toString() {
            return "FhircastRelease[accessToken=<redacted>, expiresAt=" + expiresAt
                    + ", scope=" + scope + ", hubUrl=" + hubUrl + ", hubTopic=<redacted>]";
        }
    }

    /** Nothing is released to this session. It carries no message and no reason. */
    static final class NothingReleasedException extends ResponseStatusException {

        NothingReleasedException() {
            super(HttpStatus.NOT_FOUND);
        }
    }
}

package com.example.backend.auth.epic.controller;

import com.example.backend.auth.epic.EpicJwks;
import com.example.backend.auth.epic.EpicLogin;
import com.example.backend.auth.epic.EpicRoutes;
import java.util.Map;
import org.springframework.context.annotation.Conditional;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code GET /api/auth/epic/jwks.json}: our public JWKS, which Epic fetches to verify our client
 * assertions (D14) — the active key, and the next key when one is configured, public halves only.
 *
 * <p>Public: Epic presents no session and no credential, so the application chain permits the
 * route to anyone, and a {@code GET} needs no CSRF token. Exists only while Epic Login is on;
 * while it is off the route is not mapped, and the release gate answers {@code 404} for it.
 */
@RestController
@Conditional(EpicLogin.WhenOn.class)
public class EpicJwksController {

    private final EpicJwks jwks;

    public EpicJwksController(EpicJwks jwks) {
        this.jwks = jwks;
    }

    @GetMapping(path = EpicRoutes.JWKS, produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> jwks() {
        return jwks.document();
    }
}

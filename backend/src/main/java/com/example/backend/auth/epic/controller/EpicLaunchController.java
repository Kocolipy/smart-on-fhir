package com.example.backend.auth.epic.controller;

import com.example.backend.auth.epic.EpicLaunchContext;
import com.example.backend.auth.epic.EpicLoginSettings;
import com.example.backend.auth.epic.EpicSignInRedirect;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.io.IOException;
import java.util.Optional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code GET /api/auth/epic/launch}: the launch URL registered with Epic, which Epic opens in the
 * clinician's system browser as {@code ?iss={fhirBase}&launch={opaque}} (flow step 1, D1).
 *
 * <p>Every launch is a fresh Login (D9): whatever session the browser holds — whoever it belongs
 * to — is ended here, and the launch continues in a new one, so nothing the previous session
 * carried survives into the clinician's. The {@code iss} must equal {@code APP_EPIC_FHIR_BASE}
 * exactly (D10), and a {@code launch} must be present; otherwise the browser lands at
 * {@code /?signin=refused}.
 *
 * <p>An accepted launch is sent on to {@code /api/auth/epic/authorize}, the internal hop Spring
 * Security's authorization-request filter answers with the redirect to Epic. That filter takes
 * the hop only while a launch is pending; a request reaching the hop's handler here held none,
 * and is refused.
 *
 * <p>Public: the browser arrives from Epic with no session of ours. While
 * {@code APP_EPIC_ENABLED} is off the release gate answers {@code 404} before either route is
 * reached, so the settings are always present when a handler runs.
 */
@RestController
public class EpicLaunchController {

    static final String LAUNCH_PATH = "/api/auth/epic/launch";

    static final String AUTHORIZE_PATH = "/api/auth/epic/authorize";

    private final Optional<EpicLoginSettings> settings;

    public EpicLaunchController(Optional<EpicLoginSettings> settings) {
        this.settings = settings;
    }

    @GetMapping(LAUNCH_PATH)
    public void launch(
            @RequestParam(name = "iss", required = false) String iss,
            @RequestParam(name = "launch", required = false) String launch,
            HttpServletRequest request,
            HttpServletResponse response) throws IOException {
        boolean issued = settings.map(epic -> epic.fhirBase().toString().equals(iss))
                .orElse(false);
        if (!issued || launch == null || launch.isEmpty()) {
            EpicSignInRedirect.refused(request, response);
            return;
        }
        HttpSession previous = request.getSession(false);
        if (previous != null) {
            previous.invalidate();
        }
        EpicLaunchContext.hold(request.getSession(true), launch);
        response.sendRedirect(request.getContextPath() + AUTHORIZE_PATH);
    }

    /** Reached only when no launch is pending: the authorize hop has nothing to send to Epic. */
    @GetMapping(AUTHORIZE_PATH)
    public void authorizeWithoutLaunch(HttpServletRequest request, HttpServletResponse response)
            throws IOException {
        EpicSignInRedirect.refused(request, response);
    }
}

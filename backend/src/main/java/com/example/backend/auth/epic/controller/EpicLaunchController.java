package com.example.backend.auth.epic.controller;

import com.example.backend.auth.application.EpicSignInRefusedException;
import com.example.backend.auth.domain.EpicInputBounds;
import com.example.backend.auth.domain.EpicInputField;
import com.example.backend.auth.domain.EpicInputRule;
import com.example.backend.auth.domain.EpicLoginFailureReason;
import com.example.backend.auth.epic.EpicLaunchContext;
import com.example.backend.auth.epic.EpicLoginSettings;
import com.example.backend.auth.epic.EpicRoutes;
import com.example.backend.auth.epic.EpicSignInFailure;
import jakarta.servlet.ServletException;
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
 * exactly, compared as a string and never normalized (D10), and the {@code launch} must be within
 * D18's bounds; otherwise the launch is refused as {@code ISS_MISMATCH} or {@code INVALID_LAUNCH},
 * through the one {@link EpicSignInFailure}, which ends the session, records the refusal and logs
 * the field and the rule it broke — never the value.
 *
 * <p>An accepted launch is sent on to {@code /api/auth/epic/authorize}, the internal hop Spring
 * Security's authorization-request filter answers with the redirect to Epic. That filter takes
 * the hop only while a launch is pending; a request reaching the hop's handler here held none,
 * and is refused as {@code INVALID_LAUNCH}.
 *
 * <p>Public: the browser arrives from Epic with no session of ours. While
 * {@code APP_EPIC_ENABLED} is off the release gate answers {@code 404} before either route is
 * reached, so the settings are always present when a handler runs.
 */
@RestController
public class EpicLaunchController {

    private final Optional<EpicLoginSettings> settings;

    private final EpicSignInFailure signInFailure;

    public EpicLaunchController(
            Optional<EpicLoginSettings> settings, EpicSignInFailure signInFailure) {
        this.settings = settings;
        this.signInFailure = signInFailure;
    }

    @GetMapping(EpicRoutes.LAUNCH)
    public void launch(
            @RequestParam(name = "iss", required = false) String iss,
            @RequestParam(name = "launch", required = false) String launch,
            HttpServletRequest request,
            HttpServletResponse response) throws IOException, ServletException {
        String fhirBase = settings.map(epic -> epic.fhirBase().toString()).orElse("");
        Optional<EpicInputRule> issBroken = EpicInputBounds.issBrokenBy(iss, fhirBase);
        if (issBroken.isPresent()) {
            refuse(EpicLoginFailureReason.ISS_MISMATCH, EpicInputField.ISS, issBroken.get(),
                    request, response);
            return;
        }
        Optional<EpicInputRule> launchBroken = EpicInputBounds.brokenBy(launch);
        if (launchBroken.isPresent()) {
            refuse(EpicLoginFailureReason.INVALID_LAUNCH, EpicInputField.LAUNCH,
                    launchBroken.get(), request, response);
            return;
        }
        HttpSession previous = request.getSession(false);
        if (previous != null) {
            previous.invalidate();
        }
        EpicLaunchContext.hold(request.getSession(true), launch);
        response.sendRedirect(request.getContextPath() + EpicRoutes.AUTHORIZE);
    }

    /** Reached only when no launch is pending: the authorize hop has nothing to send to Epic. */
    @GetMapping(EpicRoutes.AUTHORIZE)
    public void authorizeWithoutLaunch(HttpServletRequest request, HttpServletResponse response)
            throws IOException, ServletException {
        refuse(EpicLoginFailureReason.INVALID_LAUNCH, EpicInputField.LAUNCH,
                EpicInputRule.MISSING, request, response);
    }

    private void refuse(EpicLoginFailureReason reason, EpicInputField field, EpicInputRule rule,
            HttpServletRequest request, HttpServletResponse response)
            throws IOException, ServletException {
        signInFailure.onAuthenticationFailure(request, response,
                new EpicSignInRefusedException(reason, field, rule));
    }
}

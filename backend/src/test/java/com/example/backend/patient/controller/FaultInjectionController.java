package com.example.backend.patient.controller;

import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * A test-only route that fails with an exception no handler expects, for the app-wide error
 * handler's tests.
 *
 * <p>In an application controller package because {@code ApiExceptionHandler} answers for those
 * packages only. Test classes are on the component scan's classpath, so the profile is what keeps
 * this route out of every test context — the route-contract tests' among them — that does not ask
 * for it; a standalone MockMvc registers it directly, profile or no.
 */
@RestController
@Profile(FaultInjectionController.PROFILE)
public class FaultInjectionController {

    /** No context activates it: the route exists only where a test registers it by hand. */
    public static final String PROFILE = "fault-injection-route";

    public static final String PATH = "/api/test-only/fault";

    /** The failure's message: if it appears in a response, the handler leaked it. */
    public static final String FAILURE_MESSAGE = "fault-injection internal detail";

    @GetMapping(PATH)
    public String fail() {
        throw new IllegalStateException(FAILURE_MESSAGE);
    }
}

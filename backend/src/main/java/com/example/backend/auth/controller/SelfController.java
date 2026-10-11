package com.example.backend.auth.controller;

import com.example.backend.auth.application.SelfReadService;
import com.example.backend.auth.application.SelfRecord;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code GET /api/self}: the caller's own record.
 *
 * <p>The handler declares no parameter — no path variable, no query parameter, no body — so there
 * is nothing a request could carry that would name another User. The User is the one the SESSION
 * belongs to, read from the stable id its principal index holds, which the login wrote and the
 * client cannot; a session whose index is missing or is no stable id identifies nobody, and is
 * answered as an anonymous request is. A query string or header carrying somebody else's id is
 * therefore not refused but simply never read.
 *
 * <p>Authorization is the filter chain's: the path falls under the {@code ROLE_USER} rule, so a
 * session confined by a required password change is refused with {@code 403} before this runs, as
 * every route beyond its three is.
 */
@RestController
@RequestMapping("/api/self")
public class SelfController {

    private final SelfReadService selfReads;

    public SelfController(SelfReadService selfReads) {
        this.selfReads = selfReads;
    }

    @GetMapping
    public SelfRecord read(HttpServletRequest request) {
        return selfReads.read(HttpSessionAttributes.ownerOf(request));
    }
}

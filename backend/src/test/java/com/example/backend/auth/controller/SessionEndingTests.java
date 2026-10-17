package com.example.backend.auth.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import com.example.backend.audit.CapturedLog;
import com.example.backend.audit.RecordingAuditTrail;
import com.example.backend.audit.RecordingAuditTrail.Recorded;
import com.example.backend.audit.domain.AuditOperation;
import com.example.backend.audit.domain.AuditTrail;
import com.example.backend.audit.domain.Audited;
import com.example.backend.auth.controller.SessionEnding.Reason;
import com.example.backend.auth.domain.SignedInSession;
import com.example.backend.observability.LogContext;
import com.example.backend.observability.LogEvent;
import jakarta.servlet.http.Cookie;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.session.web.http.DefaultCookieSerializer;

/**
 * Every way the backend ends the caller's own session, one {@link Reason} at a time: each test
 * asserts the whole sequence its reason runs — the record where it has one, the session
 * invalidated, the security context cleared, the session cookie expired, and {@code
 * Clear-Site-Data} where the reason sends it — so a step one reason drops is a failure here rather
 * than a drift between callers.
 */
class SessionEndingTests {

    /** Mirrors {@code server.servlet.session.cookie.name}. */
    private static final String SESSION_COOKIE = "JSESSIONID";

    /** The one value a logout sends: every data type a signed-out session may have left. */
    private static final String CLEAR_SITE_DATA = "\"cache\",\"cookies\",\"storage\"";

    private static final UUID ADA = UUID.fromString("5f0c3a0e-6f43-4d36-9a59-2f3c0b7d1e11");

    private final RecordingAuditTrail audit = new RecordingAuditTrail();

    private SessionEnding ending;

    @BeforeEach
    void setUp() {
        ending = endingRecordingTo(audit);
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    // ---- LOGOUT ---------------------------------------------------------------------------------

    /**
     * A Logout of a signed-in session: the {@code LOGOUT} audit record and the {@code user-logout}
     * line, both naming the account by the stable id the principal index holds, then the session
     * invalidated, the context cleared, the cookie expired and the browser told to clear the site.
     */
    @Test
    void aLogoutRecordsTheLogoutThenEndsTheSessionAndClearsTheSite() {
        MockHttpServletRequest request = signedInRequest();
        MockHttpSession session = (MockHttpSession) request.getSession(false);
        MockHttpServletResponse response = new MockHttpServletResponse();

        try (CapturedLog captured = CapturedLog.attach()) {
            ending.end(Reason.LOGOUT, request, response);

            assertThat(audit.recorded())
                    .containsExactly(new Recorded(AuditOperation.LOGOUT, ADA, ADA, null));
            assertThat(logoutRecords(captured)).singleElement().satisfies(record -> {
                assertThat(record.getLevel()).isEqualTo(Level.INFO);
                assertThat(record.getMDCPropertyMap())
                        .containsEntry(LogContext.USER_ID, ADA.toString());
            });
        }
        assertEnded(session, response);
        assertThat(response.getHeaders("Clear-Site-Data")).containsExactly(CLEAR_SITE_DATA);
    }

    /**
     * Fail-closed: a Logout this service cannot account for leaves the session standing, its
     * context and cookie untouched, rather than ending it silently.
     */
    @Test
    void aLogoutWhoseAuditFailsLeavesTheSessionStanding() {
        SessionEnding failing = endingRecordingTo(auditFailingOnLogout());
        MockHttpServletRequest request = signedInRequest();
        MockHttpSession session = (MockHttpSession) request.getSession(false);
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertThatThrownBy(() -> failing.end(Reason.LOGOUT, request, response))
                .isInstanceOf(IllegalStateException.class);

        assertThat(List.of(session.isInvalid(),
                        SecurityContextHolder.getContext().getAuthentication() == null,
                        response.getCookie(SESSION_COOKIE) == null,
                        response.getHeader("Clear-Site-Data") == null))
                .containsExactly(false, false, true, true);
    }

    /**
     * A session nobody signed in to names no account: nothing is recorded, and it still ends the
     * whole way, Clear-Site-Data included.
     */
    @Test
    void aLogoutOfASessionThatIdentifiesNobodyRecordsNothingAndStillEndsIt() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpSession session = (MockHttpSession) request.getSession(true);
        session.setAttribute(SignedInSession.OWNER_ATTRIBUTE, "not-an-id");
        MockHttpServletResponse response = new MockHttpServletResponse();

        try (CapturedLog captured = CapturedLog.attach()) {
            ending.end(Reason.LOGOUT, request, response);

            assertThat(audit.recorded()).isEmpty();
            assertThat(logoutRecords(captured)).isEmpty();
        }
        assertEnded(session, response);
        assertThat(response.getHeaders("Clear-Site-Data")).containsExactly(CLEAR_SITE_DATA);
    }

    /** A caller whose session already expired is cleaned up the same way, minus the record. */
    @Test
    void aLogoutWithoutASessionStillClearsTheContextTheCookieAndTheSite() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        SecurityContextHolder.getContext().setAuthentication(
                new TestingAuthenticationToken("ada", null, "ROLE_USER"));
        MockHttpServletResponse response = new MockHttpServletResponse();

        ending.end(Reason.LOGOUT, request, response);

        assertThat(audit.recorded()).isEmpty();
        assertThat(request.getSession(false)).as("no session is created").isNull();
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        assertCookieExpired(response);
        assertThat(response.getHeaders("Clear-Site-Data")).containsExactly(CLEAR_SITE_DATA);
    }

    // ---- PASSWORD_CHANGED -----------------------------------------------------------------------

    /**
     * After a successful password change: no record of its own — the change recorded its events —
     * then the session invalidated, the context cleared and the cookie expired, and no
     * Clear-Site-Data.
     */
    @Test
    void aPasswordChangeEndsTheSessionWithoutARecordOrClearSiteData() {
        MockHttpServletRequest request = signedInRequest();
        MockHttpSession session = (MockHttpSession) request.getSession(false);
        MockHttpServletResponse response = new MockHttpServletResponse();

        try (CapturedLog captured = CapturedLog.attach()) {
            ending.end(Reason.PASSWORD_CHANGED, request, response);

            assertThat(audit.recorded()).isEmpty();
            assertThat(logoutRecords(captured)).isEmpty();
        }
        assertEnded(session, response);
        assertThat(response.getHeader("Clear-Site-Data")).isNull();
    }

    // ---- LOGIN_SIGNED_NOBODY_IN -----------------------------------------------------------------

    /**
     * A Login that signed nobody in ends whatever session the browser held, whoever it belonged
     * to: no record of its own — the Login's outcome is recorded by {@code LoginOutcomeService} —
     * then the session invalidated, the context cleared and the cookie expired, and no
     * Clear-Site-Data, which would wipe the SPA mid-sign-in.
     */
    @Test
    void aLoginThatSignedNobodyInEndsTheBrowsersSessionWithoutARecordOrClearSiteData() {
        MockHttpServletRequest request = signedInRequest();
        MockHttpSession session = (MockHttpSession) request.getSession(false);
        MockHttpServletResponse response = new MockHttpServletResponse();

        try (CapturedLog captured = CapturedLog.attach()) {
            ending.end(Reason.LOGIN_SIGNED_NOBODY_IN, request, response);

            assertThat(audit.recorded()).isEmpty();
            assertThat(logoutRecords(captured)).isEmpty();
        }
        assertEnded(session, response);
        assertThat(response.getHeader("Clear-Site-Data")).isNull();
    }

    /** ADR 0001, step 5: a refused Login that arrived without a session creates none. */
    @Test
    void aLoginThatSignedNobodyInCreatesNoSessionWhenTheBrowserHeldNone() {
        MockHttpServletRequest request = new MockHttpServletRequest();

        ending.end(Reason.LOGIN_SIGNED_NOBODY_IN, request, new MockHttpServletResponse());

        assertThat(request.getSession(false)).isNull();
    }

    // ---- helpers ----------------------------------------------------------------------------------

    private static SessionEnding endingRecordingTo(AuditTrail trail) {
        DefaultCookieSerializer cookies = new DefaultCookieSerializer();
        cookies.setCookieName(SESSION_COOKIE);
        return new SessionEnding(trail, cookies);
    }

    /** A request carrying ADA's signed-in session, its authentication on the thread. */
    private static MockHttpServletRequest signedInRequest() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.getSession(true).setAttribute(SignedInSession.OWNER_ATTRIBUTE, ADA.toString());
        SecurityContextHolder.getContext().setAuthentication(
                new TestingAuthenticationToken("ada", null, "ROLE_USER"));
        return request;
    }

    /** Ended: invalidated, its authentication gone from the thread, its cookie expired. */
    private static void assertEnded(MockHttpSession session, MockHttpServletResponse response) {
        assertThat(session.isInvalid()).as("the session is invalidated").isTrue();
        assertThat(SecurityContextHolder.getContext().getAuthentication())
                .as("the security context is cleared")
                .isNull();
        assertCookieExpired(response);
    }

    private static void assertCookieExpired(MockHttpServletResponse response) {
        Cookie cleared = response.getCookie(SESSION_COOKIE);
        assertThat(cleared).as("the session cookie is written").isNotNull();
        assertThat(List.of(cleared.getValue(), cleared.getMaxAge())).containsExactly("", 0);
    }

    private static List<ILoggingEvent> logoutRecords(CapturedLog captured) {
        return captured.withAction(Level.TRACE, LogEvent.ACTION, "user-logout");
    }

    /** An audit trail whose LOGOUT append fails, as a database outage would make it. */
    private static AuditTrail auditFailingOnLogout() {
        return (AuditTrail) Proxy.newProxyInstance(
                AuditTrail.class.getClassLoader(),
                new Class<?>[] {AuditTrail.class},
                (proxy, method, arguments) -> {
                    if (method.getName().equals("append")
                            && arguments[0] instanceof Audited.Logout) {
                        throw new IllegalStateException("the audit store is unavailable");
                    }
                    throw new AssertionError("unexpected audit call: " + method.getName()
                            + " " + java.util.Arrays.toString(arguments));
                });
    }
}

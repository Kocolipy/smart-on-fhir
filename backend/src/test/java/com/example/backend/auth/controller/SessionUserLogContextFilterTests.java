package com.example.backend.auth.controller;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import com.example.backend.audit.CapturedLog;
import com.example.backend.observability.LogContext;
import com.example.backend.observability.LogEvent;
import com.example.backend.observability.RequestIdFilter;
import jakarta.servlet.FilterChain;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.session.FindByIndexNameSessionRepository;

/**
 * {@code user.id} is in the logging context while the rest of the chain runs, and
 * only when the request is authenticated AND its session's principal index holds a
 * well-formed id — never the userName the {@code Authentication} carries.
 */
class SessionUserLogContextFilterTests {

    private static final UUID ADA = UUID.fromString("0f8fad5b-d9cb-469f-a165-70867728950e");

    private final SessionUserLogContextFilter filter = new SessionUserLogContextFilter();

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
        MDC.clear();
    }

    @Test
    void anAuthenticatedSessionsIndexedIdIsInScopeForTheChainAndGoneAfter() throws Exception {
        authenticateAs("ada");
        MockHttpServletRequest request = withIndex(ADA.toString());

        List<String> seen = run(request);

        assertThat(seen).containsExactly(ADA.toString());
        assertThat(MDC.get(LogContext.USER_ID)).isNull();
    }

    @Test
    void anAnonymousRequestCarriesNoUserId() throws Exception {
        List<String> seen = run(new MockHttpServletRequest());

        assertThat(seen).containsExactly((String) null);
    }

    @Test
    void anUnauthenticatedContextIgnoresTheIndex() throws Exception {
        SecurityContextHolder.getContext().setAuthentication(
                UsernamePasswordAuthenticationToken.unauthenticated("ada", "secret"));

        assertThat(run(withIndex(ADA.toString()))).containsExactly((String) null);
    }

    @Test
    void anAuthenticatedRequestWithNoSessionCarriesNoUserId() throws Exception {
        authenticateAs("ada");

        assertThat(run(new MockHttpServletRequest())).containsExactly((String) null);
    }

    @Test
    void aSessionWithNoIndexCarriesNoUserId() throws Exception {
        authenticateAs("ada");
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.getSession();

        assertThat(run(request)).containsExactly((String) null);
    }

    /** An index holding a userName — or anything else not an id — is never logged. */
    @Test
    void anIndexThatIsNotAnIdCarriesNoUserId() throws Exception {
        authenticateAs("ada");

        assertThat(run(withIndex("ada"))).containsExactly((String) null);
    }

    /**
     * The id outlives the filter's own scope on the request alone, so the request record
     * {@link RequestIdFilter} writes outside the security chain names the same caller — and a
     * request this filter resolved to nobody is recorded under nobody.
     */
    @Test
    void theRequestRecordNamesTheSessionsUser() throws Exception {
        authenticateAs("ada");

        assertThat(requestRecordContext(withIndex(ADA.toString())))
                .containsEntry(LogContext.USER_ID, ADA.toString());
    }

    @Test
    void theRequestRecordOfAnUnresolvedSessionNamesNobody() throws Exception {
        authenticateAs("ada");

        assertThat(requestRecordContext(withIndex("ada"))).doesNotContainKey(LogContext.USER_ID);
    }

    /** The request record's logging context, with this filter running inside the request's. */
    private Map<String, String> requestRecordContext(MockHttpServletRequest request)
            throws Exception {
        try (CapturedLog captured = CapturedLog.attach()) {
            new RequestIdFilter().doFilter(request, new MockHttpServletResponse(),
                    (req, res) -> filter.doFilter(req, res, (inner, innerResponse) -> { }));
            List<ILoggingEvent> records =
                    captured.withAction(Level.TRACE, LogEvent.LOCAL_ACTION, "http.request");
            assertThat(records).hasSize(1);
            return records.getFirst().getMDCPropertyMap();
        }
    }

    private static void authenticateAs(String name) {
        SecurityContextHolder.getContext().setAuthentication(
                UsernamePasswordAuthenticationToken.authenticated(name, null, List.of()));
    }

    private static MockHttpServletRequest withIndex(String indexed) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.getSession().setAttribute(
                FindByIndexNameSessionRepository.PRINCIPAL_NAME_INDEX_NAME, indexed);
        return request;
    }

    /** Runs the filter and returns what the rest of the chain saw as {@code user.id}. */
    private List<String> run(MockHttpServletRequest request) throws Exception {
        List<String> seen = new ArrayList<>();
        FilterChain chain = (req, res) -> seen.add(MDC.get(LogContext.USER_ID));
        filter.doFilter(request, new MockHttpServletResponse(), chain);
        return seen;
    }
}

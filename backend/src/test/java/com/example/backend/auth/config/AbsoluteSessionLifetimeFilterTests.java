package com.example.backend.auth.config;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import com.example.backend.audit.CapturedLog;
import com.example.backend.auth.MutableClock;
import com.example.backend.auth.domain.AbsoluteSessionLifetimePolicy;
import com.example.backend.auth.domain.EpicTokenSet;
import com.example.backend.auth.domain.EpicTokens;
import com.example.backend.observability.LogContext;
import com.example.backend.observability.LogEvent;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.session.FindByIndexNameSessionRepository;

class AbsoluteSessionLifetimeFilterTests {

    private static final AbsoluteSessionLifetimePolicy POLICY =
            new AbsoluteSessionLifetimePolicy(Duration.ofHours(8));

    /**
     * A request with no session at all: the filter must not create one just to
     * check its age, and must let the request through unchanged.
     */
    @Test
    void doesNothingWhenThereIsNoSession() throws Exception {
        MutableClock clock = new MutableClock(Instant.now());
        AbsoluteSessionLifetimeFilter filter = new AbsoluteSessionLifetimeFilter(POLICY, clock);
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(request.getSession(false)).isNull();
        assertThat(chain.getRequest()).isNotNull();
    }

    @Test
    void leavesASessionYoungerThanTheLifetimeUntouched() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpSession session = (MockHttpSession) request.getSession();
        MutableClock clock = new MutableClock(
                Instant.ofEpochMilli(session.getCreationTime()).plus(Duration.ofHours(1)));
        AbsoluteSessionLifetimeFilter filter = new AbsoluteSessionLifetimeFilter(POLICY, clock);

        filter.doFilter(request, new MockHttpServletResponse(), new MockFilterChain());

        assertThat(session.isInvalid()).isFalse();
    }

    /**
     * Past the policy's ceiling, the session is invalidated and the security
     * context cleared before the rest of the chain runs — so a protected path
     * sees the request exactly as it would an unauthenticated one, rather than a
     * distinct failure mode.
     */
    @Test
    void invalidatesASessionPastTheLifetimeAndClearsTheSecurityContext() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpSession session = (MockHttpSession) request.getSession();
        SecurityContextHolder.getContext().setAuthentication(
                UsernamePasswordAuthenticationToken.authenticated("ada", null, null));
        try {
            MutableClock clock = new MutableClock(
                    Instant.ofEpochMilli(session.getCreationTime())
                            .plus(Duration.ofHours(8))
                            .plusMillis(1));
            AbsoluteSessionLifetimeFilter filter =
                    new AbsoluteSessionLifetimeFilter(POLICY, clock);

            filter.doFilter(request, new MockHttpServletResponse(), new MockFilterChain());

            assertThat(session.isInvalid()).isTrue();
            assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    @Test
    void aSessionExactlyAtTheLifetimeIsNotYetInvalidated() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpSession session = (MockHttpSession) request.getSession();
        MutableClock clock = new MutableClock(
                Instant.ofEpochMilli(session.getCreationTime()).plus(Duration.ofHours(8)));
        AbsoluteSessionLifetimeFilter filter = new AbsoluteSessionLifetimeFilter(POLICY, clock);

        filter.doFilter(request, new MockHttpServletResponse(), new MockFilterChain());

        assertThat(session.isInvalid()).isFalse();
    }

    // ---- session-end record (#69) ------------------------------------------------------------

    /**
     * An expiry is one INFO {@code session-end} record whose cause is the absolute lifetime and
     * whose {@code user.id} is the session's own account, read from its principal index before
     * the session went; the request is marked so the entry point can say "expired".
     */
    @Test
    void anExpiryIsOneSessionEndRecordNamingTheSessionsAccount() throws Exception {
        UUID owner = UUID.randomUUID();
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpSession session = (MockHttpSession) request.getSession();
        session.setAttribute(
                FindByIndexNameSessionRepository.PRINCIPAL_NAME_INDEX_NAME, owner.toString());
        AbsoluteSessionLifetimeFilter filter = new AbsoluteSessionLifetimeFilter(POLICY,
                new MutableClock(Instant.ofEpochMilli(session.getCreationTime())
                        .plus(Duration.ofHours(9))));

        List<ILoggingEvent> records;
        try (CapturedLog captured = CapturedLog.attach()) {
            filter.doFilter(request, new MockHttpServletResponse(), new MockFilterChain());
            records = captured.withAction(Level.TRACE, LogEvent.KIND, "event");
        }

        assertThat(session.isInvalid()).isTrue();
        assertThat(request.getAttribute(AbsoluteSessionLifetimeFilter.ENDED_ATTRIBUTE))
                .isEqualTo(Boolean.TRUE);
        assertThat(records).singleElement().satisfies(record -> {
            assertThat(record.getLevel()).isEqualTo(Level.INFO);
            assertThat(record.getFormattedMessage()).isEqualTo("Session ended");
            assertThat(CapturedLog.fields(record))
                    .containsEntry(LogEvent.ACTION, "session-end")
                    .containsEntry(LogEvent.CATEGORY, List.of("process"))
                    .containsEntry(LogEvent.TYPE, List.of("end"))
                    .containsEntry(LogEvent.OUTCOME, "success")
                    .containsEntry(LogEvent.REASON, "absolute-lifetime");
            assertThat(record.getMDCPropertyMap())
                    .containsEntry(LogContext.USER_ID, owner.toString());
        });
        assertThat(MDC.get(LogContext.USER_ID)).as("the scope closes").isNull();
    }

    /** A session nobody signed in to, or whose index is not an id, ends naming nobody. */
    @Test
    void anExpiredSessionWithNoAccountEndsNamingNobody() throws Exception {
        for (Object index : new Object[] {null, "not-a-uuid"}) {
            MockHttpServletRequest request = new MockHttpServletRequest();
            MockHttpSession session = (MockHttpSession) request.getSession();
            session.setAttribute(FindByIndexNameSessionRepository.PRINCIPAL_NAME_INDEX_NAME, index);
            AbsoluteSessionLifetimeFilter filter = new AbsoluteSessionLifetimeFilter(POLICY,
                    new MutableClock(Instant.ofEpochMilli(session.getCreationTime())
                            .plus(Duration.ofHours(9))));

            List<ILoggingEvent> records;
            try (CapturedLog captured = CapturedLog.attach()) {
                filter.doFilter(request, new MockHttpServletResponse(), new MockFilterChain());
                records = captured.withAction(Level.TRACE, LogEvent.KIND, "event");
            }

            assertThat(records).singleElement().satisfies(record ->
                    assertThat(record.getMDCPropertyMap()).doesNotContainKey(LogContext.USER_ID));
        }
    }

    /** An unexpired session ends nothing and is recorded nowhere. */
    @Test
    void anUnexpiredSessionWritesNoRecordAndIsNotMarked() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpSession session = (MockHttpSession) request.getSession();
        AbsoluteSessionLifetimeFilter filter = new AbsoluteSessionLifetimeFilter(POLICY,
                new MutableClock(Instant.ofEpochMilli(session.getCreationTime())));

        try (CapturedLog captured = CapturedLog.attach()) {
            filter.doFilter(request, new MockHttpServletResponse(), new MockFilterChain());

            assertThat(captured.withAction(Level.TRACE, LogEvent.KIND, "event")).isEmpty();
        }
        assertThat(request.getAttribute(AbsoluteSessionLifetimeFilter.ENDED_ATTRIBUTE)).isNull();
    }

    // ---- a session holding Epic tokens is stored no longer than its lifetime (#24) -----------

    /** The deployed idle bound, 15 minutes. */
    private static final int IDLE_SECONDS = 900;

    private static final EpicTokenSet TOKENS = EpicTokenSet.issued("access-token-value",
            Duration.ofHours(1), Instant.parse("2026-10-09T09:00:00Z"), Set.of("openid"),
            Optional.empty(), "id-token-value");

    /** A session's request at {@code age}, through the filter; the session, after it. */
    private static MockHttpSession requestedAt(Duration age, boolean holdingEpicTokens)
            throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpSession session = (MockHttpSession) request.getSession();
        session.setMaxInactiveInterval(IDLE_SECONDS);
        if (holdingEpicTokens) {
            session.setAttribute(EpicTokens.SESSION_ATTRIBUTE, TOKENS);
        }
        AbsoluteSessionLifetimeFilter filter = new AbsoluteSessionLifetimeFilter(POLICY,
                new MutableClock(Instant.ofEpochMilli(session.getCreationTime()).plus(age)));
        filter.doFilter(request, new MockHttpServletResponse(), new MockFilterChain());
        return session;
    }

    /** Five minutes from the lifetime's end, a request may renew the session for five only. */
    @Test
    void aSessionHoldingEpicTokensNearItsLifetimesEndIsRenewedOnlyUntilThatEnd() throws Exception {
        assertThat(requestedAt(Duration.ofHours(7).plusMinutes(55), true).getMaxInactiveInterval())
                .isEqualTo(300);
    }

    @Test
    void aSessionHoldingEpicTokensFarFromItsLifetimesEndKeepsItsIdleBound() throws Exception {
        assertThat(requestedAt(Duration.ofHours(1), true).getMaxInactiveInterval())
                .isEqualTo(IDLE_SECONDS);
    }

    /** Only the Epic tokens' storage is bounded so: a password session's idle bound is its own. */
    @Test
    void aSessionWithoutEpicTokensKeepsItsIdleBoundNearItsLifetimesEnd() throws Exception {
        assertThat(requestedAt(Duration.ofHours(7).plusMinutes(55), false).getMaxInactiveInterval())
                .isEqualTo(IDLE_SECONDS);
    }
}

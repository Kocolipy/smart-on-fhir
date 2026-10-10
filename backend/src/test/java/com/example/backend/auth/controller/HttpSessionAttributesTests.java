package com.example.backend.auth.controller;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpSession;

/**
 * The web adapters' view of the servlet container's session, as the signed-in session module
 * reads and writes it: each operation lands on the session itself, in the servlet API's units.
 */
class HttpSessionAttributesTests {

    @Test
    void anAttributeSetThroughTheViewIsOnTheSession() {
        MockHttpSession session = new MockHttpSession();

        new HttpSessionAttributes(session).setAttribute("app.example", "value");

        assertThat(session.getAttribute("app.example")).isEqualTo("value");
    }

    @Test
    void anAttributeOnTheSessionIsReadThroughTheView() {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute("app.example", "value");

        assertThat(new HttpSessionAttributes(session).attribute("app.example")).isEqualTo("value");
    }

    /** The servlet API counts the creation time in epoch milliseconds. */
    @Test
    void theSessionWasCreatedWhenTheContainerSaysSo() {
        MockHttpSession session = new MockHttpSession() {
            @Override
            public long getCreationTime() {
                return 1_791_619_200_000L;
            }
        };

        assertThat(new HttpSessionAttributes(session).createdAt())
                .isEqualTo(Instant.parse("2026-10-10T08:00:00Z"));
    }

    /** The servlet API counts the idle bound in seconds. */
    @Test
    void theIdleBoundIsTheSessionsMaxInactiveInterval() {
        MockHttpSession session = new MockHttpSession();
        session.setMaxInactiveInterval(1800);

        assertThat(new HttpSessionAttributes(session).idleBound()).isEqualTo(Duration.ofMinutes(30));
    }

    @Test
    void settingTheIdleBoundSetsTheSessionsMaxInactiveIntervalInSeconds() {
        MockHttpSession session = new MockHttpSession();

        new HttpSessionAttributes(session).setIdleBound(Duration.ofSeconds(869));

        assertThat(session.getMaxInactiveInterval()).isEqualTo(869);
    }
}

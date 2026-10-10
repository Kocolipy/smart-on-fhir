package com.example.backend.auth.infrastructure.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.session.MapSession;

/**
 * The store adapters' read-only view of a Spring Session found by id: it reads what the stored
 * session holds, and refuses a write, which would change only a copy the store never sees.
 */
class SpringSessionAttributesTests {

    @Test
    void anAttributeOnTheSessionIsReadThroughTheView() {
        MapSession session = new MapSession();
        session.setAttribute("app.example", "value");

        assertThat(new SpringSessionAttributes(session).attribute("app.example"))
                .isEqualTo("value");
    }

    @Test
    void theSessionWasCreatedWhenTheStoreSaysSo() {
        MapSession session = new MapSession();
        session.setCreationTime(Instant.parse("2026-10-10T08:00:00Z"));

        assertThat(new SpringSessionAttributes(session).createdAt())
                .isEqualTo(Instant.parse("2026-10-10T08:00:00Z"));
    }

    @Test
    void theIdleBoundIsTheSessionsMaxInactiveInterval() {
        MapSession session = new MapSession();
        session.setMaxInactiveInterval(Duration.ofMinutes(30));

        assertThat(new SpringSessionAttributes(session).idleBound())
                .isEqualTo(Duration.ofMinutes(30));
    }

    @Test
    void settingAnAttributeIsRefused() {
        SpringSessionAttributes view = new SpringSessionAttributes(new MapSession());

        assertThatThrownBy(() -> view.setAttribute("app.example", "value"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void settingTheIdleBoundIsRefused() {
        SpringSessionAttributes view = new SpringSessionAttributes(new MapSession());

        assertThatThrownBy(() -> view.setIdleBound(Duration.ofMinutes(5)))
                .isInstanceOf(UnsupportedOperationException.class);
    }
}

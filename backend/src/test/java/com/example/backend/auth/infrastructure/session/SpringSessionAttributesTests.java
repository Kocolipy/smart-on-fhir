package com.example.backend.auth.infrastructure.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.backend.auth.domain.SignedInSession;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.session.MapSession;

/**
 * The store adapters' read-only view of a Spring Session found by id: it reads what the stored
 * session holds, and refuses a write, which would change only a copy the store never sees.
 */
class SpringSessionAttributesTests {

    /** A stored session is read as the signed-in session module reads it: here, its owner. */
    @Test
    void aStoredSessionIsReadAsTheSignedInSessionItIs() {
        MapSession session = new MapSession();
        session.setAttribute(SignedInSession.OWNER_ATTRIBUTE,
                "0c9f3e1a-7b2d-4c5e-8f6a-2d1b3c4e5f60");

        assertThat(SpringSessionAttributes.signedIn(session).owner())
                .contains(UUID.fromString("0c9f3e1a-7b2d-4c5e-8f6a-2d1b3c4e5f60"));
    }

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
    void removingAnAttributeIsRefused() {
        SpringSessionAttributes view = new SpringSessionAttributes(new MapSession());

        assertThatThrownBy(() -> view.removeAttribute("app.example"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void settingTheIdleBoundIsRefused() {
        SpringSessionAttributes view = new SpringSessionAttributes(new MapSession());

        assertThatThrownBy(() -> view.setIdleBound(Duration.ofMinutes(5)))
                .isInstanceOf(UnsupportedOperationException.class);
    }
}

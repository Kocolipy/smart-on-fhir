package com.example.backend.auth.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.backend.auth.application.UnknownSessionIdentityException;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpSession;

/**
 * The web adapters' view of the servlet container's session, as the signed-in session module
 * reads and writes it: each operation lands on the session itself, in the servlet API's units.
 */
class HttpSessionAttributesTests {

    private static final UUID OWNER = UUID.fromString("0c9f3e1a-7b2d-4c5e-8f6a-2d1b3c4e5f60");

    /** A handler naming its caller reads the owner the Login signed the request's session in to. */
    @Test
    void theRequestsSessionIsOwnedByTheUserItWasSignedInTo() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        HttpSessionAttributes.signedIn(request.getSession())
                .signIn(OWNER, Instant.parse("2026-10-10T08:00:00Z"));

        assertThat(HttpSessionAttributes.ownerOf(request)).isEqualTo(OWNER);
    }

    /** A request with no session names nobody: the refusal an anonymous request gets. */
    @Test
    void aRequestWithNoSessionNamesNoOwner() {
        MockHttpServletRequest request = new MockHttpServletRequest();

        assertThatThrownBy(() -> HttpSessionAttributes.ownerOf(request))
                .isInstanceOf(UnknownSessionIdentityException.class);
    }

    /** A session nobody signed in to names nobody either. */
    @Test
    void aSessionNobodySignedInToNamesNoOwner() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.getSession();

        assertThatThrownBy(() -> HttpSessionAttributes.ownerOf(request))
                .isInstanceOf(UnknownSessionIdentityException.class);
    }

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

    @Test
    void anAttributeRemovedThroughTheViewIsGoneFromTheSession() {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute("app.example", "value");

        new HttpSessionAttributes(session).removeAttribute("app.example");

        assertThat(session.getAttributeNames().hasMoreElements()).isFalse();
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
